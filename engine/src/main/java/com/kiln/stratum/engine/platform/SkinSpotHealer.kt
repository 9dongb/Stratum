package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.MAX_PREVIEW_LEVEL
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColorMath
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinSpot
import com.kiln.stratum.engine.skin.SkinSpots
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 잡티 자동 제거의 찾기와 지우기(결정 기록 '피부 보정'의 5단계, 사용자 결정 "바로 지우기, 점은 남김").
 *
 * - 찾기([find]): 얼굴마다 그 둘레를 두 눈꼬리 사이가 [SkinSpots.FRAME_UNIT]px인 틀로 그려 [SkinSpots]로 찾는다. 피부 마스크 안쪽만 보고,
 *   다른 얼굴이 더 가까운 곳은 그 얼굴이 맡는다(단체 사진). 점(모반)은 남기고, 이미 지운 자리는 다시 찾지 않는다.
 * - 지우기([heal]): 잡티마다 스팟 복구(둘레에서 가져올 곳을 찾아 밝기·색을 맞춤)를 잡티 제거 레이어에 그린다. 가져오는 그림은 그 레이어와
 *   그 아래([BelowSampler])이고, 앞에서 지운 결과를 다음 잡티가 읽는다(지운 자리를 다른 잡티의 가져올 곳으로 써도 깨끗하게).
 * - Kotlin 반복은 틀(얼굴 하나, 수십만 픽셀)에서만 한다. 지우기는 리터칭과 같은 계산이다(줄인 그림의 찾기·맞추기와 GPU 합치기).
 */
internal object SkinSpotHealer {

    private const val TAG = "SkinSpotHealer"

    /** 한 얼굴에서 한 번에 지울 잡티의 한도(센 것부터). 주근깨가 아주 많은 얼굴에서 너무 오래 걸리지 않게(다시 누르면 남은 것을 지운다). */
    const val MAX_PER_FACE = 150

    /** 지우는 붓의 경도(가장자리를 부드럽게 섞음) */
    const val HEAL_HARDNESS = 0.5f

    /** 찾을 얼굴 둘레: 가운데에서 반지름의 이 배수(피부 마스크를 만든 정사각형과 비슷하게 목까지) */
    private const val WINDOW_RADII = 2.2f

    /** 이보다 작은 얼굴(두 눈꼬리 사이, 문서 픽셀)은 잡티를 찾지 않는다: 잡티가 한두 픽셀이라 지울 것이 없고, 틀을 키우는 계산만 크다 */
    const val MIN_UNIT = 40f

    /** 틀 한 변의 한도(픽셀): 얼굴 둘레를 틀로 잘라 보통 약 770px이고, 문서 끝에 걸쳐도 넘지 않게 */
    private const val MAX_FRAME_EDGE = 1024

    /** 찾은 잡티(문서 좌표, 센 것부터, 반지름은 지우는 붓의 반지름)와 남긴 점(모반) 수 */
    class Found(val spots: List<SkinSpot>, val moles: Int)

    /**
     * [below](잡티 제거 레이어와 그 아래를 합친 문서)에서 [faces]마다 잡티를 찾는다. [mask]는 피부 마스크, [skip]은 이미 지운 자리다.
     * GPU가 그리지 못하면 [NotEnoughMemoryException].
     */
    fun find(below: Document, mask: Selection, faces: List<SkinFaceArea>, skip: List<SkinSpot>, checkCanceled: () -> Unit): Found {
        val size = PixelSize(below.width, below.height)
        val space = OkLab.Space.of(below.colorSpace)
        val found = ArrayList<SkinSpot>()
        var moles = 0
        for ((index, face) in faces.withIndex()) {
            checkCanceled()
            if (face.unit < MIN_UNIT) continue
            val started = SystemClock.elapsedRealtime()
            val toFrame = SkinSpots.FRAME_UNIT / face.unit
            // 틀보다 크거나 같은 가장 작은 단계로 그린 뒤, 얼굴 둘레만 잘라 틀 크기로 줄인다(쌍선형). 그리는 곳은 타일에 맞춰 넓어지므로 자른다(6단계 검토).
            val level = max(0, floor(ln(face.unit / SkinSpots.FRAME_UNIT) / ln(2f)).toInt()).coerceAtMost(MAX_PREVIEW_LEVEL)
            val cell = 1 shl level
            val reach = face.radius * WINDOW_RADII
            val wanted = PixelRect((face.centerX - reach).toInt(), (face.centerY - reach).toInt(), (face.centerX + reach).toInt() + 1, (face.centerY + reach).toInt() + 1)
            val region = PreviewLevels.region(size, level, wanted) ?: continue
            val inside = wanted.intersect(region.rect) ?: continue
            // 단계 칸에 맞춘 자를 곳(그린 그림의 픽셀)과 그 문서 자리
            val cropLeft = (inside.left - region.rect.left) / cell
            val cropTop = (inside.top - region.rect.top) / cell
            val cropWidth = min(region.previewWidth - cropLeft, (inside.right - region.rect.left + cell - 1) / cell - cropLeft)
            val cropHeight = min(region.previewHeight - cropTop, (inside.bottom - region.rect.top + cell - 1) / cell - cropTop)
            if (cropWidth < 4 || cropHeight < 4) continue
            val frameLeft = region.rect.left + cropLeft * cell
            val frameTop = region.rect.top + cropTop * cell
            // 틀 배율(문서 픽셀 하나가 틀의 몇 픽셀인지). 한 변이 한도를 넘으면 두 쪽을 같이 줄인다.
            val scale = min(toFrame, MAX_FRAME_EDGE / (max(cropWidth, cropHeight) * cell).toFloat())
            val width = (cropWidth * cell * scale).roundToInt()
            val height = (cropHeight * cell * scale).roundToInt()
            if (width < 16 || height < 16) continue
            val pixels = IntArray(width * height)
            val drawn = PreviewRenderer.renderRegion(below, region, checkCanceled) ?: throw NotEnoughMemoryException()
            try {
                val software = drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
                try {
                    val cropped = Bitmap.createBitmap(software, cropLeft, cropTop, cropWidth, cropHeight)
                    try {
                        val frame = Bitmap.createScaledBitmap(cropped, width, height, true)
                        frame.getPixels(pixels, 0, width, 0, 0, width, height)
                        if (frame !== cropped) frame.recycle()
                    } finally {
                        if (cropped !== software) cropped.recycle()
                    }
                } finally {
                    software.recycle()
                }
            } finally {
                drawn.recycle()
            }
            // 틀 한 칸의 문서 크기(한도에 걸려 줄였으면 그만큼 크다, 가로·세로는 반올림만큼 다르다)
            val toDocumentX = cropWidth * cell / width.toFloat()
            val toDocumentY = cropHeight * cell / height.toFloat()
            val maskBitmap = SelectionPreview.maskAt(mask, frameLeft.toFloat(), frameTop.toFloat(), scale, width, height)
            val alphas = ByteArray(width * height)
            try {
                val software = maskBitmap.copy(Bitmap.Config.ALPHA_8, false) ?: throw NotEnoughMemoryException()
                software.copyPixelsToBuffer(ByteBuffer.wrap(alphas))
                software.recycle()
            } finally {
                maskBitmap.recycle()
            }
            val weights = FloatArray(width * height) { i ->
                val alpha = (alphas[i].toInt() and 0xFF) / 255f
                // 다른 얼굴이 더 가까운 픽셀은 그 얼굴이 맡는다
                if (faces.size == 1 || alpha == 0f) {
                    alpha
                } else {
                    val x = frameLeft + (i % width + 0.5f) * toDocumentX
                    val y = frameTop + (i / width + 0.5f) * toDocumentY
                    if (nearestFace(faces, x, y) == index) alpha else 0f
                }
            }
            checkCanceled()
            val (lightness, a) = OkLab.fromPixels(pixels, space)
            // 지울 잡티의 반지름은 지우는 붓의 반지름, 남길 점은 찾은 반지름이다(문서 픽셀)
            val spots = SkinSpots.detect(lightness, a, weights, width, height).map {
                val radius = if (it.isMole) it.radius else SkinSpots.healRadius(it.radius)
                SkinSpot(frameLeft + it.x * toDocumentX, frameTop + it.y * toDocumentY, radius * toDocumentX) to it.isMole
            }
            val kept = spots.filter { it.second }.map { it.first }
            moles += kept.size
            var count = 0
            for ((spot, mole) in spots) {
                if (mole || count >= MAX_PER_FACE) continue
                // 지우는 붓이 남길 점에 닿으면 지우지 않는다(점 가장자리가 지워지지 않게)
                if (kept.any { hypot(it.x - spot.x, it.y - spot.y) < it.radius + spot.radius }) continue
                // 이미 지운 자리(앞에서 누른 것)는 다시 지우지 않는다
                if (skip.any { hypot(it.x - spot.x, it.y - spot.y) < max(it.radius, spot.radius) }) continue
                found += spot
                count++
            }
            Log.i(TAG, "Face $index: ${spots.size} spots in a ${width}x$height frame (level $level), $count to heal in ${SystemClock.elapsedRealtime() - started} ms")
        }
        return Found(found, moles)
    }

    /** [x], [y]에서 가장 가까운 얼굴(색을 섞는 무게가 가장 큰 얼굴)의 번호. 픽셀마다 부르므로 목록을 만들지 않는다. */
    private fun nearestFace(faces: List<SkinFaceArea>, x: Float, y: Float): Int {
        var best = 0
        var bestWeight = -1f
        for (i in faces.indices) {
            val weight = SkinColorMath.weight(faces[i], x, y)
            if (weight > bestWeight) {
                best = i
                bestWeight = weight
            }
        }
        return best
    }

    /** 지운 잡티 제거 레이어: 픽셀, 게인맵(원본의 것을 함께 쓰던 것을 무늬를 가져온 자리대로 고침), 실제로 지운 잡티 */
    class Healed(val raster: Raster, val gainmap: HdrGainmap?, val spots: List<SkinSpot>)

    /**
     * [document]의 [layerId](잡티 제거 레이어)에 [spots]를 스팟 복구로 지운다. 가져올 곳을 찾지 못한 잡티는 건너뛴다.
     * 선택 영역과 상관없이 지운다(피부 보정 그룹에 하는 일이고, 자리는 피부 마스크가 정함, 6단계 검토). 픽셀 레이어가 아니면 null이다.
     */
    fun heal(document: Document, layerId: LayerId, spots: List<SkinSpot>, checkCanceled: () -> Unit): Healed? {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return null
        var raster = (layer.content as? RasterContent)?.raster ?: return null
        var gainmap = layer.gainmap
        val size = PixelSize(document.width, document.height)
        val sampler = BelowSampler(document, layerId)
        val healed = ArrayList<SkinSpot>()
        val started = SystemClock.elapsedRealtime()
        // 위에서 아래로(가져올 그림을 묶음씩 그려 이어 쓴다)
        for (spot in spots.sortedWith(compareBy({ it.y }, { it.x }))) {
            checkCanceled()
            val dabs = listOf(StrokePoint(spot.x, spot.y))
            val settings = brushOf(spot)
            val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: continue
            val sample = sampler.sample(bounds.expanded(Retouch.sampleReach(RetouchMode.SPOT_HEAL, dabs, settings, null)), checkCanceled)
            if (sample.tiles.isEmpty()) continue
            val from = Retouch.spotOffset(sample, size, bounds, dabs, settings, checkCanceled) ?: continue
            if (from.x == 0 && from.y == 0) continue
            val targets = if (sampler.stacksPlainly) listOf(raster, sample) else listOf(raster)
            val painted = Retouch.paintAll(targets, sample, size, bounds, dabs, settings, from, heal = true, selection = null, checkCanceled)
            if (painted[0] === raster) continue
            raster = painted[0]
            sampler.painted(raster, painted.getOrNull(1))
            gainmap = gainmap?.let { GainmapEdits.clone(it, size, dabs, settings, from, null, checkCanceled) }
            healed += spot
        }
        Log.i(TAG, "Healed ${healed.size} of ${spots.size} spots in ${SystemClock.elapsedRealtime() - started} ms")
        return Healed(raster, gainmap, healed)
    }

    /** [spot]을 지우는 붓: 지름 = 그 반지름(지우는 붓의 반지름, [SkinSpots.healRadius])의 두 배(2px 아래로 내리지 않음), 경도 [HEAL_HARDNESS] */
    fun brushOf(spot: SkinSpot): BrushSettings =
        BrushSettings(size = (spot.radius * 2f).coerceIn(2f, BrushSettings.MAX_SIZE), hardness = HEAL_HARDNESS, opacity = 1f)
}

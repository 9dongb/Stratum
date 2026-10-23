package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.face.FaceImage
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceRoi
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinModel
import com.kiln.stratum.engine.skin.SkinRegion
import com.kiln.stratum.engine.skin.SkinTone
import com.kiln.stratum.engine.skin.SkinUnavailableException
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 피부 보정의 피부 마스크를 만든다(결정 기록 '피부 보정'의 2b). 앱 전체에서 오래 사는 서비스이고, 분할 모델([model])을 감싼다.
 *
 * - [masks]: 얼굴마다 잘라 세운 정사각형([SkinRegion.frameOf])을 얼굴 찾기 그림([FacePicture])에서 읽어 분할 모델에 넣고,
 *   [SkinRegion.mask]로 [SkinRegion.MASK_SIZE]² 마스크를 만든다. 모델을 쓸 수 없으면 윤곽만으로 만든다([FaceMasks.segmented]가 false).
 *   같은 그림·마스크로 그 얼굴의 피부 색 통계([SkinTone])도 잰다(3단계, 색 보정의 기준).
 * - [tiles]: 그 마스크들을 문서 크기 알파 타일로 그린다(정사각형이 닿는 타일만, 안드로이드 소프트웨어 그리기로 쌍선형).
 * - 메인 스레드가 아닌 곳에서 부른다. 모델 계산은 AI 스레드에서 돌고, 나머지는 작은 배열에서만 돈다.
 */
class SkinMasker(private val model: SkinModel) {

    /**
     * 얼굴 하나의 마스크: 잘라 세운 정사각형 [frame](문서 좌표)을 [size]²로 나눈 알파([alphas], 줄마다).
     * [tone]은 그 마스크 안 피부의 색 통계다(피부가 너무 적으면 null, 그 얼굴은 색을 바꾸지 않는다).
     */
    class FaceMask(val frame: FaceRoi, val size: Int, val alphas: ByteArray, val tone: SkinTone? = null)

    /** 얼굴들의 마스크. [segmented]가 false면 분할 모델을 쓸 수 없어 윤곽만으로 만든 것이다(목·귀·이마 위가 빠짐). */
    class FaceMasks(val masks: List<FaceMask>, val segmented: Boolean)

    /**
     * [faces](문서 좌표)의 피부 마스크를 [picture]에서 만든다. 피부 색 통계는 [space](문서 색 공간)의 OKLab으로 잰다.
     * 메모리가 모자라면 [NotEnoughMemoryException]을 던진다. [checkCanceled]는 얼굴 사이마다 부른다.
     */
    fun masks(
        picture: FacePicture,
        faces: List<FaceLandmarks>,
        space: OkLab.Space = OkLab.Space.SRGB,
        checkCanceled: () -> Unit = {}
    ): FaceMasks {
        val scaleX = picture.width / picture.documentWidth.toFloat()
        val scaleY = picture.height / picture.documentHeight.toFloat()
        var segmented = true
        val masks = faces.map { face ->
            checkCanceled()
            val started = SystemClock.elapsedRealtime()
            val frame = SkinRegion.frameOf(face)
            val pictureFrame = FaceRoi(frame.centerX * scaleX, frame.centerY * scaleY, frame.side * (scaleX + scaleY) / 2f, frame.angle)
            val probabilities = if (segmented) {
                try {
                    val input = SkinRegion.modelInput(picture.read(pictureFrame, SkinModel.INPUT_SIZE, FaceImage.Outside.BLACK))
                    SkinRegion.probabilities(model.segment(input))
                } catch (e: SkinUnavailableException) {
                    Log.w(TAG, "The skin model cannot run; using the face oval", e)
                    segmented = false
                    null
                }
            } else {
                null
            }
            checkCanceled()
            val size = SkinRegion.MASK_SIZE
            val crop = picture.read(pictureFrame, size, FaceImage.Outside.EDGE)
            val (values, tone) = try {
                val values = SkinRegion.mask(probabilities, face, frame, crop, size)
                values to measureTone(crop, values, size, space)
            } catch (e: OutOfMemoryError) {
                throw NotEnoughMemoryException(e)
            }
            Log.i(TAG, "Skin mask in ${SystemClock.elapsedRealtime() - started} ms (side ${frame.side.toInt()} px, segmented ${probabilities != null}, tone $tone)")
            FaceMask(frame, size, toAlphas(values), tone)
        }
        return FaceMasks(masks, segmented)
    }

    /**
     * [masks]를 문서 [size]의 알파 타일로 그린다(얼굴 정사각형이 닿는 타일만, 겹치면 합침). 다 0인 타일은 넣지 않고 다 255면 [MaskTile.FULL]이다.
     * 메모리가 모자라면 [NotEnoughMemoryException]. [checkCanceled]는 타일 사이마다 부른다.
     */
    fun tiles(masks: List<FaceMask>, size: PixelSize, checkCanceled: () -> Unit = {}): Map<TileKey, MaskTile> {
        if (masks.isEmpty()) return emptyMap()
        val grid = TileGrid(size.width, size.height)
        val bitmaps = ArrayList<Bitmap>()
        try {
            val sources = masks.map { mask ->
                val bitmap = try {
                    Bitmap.createBitmap(mask.size, mask.size, Bitmap.Config.ALPHA_8)
                } catch (e: OutOfMemoryError) {
                    throw NotEnoughMemoryException(e)
                }
                bitmaps += bitmap
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(mask.alphas))
                Triple(bitmap, matrixOf(mask), boundsOf(mask.frame, size))
            }
            val keys = LinkedHashSet<TileKey>()
            for ((_, _, bounds) in sources) {
                if (bounds == null) continue
                for (row in bounds.top / TILE_SIZE..(bounds.bottom - 1) / TILE_SIZE) {
                    for (column in bounds.left / TILE_SIZE..(bounds.right - 1) / TILE_SIZE) keys += TileKey(column, row)
                }
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val tiles = HashMap<TileKey, MaskTile>()
            for (key in keys) {
                checkCanceled()
                val tile = grid.bounds(key)
                val tileRect = PixelRect(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)
                MaskTiles.draw(tile.width, tile.height) { canvas ->
                    for ((bitmap, matrix, bounds) in sources) {
                        if (bounds == null || bounds.intersect(tileRect) == null) continue
                        canvas.save()
                        canvas.translate(-tile.left.toFloat(), -tile.top.toFloat())
                        canvas.concat(matrix)
                        canvas.drawBitmap(bitmap, 0f, 0f, paint)
                        canvas.restore()
                    }
                }?.let { tiles[key] = it }
            }
            return tiles
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    /** 얼굴 [faceCount]개의 마스크를 만들 때 잠깐 쓰는 메모리의 어림: 얼굴 하나씩 만드는 동안의 배열, 얼굴마다 남는 마스크, 모델을 아직 불러오지 않았으면 그것도 */
    fun workBytes(faceCount: Int): Long =
        WORK_BYTES_PER_FACE + faceCount.toLong() * SkinRegion.MASK_SIZE * SkinRegion.MASK_SIZE + model.pendingLoadBytes

    /** 모델을 미리 불러 둔다(기다리지 않는다) */
    fun prepare() = model.prepare()

    /** 모델을 내려놓는다. 다음에 쓰면 다시 불러온다. */
    fun release() = model.release()

    companion object {
        /** 얼굴 하나를 만드는 동안의 배열(512² 칸의 실수 배열 스무 장 안팎과 입력·확률) */
        private const val WORK_BYTES_PER_FACE = 32L * 1024 * 1024

        /** 이것보다 크면 255, 작으면 0으로 맞춘다(마스크 안쪽이 다 보이는 타일 하나를 함께 써 메모리를 아낀다) */
        private const val SNAP = 0.02f

        private const val TAG = "SkinMasker"

        /** 피부 색 통계는 마스크의 [TONE_STRIDE]칸마다 한 칸으로 잰다(얼굴 하나에 피부 픽셀 수만 개면 통계가 같고 네 배 빠르다) */
        private const val TONE_STRIDE = 2

        /** [size]² 그림 [crop]과 마스크 [values]에서 [TONE_STRIDE]칸마다 한 칸으로 피부 색 통계를 잰다 */
        private fun measureTone(crop: IntArray, values: FloatArray, size: Int, space: OkLab.Space): SkinTone? {
            val side = (size + TONE_STRIDE - 1) / TONE_STRIDE
            val pixels = IntArray(side * side)
            val weights = FloatArray(side * side)
            for (y in 0 until side) {
                for (x in 0 until side) {
                    val source = (y * TONE_STRIDE) * size + x * TONE_STRIDE
                    pixels[y * side + x] = crop[source]
                    weights[y * side + x] = values[source]
                }
            }
            val (lightness, a, b) = OkLab.fromPixels(pixels, space)
            return SkinTone.measure(lightness, a, b, weights)
        }

        private fun toAlphas(values: FloatArray): ByteArray = ByteArray(values.size) {
            val value = values[it]
            when {
                value >= 1f - SNAP -> 255.toByte()
                value <= SNAP -> 0
                else -> (value * 255f + 0.5f).toInt().toByte()
            }
        }

        /** 마스크 칸 (u, v)(0..size)를 문서 점으로 옮기는 행렬 */
        private fun matrixOf(mask: FaceMask): Matrix {
            val frame = mask.frame
            val scale = frame.side / mask.size
            val cosine = kotlin.math.cos(frame.angle.toDouble()).toFloat()
            val sine = kotlin.math.sin(frame.angle.toDouble()).toFloat()
            return Matrix().apply {
                setValues(floatArrayOf(scale * cosine, -scale * sine, frame.imageX(0f, 0f), scale * sine, scale * cosine, frame.imageY(0f, 0f), 0f, 0f, 1f))
            }
        }

        /** 정사각형 [frame]이 덮는 문서 [size] 안의 사각형(없으면 null) */
        private fun boundsOf(frame: FaceRoi, size: PixelSize): PixelRect? {
            val xs = floatArrayOf(frame.imageX(0f, 0f), frame.imageX(1f, 0f), frame.imageX(0f, 1f), frame.imageX(1f, 1f))
            val ys = floatArrayOf(frame.imageY(0f, 0f), frame.imageY(1f, 0f), frame.imageY(0f, 1f), frame.imageY(1f, 1f))
            val left = max(0, floor(xs.min()).toInt())
            val top = max(0, floor(ys.min()).toInt())
            val right = min(size.width, ceil(xs.max()).toInt())
            val bottom = min(size.height, ceil(ys.max()).toInt())
            return if (left < right && top < bottom) PixelRect(left, top, right, bottom) else null
        }
    }
}

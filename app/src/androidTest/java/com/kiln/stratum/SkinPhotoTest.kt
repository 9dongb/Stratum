package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.command.SetSkinColor
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceUnavailableException
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.LiteRtFaceModel
import com.kiln.stratum.engine.platform.LiteRtSkinModel
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.CreateSkinRetouch
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.SkinLayerNames
import com.kiln.stratum.engine.platform.SkinMasker
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.NoFaceException
import com.kiln.stratum.engine.skin.OkLab
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진짜 얼굴 모델·분할 모델로 시험 사진(NASA 퍼블릭 도메인)의 피부 마스크를 만든다(결정 기록 '피부 보정'의 2b).
 * 사진은 앱 내부 저장소 files/face/에 넣어야 돌고(없으면 건너뜀, docs/emulator.md '얼굴 시험 사진'),
 * 볼·이마는 피부, 눈동자·입술·바탕은 피부가 아닌지 본다. 확인용 그림(마스크를 빨갛게 덮은 것)을 앱 캐시의 skin-debug에 남긴다.
 * 걸린 시간은 logcat의 "SkinMasker"·"SkinPhoto"로 본다.
 */
@RunWith(AndroidJUnit4::class)
class SkinPhotoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val finder = FaceFinder(LiteRtFaceModel(context.assets, context.cacheDir))
    private val masker = SkinMasker(LiteRtSkinModel(context.assets, context.cacheDir))

    @After
    fun release() {
        finder.release()
        masker.release()
    }

    @Test
    fun aPortrait_masksTheSkin_notTheEyesLipsOrBackground() {
        val document = photo("Neil_Armstrong_pose.jpg")
        val (faces, mask) = skinOf(document, "portrait")

        assertEquals(1, faces.size)
        assertSkin(faces[0], mask)
        assertEquals(0, mask.alphaAt(8, 8))
    }

    @Test
    fun aGroupPhoto_masksEveryFace() {
        val document = photo("Apollo_11_Crew.jpg")
        val (faces, mask) = skinOf(document, "group")

        assertEquals(3, faces.size)
        faces.forEach { assertSkin(it, mask) }
        assertEquals(0, mask.alphaAt(8, 8))
    }

    /**
     * 피부 색 보정(결정 기록 '피부 보정'의 3단계)을 실제 피부 사진에 건다: 피부 안의 밝기는 거의 그대로이고, 연두(색상 상한 너머)로 가는 픽셀이 없다.
     * 앞·뒤를 나란히 붙인 그림을 skin-debug/[이름]-color.png에 남긴다(맥에서 보고 상수를 다듬는 데 쓴다). 사진이 없으면 건너뛴다.
     */
    @Test
    fun colorSliders_onRealSkin_keepTheLightness_andNeverTurnGreen() {
        val names = listOf("flushed_hotwings.jpg" to 1, "redhead_freckles.jpg" to 4, "teen_acne.jpg" to 1, "redlight_navy.jpg" to 1, "Buzz_Aldrin.jpg" to 1)
            .filter { File(File(context.filesDir, "face"), it.first).isFile }
        assumeTrue("No skin test photos in files/face", names.isNotEmpty())
        for ((name, sample) in names) {
            val document = (PhotoImporter.import(ImageDecoder.createSource(file(name)), "Photo", sample) as ImportResult.Success).document
            val created = try {
                CreateSkinRetouch(LayerId(0), SkinLayerNames("Skin", "Color", "Smooth", "Spots"), finder, masker).applyTo(document)
            } catch (e: NoFaceException) {
                Log.i("SkinPhoto", "$name: no face")
                continue
            }
            val colorLayer = created.layers[3]
            val color = checkNotNull((colorLayer.content as AdjustmentContent).skin)
            val fixed = SetSkinColor(colorLayer.id, color.withValues(local = 1f, global = 0.6f, even = 0.6f)).applyTo(created)
            var level = 0
            while (maxOf(document.width, document.height) shr level > 1024) level++
            val before = checkNotNull(PreviewRenderer.render(created, level)).copy(Bitmap.Config.ARGB_8888, false)
            val after = checkNotNull(PreviewRenderer.render(fixed, level)).copy(Bitmap.Config.ARGB_8888, false)
            val mask = checkNotNull(colorLayer.mask)
            val lab0 = FloatArray(3)
            val lab1 = FloatArray(3)
            var count = 0
            var lightness = 0.0
            var green = 0
            for (y in 0 until before.height) {
                for (x in 0 until before.width) {
                    if (mask.alphaAt(minOf(x shl level, document.width - 1), minOf(y shl level, document.height - 1)) < 200) continue
                    val p0 = before.getPixel(x, y)
                    val p1 = after.getPixel(x, y)
                    OkLab.fromRgb(Color.red(p0) / 255f, Color.green(p0) / 255f, Color.blue(p0) / 255f, lab0)
                    OkLab.fromRgb(Color.red(p1) / 255f, Color.green(p1) / 255f, Color.blue(p1) / 255f, lab1)
                    count++
                    lightness += abs(lab1[0] - lab0[0])
                    val hue0 = Math.toDegrees(atan2(lab0[2], lab0[1]).toDouble())
                    val hue1 = Math.toDegrees(atan2(lab1[2], lab1[1]).toDouble())
                    if (hue0 < 62.0 && hue1 > 67.0 && hypot(lab1[1], lab1[2]) > 0.02f) green++
                }
            }
            val redCast = created.layers[4].skin?.redCast
            Log.i("SkinPhoto", "$name: ${color.faces.size} faces ${color.faces.map { Math.toDegrees(it.tone.hue.toDouble()).toInt() }}°, red cast $redCast, $count skin pixels, mean |dL| ${lightness / count}, greener $green")
            saveSideBySide(before, after, "${name.substringBeforeLast('.')}-color")
            before.recycle()
            after.recycle()
            if (count == 0) continue
            assertTrue("$name mean |dL| ${lightness / count}", lightness / count < 0.02)
            assertTrue("$name greener $green of $count", green < count / 100 + 1)
        }
    }

    /**
     * 피부 정돈(결정 기록 '피부 보정'의 4단계)을 실제 피부 사진에 다 올려 건다: 얼굴 둘레를 원본 크기로 잘라 앞·뒤를 나란히 붙인 그림을
     * skin-debug/[이름]-smooth.png에 남기고(맥에서 보고 상수를 다듬는 데 쓴다), 피부 안의 밝기 평균이 거의 그대로인지 본다. 사진이 없으면 건너뛴다.
     */
    @Test
    fun smoothing_onRealSkin_keepsTheLightness() {
        val names = listOf("redhead_freckles.jpg" to 2, "flushed_hotwings.jpg" to 1, "Buzz_Aldrin.jpg" to 1)
            .filter { File(File(context.filesDir, "face"), it.first).isFile }
        assumeTrue("No skin test photos in files/face", names.isNotEmpty())
        for ((name, sample) in names) {
            val document = (PhotoImporter.import(ImageDecoder.createSource(file(name)), "Photo", sample) as ImportResult.Success).document
            val started = SystemClock.elapsedRealtime()
            val created = try {
                CreateSkinRetouch(LayerId(0), SkinLayerNames("Skin", "Color", "Smooth", "Spots"), finder, masker).applyTo(document)
            } catch (e: NoFaceException) {
                continue
            }
            val smoothLayer = created.layers[2]
            Log.i("SkinPhoto", "$name ${document.width}x${document.height}: created with smoothing in ${SystemClock.elapsedRealtime() - started} ms, ${(smoothLayer.content as RasterContent).raster.tiles.size} smooth tiles")
            val smoothed = SetLayerOpacity(smoothLayer.id, 1f).applyTo(created)
            val face = checkNotNull(created.layers[4].skin).faces[0]
            val reach = (face.radius * 1.2f).toInt()
            val rect = PixelRect((face.centerX - reach).toInt(), (face.centerY - reach).toInt(), (face.centerX + reach).toInt(), (face.centerY + reach).toInt())
            val region = checkNotNull(PreviewLevels.region(PixelSize(document.width, document.height), 0, rect))
            val before = checkNotNull(PreviewRenderer.renderRegion(created, region)).copy(Bitmap.Config.ARGB_8888, false)
            val after = checkNotNull(PreviewRenderer.renderRegion(smoothed, region)).copy(Bitmap.Config.ARGB_8888, false)
            val mask = checkNotNull(smoothLayer.mask)
            val lab = FloatArray(3)
            var sum0 = 0.0
            var sum1 = 0.0
            var count = 0
            for (y in 0 until before.height step 2) {
                for (x in 0 until before.width step 2) {
                    if (mask.alphaAt(region.rect.left + x, region.rect.top + y) < 200) continue
                    val p0 = before.getPixel(x, y)
                    val p1 = after.getPixel(x, y)
                    OkLab.fromRgb(Color.red(p0) / 255f, Color.green(p0) / 255f, Color.blue(p0) / 255f, lab)
                    sum0 += lab[0]
                    OkLab.fromRgb(Color.red(p1) / 255f, Color.green(p1) / 255f, Color.blue(p1) / 255f, lab)
                    sum1 += lab[0]
                    count++
                }
            }
            Log.i("SkinPhoto", "$name: smoothed face ${before.width}x${before.height}, mean L ${sum0 / count} -> ${sum1 / count}")
            saveSideBySide(before, after, "${name.substringBeforeLast('.')}-smooth")
            before.recycle()
            after.recycle()
            assertTrue("$name mean L ${sum0 / count} -> ${sum1 / count}", abs(sum1 / count - sum0 / count) < 0.01)
        }
    }

    /** 앞·뒤 그림을 나란히 붙여 앱 캐시 skin-debug/[name].png로 남긴다 */
    private fun saveSideBySide(before: Bitmap, after: Bitmap, name: String) {
        val joined = Bitmap.createBitmap(before.width * 2, before.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(joined)
        canvas.drawBitmap(before, 0f, 0f, null)
        canvas.drawBitmap(after, before.width.toFloat(), 0f, null)
        val folder = File(context.cacheDir, "skin-debug").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { joined.compress(Bitmap.CompressFormat.PNG, 100, it) }
        joined.recycle()
    }

    /** 볼·이마는 피부, 눈동자·윗입술은 피부가 아니다 */
    private fun assertSkin(face: FaceLandmarks, mask: Selection) {
        val cheekX = (face.x(FaceMeshIndex.RIGHT_EYE_OUTER) + face.x(FaceMeshIndex.MOUTH_RIGHT)) / 2f
        val cheekY = (face.y(FaceMeshIndex.RIGHT_EYE_OUTER) + face.y(FaceMeshIndex.MOUTH_RIGHT)) / 2f
        val browX = (face.x(105) + face.x(334)) / 2f
        val browY = (face.y(105) + face.y(334)) / 2f
        val foreheadX = browX + (face.x(FaceMeshIndex.FOREHEAD) - browX) * 0.5f
        val foreheadY = browY + (face.y(FaceMeshIndex.FOREHEAD) - browY) * 0.5f
        val lipX = (face.x(FaceMeshIndex.UPPER_LIP_TOP) + face.x(FaceMeshIndex.UPPER_LIP_INNER)) / 2f
        val lipY = (face.y(FaceMeshIndex.UPPER_LIP_TOP) + face.y(FaceMeshIndex.UPPER_LIP_INNER)) / 2f
        val cheek = mask.alphaAt(cheekX.toInt(), cheekY.toInt())
        val forehead = mask.alphaAt(foreheadX.toInt(), foreheadY.toInt())
        val eye = mask.alphaAt(face.x(FaceMeshIndex.RIGHT_IRIS).toInt(), face.y(FaceMeshIndex.RIGHT_IRIS).toInt())
        val lip = mask.alphaAt(lipX.toInt(), lipY.toInt())
        assertTrue("cheek $cheek", cheek > 200)
        assertTrue("forehead $forehead", forehead > 200)
        assertTrue("eye $eye", eye < 60)
        assertTrue("lip $lip", lip < 60)
    }

    /** [document]의 얼굴과 피부 마스크. 확인용 그림을 skin-debug/[name].png에 남긴다. */
    private fun skinOf(document: Document, name: String): Pair<List<FaceLandmarks>, Selection> {
        val layer = document.layers[0]
        val started = SystemClock.elapsedRealtime()
        val picture = finder.render(document, layer)
        try {
            val faces = try {
                finder.search(picture)
            } catch (e: FaceUnavailableException) {
                assumeNoException("The face models cannot run on this device", e)
                emptyList()
            }
            val found = SystemClock.elapsedRealtime()
            val masks = masker.masks(picture, faces)
            assertTrue("The skin model should run here", masks.segmented)
            val masked = SystemClock.elapsedRealtime()
            val size = PixelSize(document.width, document.height)
            val mask = Selection.of(masker.tiles(masks.masks, size))
            Log.i(
                "SkinPhoto",
                "$name ${document.width}x${document.height}: ${faces.size} faces, find ${found - started} ms, masks ${masked - found} ms, " +
                    "tiles ${SystemClock.elapsedRealtime() - masked} ms (${mask.tiles.size} tiles, ${mask.byteCount / 1024} KB)"
            )
            saveDebug(document, mask, name)
            faces.forEachIndexed { index, face -> saveFaceDebug(document, mask, face, "$name-face$index") }
            return faces to mask
        } finally {
            picture.close()
        }
    }

    /** 사진을 긴 변 1024px 안으로 그리고 피부 마스크를 빨갛게 덮어 앱 캐시 skin-debug/[name].png로 남긴다 */
    private fun saveDebug(document: Document, mask: Selection, name: String) {
        var level = 0
        while (maxOf(document.width, document.height) shr level > 1024) level++
        val drawn = PreviewRenderer.render(document, level) ?: return
        val image = drawn.copy(Bitmap.Config.ARGB_8888, true)
        drawn.recycle()
        try {
            val scale = 1 shl level
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val alpha = mask.alphaAt(minOf(x * scale, document.width - 1), minOf(y * scale, document.height - 1)) / 255f
                    if (alpha <= 0f) continue
                    val pixel = image.getPixel(x, y)
                    val r = (Color.red(pixel) * (1 - 0.5f * alpha) + 255 * 0.5f * alpha).toInt()
                    val g = (Color.green(pixel) * (1 - 0.5f * alpha)).toInt()
                    val b = (Color.blue(pixel) * (1 - 0.5f * alpha)).toInt()
                    image.setPixel(x, y, Color.rgb(r, g, b))
                }
            }
            val folder = File(context.cacheDir, "skin-debug").apply { mkdirs() }
            FileOutputStream(File(folder, "$name.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
    }

    /** 얼굴 둘레(특징점 상자의 두 배)를 원본 크기의 절반으로 잘라 마스크를 빨갛게 덮은 확인용 그림 */
    private fun saveFaceDebug(document: Document, mask: Selection, face: FaceLandmarks, name: String) {
        val bounds = face.bounds()
        val margin = maxOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) * 0.5f
        val left = (bounds[0] - margin).toInt().coerceIn(0, document.width - 1)
        val top = (bounds[1] - margin).toInt().coerceIn(0, document.height - 1)
        val right = (bounds[2] + margin).toInt().coerceIn(left + 1, document.width)
        val bottom = (bounds[3] + margin * 1.6f).toInt().coerceIn(top + 1, document.height)
        val drawn = PreviewRenderer.render(document, 1) ?: return
        val full = drawn.copy(Bitmap.Config.ARGB_8888, false)
        drawn.recycle()
        val image = Bitmap.createBitmap(full, left / 2, top / 2, (right - left) / 2, (bottom - top) / 2).copy(Bitmap.Config.ARGB_8888, true)
        full.recycle()
        try {
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val alpha = mask.alphaAt(minOf(left + x * 2, document.width - 1), minOf(top + y * 2, document.height - 1)) / 255f
                    if (alpha <= 0f) continue
                    val pixel = image.getPixel(x, y)
                    val r = (Color.red(pixel) * (1 - 0.45f * alpha) + 255 * 0.45f * alpha).toInt()
                    val g = (Color.green(pixel) * (1 - 0.45f * alpha)).toInt()
                    val b = (Color.blue(pixel) * (1 - 0.45f * alpha)).toInt()
                    image.setPixel(x, y, Color.rgb(r, g, b))
                }
            }
            val folder = File(context.cacheDir, "skin-debug").apply { mkdirs() }
            FileOutputStream(File(folder, "$name.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
    }

    private fun file(name: String): File {
        val file = File(File(context.filesDir, "face"), name)
        assumeTrue("The test photo is not in ${file.parent}", file.isFile)
        return file
    }

    private fun photo(name: String): Document {
        val result = PhotoImporter.import(ImageDecoder.createSource(file(name)), "Photo", 1)
        return (result as ImportResult.Success).document
    }
}

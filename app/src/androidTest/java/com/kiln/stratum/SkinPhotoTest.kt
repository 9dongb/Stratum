package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceUnavailableException
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.LiteRtFaceModel
import com.kiln.stratum.engine.platform.LiteRtSkinModel
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.SkinMasker
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.Selection
import java.io.File
import java.io.FileOutputStream
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

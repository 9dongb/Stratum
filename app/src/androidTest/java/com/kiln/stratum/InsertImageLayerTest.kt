package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.InsertImageLayer
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PhotoInfo
import com.kiln.stratum.engine.platform.PhotoUnreadableException
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 사진 넣기([InsertImageLayer], 사용자 피드백 12차: 레이어 패널의 사진 넣기)를 에뮬레이터의 안드로이드 해독기로 확인한다:
 * 문서보다 작은 사진은 그대로 가운데에(픽셀 값 그대로), 큰 사진은 비율대로 줄여 가운데에 놓고, 고른 자리에 새 레이어를 만든다.
 * P3 문서에는 P3 값으로 넣고, 읽지 못하는 파일은 [PhotoUnreadableException]이다.
 */
@RunWith(AndroidJUnit4::class)
class InsertImageLayerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "insert-image-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** 자리마다 다른 불투명한 색. 어느 픽셀이 어디로 갔는지 알 수 있다. */
    private fun colorAt(x: Int, y: Int): Int = 0xFF000000.toInt() or ((y * 4099 + x) and 0xFFFFFF)

    /** [width]×[height] PNG 파일(손실 없음). 색은 [color]다. */
    private fun png(width: Int, height: Int, name: String = "photo.png", color: (Int, Int) -> Int = ::colorAt): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { color(it % width, it / width) }, 0, width, 0, 0, width, height)
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun insert(document: Document, file: File, index: Int = document.layers.size, name: String = "Photo"): Document {
        val source = ImageDecoder.createSource(file)
        val info = checkNotNull(PhotoImporter.readInfo(source))
        return InsertImageLayer(source, info, name, index).applyTo(document)
    }

    private fun Document.raster(index: Int): Raster = (layers[index].content as RasterContent).raster

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun assertClose(message: String, expected: Int, actual: Int, tolerance: Int = 2) {
        for (shift in listOf(24, 16, 8, 0)) {
            val e = (expected ushr shift) and 0xFF
            val a = (actual ushr shift) and 0xFF
            assertTrue("$message: expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}", abs(e - a) <= tolerance)
        }
    }

    @Test
    fun aSmallPhoto_goesToTheMiddleAsIs_asANewLayer_pixelExact() {
        val document = Document.create(1000, 800, "Background", Raster.EMPTY)
        val after = insert(document, png(300, 200), index = 1, name = "street")

        assertEquals(2, after.layers.size)
        val layer = after.layers[1]
        assertEquals(LayerId(document.nextLayerId), layer.id)
        assertEquals("street", layer.name)
        assertEquals(document.nextLayerId + 1, after.nextLayerId)
        // 가운데 (350, 300)부터 그대로
        val raster = after.raster(1)
        for ((x, y) in listOf(0 to 0, 299 to 0, 0 to 199, 299 to 199, 150 to 100, 257 to 33)) {
            assertEquals("($x, $y)", colorAt(x, y), pixel(raster, 350 + x, 300 + y))
        }
        // 밖은 투명
        for ((x, y) in listOf(349 to 300, 650 to 300, 350 to 299, 350 to 500)) assertEquals("($x, $y)", 0, pixel(raster, x, y))
        // 원래 레이어는 그대로
        assertEquals(document.layers[0], after.layers[0])
    }

    @Test
    fun aBigPhoto_isShrunkToFit_keepingItsRatio_inTheMiddle() {
        val document = Document.create(1000, 800, "Background", Raster.EMPTY)
        // 2000×1000: 왼쪽 반 빨강, 오른쪽 반 파랑 → 1000×500으로 줄여 (0, 150)에
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val after = insert(document, png(2000, 1000) { x, _ -> if (x < 1000) red else blue })

        val raster = after.raster(1)
        assertClose("left", red, pixel(raster, 250, 400))
        assertClose("right", blue, pixel(raster, 750, 400))
        // 위아래 끝 줄은 불투명하고, 그 밖은 투명하다
        assertEquals(0xFF, pixel(raster, 500, 150) ushr 24)
        assertEquals(0xFF, pixel(raster, 500, 649) ushr 24)
        assertEquals(0, pixel(raster, 500, 149))
        assertEquals(0, pixel(raster, 500, 650))
        assertEquals(0xFF, pixel(raster, 0, 400) ushr 24)
        assertEquals(0xFF, pixel(raster, 999, 400) ushr 24)
    }

    @Test
    fun theNewLayerGoesToTheGivenIndex() {
        val document = Document.create(400, 300, "Background", Raster.EMPTY)
        val bottom = insert(document, png(10, 10), index = 0)
        assertEquals(LayerId(document.nextLayerId), bottom.layers[0].id)
        assertEquals(document.layers[0].id, bottom.layers[1].id)
    }

    @Test
    fun aP3Document_getsTheSameColorAsP3Values() {
        val document = Document.create(400, 300, "Background", Raster.EMPTY).copy(colorSpace = DocumentColorSpace.DISPLAY_P3)
        val red = 0xFFFF0000.toInt()
        val after = insert(document, png(100, 100) { _, _ -> red })

        // sRGB 빨강은 P3 값으로 약 (234, 51, 35)다(같은 색으로 보이게)
        assertClose("sRGB red as P3", 0xFFEA3323.toInt(), pixel(after.raster(1), 200, 150), tolerance = 3)
    }

    @Test
    fun aFileThatIsNotAPhoto_throwsUnreadable_andTheDocumentStays() {
        val document = Document.create(400, 300, "Background", Raster.EMPTY)
        val file = File(dir, "note.png").apply { writeText("not a photo") }
        val command = InsertImageLayer(ImageDecoder.createSource(file), PhotoInfo(PixelSize(10, 10), "image/png"), "Photo", 1)

        assertThrows(PhotoUnreadableException::class.java) { command.applyTo(document) }
    }
}

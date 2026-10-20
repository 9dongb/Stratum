package com.kiln.stratum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.DocumentColors
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.InpaintRegion
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 넓은 색(Display P3) 사진을 에뮬레이터에서 확인한다(결정 기록 '넓은 색'): 들여올 때 값이 깎이지 않고,
 * 내보낸 파일에 P3 색 정보가 붙고, sRGB로 고른 색은 P3 값으로 바꿔 칠하고, 저장·불러오기와 화면 그림 꼬리표.
 */
@RunWith(AndroidJUnit4::class)
class WideColorTest {

    private val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    private val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    private val width = 300
    private val height = 200

    /** sRGB 안에 다 들어가지 않는 P3 값(가로로 세 띠: 진한 빨강·초록·주황) */
    private fun wideValue(x: Int): Int =
        when (x * 3 / width) {
            0 -> Color.rgb(250, 30, 20)
            1 -> Color.rgb(20, 230, 40)
            else -> Color.rgb(240, 140, 10)
        }

    /** 값 [value]를 [space]의 값으로 담은 PNG(그 색 정보가 붙는다) */
    private fun png(space: ColorSpace, value: (x: Int) -> Int): ByteArray {
        // 값을 그대로 넣으려고 sRGB 꼬리표로 채운 뒤 꼬리표만 바꾼다(setPixels는 sRGB 값으로 보고 바꾼다)
        val bitmap = Bitmap.createBitmap(IntArray(width * height) { value(it % width) }, width, height, Bitmap.Config.ARGB_8888)
            .copy(Bitmap.Config.ARGB_8888, true)
        bitmap.setColorSpace(space)
        return ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    }

    private fun import(bytes: ByteArray): Document {
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private fun rasterOf(document: Document): Raster = (document.layers[0].content as RasterContent).raster

    /** 문서 좌표의 값(꼬리표와 상관없이 저장된 값, 알파를 곱하지 않음) */
    private fun value(raster: Raster, x: Int, y: Int): Int =
        checkNotNull(raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]).pixelAt(x % TILE_SIZE, y % TILE_SIZE)

    private fun near(expected: Int, actual: Int, tolerance: Int = 1) =
        abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int) = Integer.toHexString(color)

    /** sRGB 색 [srgbColor]와 같아 보이는 P3 값 */
    private fun inP3(srgbColor: Int): Int {
        val v = ColorSpace.connect(srgb, p3).transform(Color.red(srgbColor) / 255f, Color.green(srgbColor) / 255f, Color.blue(srgbColor) / 255f)
        return Color.rgb((v[0] * 255).roundToInt(), (v[1] * 255).roundToInt(), (v[2] * 255).roundToInt())
    }

    @Test
    fun displayP3Photo_becomesAP3Document_keepingItsValues() {
        val document = import(png(p3, ::wideValue))

        assertEquals(DocumentColorSpace.DISPLAY_P3, document.colorSpace)
        val raster = rasterOf(document)
        for (x in listOf(10, 150, 290)) {
            assertTrue("x $x: ${hex(value(raster, x, 100))}", near(wideValue(x), value(raster, x, 100), tolerance = 0))
        }
    }

    @Test
    fun adobeRgbPhoto_isConvertedIntoP3() {
        val adobe = ColorSpace.get(ColorSpace.Named.ADOBE_RGB)
        val document = import(png(adobe, ::wideValue))

        assertEquals(DocumentColorSpace.DISPLAY_P3, document.colorSpace)
        val v = ColorSpace.connect(adobe, p3).transform(240 / 255f, 140 / 255f, 10 / 255f)
        val expected = Color.rgb((v[0].coerceIn(0f, 1f) * 255).roundToInt(), (v[1].coerceIn(0f, 1f) * 255).roundToInt(), (v[2].coerceIn(0f, 1f) * 255).roundToInt())
        assertTrue("${hex(expected)} vs ${hex(value(rasterOf(document), 290, 100))}", near(expected, value(rasterOf(document), 290, 100), tolerance = 2))
    }

    @Test
    fun aiRemove_onAP3Document_fillsWithTheRawValues_andKeepsTheColorSpace() {
        // sRGB에 다 들어가지 않는 P3 값 한 가지로 칠한 사진. 모델에 값을 그대로 넣으므로(sRGB로 바꾸지 않음) 채운 곳도 같은 값이다.
        val wide = Color.rgb(250, 30, 20)
        val document = import(png(p3) { wide })

        val removed = InpaintRegion(LayerId(0), InpaintArea.Painted(listOf(StrokePoint(150f, 100f)), 20f), FakeInpaintModel()).applyTo(document)

        assertEquals(DocumentColorSpace.DISPLAY_P3, removed.colorSpace)
        val raster = rasterOf(removed)
        assertNotSame(rasterOf(document), raster)
        assertTrue(hex(value(raster, 150, 100)), near(wide, value(raster, 150, 100), tolerance = 2))
        assertEquals(wide, value(raster, 10, 10))
    }

    @Test
    fun srgbPhoto_staysAnSrgbDocument() {
        assertEquals(DocumentColorSpace.SRGB, import(png(srgb, ::wideValue)).colorSpace)
    }

    /** [document]를 원본 크기 [format]으로 내보내 다시 푼 그림(색 공간 그대로) */
    private fun exportAndDecode(document: Document, format: ExportFormat): Bitmap {
        val bytes = ByteArrayOutputStream().also { out ->
            ImageExporter.render(document, ExportSettings(format, 95, PixelSize(width, height))).use { it.writeTo(out) }
        }.toByteArray()
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    @Test
    fun p3Document_exportsWithTheP3Profile_andTheSameValues() {
        val document = import(png(p3, ::wideValue))

        val png = exportAndDecode(document, ExportFormat.PNG)
        assertEquals(p3, png.colorSpace)
        // 값을 그대로 읽으려고 꼬리표만 sRGB로 바꾼다(getPixel은 sRGB 값으로 바꿔 돌려준다)
        png.setColorSpace(srgb)
        for (x in listOf(10, 150, 290)) assertTrue("x $x", near(wideValue(x), png.getPixel(x, 100), tolerance = 0))
        assertEquals(p3, exportAndDecode(document, ExportFormat.JPEG).colorSpace)
        assertEquals(p3, exportAndDecode(document, ExportFormat.WEBP).colorSpace)
    }

    /** 흰 바탕의 P3 문서(새 캔버스를 P3로 표시한 것) */
    private fun p3Canvas(): Document =
        checkNotNull(BlankCanvas.create(width, height, CanvasFill.Solid(Color.WHITE), "Background")).copy(colorSpace = DocumentColorSpace.DISPLAY_P3)

    private val red = BrushSettings(size = 40f, hardness = 1f, color = Color.RED)

    @Test
    fun brushColor_isPaintedAsTheSameLookingP3Value() {
        val painted = PaintStroke(LayerId(0), listOf(StrokePoint(150f, 100f)), red, erase = false).applyTo(p3Canvas())

        val actual = value(rasterOf(painted), 150, 100)
        assertTrue("${hex(inP3(Color.RED))} vs ${hex(actual)}", near(inP3(Color.RED), actual))
        // sRGB 문서에는 고른 값 그대로다
        val srgbPainted = PaintStroke(LayerId(0), listOf(StrokePoint(150f, 100f)), red, erase = false).applyTo(p3Canvas().copy(colorSpace = DocumentColorSpace.SRGB))
        assertEquals(Color.RED, value(rasterOf(srgbPainted), 150, 100))
    }

    @Test
    fun livePreview_paintsTheP3ValueToo() {
        val preview = checkNotNull(
            LayerPreviewCache().render(p3Canvas(), level = 0, strokes = listOf(LiveStroke(LayerId(0), listOf(StrokePoint(150f, 100f)), red, erase = false)))
        ).copy(Bitmap.Config.ARGB_8888, false)

        assertTrue(hex(preview.getPixel(150, 100)), near(inP3(Color.RED), preview.getPixel(150, 100), tolerance = 2))
    }

    @Test
    fun projectFolder_keepsTheColorSpace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "wide-color-${System.nanoTime()}")
        try {
            ProjectFolder(directory).save(import(png(p3, ::wideValue)), 1L, 2L)

            val loaded = ProjectFolder(directory).load().document
            assertEquals(DocumentColorSpace.DISPLAY_P3, loaded.colorSpace)
            assertEquals(wideValue(10), value(rasterOf(loaded), 10, 100))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun forScreen_wrapsTheSameGpuPixelsWithTheP3Tag_andLeavesSrgbAlone() {
        val document = import(png(p3, ::wideValue))
        val preview = checkNotNull(LayerPreviewCache().render(document, level = 0))

        val shown = DocumentColors.forScreen(preview, DocumentColorSpace.DISPLAY_P3)

        assertNotSame(preview, shown)
        assertEquals(p3, shown.colorSpace)
        assertEquals(Bitmap.Config.HARDWARE, shown.config)
        assertEquals(preview.width, shown.width)
        // 같은 값이다(꼬리표만 다르다). 엔진의 그림은 sRGB 꼬리표 그대로다.
        val values = shown.copy(Bitmap.Config.ARGB_8888, false).also { it.setColorSpace(srgb) }
        assertTrue(near(wideValue(150), values.getPixel(150, 100), tolerance = 0))
        assertEquals(srgb, preview.colorSpace)
        assertSame(preview, DocumentColors.forScreen(preview, DocumentColorSpace.SRGB))
    }
}

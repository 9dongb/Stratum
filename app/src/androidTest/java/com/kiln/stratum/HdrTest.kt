package com.kiln.stratum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HDR 사진(Ultra HDR JPEG, 게인맵)을 에뮬레이터(안드로이드 37)에서 확인한다(결정 기록 'HDR'): 들여올 때 게인맵을 지니고,
 * 돌리기·자르기에 따라가고, JPEG로 내보내면 게인맵이 붙으며 위 레이어에 칠한 곳은 '밝히지 않음'이고, 저장·불러오기에 남는다.
 */
@RunWith(AndroidJUnit4::class)
class HdrTest {

    private val width = 800
    private val height = 600

    /** 왼쪽 절반은 게인맵 200(많이 밝힘), 오른쪽 절반은 50인 회색 Ultra HDR JPEG. 게인맵은 가로·세로 1/4, 1채널. */
    private fun ultraHdrJpeg(): ByteArray {
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 120, 120)) }
        val gainWidth = width / 4f
        val gainHeight = height / 4f
        val contents = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ALPHA_8)
        // apply 안의 width·height는 캔버스 크기라 바깥에서 정한 값을 쓴다
        Canvas(contents).apply {
            drawRect(0f, 0f, gainWidth / 2, gainHeight, Paint().apply { alpha = 200 })
            drawRect(gainWidth / 2, 0f, gainWidth, gainHeight, Paint().apply { alpha = 50 })
        }
        base.gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(4f, 4f, 4f)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0.015625f, 0.015625f, 0.015625f)
            setEpsilonHdr(0.015625f, 0.015625f, 0.015625f)
            displayRatioForFullHdr = 4f
            minDisplayRatioForHdrTransition = 1f
        }
        return ByteArrayOutputStream().also { check(base.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
    }

    private fun import(bytes: ByteArray): Document {
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private fun HdrGainmap.value(x: Int, y: Int): Int =
        Color.red(checkNotNull(raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]).pixelAt(x % TILE_SIZE, y % TILE_SIZE))

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 8) =
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= tolerance)

    @Test
    fun ultraHdrPhoto_keepsItsGainmapAndValues() {
        val document = import(ultraHdrJpeg())

        val hdr = checkNotNull(document.hdr)
        assertEquals(4f, hdr.ratioMax.r, 0.05f)
        assertEquals(1f, hdr.ratioMin.r, 0.05f)
        assertTrue(hdr.singleChannel)
        val gainmap = checkNotNull(document.layers[0].gainmap)
        assertEquals(width / 4, gainmap.width)
        assertEquals(height / 4, gainmap.height)
        assertNear(200, gainmap.value(20, 70))
        assertNear(50, gainmap.value(180, 70))
        assertTrue(document.hasHdr)
    }

    @Test
    fun gainmap_followsRotation_cropping_andResizing() {
        val document = import(ultraHdrJpeg())

        // 시계 방향으로 돌리면 왼쪽(밝힘)이 위로 간다
        val turned = checkNotNull(RotateImage(clockwise = true).applyTo(document).layers[0].gainmap)
        assertEquals(height / 4, turned.width)
        assertEquals(width / 4, turned.height)
        assertNear(200, turned.value(70, 20))
        assertNear(50, turned.value(70, 180))
        // 오른쪽 절반만 남기면 게인맵도 오른쪽 절반(50)만 남는다
        val cropped = checkNotNull(CropImage(PixelRect(400, 0, 800, 600), PixelSize(width, height)).applyTo(document).layers[0].gainmap)
        assertEquals(width / 8, cropped.width)
        assertNear(50, cropped.value(10, 70))
        // 크기를 바꿔도 게인맵은 늘여 덮으므로 그대로다
        assertSame(document.layers[0].gainmap, ResizeImage(400, 300).applyTo(document).layers[0].gainmap)
    }

    /** [document]를 원본 크기 [format]으로 내보내 다시 푼 그림 */
    private fun exportAndDecode(document: Document, format: ExportFormat): Bitmap {
        val bytes = ByteArrayOutputStream().also { out ->
            ImageExporter.render(document, ExportSettings(format, 95, PixelSize(width, height))).use { it.writeTo(out) }
        }.toByteArray()
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** 게인맵 그림의 값(1채널은 알파) */
    private fun Bitmap.gainValue(x: Int, y: Int): Int {
        val pixel = getPixel(x, y)
        return if (config == Bitmap.Config.ALPHA_8) Color.alpha(pixel) else Color.red(pixel)
    }

    @Test
    fun jpegExport_isUltraHdr_andPaintedLayersAreNotBrightened() {
        val photo = import(ultraHdrJpeg())
        // 왼쪽 위 타일 하나(256px)를 불투명한 빨강으로 덮은 레이어
        val paint = Layer(LayerId(photo.nextLayerId), "Paint", RasterContent(Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(Color.RED)))))
        val document = photo.copy(layers = photo.layers + paint, nextLayerId = photo.nextLayerId + 1)

        val exported = exportAndDecode(document, ExportFormat.JPEG)

        assertTrue(exported.hasGainmap())
        val gainmap = checkNotNull(exported.gainmap)
        assertEquals(4f, gainmap.ratioMax[0], 0.05f)
        val contents = gainmap.gainmapContents
        assertEquals(width / 4, contents.width)
        // 칠한 곳(게인맵 왼쪽 위 64칸)은 '밝히지 않음'(0), 나머지는 사진의 게인맵 그대로
        assertNear(0, contents.gainValue(30, 30))
        assertNear(200, contents.gainValue(30, 120))
        assertNear(50, contents.gainValue(180, 120))
    }

    @Test
    fun otherBlendModes_keepThePhotosGainmap() {
        val photo = import(ultraHdrJpeg())
        val tint = Layer(
            LayerId(photo.nextLayerId), "Tint", RasterContent(Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(Color.rgb(200, 180, 160))))),
            blendMode = BlendMode.MULTIPLY
        )

        val exported = exportAndDecode(photo.copy(layers = photo.layers + tint, nextLayerId = photo.nextLayerId + 1), ExportFormat.JPEG)

        assertNear(200, checkNotNull(exported.gainmap).gainmapContents.gainValue(30, 30))
    }

    @Test
    fun pngAndWebpExport_haveNoGainmap() {
        val document = import(ultraHdrJpeg())

        assertFalse(exportAndDecode(document, ExportFormat.PNG).hasGainmap())
        assertFalse(exportAndDecode(document, ExportFormat.WEBP).hasGainmap())
    }

    @Test
    fun projectFolder_keepsTheGainmapAndHdrValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "hdr-${System.nanoTime()}")
        try {
            val document = import(ultraHdrJpeg())
            ProjectFolder(directory).save(document, 1L, 2L)

            val loaded = ProjectFolder(directory).load().document
            assertEquals(document.hdr, loaded.hdr)
            val gainmap = checkNotNull(loaded.layers[0].gainmap)
            assertEquals(document.layers[0].gainmap?.width, gainmap.width)
            assertEquals(checkNotNull(document.layers[0].gainmap).value(20, 70), gainmap.value(20, 70))
        } finally {
            directory.deleteRecursively()
        }
    }
}

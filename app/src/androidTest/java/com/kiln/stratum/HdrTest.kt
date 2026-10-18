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
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.SetLayerAdjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.CopySelectedToLayer
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.DocumentColors
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiquifyLayer
import com.kiln.stratum.engine.platform.MergeDown
import com.kiln.stratum.engine.platform.MergeVisible
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.platform.RetouchStroke
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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

    private val bigBrush = BrushSettings(size = 120f, hardness = 1f, color = Color.BLUE)

    @Test
    fun brushOnThePhoto_makesTheGainmapNeutralUnderTheStroke_butTheEraserDoesNot() {
        val document = import(ultraHdrJpeg())

        // 문서 (200, 300)은 게인맵 (50, 75): 왼쪽 절반(200)
        val painted = checkNotNull(PaintStroke(LayerId(0), listOf(StrokePoint(200f, 300f)), bigBrush, erase = false).applyTo(document).layers[0].gainmap)
        assertNear(0, painted.value(50, 75))
        assertNear(200, painted.value(10, 10))
        val erased = PaintStroke(LayerId(0), listOf(StrokePoint(200f, 300f)), bigBrush, erase = true).applyTo(document)
        assertSame(document.layers[0].gainmap, erased.layers[0].gainmap)
    }

    @Test
    fun brushWithASelection_neutralizesOnlyInsideIt() {
        val selected = SelectShape(SelectionShape.Rectangle(0f, 0f, 200f, 600f), SelectionMode.REPLACE).applyTo(import(ultraHdrJpeg()))

        // 선택 경계(문서 x 200, 게인맵 x 50)에 걸친 획: 안쪽만 '밝히지 않음'이 된다
        val gainmap = checkNotNull(PaintStroke(LayerId(0), listOf(StrokePoint(200f, 300f)), bigBrush, erase = false).applyTo(selected).layers[0].gainmap)
        assertNear(0, gainmap.value(40, 75))
        assertNear(200, gainmap.value(60, 75))
    }

    @Test
    fun liquify_warpsTheGainmapWithThePixels() {
        val document = import(ultraHdrJpeg())
        // 경계(문서 x 400)에서 오른쪽으로 100px 밀면 경계 오른쪽에 왼쪽(밝힘)이 온다
        val mesh = LiquifyMesh.identity(PixelSize(width, height)).apply { brush(LiquifyTool.PUSH, 400f, 300f, 200f, 1f, moveX = 100f) }

        val warped = checkNotNull(LiquifyLayer(LayerId(0), mesh).applyTo(document).layers[0].gainmap)

        assertNear(200, warped.value(105, 75), tolerance = 20)
        // 멀리 떨어진 곳은 그대로다
        assertNear(50, warped.value(190, 10))
    }

    @Test
    fun cloneStamp_copiesTheGainmapFromTheSource() {
        val document = import(ultraHdrJpeg())

        // 오른쪽 (600, 300)에 왼쪽 (300, 300)을 베낀다
        val cloned = RetouchStroke(LayerId(0), listOf(StrokePoint(600f, 300f)), bigBrush.copy(size = 80f), RetouchMode.CLONE, SourceOffset(300, 0))
            .applyTo(document)

        val gainmap = checkNotNull(cloned.layers[0].gainmap)
        assertNear(200, gainmap.value(150, 75))
        assertNear(50, gainmap.value(190, 10))
    }

    @Test
    fun copyingTheSelection_sharesTheGainmap() {
        val selected = SelectShape(SelectionShape.Rectangle(100f, 100f, 300f, 300f), SelectionMode.REPLACE).applyTo(import(ultraHdrJpeg()))

        val copied = CopySelectedToLayer(LayerId(0), "Copy", cut = false).applyTo(selected)

        assertSame(selected.layers[0].gainmap, copied.layers[1].gainmap)
    }

    @Test
    fun merging_combinesTheGainmapsByTheSameRule() {
        val photo = import(ultraHdrJpeg())
        val paint = Layer(LayerId(photo.nextLayerId), "Paint", RasterContent(Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(Color.RED)))))
        val document = photo.copy(layers = photo.layers + paint, nextLayerId = photo.nextLayerId + 1)

        for (merged in listOf(MergeDown(paint.id).applyTo(document), MergeVisible.applyTo(document))) {
            assertEquals(1, merged.layers.size)
            val gainmap = checkNotNull(merged.layers[0].gainmap)
            assertNear(0, gainmap.value(30, 30))
            assertNear(200, gainmap.value(30, 120))
            assertNear(50, gainmap.value(180, 120))
        }
    }

    @Test
    fun screenGainmap_isTheComposedGainmap_andIsReusedUntilTheLayersChange() {
        val document = import(ultraHdrJpeg())
        val cache = LayerPreviewCache()
        checkNotNull(cache.render(document, level = 1))

        val gainmap = checkNotNull(cache.screenGainmap(document, level = 1))

        // 축소본(400×300)과 게인맵(200×150) 중 작은 쪽
        val shown = DocumentColors.forScreen(checkNotNull(cache.render(document, level = 1)), DocumentColorSpace.SRGB, gainmap, PixelRect(0, 0, width, height), PixelSize(width, height))
        assertTrue(shown.hasGainmap())
        val contents = checkNotNull(shown.gainmap).gainmapContents
        assertEquals(width / 4, contents.width)
        assertNear(200, contents.gainValue(30, 75))
        assertNear(50, contents.gainValue(170, 75))
        // 보정만 바뀌면 다시 만들지 않고, 픽셀이 바뀌면 다시 만든다
        val adjusted = SetLayerAdjustments(LayerId(0), Adjustments(exposure = 0.5f)).applyTo(document)
        assertSame(gainmap, cache.screenGainmap(adjusted, level = 1))
        val painted = PaintStroke(LayerId(0), listOf(StrokePoint(200f, 300f)), bigBrush, erase = false).applyTo(document)
        checkNotNull(cache.render(painted, level = 1))
        assertNotSame(gainmap, cache.screenGainmap(painted, level = 1))
        assertNull(LayerPreviewCache().screenGainmap(document.copy(hdr = null), level = 1))
    }

    @Test
    fun forScreen_attachesOnlyThePartTheDetailShows() {
        val document = import(ultraHdrJpeg())
        val cache = LayerPreviewCache()
        val preview = checkNotNull(cache.render(document, level = 1))
        val gainmap = checkNotNull(cache.screenGainmap(document, level = 1))

        // 오른쪽 절반을 그린 부분 그림에는 오른쪽 절반(50)의 게인맵만 붙는다
        val detail = DocumentColors.forScreen(preview, DocumentColorSpace.SRGB, gainmap, PixelRect(400, 0, 800, 600), PixelSize(width, height))

        assertNotSame(preview, detail)
        assertFalse(preview.hasGainmap())
        val contents = checkNotNull(detail.gainmap).gainmapContents
        assertEquals(width / 8, contents.width)
        assertNear(50, contents.gainValue(10, 75))
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

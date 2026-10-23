package com.kiln.stratum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.DuplicateLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.platform.AddLayerMask
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.DeleteLayerMask
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.InvertLayerMask
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiquifyRenderer
import com.kiln.stratum.engine.platform.MergeDown
import com.kiln.stratum.engine.platform.MergeVisible
import com.kiln.stratum.engine.platform.OrientLayer
import com.kiln.stratum.engine.platform.PaintLayerMask
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.project.ProjectManifestCodec
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.transform.Orientation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 레이어 마스크(결정 기록 '피부 보정'의 1c)를 에뮬레이터에서 확인한다: 그릴 때 곱해지는지(축소본·레이어 축소본·내보내기),
 * 병합하면 픽셀에 굳는지, 더하기·지우기·뒤집기·칠하기, 변형을 따라가는지, 프로젝트에 저장되는지.
 */
@RunWith(AndroidJUnit4::class)
class LayerMaskTest {

    private val width = 600
    private val height = 300
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "mask-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** [color]로 칠한 사진을 PNG로 만들어 앱과 같은 방법으로 불러온 래스터 */
    private fun raster(color: (x: Int, y: Int) -> Int): Raster {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Layer")
        val document = (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
        return (document.layers[0].content as RasterContent).raster
    }

    private val gradient: Raster by lazy { raster { x, y -> Color.rgb(x * 255 / width, y * 255 / height, 200 - x / 3) } }

    private val red: Raster by lazy { raster { _, _ -> Color.RED } }

    /** 끝 타일(2, 0)의 캔버스 안쪽 폭 */
    private val edgeWidth = width - 2 * TILE_SIZE

    /**
     * 시험 마스크: (0, 0) 타일은 가림(없음), (1, 0)은 다 보임, (2, 0)은 왼쪽에서 오른쪽으로 짙어지는 알파, 아래 줄은 다 보임.
     * 끝 타일은 캔버스 안쪽(88px)에만 알파가 있다.
     */
    private val mask: Selection by lazy {
        Selection.of(
            mapOf(
                TileKey(1, 0) to MaskTile.FULL,
                TileKey(2, 0) to MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { i -> rampAlpha(i % TILE_SIZE).toByte() }),
                TileKey(0, 1) to MaskTile.FULL,
                TileKey(1, 1) to MaskTile.FULL,
                TileKey(2, 1) to MaskTile.FULL
            )
        )
    }

    private fun rampAlpha(x: Int): Int = if (x < edgeWidth) x * 255 / (edgeWidth - 1) else 0

    /** 시험 마스크의 문서 ([x], [y]) 알파 */
    private fun maskAlpha(x: Int, y: Int): Int =
        when {
            y >= TILE_SIZE -> 255
            x < TILE_SIZE -> 0
            x < 2 * TILE_SIZE -> 255
            else -> rampAlpha(x - 2 * TILE_SIZE)
        }

    private fun layer(id: Long, raster: Raster, mask: Selection? = null) = Layer(LayerId(id), "L$id", RasterContent(raster), mask = mask)

    private fun document(vararg layers: Layer) = Document(width, height, layers.toList(), nextLayerId = layers.maxOf { it.id.value } + 1)

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(software.width * software.height).also { software.getPixels(it, 0, software.width, 0, 0, software.width, software.height) }
    }

    /** 문서를 원본 크기로 합성한 픽셀(알파를 곱하지 않은 ARGB) */
    private fun look(document: Document): IntArray = pixelsOf(checkNotNull(PreviewRenderer.render(document, level = 0)))

    private fun near(a: Int, b: Int, tolerance: Int = 2): Boolean =
        abs(Color.alpha(a) - Color.alpha(b)) <= tolerance && (Color.alpha(a) < 32 ||
            (abs(Color.red(a) - Color.red(b)) <= tolerance + 2 && abs(Color.green(a) - Color.green(b)) <= tolerance + 2 &&
                abs(Color.blue(a) - Color.blue(b)) <= tolerance + 2))

    private fun assertSameLook(a: IntArray, b: IntArray, tolerance: Int = 2) {
        for (i in a.indices) {
            assertTrue("pixel (${i % width}, ${i / width}): ${Integer.toHexString(a[i])} vs ${Integer.toHexString(b[i])}", near(a[i], b[i], tolerance))
        }
    }

    /** 원본 래스터의 문서 ([x], [y]) 색 */
    private fun colorOf(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    @Test
    fun renderer_multipliesTheMaskAlpha() {
        val pixels = look(document(layer(0, gradient, mask)))

        for ((x, y) in listOf(10 to 10, 300 to 100, 520 to 50, 560 to 200, 599 to 0, 100 to 290)) {
            val color = pixels[y * width + x]
            assertTrue("($x, $y) alpha ${Color.alpha(color)} vs ${maskAlpha(x, y)}", abs(Color.alpha(color) - maskAlpha(x, y)) <= 1)
        }
        // 보이는 곳의 색은 그대로다
        assertTrue(near(colorOf(gradient, 300, 100) or (0xFF shl 24), pixels[100 * width + 300]))
    }

    @Test
    fun maskedLayer_showsTheLowerLayerWhereHidden() {
        val pixels = look(document(layer(0, red), layer(1, gradient, mask)))

        assertEquals(Color.RED, pixels[10 * width + 10])
        assertTrue(near(pixels[100 * width + 300], Color.rgb(300 * 255 / width, 100 * 255 / height, 200 - 300 / 3)))
        // 반쯤 보이는 곳은 아래 빨강과 섞인다
        val half = pixels[50 * width + 2 * TILE_SIZE + edgeWidth / 2]
        assertEquals(255, Color.alpha(half))
        assertTrue(Color.red(half) > 120 && Color.green(half) > 20)
    }

    @Test
    fun layerCache_andExport_matchTheRenderer() {
        val document = document(layer(0, red), layer(1, gradient, mask))
        val tiles = look(document)

        assertSameLook(tiles, pixelsOf(checkNotNull(LayerPreviewCache().render(document, level = 0))))
        val png = ByteArrayOutputStream().also { out ->
            ImageExporter.render(document, ExportSettings(ExportFormat.PNG, 100, PixelSize(width, height))).use { it.writeTo(out) }
        }.toByteArray()
        assertSameLook(tiles, pixelsOf(checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))))
    }

    @Test
    fun merges_bakeTheMaskIntoPixels() {
        val before = document(layer(0, red), layer(1, gradient, mask))

        val down = MergeDown(LayerId(1)).applyTo(before)
        assertEquals(1, down.layers.size)
        assertNull(down.layers[0].mask)
        assertSameLook(look(before), look(down))

        val visible = MergeVisible.applyTo(before)
        assertNull(visible.layers.single().mask)
        assertSameLook(look(before), look(visible))
    }

    @Test
    fun merging_aFullyHiddenLayer_keepsNoTransparentTiles() {
        // 마스크가 다 가린 레이어를 합쳐도 투명한 타일을 남기지 않는다(메모리, 결정 기록 '피부 보정'의 1i)
        val hidden = document(layer(0, Raster.EMPTY), layer(1, gradient, Selection.EMPTY))

        val merged = MergeVisible.applyTo(hidden)

        assertTrue((merged.layers.single().content as RasterContent).raster.tiles.isEmpty())
    }

    @Test
    fun liquifyPreview_warpsThePixels_andAppliesTheMaskUnwarped() {
        // 유동화는 마스크가 가린 픽셀도 비틀고 마스크는 그대로라, 미리 보기는 마스크 없이 그린 레이어를 비튼 뒤 비틀지 않은 마스크를 곱한다(1i)
        val document = document(layer(0, gradient, mask))
        val image = checkNotNull(LiquifyRenderer.layerPreview(document, LayerId(0), level = 0))
        assertEquals(255, Color.alpha(pixelsOf(image)[10 * width + 10]))
        val alpha = checkNotNull(LiquifyRenderer.layerMask(document, LayerId(0), 0, image.width, image.height))
        val mesh = LiquifyMesh.identity(PixelSize(width, height))
        val field = LiquifyRenderer.fieldBitmap(mesh)
        LiquifyRenderer.fill(field, FloatArray(mesh.columns * mesh.rows * 2), ShortArray(mesh.columns * mesh.rows * 4))

        val preview = pixelsOf(checkNotNull(LiquifyRenderer.preview(image, 0, field, mesh.cellSize, alpha)))

        // 비틀지 않으면 마스크를 건 레이어 그대로다
        assertSameLook(look(document), preview)
        assertNull(LiquifyRenderer.layerMask(document(layer(0, gradient)), LayerId(0), 0, image.width, image.height))
    }

    @Test
    fun addDeleteInvert_andDuplicateShareTheMask() {
        val plain = document(layer(0, gradient))

        // 선택 영역이 없으면 다 보이는 마스크(타일 한 장을 함께 씀)
        val revealed = AddLayerMask(LayerId(0)).applyTo(plain)
        val all = checkNotNull(revealed.layers[0].mask)
        assertEquals(TileGrid(width, height).keys().toSet(), all.tiles.keys)
        assertTrue(all.tiles.values.all { it === MaskTile.FULL })
        assertSame(revealed, AddLayerMask(LayerId(0)).applyTo(revealed))

        // 선택 영역이 있으면 그 모양이고 선택을 푼다
        val selected = AddLayerMask(LayerId(0)).applyTo(plain.copy(selection = mask))
        assertSame(mask, selected.layers[0].mask)
        assertNull(selected.selection)

        // 뒤집기: 가린 곳은 보이고 보이던 곳은 가린다
        val inverted = checkNotNull(InvertLayerMask(LayerId(0)).applyTo(selected).layers[0].mask)
        assertSame(MaskTile.FULL, inverted.tiles[TileKey(0, 0)])
        assertNull(inverted.tiles[TileKey(1, 0)])
        assertEquals(255 - rampAlpha(30), inverted.alphaAt(2 * TILE_SIZE + 30, 40))

        assertNull(DeleteLayerMask(LayerId(0)).applyTo(selected).layers[0].mask)
        val duplicated = DuplicateLayer(LayerId(0), "copy").applyTo(selected)
        assertSame(mask, duplicated.layers[1].mask)
    }

    @Test
    fun paintingTheMask_revealsAndHides_insideTheSelection() {
        val hidden = document(layer(0, gradient, Selection.EMPTY))
        val brush = BrushSettings(size = 80f, hardness = 1f, opacity = 1f)

        val revealed = PaintLayerMask(LayerId(0), listOf(StrokePoint(300f, 150f)), brush, reveal = true).applyTo(hidden)
        val revealedMask = checkNotNull(revealed.layers[0].mask)
        assertEquals(255, revealedMask.alphaAt(300, 150))
        assertEquals(0, revealedMask.alphaAt(400, 150))

        val halfHidden = PaintLayerMask(LayerId(0), listOf(StrokePoint(300f, 150f)), brush.copy(opacity = 0.5f), reveal = false).applyTo(revealed)
        assertTrue(abs(checkNotNull(halfHidden.layers[0].mask).alphaAt(300, 150) - 128) <= 1)

        // 선택 영역 밖에는 칠하지 않는다
        val clipped = PaintLayerMask(LayerId(0), listOf(StrokePoint(300f, 150f)), brush, reveal = true)
            .applyTo(hidden.copy(selection = Selection.of(mapOf(TileKey(0, 0) to MaskTile.FULL))))
        assertSame(hidden.layers[0].mask, clipped.layers[0].mask)
    }

    @Test
    fun transforms_carryTheMaskWithThePixels() {
        val masked = document(layer(0, gradient, mask))

        val turned = checkNotNull(RotateImage(clockwise = true).applyTo(masked).layers[0].mask)
        for ((x, y) in listOf(0 to 0, 299 to 599, 150 to 540, 10 to 300)) {
            val from = Orientation.ROTATE_CLOCKWISE.inverse.mapPixel(x, y, height, width)
            assertEquals("turned ($x, $y)", maskAlpha(from.x, from.y), turned.alphaAt(x, y))
        }

        val cropped = checkNotNull(CropImage(PixelRect(500, 0, 600, 300), PixelSize(width, height)).applyTo(masked).layers[0].mask)
        assertEquals(maskAlpha(530, 20), cropped.alphaAt(30, 20))

        val moved = checkNotNull(TranslateLayer(LayerId(0), 20, 0).applyTo(masked).layers[0].mask)
        assertEquals(maskAlpha(530, 20), moved.alphaAt(550, 20))
        assertEquals(0, moved.alphaAt(5, 280))

        val flipped = checkNotNull(OrientLayer(LayerId(0), Orientation.FLIP_HORIZONTAL).applyTo(masked).layers[0].mask)
        assertEquals(maskAlpha(width - 1 - 40, 20), flipped.alphaAt(40, 20))

        // 크기를 바꾸면 마스크도 픽셀과 같이 줄어든다(경계는 보간)
        val resized = checkNotNull(ResizeImage(300, 150).applyTo(masked).layers[0].mask)
        assertEquals(0, resized.alphaAt(20, 20))
        assertEquals(255, resized.alphaAt(200, 20))
        assertEquals(255, resized.alphaAt(100, 140))
    }

    @Test
    fun projectFolder_savesAndLoadsTheMask() {
        val document = document(layer(0, red), layer(1, gradient, mask))
        val folder = ProjectFolder(directory)

        folder.save(document, createdMillis = 1, modifiedMillis = 2)

        // 판 2로 쓰고, 다 보이는 타일은 파일 없이 적는다(마스크 파일은 경계 타일 하나)
        val manifest = FileInputStream(File(directory, ProjectFolder.MANIFEST)).use { ProjectManifestCodec.read(it) }
        assertEquals(1, manifest.layers[1].mask?.count { it.file != null })
        val maskFiles = File(directory, "tiles").list().orEmpty().filter { it.endsWith(".mask") }
        assertEquals(1, maskFiles.size)
        val loaded = ProjectFolder(directory).load().document.layers[1].mask
        assertNotNull(loaded)
        assertSame(MaskTile.FULL, checkNotNull(loaded).tiles[TileKey(1, 0)])
        for (x in 2 * TILE_SIZE until width) assertEquals(maskAlpha(x, 9), loaded.alphaAt(x, 9))
        assertNull(loaded.tiles[TileKey(0, 0)])
        // 같은 마스크로 다시 저장하면 새 파일을 쓰지 않는다
        folder.save(document, createdMillis = 1, modifiedMillis = 3)
        assertEquals(maskFiles.toSet(), File(directory, "tiles").list().orEmpty().filter { it.endsWith(".mask") }.toSet())
    }
}

package com.kiln.stratum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.AdjustmentMath
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.command.AddAdjustmentLayer
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.MergeDown
import com.kiln.stratum.engine.platform.MergeVisible
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 조정 레이어(결정 기록 '피부 보정'의 1e)를 에뮬레이터에서 확인한다: 아래 모두에 보정이 걸리는지(기준 계산과 같은지), 마스크·불투명도,
 * 투명한 곳은 그대로인지, 레이어 축소본·내보내기, 병합에 굳는지, 더하기, 변형, 저장.
 */
@RunWith(AndroidJUnit4::class)
class AdjustmentLayerTest {

    private val width = 600
    private val height = 300
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "adjustment-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun raster(color: (x: Int, y: Int) -> Int): Raster {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Layer")
        val document = (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
        return (document.layers[0].content as RasterContent).raster
    }

    private fun gradientColor(x: Int, y: Int): Int = Color.rgb(x * 255 / width, y * 255 / height, 200 - x / 3)

    private val gradient: Raster by lazy { raster(::gradientColor) }

    /** 왼쪽 반은 그러데이션, 오른쪽 반은 투명 */
    private val half: Raster by lazy { raster { x, y -> if (x < width / 2) gradientColor(x, y) else Color.TRANSPARENT } }

    private val red: Raster by lazy { raster { _, _ -> Color.RED } }

    private val gray = Adjustments(saturation = -1f, exposure = 0.3f)

    private fun pixelLayer(id: Long, raster: Raster) = Layer(LayerId(id), "L$id", RasterContent(raster))

    private fun adjustment(id: Long, adjustments: Adjustments = gray, opacity: Float = 1f, mask: Selection? = null, mode: BlendMode = BlendMode.NORMAL) =
        Layer(LayerId(id), "A$id", AdjustmentContent, opacity, mode, adjustments, mask = mask)

    private fun document(vararg layers: Layer) = Document(width, height, layers.toList(), nextLayerId = layers.maxOf { it.id.value } + 1)

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(software.width * software.height).also { software.getPixels(it, 0, software.width, 0, 0, software.width, software.height) }
    }

    private fun look(document: Document): IntArray = pixelsOf(checkNotNull(PreviewRenderer.render(document, level = 0)))

    /** [adjustments]를 기준 계산으로 [color]에 건 색 */
    private fun reference(adjustments: Adjustments, color: Int): Int {
        val c = AdjustmentMath.apply(adjustments, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        return Color.argb(Color.alpha(color), (c[0] * 255).roundToInt(), (c[1] * 255).roundToInt(), (c[2] * 255).roundToInt())
    }

    private fun near(a: Int, b: Int, tolerance: Int = 3): Boolean =
        abs(Color.alpha(a) - Color.alpha(b)) <= tolerance && (Color.alpha(a) < 32 ||
            (abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance))

    private fun assertSameLook(a: IntArray, b: IntArray) {
        for (i in a.indices) assertTrue("pixel (${i % width}, ${i / width}): ${Integer.toHexString(a[i])} vs ${Integer.toHexString(b[i])}", near(a[i], b[i]))
    }

    private val points = listOf(10 to 10, 300 to 100, 520 to 50, 590 to 290, 120 to 280)

    @Test
    fun adjustment_changesEverythingBelow_likeTheReference() {
        val pixels = look(document(pixelLayer(0, gradient), adjustment(1)))

        for ((x, y) in points) {
            val expected = reference(gray, gradientColor(x, y))
            assertTrue("($x, $y): ${Integer.toHexString(pixels[y * width + x])} vs ${Integer.toHexString(expected)}", near(expected, pixels[y * width + x]))
        }
    }

    @Test
    fun maskAndOpacity_limitTheAdjustment_andTransparentAreasStayTransparent() {
        // 왼쪽 두 타일은 가림, 끝 타일은 보임
        val mask = Selection.of(mapOf(TileKey(2, 0) to MaskTile.FULL, TileKey(2, 1) to MaskTile.FULL))
        val masked = look(document(pixelLayer(0, gradient), adjustment(1, mask = mask)))
        assertEquals(gradientColor(100, 100), masked[100 * width + 100])
        assertTrue(near(reference(gray, gradientColor(560, 100)), masked[100 * width + 560]))

        val faded = look(document(pixelLayer(0, gradient), adjustment(1, opacity = 0.5f)))
        val original = gradientColor(300, 100)
        val adjusted = reference(gray, original)
        val expected = Color.rgb((Color.red(original) + Color.red(adjusted)) / 2, (Color.green(original) + Color.green(adjusted)) / 2, (Color.blue(original) + Color.blue(adjusted)) / 2)
        assertTrue(near(expected, faded[100 * width + 300], 4))

        // 투명한 곳(오른쪽 반)은 투명한 채다
        val overHalf = look(document(pixelLayer(0, half), adjustment(1)))
        assertEquals(0, Color.alpha(overHalf[100 * width + 450]))
        assertTrue(near(reference(gray, gradientColor(100, 100)), overHalf[100 * width + 100]))
    }

    @Test
    fun layerCache_andExport_matchTheRenderer() {
        val document = document(pixelLayer(0, red), pixelLayer(1, half), adjustment(2, mode = BlendMode.MULTIPLY), pixelLayer(3, Raster.EMPTY))
        val tiles = look(document)

        assertSameLook(tiles, pixelsOf(checkNotNull(LayerPreviewCache().render(document, level = 0))))
        val png = ByteArrayOutputStream().also { out ->
            ImageExporter.render(document, ExportSettings(ExportFormat.PNG, 100, PixelSize(width, height))).use { it.writeTo(out) }
        }.toByteArray()
        assertSameLook(tiles, pixelsOf(checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))))
    }

    @Test
    fun merges_bakeTheAdjustment() {
        val before = document(pixelLayer(0, red), pixelLayer(1, half), adjustment(2))

        // 아래와 병합: 바로 아래 레이어에만 굳힌다(맨 아래 빨강은 그대로라 모습이 달라짐)
        val down = MergeDown(LayerId(2)).applyTo(before)
        assertEquals(listOf(LayerId(0), LayerId(1)), down.layers.map { it.id })
        val downLook = look(down)
        assertTrue(near(reference(gray, gradientColor(100, 100)), downLook[100 * width + 100]))
        assertEquals(Color.RED, downLook[100 * width + 450])

        // 보이는 레이어 병합: 보이던 모습 그대로
        val visible = MergeVisible.applyTo(before)
        assertEquals(1, visible.layers.size)
        assertSameLook(look(before), look(visible))
        // 조정 레이어 위로는 합치지 않는다
        val onto = document(adjustment(0), pixelLayer(1, gradient))
        assertSame(onto, MergeDown(LayerId(1)).applyTo(onto))
    }

    @Test
    fun add_usesTheSelection_andTransformsMoveTheMask() {
        val plain = document(pixelLayer(0, gradient))
        val selection = Selection.of(mapOf(TileKey(0, 0) to MaskTile.FULL))

        val added = AddAdjustmentLayer("Adjust", 1).applyTo(plain.copy(selection = selection))
        val layer = added.layers[1]
        assertEquals(AdjustmentContent, layer.content)
        assertSame(selection, layer.mask)
        assertNull(added.selection)
        val revealAll = AddAdjustmentLayer("Adjust", 1).applyTo(plain).layers[1].mask
        assertEquals(TileGrid(width, height).keys().toSet(), checkNotNull(revealAll).tiles.keys)

        // 옮기기는 마스크만, 이미지 회전은 마스크도 함께
        val moved = TranslateLayer(LayerId(1), TILE_SIZE, 0).applyTo(added).layers[1]
        assertSame(MaskTile.FULL, checkNotNull(moved.mask).tiles[TileKey(1, 0)])
        assertNull(moved.mask?.tiles?.get(TileKey(0, 0)))
        val turned = RotateImage(clockwise = true).applyTo(added)
        assertEquals(PixelSize(height, width), PixelSize(turned.width, turned.height))
        assertEquals(255, checkNotNull(turned.layers[1].mask).alphaAt(height - 1 - 10, 10))
    }

    @Test
    fun projectFolder_savesAndLoadsAdjustmentLayers() {
        val document = document(pixelLayer(0, gradient), adjustment(1, opacity = 0.7f, mode = BlendMode.SCREEN, mask = Selection.all(PixelSize(width, height))))

        ProjectFolder(directory).save(document, createdMillis = 1, modifiedMillis = 2)
        val loaded = ProjectFolder(directory).load().document.layers[1]

        assertEquals(AdjustmentContent, loaded.content)
        assertEquals(gray, loaded.adjustments)
        assertEquals(0.7f, loaded.opacity, 0f)
        assertEquals(BlendMode.SCREEN, loaded.blendMode)
        assertTrue(checkNotNull(loaded.mask).tiles.values.all { it === MaskTile.FULL })
    }
}

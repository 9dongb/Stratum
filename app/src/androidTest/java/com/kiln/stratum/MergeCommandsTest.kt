package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.MergeDown
import com.kiln.stratum.engine.platform.MergeVisible
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 레이어 병합(아래와 병합·보이는 레이어 병합)을 에뮬레이터에서 확인한다: 합친 뒤에도 보이던 모습 그대로인지(GPU 반올림 ±2),
 * 결과 레이어의 속성, 바뀌지 않는 타일은 같은 객체로 남는지, 숨긴 레이어는 그대로인지.
 */
@RunWith(AndroidJUnit4::class)
class MergeCommandsTest {

    private val width = 300
    private val height = 200

    /** [color]로 칠한 사진을 PNG로 만들어 앱과 같은 방법으로 불러온 래스터 */
    private fun raster(color: (x: Int, y: Int) -> Int): Raster {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Layer")
        val document = (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
        return (document.layers[0].content as RasterContent).raster
    }

    /** 불투명한 색 그러데이션 */
    private val gradient: Raster by lazy { raster { x, y -> Color.rgb(x * 255 / width, y * 255 / height, 200 - x / 3) } }

    /** 다른 방향의 그러데이션. 왼쪽 위는 반투명, 오른쪽 아래는 투명이다. */
    private val partial: Raster by lazy {
        raster { x, y ->
            when {
                x >= width / 2 && y >= height / 2 -> Color.TRANSPARENT
                x < width / 2 && y < height / 2 -> Color.argb(128, 255 - y, 128, x * 255 / width)
                else -> Color.rgb(255 - y, 128, x * 255 / width)
            }
        }
    }

    private fun layer(id: Long, raster: Raster, opacity: Float = 1f, mode: BlendMode = BlendMode.NORMAL, adjustments: Adjustments = Adjustments.NONE) =
        Layer(LayerId(id), "L$id", RasterContent(raster), opacity, mode, adjustments)

    private fun document(vararg layers: Layer) = Document(width, height, layers.toList(), nextLayerId = layers.maxOf { it.id.value } + 1)

    private fun rasterOf(document: Document, index: Int): Raster = (document.layers[index].content as RasterContent).raster

    /** 문서를 원본 크기로 합성한 픽셀(알파를 곱하지 않은 ARGB) */
    private fun look(document: Document): IntArray {
        val drawn = checkNotNull(PreviewRenderer.render(document, level = 0)).copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(width * height).also { drawn.getPixels(it, 0, width, 0, 0, width, height) }
    }

    /** 두 합성이 모든 픽셀에서 ±2 안으로 같다 */
    private fun assertSameLook(before: Document, after: Document) {
        val a = look(before)
        val b = look(after)
        for (i in a.indices) {
            val same = abs(Color.alpha(a[i]) - Color.alpha(b[i])) <= 2 && (Color.alpha(a[i]) == 0 ||
                (abs(Color.red(a[i]) - Color.red(b[i])) <= 2 && abs(Color.green(a[i]) - Color.green(b[i])) <= 2 && abs(Color.blue(a[i]) - Color.blue(b[i])) <= 2))
            assertTrue("pixel (${i % width}, ${i / width}): ${Integer.toHexString(a[i])} vs ${Integer.toHexString(b[i])}", same)
        }
    }

    @Test
    fun mergeDown_looksLikeBefore_bakingTheAdjustmentsOpacityAndBlendMode() {
        val before = document(
            layer(0, gradient, adjustments = Adjustments(exposure = 0.3f, contrast = 0.2f)),
            layer(1, partial, opacity = 0.6f, mode = BlendMode.MULTIPLY, adjustments = Adjustments(brightness = 0.2f))
        )

        val after = MergeDown(LayerId(1)).applyTo(before)

        assertEquals(1, after.layers.size)
        val merged = after.layers[0]
        assertEquals(LayerId(0), merged.id)
        assertEquals("L0", merged.name)
        assertEquals(Adjustments.NONE, merged.adjustments)
        assertEquals(before.nextLayerId, after.nextLayerId)
        assertSameLook(before, after)
    }

    @Test
    fun mergeDown_keepsTheLowerLayersOpacityAndBlendMode() {
        val before = document(
            layer(0, gradient),
            layer(1, partial, opacity = 0.7f, mode = BlendMode.SCREEN),
            layer(2, partial)
        )

        val after = MergeDown(LayerId(2)).applyTo(before)

        assertEquals(listOf(LayerId(0), LayerId(1)), after.layers.map { it.id })
        assertEquals(0.7f, after.layers[1].opacity, 0f)
        assertEquals(BlendMode.SCREEN, after.layers[1].blendMode)
        assertSame(before.layers[0], after.layers[0])
    }

    @Test
    fun mergeDown_reusesTilesThatDoNotChange() {
        val canvas = checkNotNull(BlankCanvas.create(1300, 600, CanvasFill.Solid(Color.GRAY), "Background"))
        val red = Tile.solid(Color.RED)
        val top = Layer(LayerId(1), "Top", RasterContent(Raster.EMPTY.withTiles(mapOf(TileKey(1, 1) to red))))
        val before = canvas.copy(layers = canvas.layers + top, nextLayerId = 2)
        val gray = rasterOf(before, 0).tiles.getValue(TileKey(0, 0))

        val after = MergeDown(LayerId(1)).applyTo(before)

        val merged = rasterOf(after, 0)
        // 위 레이어가 없는 자리는 아래 타일 그대로, 겹친 자리는 새로 그린 타일이다(불투명한 빨강이 덮음)
        assertSame(gray, merged.tiles[TileKey(0, 0)])
        // 구석 자리는 캔버스 안쪽 크기로 자른 회색 타일 그대로다(결정 기록 '캔버스 밖 픽셀')
        assertSame(rasterOf(before, 0).tiles[TileKey(5, 2)], merged.tiles[TileKey(5, 2)])
        val drawn = checkNotNull(merged.tiles[TileKey(1, 1)])
        assertNotSame(gray, drawn)
        assertEquals(Color.RED, drawn.pixelAt(10, 10))
    }

    @Test
    fun mergeDown_withAnEmptyOrHiddenUpperLayer_keepsTheLowerPixels_andOntoEmptyKeepsTheUpperPixels() {
        val emptyMerged = MergeDown(LayerId(1)).applyTo(document(layer(0, gradient), layer(1, Raster.EMPTY)))
        val hiddenMerged = MergeDown(LayerId(1)).applyTo(document(layer(0, gradient), layer(1, partial, opacity = 0f)))

        for ((key, tile) in gradient.tiles) {
            assertSame(tile, rasterOf(emptyMerged, 0).tiles[key])
            assertSame(tile, rasterOf(hiddenMerged, 0).tiles[key])
        }
        // 빈 레이어 위에 합치면 위 레이어 타일 그대로다(투명한 바탕에서는 혼합 모드와 상관없다)
        val onto = MergeDown(LayerId(1)).applyTo(document(layer(0, Raster.EMPTY), layer(1, partial, mode = BlendMode.OVERLAY)))
        for ((key, tile) in partial.tiles) assertSame(tile, rasterOf(onto, 0).tiles[key])
    }

    @Test
    fun mergeDown_onTheBottomOrAMissingLayer_keepsTheDocument() {
        val before = document(layer(0, gradient), layer(1, partial))

        assertSame(before, MergeDown(LayerId(0)).applyTo(before))
        assertSame(before, MergeDown(LayerId(9)).applyTo(before))
        assertEquals(0L, MergeDown(LayerId(0)).estimateNewBytes(before))
        assertTrue(MergeDown(LayerId(1)).estimateNewBytes(before) > 0L)
    }

    @Test
    fun mergeVisible_mergesIntoTheLowestVisibleLayer_lookingLikeBefore_andKeepsHiddenLayers() {
        val hiddenBottom = layer(0, gradient, opacity = 0f)
        val hiddenMiddle = layer(2, partial, opacity = 0f)
        val before = document(
            hiddenBottom,
            layer(1, gradient, opacity = 0.8f, mode = BlendMode.SCREEN, adjustments = Adjustments(saturation = -0.5f)),
            hiddenMiddle,
            layer(3, partial, mode = BlendMode.MULTIPLY, adjustments = Adjustments(contrast = 0.3f)),
            layer(4, Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(Color.RED))), opacity = 0.5f)
        )

        val after = MergeVisible.applyTo(before)

        assertEquals(listOf(LayerId(0), LayerId(1), LayerId(2)), after.layers.map { it.id })
        assertSame(hiddenBottom, after.layers[0])
        assertSame(hiddenMiddle, after.layers[2])
        val merged = after.layers[1]
        assertEquals("L1", merged.name)
        assertEquals(1f, merged.opacity, 0f)
        assertEquals(BlendMode.NORMAL, merged.blendMode)
        assertEquals(Adjustments.NONE, merged.adjustments)
        assertSameLook(before, after)
    }

    @Test
    fun mergeDown_withAHiddenLayer_keepsTheDocument() {
        // 눈을 끈 레이어는 위든 아래든 합치지 않는다(결정 기록 '레이어 보이기')
        val hiddenUpper = document(layer(0, gradient), layer(1, partial).copy(visible = false))
        val hiddenLower = document(layer(0, gradient).copy(visible = false), layer(1, partial))

        assertSame(hiddenUpper, MergeDown(LayerId(1)).applyTo(hiddenUpper))
        assertSame(hiddenLower, MergeDown(LayerId(1)).applyTo(hiddenLower))
        assertEquals(0L, MergeDown(LayerId(1)).estimateNewBytes(hiddenLower))
    }

    @Test
    fun mergeVisible_leavesLayersWithTheirEyeOff() {
        val hidden = layer(1, partial).copy(visible = false)
        val before = document(layer(0, gradient), hidden, layer(2, partial, mode = BlendMode.MULTIPLY))

        val after = MergeVisible.applyTo(before)

        assertEquals(listOf(LayerId(0), LayerId(1)), after.layers.map { it.id })
        assertSame(hidden, after.layers[1])
        assertSameLook(before, after)
    }

    @Test
    fun mergeVisible_withFewerThanTwoVisibleLayers_keepsTheDocument() {
        val one = document(layer(0, gradient), layer(1, partial, opacity = 0f))

        assertSame(one, MergeVisible.applyTo(one))
        assertEquals(0L, MergeVisible.estimateNewBytes(one))
    }
}

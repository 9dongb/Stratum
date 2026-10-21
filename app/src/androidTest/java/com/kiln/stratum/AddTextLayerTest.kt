package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.RasterizeLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.AddTextLayer
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.EditTextLayer
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 글자 레이어 더하기(글자를 픽셀로 그려 새 레이어로)를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class AddTextLayerTest {

    private fun white(): Document = checkNotNull(BlankCanvas.create(800, 600, CanvasFill.Solid(Color.WHITE), "Background"))

    private fun rasterOf(document: Document, index: Int): Raster = (document.layers[index].content as RasterContent).raster

    /** [raster]의 [left]..[right]×[top]..[bottom] 안에 불투명한 픽셀이 있는지(2픽셀 간격으로 본다) */
    private fun hasInk(raster: Raster, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val tile = raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: continue
                if (Color.alpha(tile.pixelAt(x % TILE_SIZE, y % TILE_SIZE)) > 128) return true
            }
        }
        return false
    }

    @Test
    fun text_isDrawnFromTheTopLeftPoint_intoANewLayer() {
        val document = white()
        val style = TextStyle(size = 80f, color = Color.RED)

        val added = AddTextLayer("Hello 안녕", x = 100f, y = 200f, style = style, name = "Hello", index = 1).applyTo(document)

        assertEquals(2, added.layers.size)
        val layer = added.layers[1]
        assertEquals(LayerId(1), layer.id)
        assertEquals("Hello", layer.name)
        assertEquals(2L, added.nextLayerId)
        val raster = rasterOf(added, 1)
        // 글자는 누른 자리의 오른쪽 아래로 그려지고, 그 위·왼쪽에는 없다
        assertTrue(hasInk(raster, 100, 200, 500, 300))
        assertTrue(!hasInk(raster, 0, 0, 800, 190))
        assertTrue(!hasInk(raster, 0, 200, 95, 300))
        // 글자 색이다(가운데 굵은 획 어딘가)
        val red = (100 until 500 step 2).flatMap { x -> (200 until 300 step 2).map { y -> x to y } }
            .map { (x, y) -> raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0 }
            .first { Color.alpha(it) == 255 }
        assertEquals(Color.RED, red)
    }

    @Test
    fun multipleLines_goDown() {
        val style = TextStyle(size = 60f)

        val added = AddTextLayer("A\nB\nC", x = 50f, y = 50f, style = style, name = "A", index = 1).applyTo(white())

        // 세 줄째(약 50 + 2 × 70px 아래)에도 글자가 있다
        assertTrue(hasInk(rasterOf(added, 1), 50, 190, 120, 260))
    }

    @Test
    fun blankText_orOutsideTheDocument_changesNothing() {
        val document = white()
        val style = TextStyle(size = 40f)

        assertSame(document, AddTextLayer("   ", 10f, 10f, style, "", 1).applyTo(document))
        assertSame(document, AddTextLayer("Far away", 5000f, 5000f, style, "Far", 1).applyTo(document))
    }

    @Test
    fun memoryEstimate_countsTheTouchedTiles() {
        val command = AddTextLayer("Hi", x = 10f, y = 10f, style = TextStyle(size = 40f), name = "Hi", index = 1)

        assertEquals(TILE_SIZE * TILE_SIZE * 4L, command.estimateNewBytes(white()))
    }

    @Test
    fun addedLayer_remembersTheText_soItCanBeEditedAgain() {
        // 사용자 피드백 8차 1-6: 다시 누르면 고칠 수 있게 글자 정보를 레이어에 둔다
        val style = TextStyle(size = 80f, color = Color.RED)

        val added = AddTextLayer("Hello", x = 100f, y = 200f, style = style, name = "Hello", index = 1).applyTo(white())

        val spec = checkNotNull(added.layers[1].text)
        assertEquals("Hello", spec.text)
        assertEquals(100f, spec.x, 0f)
        assertEquals(200f, spec.y, 0f)
        assertEquals(style, spec.style)
        // 글자 상자는 그린 글자를 덮는다
        assertTrue(spec.width > 100f && spec.height > 60f)
        assertTrue(spec.contains(120f, 240f))
    }

    @Test
    fun editTextLayer_redrawsInPlace_withTheNewTextAndStyle() {
        val added = AddTextLayer("Hello", x = 100f, y = 200f, style = TextStyle(size = 80f, color = Color.RED), name = "Hello", index = 1).applyTo(white())

        val edited = EditTextLayer(LayerId(1), "Hi", TextStyle(size = 40f, color = Color.BLUE), "Hi").applyTo(added)

        val layer = edited.layers[1]
        assertEquals("Hi", layer.name)
        val spec = checkNotNull(layer.text)
        assertEquals("Hi", spec.text)
        assertEquals(TextStyle(size = 40f, color = Color.BLUE), spec.style)
        // 같은 자리(왼쪽 위)에 다시 그리고, 전 글자(더 크고 긴 Hello)의 자국은 남지 않는다
        assertEquals(100f, spec.x, 0f)
        assertEquals(200f, spec.y, 0f)
        val raster = rasterOf(edited, 1)
        assertTrue(hasInk(raster, 100, 200, 100 + spec.width.toInt(), 200 + spec.height.toInt()))
        assertTrue(!hasInk(raster, 100 + spec.width.toInt() + 4, 200, 500, 300))
        val blue = (100 until 160 step 2).flatMap { x -> (200 until 250 step 2).map { y -> x to y } }
            .map { (x, y) -> raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0 }
            .first { Color.alpha(it) == 255 }
        assertEquals(Color.BLUE, blue)
        // 실행 취소는 문서 기록이 한다: 고치기 전 문서는 그대로다
        assertEquals("Hello", added.layers[1].text?.text)
    }

    @Test
    fun editTextLayer_onAPixelLayer_orWithBlankText_changesNothing() {
        val document = white()
        val added = AddTextLayer("Hello", x = 100f, y = 200f, style = TextStyle(size = 80f), name = "Hello", index = 1).applyTo(document)

        assertSame(document, EditTextLayer(LayerId(0), "Hi", TextStyle(size = 40f), "Hi").applyTo(document))
        assertSame(added, EditTextLayer(LayerId(1), "  ", TextStyle(size = 40f), "").applyTo(added))
        // 픽셀로 바꾼 레이어는 더 고칠 수 없다
        val rasterized = RasterizeLayer(LayerId(1)).applyTo(added)
        assertSame(rasterized, EditTextLayer(LayerId(1), "Hi", TextStyle(size = 40f), "Hi").applyTo(rasterized))
    }
}

package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.platform.ToneStroke
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.engine.tone.ToneFill
import com.kiln.stratum.engine.tone.ToneMath
import com.kiln.stratum.engine.tone.ToneRange
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 닷지·번 획을 원본 해상도에 그리는지(값은 [ToneMath]와 같은지), 끄는 동안의 미리 보기를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class ToneStrokeTest {

    private fun gray(level: Int) = Color.rgb(level, level, level)

    /** [color] 바탕 800×600 */
    private fun canvas(color: Int = gray(128)): Document = checkNotNull(BlankCanvas.create(800, 600, CanvasFill.Solid(color), "Background"))

    private fun rasterOf(document: Document, index: Int = 0): Raster = (document.layers[index].content as RasterContent).raster

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun dot(x: Float, y: Float) = listOf(StrokePoint(x, y))

    private val hard = BrushSettings(size = 40f, hardness = 1f)

    /** 회색 [level]을 [fill]로 바꾼 값(0..255, [ToneMath]의 식) */
    private fun expected(level: Int, fill: ToneFill): Int {
        val c = level / 255f
        return (ToneMath.tone(c, c, fill.range, fill.exposure, fill.dodge) * 255).roundToInt()
    }

    @Test
    fun dodgeBrightens_andBurnDarkens_asTheFormulaSays_onlyUnderTheStroke() {
        val document = canvas()
        val dodge = ToneFill(dodge = true, range = ToneRange.MIDTONES, exposure = 0.5f)
        val burn = ToneFill(dodge = false, range = ToneRange.MIDTONES, exposure = 0.5f)

        val dodged = rasterOf(ToneStroke(LayerId(0), dot(400f, 300f), hard, dodge).applyTo(document))
        val burned = rasterOf(ToneStroke(LayerId(0), dot(400f, 300f), hard, burn).applyTo(document))

        assertTrue(abs(Color.red(pixel(dodged, 400, 300)) - expected(128, dodge)) <= 2)
        assertTrue(abs(Color.red(pixel(burned, 400, 300)) - expected(128, burn)) <= 2)
        assertTrue(Color.red(pixel(dodged, 400, 300)) > 150 && Color.red(pixel(burned, 400, 300)) < 105)
        // 획 밖은 값이 그대로다
        assertEquals(gray(128), pixel(dodged, 400, 360))
        assertEquals(gray(128), pixel(burned, 100, 100))
    }

    @Test
    fun ranges_choseWhichBrightnessChangesMost() {
        val shadowsBurn = ToneFill(dodge = false, range = ToneRange.SHADOWS, exposure = 1f)
        val dark = Color.red(pixel(rasterOf(ToneStroke(LayerId(0), dot(400f, 300f), hard, shadowsBurn).applyTo(canvas(gray(50)))), 400, 300))
        val light = Color.red(pixel(rasterOf(ToneStroke(LayerId(0), dot(400f, 300f), hard, shadowsBurn).applyTo(canvas(gray(200)))), 400, 300))

        // 어두운 영역 번은 어두운 회색을 밝은 회색보다 많이 바꾼다(비율로)
        assertTrue("dark $dark light $light", (50 - dark) / 50f > (200 - light) / 200f)
        assertTrue(abs(dark - expected(50, shadowsBurn)) <= 2 && abs(light - expected(200, shadowsBurn)) <= 2)
    }

    @Test
    fun aStroke_appliesOnce_evenWhereDabsOverlap() {
        // 한 획 안에서 자국이 겹쳐도 한 번만 걸린다(브러시의 불투명도와 같다)
        val dodge = ToneFill(dodge = true, range = ToneRange.MIDTONES, exposure = 0.5f)
        val line = StrokeDabs.place(listOf(StrokePoint(300f, 300f), StrokePoint(500f, 300f)), 4f)

        val dodged = rasterOf(ToneStroke(LayerId(0), line, hard, dodge).applyTo(canvas()))

        assertTrue(abs(Color.red(pixel(dodged, 400, 300)) - expected(128, dodge)) <= 2)
    }

    @Test
    fun tone_respectsTheSelection_skipsEmptyTiles_andSharesUntouchedTiles() {
        val document = SelectShape(SelectionShape.Rectangle(0f, 0f, 400f, 600f), SelectionMode.REPLACE).applyTo(canvas())
        val line = StrokeDabs.place(listOf(StrokePoint(300f, 300f), StrokePoint(500f, 300f)), 2f)

        val burned = ToneStroke(LayerId(0), line, hard, ToneFill(dodge = false, exposure = 1f)).applyTo(document)

        val raster = rasterOf(burned)
        assertTrue(Color.red(pixel(raster, 350, 300)) < 128)
        assertEquals(gray(128), pixel(raster, 450, 300))
        // 획이 닿지 않은 타일은 같은 객체다
        assertSame(rasterOf(document).tiles[TileKey(0, 0)], raster.tiles[TileKey(0, 0)])
        // 빈(투명한) 레이어에는 바꿀 색이 없어 그대로다
        val withEmpty = AddLayer("Empty", document.layers.size).applyTo(document)
        assertSame(withEmpty, ToneStroke(withEmpty.layers.last().id, dot(100f, 100f), hard, ToneFill(dodge = true)).applyTo(withEmpty))
    }

    @Test
    fun tonePreview_showsTheChangedColor() {
        val document = canvas()
        val dodge = ToneFill(dodge = true, range = ToneRange.MIDTONES, exposure = 0.5f)

        val preview = checkNotNull(
            LayerPreviewCache().render(document, level = 1, strokes = listOf(LiveStroke(LayerId(0), dot(400f, 300f), hard, erase = false, tone = dodge)))
        ).copy(Bitmap.Config.ARGB_8888, false)

        assertTrue(abs(Color.red(preview.getPixel(200, 150)) - expected(128, dodge)) <= 3)
        assertEquals(128, Color.red(preview.getPixel(200, 190)))
    }
}

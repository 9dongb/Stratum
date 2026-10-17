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
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 끄는 동안의 획 미리 보기와, 획을 기록한 뒤 바뀐 곳만 고쳐 그리는 축소본을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class StrokePreviewTest {

    /** 흰 바탕 1024×768 + 빈 레이어 */
    private fun document(): Document =
        AddLayer("Paint", 1).applyTo(checkNotNull(BlankCanvas.create(1024, 768, CanvasFill.Solid(Color.WHITE), "Background")))

    private val settings = BrushSettings(size = 40f, hardness = 1f, opacity = 1f, color = Color.BLUE)

    private fun line(): List<StrokePoint> = StrokeDabs.place(listOf(StrokePoint(100f, 300f), StrokePoint(700f, 300f)), settings.spacing)

    private fun software(bitmap: Bitmap?): Bitmap = checkNotNull(bitmap).copy(Bitmap.Config.ARGB_8888, false)

    private fun near(expected: Int, actual: Int, tolerance: Int = 2) =
        abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance &&
            abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance

    @Test
    fun liveStroke_isDrawnOnItsLayer_beforeItIsRecorded() {
        val cache = LayerPreviewCache()
        val document = document()

        val live = software(cache.render(document, level = 1, strokes = listOf(LiveStroke(LayerId(1), line(), settings, erase = false))))

        // 1/2 크기 축소본에서 (400, 300)은 (200, 150)이다
        assertEquals(Color.BLUE, live.getPixel(200, 150))
        assertEquals(Color.WHITE, live.getPixel(200, 120))
    }

    @Test
    fun liveEraser_cutsThroughToTransparency() {
        val cache = LayerPreviewCache()
        val eraser = settings.copy(size = 60f)

        val live = software(cache.render(document(), level = 0, strokes = listOf(LiveStroke(LayerId(0), line(), eraser, erase = true))))

        assertEquals(0, Color.alpha(live.getPixel(400, 300)))
        assertEquals(Color.WHITE, live.getPixel(400, 200))
    }

    @Test
    fun afterRecording_onlyTheChangedTilesAreRedrawn_andMatchAFullRender() {
        val cache = LayerPreviewCache()
        val document = document()
        checkNotNull(cache.render(document, level = 1))
        val renders = cache.layerRenders

        val painted = PaintStroke(LayerId(1), line(), settings, erase = false).applyTo(document)
        val patched = software(cache.render(painted, level = 1))

        // 레이어를 통째로 다시 그리지 않고 고쳐 그렸다
        assertEquals(renders, cache.layerRenders)
        assertEquals(1, cache.layerPatches)
        val full = software(PreviewRenderer.render(painted, 1))
        for (y in 0 until full.height step 3) {
            for (x in 0 until full.width step 3) {
                if (!near(full.getPixel(x, y), patched.getPixel(x, y))) fail("($x, $y): ${Integer.toHexString(patched.getPixel(x, y))} vs ${Integer.toHexString(full.getPixel(x, y))}")
            }
        }
    }

    @Test
    fun strokeOver_drawsOnAZoomedRegion_atItsOffset() {
        val cache = LayerPreviewCache()
        val document = document()
        val region = checkNotNull(PreviewLevels.region(PixelSize(1024, 768), 0, PixelRect(256, 256, 768, 512)))
        val detail = checkNotNull(PreviewRenderer.renderRegion(document, region))

        val drawn = software(cache.strokeOver(detail, region, listOf(LiveStroke(LayerId(1), line(), settings, erase = false))))

        // 문서 (400, 300)은 이 영역 그림에서 (400 - 256, 300 - 256)이다
        assertEquals(Color.BLUE, drawn.getPixel(400 - region.rect.left, 300 - region.rect.top))
        assertTrue(near(Color.WHITE, drawn.getPixel(400 - region.rect.left, 360 - region.rect.top)))
    }
}

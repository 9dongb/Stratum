package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasViewTest {

    private val area = PixelSize(1000, 1600)
    private val photo = PixelSize(4000, 3000)

    /** 화면 x·y가 같은 문서 위치를 가리키는지(1/1000픽셀까지) */
    private fun assertSameDocumentPoint(before: ViewTransform, after: ViewTransform, beforeX: Double, beforeY: Double, afterX: Double, afterY: Double) {
        assertEquals(before.toDocumentX(beforeX), after.toDocumentX(afterX), 1e-3)
        assertEquals(before.toDocumentY(beforeY), after.toDocumentY(afterY), 1e-3)
    }

    @Test
    fun fit_matchesFitRect() {
        val transform = CanvasView.Fit.transform(area, photo)
        val fit = fitRect(area.width, area.height, photo)

        assertEquals(fit.left.toDouble(), transform.toScreenX(0.0), 1e-9)
        assertEquals(fit.top.toDouble(), transform.toScreenY(0.0), 1e-9)
        assertEquals((fit.left + fit.width).toDouble(), transform.toScreenX(4000.0), 1e-9)
        assertEquals((fit.top + fit.height).toDouble(), transform.toScreenY(3000.0), 1e-9)
    }

    @Test
    fun zoom_keepsDocumentPointUnderFingers() {
        val before = CanvasView.Fit

        val after = before.transformedBy(panX = 0.0, panY = 0.0, zoom = 2.0, centroidX = 300.0, centroidY = 800.0, area = area, document = photo)

        assertEquals(0.5, after.scale, 1e-9)
        assertSameDocumentPoint(before.transform(area, photo), after.transform(area, photo), 300.0, 800.0, 300.0, 800.0)
    }

    @Test
    fun panAndZoom_documentPointFollowsFingers() {
        val before = CanvasView.Free(scale = 1.0, centerX = 2000.0, centerY = 1500.0)

        // 손가락 가운데가 (400, 700)에서 (500, 650)으로 오면서 1.5배로 벌어졌다
        val after = before.transformedBy(panX = 100.0, panY = -50.0, zoom = 1.5, centroidX = 500.0, centroidY = 650.0, area = area, document = photo)

        assertSameDocumentPoint(before.transform(area, photo), after.transform(area, photo), 400.0, 700.0, 500.0, 650.0)
    }

    @Test
    fun zoom_isLimitedToHalfFitAndMax() {
        val fit = fitScale(area, photo)

        assertEquals(MAX_VIEW_SCALE, CanvasView.Fit.transformedBy(0.0, 0.0, 10_000.0, 500.0, 800.0, area, photo).scale, 1e-9)
        assertEquals(fit * MIN_VIEW_SCALE_OF_FIT, CanvasView.Fit.transformedBy(0.0, 0.0, 0.001, 500.0, 800.0, area, photo).scale, 1e-9)
        // 아주 작은 문서는 맞춤이 3200%보다 커서, 맞춤 배율까지는 확대할 수 있다
        val tiny = PixelSize(10, 10)
        assertEquals(fitScale(area, tiny), CanvasView.Fit.transformedBy(0.0, 0.0, 1.0, 500.0, 800.0, area, tiny).scale, 1e-9)
    }

    @Test
    fun zoom_doesNotSnapWhenAlreadyOutsideLimits() {
        // 펼쳐서 화면이 커지면 맞춤의 절반이 지금 배율보다 커질 수 있다. 그래도 그 자리에서 튀지 않는다.
        val tooSmall = CanvasView.Free(scale = 0.05, centerX = 2000.0, centerY = 1500.0)

        assertEquals(0.05, tooSmall.transformedBy(0.0, 0.0, 1.0, 500.0, 800.0, area, photo).scale, 1e-9)
        // 더 줄이지는 못하고, 키우는 쪽으로는 움직인다
        assertEquals(0.05, tooSmall.transformedBy(0.0, 0.0, 0.5, 500.0, 800.0, area, photo).scale, 1e-9)
        assertEquals(0.1, tooSmall.transformedBy(0.0, 0.0, 2.0, 500.0, 800.0, area, photo).scale, 1e-9)
    }

    @Test
    fun pan_movesCenterAndStaysInsideDocument() {
        val view = CanvasView.Free(scale = 2.0, centerX = 2000.0, centerY = 1500.0)

        // 손가락이 오른쪽으로 100px 가면 문서는 따라가고, 가운데의 문서 위치는 왼쪽으로 50px 간다
        val moved = view.transformedBy(100.0, 0.0, 1.0, 600.0, 800.0, area, photo)
        assertEquals(1950.0, moved.centerX, 1e-9)
        // 아주 멀리 밀어도 가운데는 문서 끝에서 멈춘다
        val far = view.transformedBy(100_000.0, -100_000.0, 1.0, 600.0, 800.0, area, photo)
        assertEquals(0.0, far.centerX, 1e-9)
        assertEquals(3000.0, far.centerY, 1e-9)
    }

    @Test
    fun resize_keepsFitOrKeepsScaleAndCenter() {
        val free = CanvasView.Free(scale = 4.0, centerX = 1150.0, centerY = 230.0)
        val cover = PixelSize(1080, 1700)
        val main = PixelSize(2208, 1500)

        // 자유 보기는 화면이 바뀌어도 배율과 가운데의 문서 위치가 같다
        assertEquals(1150.0, free.transform(cover, photo).toDocumentX(cover.width / 2.0), 1e-9)
        assertEquals(1150.0, free.transform(main, photo).toDocumentX(main.width / 2.0), 1e-9)
        // 맞춤은 새 화면에 다시 맞춘다
        assertEquals(fitRect(main.width, main.height, photo).left.toDouble(), CanvasView.Fit.transform(main, photo).toScreenX(0.0), 1e-9)
    }

    @Test
    fun visibleRect_coversCanvasArea() {
        val view = CanvasView.Free(scale = 4.0, centerX = 1150.0, centerY = 230.0)

        assertEquals(PixelRect(1000, 200, 1300, 260), view.visibleRect(PixelSize(1200, 240), PixelSize(2600, 520)))
    }

    @Test
    fun zoomPercentText_showsDecimalOnlyBelowTen() {
        assertEquals("250", zoomPercentText(2.5))
        assertEquals("3200", zoomPercentText(32.0))
        assertEquals("10", zoomPercentText(0.1))
        assertEquals("7.5", zoomPercentText(0.075))
    }

    @Test
    fun saved_roundTrips() {
        val free = CanvasView.Free(scale = 2.5, centerX = 12.0, centerY = 34.5)

        assertEquals(free, canvasViewFromSaved(free.toSaved()))
        assertEquals(CanvasView.Fit, canvasViewFromSaved(CanvasView.Fit.toSaved()))
    }

    @Test
    fun fitInside_fitsTheDocumentRightOfTheStrip() {
        // 1000×500 영역, 왼쪽 100px 띠. 1000×1000 문서는 남은 900×500에 맞춰 배율 0.5다.
        val view = fitInside(PixelSize(1000, 500), PixelSize(1000, 1000), insetStart = 100)
        assertEquals(0.5, view.scale, 1e-9)
        val transform = view.transform(PixelSize(1000, 500), PixelSize(1000, 1000))
        // 문서 가운데가 남은 자리(100..1000)의 가운데(550)에 온다
        assertEquals(550.0, transform.toScreenX(500.0), 1e-6)
        assertEquals(250.0, transform.toScreenY(500.0), 1e-6)
        // 문서 왼쪽 끝이 띠 오른쪽에 있다
        assertTrue(transform.toScreenX(0.0) >= 100.0 - 1e-6)
    }

    @Test
    fun fitInside_withoutRoom_fallsBackToTheFitScale() {
        val view = fitInside(PixelSize(100, 100), PixelSize(200, 200), insetStart = 100)
        assertEquals(0.5, view.scale, 1e-9)
        assertEquals(100.0, view.centerX, 1e-9)
    }
}

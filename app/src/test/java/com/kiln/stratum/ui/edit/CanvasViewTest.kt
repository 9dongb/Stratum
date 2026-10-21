package com.kiln.stratum.ui.edit

import androidx.compose.ui.geometry.Offset
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
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
    fun cropStartView_keepsTheStripClear_andCentersThePhoto() {
        // 1200×500 영역, 띠 자리 100px·둘레 20px을 양쪽에 비운다. 1000×1000 문서는 남은 960×460에 맞춰 배율 0.46이다.
        val view = cropStartView(PixelSize(1200, 500), PixelSize(1000, 1000), strip = 100, margin = 20)
        assertEquals(0.46, view.scale, 1e-9)
        val transform = view.transform(PixelSize(1200, 500), PixelSize(1000, 1000))
        // 사용자 요청: 사진이 캔버스 정중앙(600, 250)에 온다
        assertEquals(600.0, transform.toScreenX(500.0), 1e-6)
        assertEquals(250.0, transform.toScreenY(500.0), 1e-6)
        // 테두리 왼쪽 끝은 띠와 둘레 바깥이다
        assertTrue(transform.toScreenX(0.0) >= 120.0 - 1e-6)
    }

    @Test
    fun fitInside_withoutRoom_fallsBackToTheFitScale() {
        val view = fitInside(PixelSize(100, 100), PixelSize(200, 200), insetStart = 100)
        assertEquals(0.5, view.scale, 1e-9)
        assertEquals(100.0, view.centerX, 1e-9)
    }

    @Test
    fun turnedBack_reportsTheVisibleAreaOfTheOriginalDocument() {
        // 4000×3000 사진을 시계 방향으로 돌려 3000×4000으로 보이는 동안, 돌린 모습의 (100, 200)~(1100, 700)이 보인다
        val shown = Viewport(PixelSize(3000, 4000), scale = 0.25, visible = PixelRect(100, 200, 1100, 700), fitScale = 0.2)

        val back = shown.turnedBack(Orientation.ROTATE_CLOCKWISE, photo)

        // 돌린 모습의 (x, y)는 원래 사진의 (y, 3000 - x)다
        assertEquals(PixelRect(200, 1900, 700, 2900), back.visible)
        assertEquals(photo, back.documentSize)
        assertEquals(0.25, back.scale, 0.0)
        assertEquals(0.2, back.fitScale, 0.0)
        // 문서 밖으로 나간 영역도 그대로 옮긴다
        val wide = Viewport(PixelSize(3000, 4000), 0.1, PixelRect(-500, -100, 3500, 4100))
        assertEquals(PixelRect(-100, -500, 4100, 3500), wide.turnedBack(Orientation.ROTATE_CLOCKWISE, photo).visible)
        assertEquals(shown, shown.turnedBack(Orientation.IDENTITY, PixelSize(3000, 4000)))
    }

    @Test
    fun orientationMatrix_putsTheUnturnedRectOntoTheScreenRect() {
        // 돌리기 전 200×100 그림을 시계 방향으로 돌려 화면 (10, 20)에 놓으면 100×200 사각형 (10, 20)~(110, 220)을 덮는다
        for (orientation in Orientation.entries) {
            val matrix = orientationMatrix(orientation, 200, 100, 10, 20)
            val corners = listOf(Offset(0f, 0f), Offset(200f, 0f), Offset(0f, 100f), Offset(200f, 100f)).map { matrix.map(it) }
            val size = orientation.targetSize(PixelSize(200, 100))
            assertEquals("$orientation", 10f, corners.minOf { it.x }, 1e-4f)
            assertEquals("$orientation", 20f, corners.minOf { it.y }, 1e-4f)
            assertEquals("$orientation", 10f + size.width, corners.maxOf { it.x }, 1e-4f)
            assertEquals("$orientation", 20f + size.height, corners.maxOf { it.y }, 1e-4f)
            // 그림의 왼쪽 위 모서리는 그 방향이 옮기는 자리로 간다
            val (x, y) = orientation.mapPoint(0.0, 0.0, 200.0, 100.0)
            assertEquals("$orientation", Offset(10f + x.toFloat(), 20f + y.toFloat()), corners[0])
        }
    }
}

package com.kiln.stratum.engine.canvas

import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasLimitsTest {

    @Test
    fun check_acceptsSidesFromOneToMax() {
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.check(1, 1))
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.check(MAX_CANVAS_SIDE, 1))
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.check(1, MAX_CANVAS_SIDE))
        // 폴드8 50MP 사진 크기도 만들 수 있다
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.check(8160, 6120))
    }

    @Test
    fun check_rejectsSidesOutsideRange() {
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, CanvasLimits.check(0, 100))
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, CanvasLimits.check(100, 0))
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, CanvasLimits.check(-1, 100))
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, CanvasLimits.check(MAX_CANVAS_SIDE + 1, 1))
        // 두 변이 모두 잘못되면 화소 수보다 변 범위를 먼저 알린다
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, CanvasLimits.check(MAX_CANVAS_SIDE + 1, MAX_CANVAS_SIDE))
    }

    @Test
    fun check_rejectsTooManyPixels() {
        // 16383×3051은 약 4998만 화소라 되고, 3052부터는 5천만을 넘는다
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.check(MAX_CANVAS_SIDE, 3051))
        assertEquals(CanvasSizeCheck.TOO_MANY_PIXELS, CanvasLimits.check(MAX_CANVAS_SIDE, 3052))
        // 정확히 5천만 화소는 된다
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.check(10000, 5000))
        assertEquals(CanvasSizeCheck.TOO_MANY_PIXELS, CanvasLimits.check(MAX_CANVAS_SIDE, MAX_CANVAS_SIDE))
    }

    @Test
    fun resize_usesTheCanvasLimitsOrTheCurrentSizeIfLarger() {
        val small = PixelSize(4000, 3000)
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.checkResize(small, 8000, 6000))
        assertEquals(CanvasSizeCheck.TOO_MANY_PIXELS, CanvasLimits.checkResize(small, 9000, 6000))
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, CanvasLimits.checkResize(small, 0, 100))
        // 원본 크기로 연 100MP 사진은 지금 크기까지 된다
        val large = PixelSize(12000, 8400)
        assertEquals(CanvasSizeCheck.OK, CanvasLimits.checkResize(large, 12000, 8400))
        assertEquals(CanvasSizeCheck.TOO_MANY_PIXELS, CanvasLimits.checkResize(large, 12001, 8400))
    }
}

package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasFitTest {

    @Test
    fun fitRect_fillsWidthAndCentersVertically() {
        // 가로 문서(4000×3000)를 세로 영역(1000×1600)에 맞춘다
        assertEquals(FitRect(0, 425, 1000, 750), fitRect(1000, 1600, PixelSize(4000, 3000)))
    }

    @Test
    fun fitRect_fillsHeightAndCentersHorizontally() {
        assertEquals(FitRect(250, 0, 500, 1000), fitRect(1000, 1000, PixelSize(1000, 2000)))
    }

    @Test
    fun fitRect_scalesSmallDocumentUp() {
        // 300×200은 영역(1000×800)보다 작아서 폭에 맞춰 키운다
        assertEquals(FitRect(0, 66, 1000, 667), fitRect(1000, 800, PixelSize(300, 200)))
    }

    @Test
    fun fitRect_keepsAtLeastOnePixelForExtremeRatios() {
        assertEquals(FitRect(499, 0, 1, 800), fitRect(1000, 800, PixelSize(1, 16383)))
        assertEquals(FitRect(0, 399, 1000, 1), fitRect(1000, 800, PixelSize(16383, 1)))
    }

    @Test
    fun fitRect_isEmptyForEmptyArea() {
        assertEquals(FitRect(0, 0, 0, 0), fitRect(0, 800, PixelSize(100, 100)))
    }
}

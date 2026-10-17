package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeInputTest {

    private val document = PixelSize(4000, 3000)

    @Test
    fun start_isTheCurrentSizeInPixels_andCannotApplyYet() {
        val input = ResizeInput.start(document)

        assertEquals("4000", input.width)
        assertEquals("3000", input.height)
        assertTrue(input.locked)
        assertEquals(CanvasSizeCheck.OK, input.check)
        // 같은 크기는 바꿀 것이 없다
        assertFalse(input.canApply)
    }

    @Test
    fun locked_keepsTheProportionsInPixels() {
        val input = ResizeInput.start(document)

        input.changeWidth("2000")
        assertEquals("1500", input.height)

        input.changeHeight("1001")
        assertEquals("1335", input.width)
        assertTrue(input.canApply)
    }

    @Test
    fun unlocked_changesOnlyOneSide() {
        val input = ResizeInput.start(document)
        input.changeLocked(false)

        input.changeHeight("1000")

        assertEquals("4000", input.width)
        assertEquals(4000, input.targetWidth())
        assertEquals(1000, input.targetHeight())
    }

    @Test
    fun relocking_matchesTheHeightToTheWidth() {
        val input = ResizeInput.start(document)
        input.changeLocked(false)
        input.changeHeight("10")

        input.changeLocked(true)

        assertEquals("3000", input.height)
    }

    @Test
    fun percent_convertsBothWays() {
        val input = ResizeInput.start(document)
        input.changeWidth("1000")

        input.changeUnit(ResizeUnit.PERCENT)
        assertEquals("25", input.width)
        assertEquals("25", input.height)

        // 비율 유지면 퍼센트는 두 칸이 같다
        input.changeWidth("33.3")
        assertEquals("33.3", input.height)
        assertEquals(1332, input.targetWidth())
        assertEquals(999, input.targetHeight())

        input.changeUnit(ResizeUnit.PIXELS)
        assertEquals("1332", input.width)
        assertEquals("999", input.height)
    }

    @Test
    fun onlyDigitsAndOneDot_areKept() {
        val input = ResizeInput.start(document)

        input.changeWidth("12a3-")
        assertEquals("123", input.width)

        input.changeUnit(ResizeUnit.PERCENT)
        input.changeWidth("1.2.5%")
        assertEquals("1.25", input.width)
    }

    @Test
    fun emptyOrTooLarge_cannotApply() {
        val input = ResizeInput.start(document)

        input.changeWidth("")
        assertNull(input.targetWidth())
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, input.check)

        // 9000×6750은 50MP를 넘는다
        input.changeWidth("9000")
        assertEquals(CanvasSizeCheck.TOO_MANY_PIXELS, input.check)
        assertFalse(input.canApply)
    }

    @Test
    fun largerPhoto_canBeKeptAtItsOwnSize() {
        // 원본 크기로 연 100MP 사진은 지금 크기까지 키울 수 있다
        val input = ResizeInput.start(PixelSize(12000, 8400))
        input.changeLocked(false)

        input.changeWidth("11999")

        assertEquals(CanvasSizeCheck.OK, input.check)
        assertTrue(input.canApply)
    }
}

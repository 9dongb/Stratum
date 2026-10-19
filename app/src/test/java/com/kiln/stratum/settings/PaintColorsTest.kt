package com.kiln.stratum.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 전경색·배경색: 처음 값, 한쪽 바꾸기(불투명하게), 맞바꾸기 */
class PaintColorsTest {

    private val blue = 0xFF1E88E5.toInt()

    @Test
    fun startsBlackOnWhite() {
        assertEquals(PaintColors.BLACK, PaintColors.DEFAULT.foreground)
        assertEquals(PaintColors.WHITE, PaintColors.DEFAULT.background)
        assertEquals(PaintColors.BLACK, PaintColors.DEFAULT[ColorSlot.FOREGROUND])
        assertEquals(PaintColors.WHITE, PaintColors.DEFAULT[ColorSlot.BACKGROUND])
    }

    @Test
    fun with_changesOnlyThatSlot_andMakesItOpaque() {
        val colors = PaintColors.DEFAULT.with(ColorSlot.BACKGROUND, blue and 0x00FFFFFF)

        assertEquals(PaintColors(PaintColors.BLACK, blue), colors)
        assertEquals(PaintColors(blue, blue), colors.with(ColorSlot.FOREGROUND, blue))
    }

    @Test
    fun swapped_trades_andTwiceIsTheSame() {
        val colors = PaintColors(blue, PaintColors.WHITE)

        assertEquals(PaintColors(PaintColors.WHITE, blue), colors.swapped())
        assertEquals(colors, colors.swapped().swapped())
    }
}

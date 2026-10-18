package com.kiln.stratum.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 최근 색 목록(결정 기록 '색 팔레트·최근 색') */
class RecentColorsTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF1E88E5.toInt()

    @Test
    fun startsWithBlackAndWhite() {
        assertEquals(listOf(black, white), RecentColors.DEFAULT)
    }

    @Test
    fun newColor_goesToTheFront() {
        assertEquals(listOf(blue, black, white), RecentColors.add(listOf(black, white), blue))
    }

    @Test
    fun sameColor_movesToTheFrontWithoutRepeating() {
        assertEquals(listOf(white, blue, black), RecentColors.add(listOf(blue, black, white), white))
    }

    @Test
    fun keepsAtMostSix_droppingTheOldest() {
        val six = List(6) { 0xFF000000.toInt() or (it + 1) }

        val added = RecentColors.add(six, blue)

        assertEquals(6, added.size)
        assertEquals(blue, added.first())
        assertEquals(six.take(5), added.drop(1))
    }

    @Test
    fun colorsAreKeptOpaque() {
        assertEquals(blue, RecentColors.add(emptyList(), 0x801E88E5.toInt()).single())
    }

    @Test
    fun encodeThenDecode_givesTheSameList_andNothingSavedGivesTheDefault() {
        val colors = listOf(blue, black, white)

        assertEquals("FF1E88E5,FF000000,FFFFFFFF", RecentColors.encode(colors))
        assertEquals(colors, RecentColors.decode(RecentColors.encode(colors)))
        assertEquals(RecentColors.DEFAULT, RecentColors.decode(null))
    }

    @Test
    fun decode_skipsBrokenValues() {
        assertEquals(listOf(blue, white), RecentColors.decode("FF1E88E5, nope,,FF1E88E5,1FFFFFFFF,FFFFFFFF"))
    }
}

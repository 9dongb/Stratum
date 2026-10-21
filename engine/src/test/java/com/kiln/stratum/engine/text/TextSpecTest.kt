package com.kiln.stratum.engine.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSpecTest {

    private val spec = TextSpec("Hello", x = 10f, y = 20f, style = TextStyle(size = 40f), width = 90f, height = 48f)

    @Test
    fun contains_isTheTextBox() {
        // 화면이 누른 자리에 글자가 있는지 본다(사용자 피드백 8차 1-6: 다시 누르면 고치기)
        assertTrue(spec.contains(10f, 20f))
        assertTrue(spec.contains(99.9f, 67.9f))
        assertFalse(spec.contains(100f, 30f))
        assertFalse(spec.contains(50f, 68f))
        assertFalse(spec.contains(9.9f, 30f))
    }

    @Test
    fun moved_shiftsOnlyTheCorner() {
        val moved = spec.moved(5f, -25f)

        assertEquals(15f, moved.x, 0f)
        assertEquals(-5f, moved.y, 0f)
        assertEquals(spec.copy(x = 15f, y = -5f), moved)
    }

    @Test
    fun blankTextOrEmptyBox_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { spec.copy(text = " ") }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(width = 0f) }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(text = "a".repeat(TextStyle.MAX_LENGTH + 1)) }
    }
}

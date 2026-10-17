package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSlidersTest {

    @Test
    fun brushSize_usesALogScale_withTheEndsAtTheLimits() {
        assertEquals(BrushSettings.MIN_SIZE, fractionToSize(0f), 0f)
        assertEquals(BrushSettings.MAX_SIZE, fractionToSize(1f), 0f)
        // 가운데는 1과 1000의 기하 평균(약 32px)이다. 작은 크기를 세밀하게 고른다.
        assertEquals(32f, fractionToSize(0.5f), 0f)
        for (size in listOf(1f, 7f, 40f, 300f, 1000f)) assertEquals(size, fractionToSize(sizeToFraction(size)), 0f)
    }

    @Test
    fun textSize_usesALogScale() {
        assertEquals(TextStyle.MIN_SIZE, fractionToTextSize(0f), 0f)
        assertEquals(TextStyle.MAX_SIZE, fractionToTextSize(1f), 0f)
        assertTrue(fractionToTextSize(0.5f) in 100f..150f)
        for (size in listOf(8f, 72f, 367f, 2000f)) assertEquals(size, fractionToTextSize(textSizeToFraction(size)), 0f)
    }
}

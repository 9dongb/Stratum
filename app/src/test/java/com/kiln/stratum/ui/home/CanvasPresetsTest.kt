package com.kiln.stratum.ui.home

import com.kiln.stratum.engine.canvas.CanvasLimits
import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasPresetsTest {

    @Test
    fun presets_haveExactRatiosAndFitLimits() {
        for (preset in CanvasPreset.entries) {
            assertEquals(preset.name, CanvasSizeCheck.OK, CanvasLimits.check(preset.width, preset.height))
            if (preset.ratioWidth > 0) {
                // 보이는 비율과 실제 픽셀 크기가 정확히 맞는다
                assertEquals(preset.name, preset.width * preset.ratioHeight, preset.height * preset.ratioWidth)
            }
        }
    }

    @Test
    fun matching_followsSwappedSize() {
        assertEquals(CanvasPreset.LANDSCAPE, CanvasPreset.matching(3840, 2160))
        // 16:9를 가로·세로 바꾸면 9:16 프리셋이다
        assertEquals(CanvasPreset.STORY, CanvasPreset.matching(2160, 3840))
        // 4:5를 바꾼 크기는 프리셋이 아니다(직접 입력)
        assertNull(CanvasPreset.matching(3000, 2400))
    }

    @Test
    fun sizeDigits_keepsOnlyAsciiDigitsUpToFive() {
        assertEquals("123", sizeDigits("1a2 3"))
        assertEquals("12345", sizeDigits("123456"))
        // 다른 문자의 숫자(아라비아 숫자 등)는 받지 않는다
        assertEquals("", sizeDigits("١٢"))
    }

    @Test
    fun checkSizeInput_treatsEmptyAsOutOfRange() {
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, checkSizeInput("", "100"))
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, checkSizeInput("0", "100"))
        assertEquals(CanvasSizeCheck.SIDE_OUT_OF_RANGE, checkSizeInput("16384", "100"))
        assertEquals(CanvasSizeCheck.TOO_MANY_PIXELS, checkSizeInput("16383", "3052"))
        assertEquals(CanvasSizeCheck.OK, checkSizeInput("3000", "3000"))
    }
}

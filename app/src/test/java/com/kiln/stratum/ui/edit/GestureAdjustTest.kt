package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.brush.BrushSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 세 손가락으로 밀어 바꾸는 값(결정 기록 '세 손가락 제스처') */
class GestureAdjustTest {

    @Test
    fun swipingUp_growsTheSize_onTheSliderScale() {
        // 눈금 전체를 밀면 1px → 1000px, 반을 밀면 슬라이더 가운데(약 32px)
        assertEquals(BrushSettings.MAX_SIZE, swipedSize(1f, THREE_FINGER_SWIPE_RANGE_DP), 0f)
        assertEquals(32f, swipedSize(1f, THREE_FINGER_SWIPE_RANGE_DP / 2), 0f)
        assertTrue(swipedSize(40f, 30f) > 40f)
        // 아래로 밀면 작아지고, 같은 거리면 같은 비율이다(로그 눈금)
        assertTrue(swipedSize(40f, -30f) < 40f)
        assertEquals(swipedSize(40f, 30f) / 40f, 40f / swipedSize(40f, -30f), 0.05f)
    }

    @Test
    fun theEnds_stopTheValue() {
        assertEquals(BrushSettings.MAX_SIZE, swipedSize(500f, 1000f), 0f)
        assertEquals(BrushSettings.MIN_SIZE, swipedSize(5f, -1000f), 0f)
        assertEquals(1f, swipedHardness(0.9f, 300f), 0f)
        assertEquals(0f, swipedHardness(0.1f, -300f), 0f)
        assertEquals(1f, swipedStrength(0.3f, 1000f), 0f)
        assertEquals(0.01f, swipedStrength(0.3f, -1000f), 0f)
    }

    @Test
    fun hardnessAndStrength_moveByTheShareOfTheRange_inWholePercents() {
        // 30dp는 눈금의 10%
        assertEquals(0.6f, swipedHardness(0.5f, 30f), 0f)
        assertEquals(0.4f, swipedHardness(0.5f, -30f), 0f)
        assertEquals(0.53f, swipedHardness(0.5f, 9.1f), 0f)
        assertEquals(0.6f, swipedStrength(0.5f, 30f), 0f)
    }

    @Test
    fun noDistance_keepsTheStart() {
        // 슬라이더 눈금에 없는 값(유동화의 처음 크기 등)도 그대로다
        assertEquals(457.5f, swipedSize(457.5f, 0f), 0f)
        assertEquals(0.333f, swipedHardness(0.333f, 0f), 0f)
        assertEquals(0.333f, swipedStrength(0.333f, 0f), 0f)
        // 시작 값에서 계산하므로 되돌아오면 처음 값이다
        assertEquals(40f, swipedSize(40f, 0f), 0f)
    }

    @Test
    fun brushSettings_verticalChangesTheSize_horizontalTheHardness() {
        val start = BrushSettings(size = 40f, hardness = 0.5f, opacity = 0.7f, color = 0xFF112233.toInt())

        val up = start.swiped(SwipeAxis.VERTICAL, 60f)
        assertTrue(up.size > start.size)
        assertEquals(start.copy(size = up.size), up)

        val right = start.swiped(SwipeAxis.HORIZONTAL, 60f)
        assertEquals(start.copy(hardness = 0.7f), right)
    }

    @Test
    fun anchor_keepsTheFirstValue_untilTheSwipeEnds() {
        val anchor = SwipeAnchor<Float>()

        assertEquals(1f, anchor.from(1f), 0f)
        assertEquals(1f, anchor.from(5f), 0f)
        anchor.end()
        assertEquals(5f, anchor.from(5f), 0f)
    }
}

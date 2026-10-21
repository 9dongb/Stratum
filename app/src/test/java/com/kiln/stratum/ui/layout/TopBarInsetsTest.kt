package com.kiln.stratum.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/** 상단 바를 카메라 구멍 아래로 내릴지 정하는 계산(결정 기록 '전체 화면') */
class TopBarInsetsTest {

    // 폭 1248px 커버 화면의 편집 화면 상단 바: 왼쪽 뒤로 버튼, 오른쪽 버튼 셋
    private val back = 10f..136f
    private val actions = 856f..1238f

    @Test
    fun holeBetweenButtons_keepsTheBarAtTheTop() {
        // 가운데 카메라 구멍(편집 화면 바의 빈 가운데)
        assertFalse(cutoutBlocks(listOf(584f..664f), listOf(back, actions), margin = 10f))
    }

    @Test
    fun holeOverAButton_movesTheBarDown() {
        // 왼쪽 위 구석의 구멍이 뒤로 버튼에 걸린다
        assertTrue(cutoutBlocks(listOf(40f..120f), listOf(back, actions), margin = 10f))
        // 오른쪽 버튼 줄에 걸린다
        assertTrue(cutoutBlocks(listOf(1000f..1080f), listOf(back, actions), margin = 10f))
    }

    @Test
    fun holeJustOutsideAButton_countsTheMargin() {
        // 버튼 끝(136)에서 여유(10)보다 멀면 그대로, 가까우면 내린다
        assertFalse(cutoutBlocks(listOf(147f..200f), listOf(back), margin = 10f))
        assertTrue(cutoutBlocks(listOf(145f..200f), listOf(back), margin = 10f))
    }

    // 폭 1000px 바의 오른쪽 버튼 넷(폭 100·200·120·120), 틈 없이 오른쪽 끝(1000)에 붙는다
    private val widths = listOf(100, 200, 120, 120)

    @Test
    fun holeAvoiding_withoutAHole_hugsTheEnd() {
        assertEquals(listOf(460, 560, 760, 880), holeAvoidingStarts(widths, end = 1000, holes = emptyList(), margin = 10f, minStart = 200))
    }

    @Test
    fun holeAvoiding_buttonOverAHole_movesLeftOfIt_withTheButtonsBeforeIt() {
        // 사용자 피드백 10차 7: 구멍(500~560)이 둘째 버튼(560~760)에 걸린다 → 둘째는 구멍 왼쪽(490에서 끝), 첫째는 그 앞
        assertEquals(listOf(190, 290, 760, 880), holeAvoidingStarts(widths, end = 1000, holes = listOf(500f..560f), margin = 10f, minStart = 150))
    }

    @Test
    fun holeAvoiding_holeAwayFromTheButtons_movesNothing() {
        // 버튼 묶음(460~1000) 앞의 구멍은 걸리지 않는다
        assertEquals(listOf(460, 560, 760, 880), holeAvoidingStarts(widths, end = 1000, holes = listOf(300f..350f), margin = 10f, minStart = 150))
    }

    @Test
    fun holeAvoiding_onlyTheShownPartCounts() {
        // 버튼 양옆 여백(30)은 구멍에 걸려도 된다: 셋째 버튼(760~880)의 보이는 부분은 790~850, 구멍 850~860은 여유 10보다 가깝다 → 옮김
        // 구멍 865~880은 보이는 부분(850에서 끝)과 여유(10)보다 멀다 → 그대로
        assertEquals(listOf(460, 560, 760, 880), holeAvoidingStarts(widths, end = 1000, holes = listOf(865f..880f), margin = 10f, minStart = 150, inset = 30))
        // 옮기면 보이는 부분이 구멍 앞(840)에서 끝난다: 셋째 750~870(보이는 부분 780~840), 그 앞 버튼들도 이어서
        assertEquals(listOf(450, 550, 750, 880), holeAvoidingStarts(widths, end = 1000, holes = listOf(850f..860f), margin = 10f, minStart = 150, inset = 30))
    }

    @Test
    fun coverEditBar_sixButtons_fitBesideACenterHole_onlyOnAWideCover() {
        // 커버 화면 개편: 편집 화면 바 오른쪽 버튼이 여섯(실행 취소·다시 실행·조정·레이어·공유·설정, 48dp, 보이는 부분은 양옆 12dp 안쪽)이다.
        // 버튼 줄은 뒤로 버튼(56dp) 뒤에서 바 끝 8dp 앞까지다. 1dp를 1로 셈하고 구멍은 줄 기준 가로 범위다.
        val widths = List(6) { 48 }
        // 폴드8 커버(약 475dp): 줄 폭 411, 위 가운데 구멍(화면 약 219~257 → 줄 163~201)이어도 구멍 양옆에 다 들어간다
        assertNotNull(holeAvoidingStarts(widths, end = 475 - 56 - 8, holes = listOf(163f..201f), margin = 4f, minStart = 0, inset = 12))
        // Pixel_Fold 에뮬레이터 커버(411dp): 줄 폭 347, 구멍(화면 약 183~225 → 줄 127~169)이면 모자라 바를 내린다
        assertNull(holeAvoidingStarts(widths, end = 411 - 56 - 8, holes = listOf(127f..169f), margin = 4f, minStart = 0, inset = 12))
    }

    @Test
    fun holeAvoiding_withoutRoomBesideTheTitle_givesUp() {
        // 구멍 왼쪽으로 옮기면 제목(끝 300)과 겹친다 → null(바를 내린다)
        assertEquals(null, holeAvoidingStarts(widths, end = 1000, holes = listOf(500f..560f), margin = 10f, minStart = 300))
    }

    @Test
    fun noHoleOrNoItems_keepsTheBarAtTheTop() {
        assertFalse(cutoutBlocks(emptyList(), listOf(back, actions), margin = 10f))
        assertFalse(cutoutBlocks(listOf(40f..120f), emptyList(), margin = 10f))
    }

    @Test
    fun anyOfSeveralHoles_movesTheBarDown() {
        // 위쪽에 구멍이 둘인 화면: 하나만 걸려도 내린다
        assertTrue(cutoutBlocks(listOf(584f..664f, 1100f..1150f), listOf(back, actions), margin = 10f))
    }
}

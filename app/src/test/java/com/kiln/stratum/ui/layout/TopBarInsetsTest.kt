package com.kiln.stratum.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun barLabel_sitsInTheMiddleOfTheBar() {
        // 메인 가로: 뒤로(0~56)와 버튼들(700~930) 사이, 바 가운데 465에 폭 60을 둔다
        assertEquals(435, barLabelLeft(60, 465, 0, 930, listOf(0f..56f, 700f..930f), emptyList(), 4f))
    }

    @Test
    fun barLabel_movesInsideTheFreeSpace_whenButtonsCrowdTheMiddle() {
        // 커버: 오른쪽 버튼들(221~411)이 가운데(205)에 가까워, 가운데에 두면 걸리므로 그 왼쪽으로 민다
        assertEquals(157, barLabelLeft(60, 205, 0, 411, listOf(0f..56f, 221f..411f), emptyList(), 4f))
    }

    @Test
    fun barLabel_stepsBesideACameraHole() {
        // 폴드8 커버처럼 위 가운데 구멍(183~225): 빈 곳(60~217) 안에서 구멍 왼쪽에 붙인다
        assertEquals(119, barLabelLeft(60, 205, 0, 411, listOf(0f..56f, 221f..411f), listOf(183f..225f), 4f))
        // 넓은 바에서 구멍이 가운데면 가까운 쪽 옆
        val left = barLabelLeft(60, 465, 0, 930, listOf(0f..56f, 700f..930f), listOf(440f..490f), 4f)
        assertTrue("label at $left should clear the hole", left + 60 + 4 <= 440 || left - 4 >= 490)
    }

    @Test
    fun barLabel_staysPut_whenThereIsNoRoom() {
        // 빈 곳이 글자 칸보다 좁으면 가운데 그대로다
        assertEquals(175, barLabelLeft(60, 205, 0, 411, listOf(0f..180f, 210f..411f), emptyList(), 4f))
    }
}

package com.kiln.stratum.ui.layout

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
}

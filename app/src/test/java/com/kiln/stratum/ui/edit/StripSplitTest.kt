package com.kiln.stratum.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 펼친 화면 띠 목록을 띠에 남길 칸과 오른쪽 수치 바의 슬라이더로 나누기(사용자 피드백 12차) */
class StripSplitTest {

    private fun slider(key: String) = SliderCell(key, key, "1", StripSlider(0.5f, {}, tag = key))

    private fun icon(key: String) = IconCell(key, 0, key, {})

    private fun keys(items: List<StripItem>) = items.map { it.key }

    @Test
    fun brush_colorsStayInTheStrip_andSizeHardnessOpacityGoToTheBarInOrder() {
        val items = listOf(icon("fore"), icon("back"), icon("swap"), icon("eyedropper"), StripDivider("beforeNumbers"), slider("size"), slider("hardness"), slider("opacity"))
        val split = splitSliders(items)
        // 띠의 끝에 남는 구분선은 지운다
        assertEquals(listOf("fore", "back", "swap", "eyedropper"), keys(split.strip))
        assertEquals(listOf("size", "hardness", "opacity"), keys(split.sliders))
    }

    @Test
    fun onlyNumbers_leaveAnEmptyStrip() {
        // 지우개·흐림·모자이크·스팟 복구는 띠 없이 바만
        val split = splitSliders(listOf(slider("size"), slider("hardness"), slider("strength")))
        assertTrue(split.strip.isEmpty())
        assertEquals(listOf("size", "hardness", "strength"), keys(split.sliders))
    }

    @Test
    fun magicWand_keepsTheModesAndTheContiguousToggle() {
        val modes = listOf("REPLACE", "ADD", "SUBTRACT", "INTERSECT").map { icon("mode$it") }
        val items = modes + StripDivider("contiguous") + icon("contiguous") + StripDivider("beforeNumbers") + slider("tolerance")
        val split = splitSliders(items)
        assertEquals(keys(modes) + listOf("contiguous", "contiguous"), keys(split.strip))
        assertTrue(split.strip[4] is StripDivider)
        assertEquals(listOf("tolerance"), keys(split.sliders))
    }

    @Test
    fun atMostThreeSlidersGoToTheBar_theRestStayInTheStrip() {
        // 사용자: "최대 3개까지". 넷째부터는 띠에 남아 옵션 창에서 고친다.
        val split = splitSliders(listOf(slider("size"), slider("hardness"), slider("opacity"), slider("strength")))
        assertEquals(listOf("size", "hardness", "opacity"), keys(split.sliders))
        assertEquals(listOf("strength"), keys(split.strip))
    }

    @Test
    fun leadingDoubledAndTrailingDividersAreDropped() {
        val items = listOf(StripDivider("a"), icon("x"), StripDivider("b"), slider("size"), StripDivider("c"), icon("y"), StripDivider("d"))
        val split = splitSliders(items)
        assertEquals(listOf("x", "b", "y"), keys(split.strip))
    }
}

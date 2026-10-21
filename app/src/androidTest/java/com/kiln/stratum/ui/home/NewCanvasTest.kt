package com.kiln.stratum.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.canvas.MAX_CANVAS_SIDE
import com.kiln.stratum.ui.layout.DIALOG_CARD
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 홈의 새 캔버스 창을 확인한다. 커버 화면 폭은 아래 시트, 메인 화면 폭은 화면 가운데의 창이다(접히는 선은 보지 않는다, 사용자 피드백 11차 1).
 * 시트는 별도 창이라 화면 크기 강제가 적용되지 않으므로, 시트에서는 동작만 본다.
 */
@RunWith(AndroidJUnit4::class)
class NewCanvasTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 창이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** '만들기'로 넘어온 값 */
    private data class Created(val width: Int, val height: Int, val fill: CanvasFill, val name: String)

    private val created = mutableListOf<Created>()

    private fun string(id: Int, vararg args: Any): String = composeRule.activity.getString(id, *args)

    private fun showHome(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        HomeScreen(
                            onOpenPhoto = {},
                            onCreateCanvas = { width, height, fill, name -> created += Created(width, height, fill, name) }
                        )
                    }
                }
            }
        }
    }

    /** 홈 위 줄의 '+'(새 캔버스) 버튼을 누른다 */
    private fun openNewCanvas() {
        composeRule.onNodeWithTag(HomeTags.NEW_CANVAS).performClick()
    }

    @Test
    fun compactSheet_createsChosenPresetAndBackground() {
        showHome(411, 800)
        openNewCanvas()

        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.LANDSCAPE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NewCanvasTags.background(CanvasBackground.TRANSPARENT)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).performClick()

        // 이름을 적지 않으면 이름 없음('제목 없음')이다
        composeRule.runOnIdle { assertEquals(listOf(Created(3840, 2160, CanvasFill.Transparent, "")), created) }
    }

    @Test
    fun invalidSize_disablesCreateAndShowsReason() {
        showHome(411, 800)
        openNewCanvas()

        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.new_canvas_error_range, MAX_CANVAS_SIDE)).assertExists()

        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).performTextReplacement("16383")
        composeRule.onNodeWithTag(NewCanvasTags.HEIGHT).performTextReplacement("3052")
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.new_canvas_error_too_large, 50)).assertExists()
    }

    @Test
    fun orientation_turnsTheSize_andSelectedPresetFollows() {
        showHome(411, 800)
        openNewCanvas()
        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.LANDSCAPE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NewCanvasTags.orientation(portrait = false)).performScrollTo().assertIsSelected()

        composeRule.onNodeWithTag(NewCanvasTags.orientation(portrait = true)).performScrollTo().performClick()

        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).assert(hasText("2160"))
        composeRule.onNodeWithTag(NewCanvasTags.HEIGHT).assert(hasText("3840"))
        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.STORY)).assertIsSelected()
        // 정사각형은 어느 방향도 고른 것으로 보이지 않는다
        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.SQUARE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NewCanvasTags.orientation(portrait = true)).performScrollTo().assertIsNotSelected()
        composeRule.onNodeWithTag(NewCanvasTags.orientation(portrait = false)).assertIsNotSelected()
    }

    @Test
    fun name_isPassedToCreate_cleaned() {
        showHome(411, 800)
        openNewCanvas()

        composeRule.onNodeWithTag(NewCanvasTags.NAME).performScrollTo().performTextReplacement("  Night   sky ")
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).performClick()

        composeRule.runOnIdle { assertEquals("Night sky", created.single().name) }
    }

    @Test
    fun expandedDialog_isCentered_withTwoEqualColumns_evenAcrossAVerticalHinge() {
        // 사용자 피드백 11차 1: 접히는 선을 비켜 두 칸 사이를 비우지 않는다(전에는 두 칸을 선 양쪽에 하나씩 놓았다)
        showHome(930, 700)
        openNewCanvas()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val before = composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()

        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }

        val card = composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(before, card)
        assertEquals(screen.center.x, card.center.x, 2f)
        // 프리셋 목록은 왼쪽, 이름·크기는 오른쪽 칸이고 두 칸의 폭이 같다
        val presets = composeRule.onNodeWithTag(NewCanvasTags.PRESETS).fetchSemanticsNode().layoutBoundsInWindow()
        val name = composeRule.onNodeWithTag(NewCanvasTags.NAME).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("presets $presets should be left of name $name", presets.right <= name.left)
        assertEquals(presets.width, name.width, 4f)
    }

    @Test
    fun mediumDialog_isCentered_evenAcrossAHorizontalHinge() {
        // 사용자 피드백 11차 1: 전에는 메인 세로에서 선 위쪽 절반에 띄웠다
        showHome(700, 900)
        openNewCanvas()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerY = screen.center.y.roundToInt()
        val before = composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()

        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = 0, top = centerY, right = screen.right.roundToInt(), bottom = centerY, isVertical = false)) }

        assertEquals(before, composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow())
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).assertExists()
    }
}

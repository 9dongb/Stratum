package com.kiln.stratum.ui.edit

import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.layout.hideStatusBar
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.workBarContentPx
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 편집 화면 상단 바(사용자 피드백 10차·11차).
 * - 맨 오른쪽은 설정 버튼이고 누르면 설정을 연다(11차 4). 바로 왼쪽이 내보내기(공유 아이콘, 11차 3)다.
 * - 위쪽 카메라 구멍: 구멍에 걸리는 버튼은 구멍 왼쪽으로 비켜 놓고(홈 위 줄과 같다), 비킬 자리가 없을 때만 바를 구멍 아래로 내린다.
 *   커버 화면 개편으로 오른쪽 버튼이 여섯(실행 취소·다시 실행·조정·레이어·내보내기·설정)이 되어, Pixel_Fold 접은 화면(411dp)에서는
 *   구멍 양옆에 다 들어가지 않아 바가 내려간다. 폴드8 커버(약 475dp)는 들어간다(TopBarInsetsTest).
 *   구멍 테스트는 위쪽 구멍이 있는 기기에서만 돌고, 화면 크기는 기기 그대로다(구멍 자리가 창 좌표라).
 *   앱처럼 상태 표시줄을 숨기고 본다(보이면 상태 표시줄 높이와 구멍 높이가 같아 내려간 것을 알 수 없다).
 */
@RunWith(AndroidJUnit4::class)
class EditTopBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cameraHoleAtTheTop_noButtonSitsUnderIt() {
        composeRule.runOnUiThread { hideStatusBar(composeRule.activity.window) }
        composeRule.waitUntil(5_000) {
            composeRule.runOnUiThread { composeRule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) == false }
        }
        composeRule.setContent {
            StratumTheme {
                EditScreen(state = EditorState.Empty, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
            }
        }
        composeRule.waitForIdle()
        val insets = composeRule.runOnIdle { composeRule.activity.window.decorView.rootWindowInsets }
        val holes = insets.displayCutout?.boundingRects.orEmpty().filter { it.top == 0 && !it.isEmpty }
        assumeTrue("needs a camera hole at the top of the screen", holes.isNotEmpty())

        val bar = composeRule.onNodeWithTag(EditScreenTags.TOP_BAR).fetchSemanticsNode()
        val density = bar.layoutInfo.density.density
        // 버튼의 보이는 부분(양옆 12dp 안쪽)이 구멍과 4dp보다 가까우면 안 된다(바가 구멍 아래로 내려갔으면 겹치지 않는다)
        val inset = 12 * density
        val margin = 4 * density
        val tags = listOf(EditScreenTags.UNDO, EditScreenTags.REDO) + MainPanel.entries.map { EditScreenTags.panelToggle(it) } +
            listOf(EditScreenTags.EXPORT, EditScreenTags.SETTINGS)
        for (tag in tags) {
            val button = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()
            for (hole in holes) {
                val besideHole = button.right - inset + margin <= hole.left || button.left + inset - margin >= hole.right
                val belowHole = button.top >= hole.bottom - 1
                assertTrue("$tag $button sits under the camera hole $hole", besideHole || belowHole)
            }
        }
        // 바는 내용 줄(상태 표시줄 높이)만큼이거나(구멍 양옆에 다 들어감), 비킬 자리가 없으면 구멍 바로 아래까지 내려간다(내용 줄 + 구멍 높이)
        val content = workBarContentPx(insets, density)
        val lowered = content + holes.maxOf { it.bottom }
        val height = bar.layoutBoundsInWindow().height
        assertTrue("bar height $height should be $content or $lowered", abs(height - content) <= 1f || abs(height - lowered) <= 1f)
    }

    @Test
    fun settingsButton_isAtTheRightEnd_rightOfExport_andOpensSettings() {
        var opened = 0
        val size = mutableStateOf(COVER)
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                StratumTheme {
                    EditScreen(
                        state = EditorState.Empty,
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        onOpenSettings = { opened++ }
                    )
                }
            }
        }
        for (screen in listOf(COVER, MAIN_LANDSCAPE)) {
            composeRule.runOnIdle { size.value = screen }
            composeRule.waitForIdle()
            val bar = composeRule.onNodeWithTag(EditScreenTags.TOP_BAR).fetchSemanticsNode()
            val density = bar.layoutInfo.density.density
            val settings = composeRule.onNodeWithTag(EditScreenTags.SETTINGS).fetchSemanticsNode().layoutBoundsInWindow()
            val export = composeRule.onNodeWithTag(EditScreenTags.EXPORT).fetchSemanticsNode().layoutBoundsInWindow()
            // 설정이 맨 오른쪽(바 끝에서 8dp 안: M3 바의 제목 자리 여백과 끝 여백, 홈 위 줄과 같다), 내보내기가 바로 왼쪽
            val barRight = bar.layoutBoundsInWindow().right
            assertTrue("$screen: settings $settings is not at the right end of the bar ($barRight)", barRight - settings.right in 0f..(8 * density + 1))
            assertEquals("$screen: export right next to settings", settings.left, export.right, 1f)
            if (screen == MAIN_LANDSCAPE) {
                // 메인 화면은 조정·레이어 패널 버튼이 내보내기 왼쪽에 있다
                for (panel in MainPanel.entries) {
                    val toggle = composeRule.onNodeWithTag(EditScreenTags.panelToggle(panel)).fetchSemanticsNode().layoutBoundsInWindow()
                    assertTrue("$panel $toggle is left of export $export", toggle.right <= export.left + 1)
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.SETTINGS).performClick()

        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    private companion object {
        /** 커버 화면 정도(Compact) */
        val COVER = DpSize(400.dp, 700.dp)

        /** 메인 가로(Expanded): Pixel_Fold 안쪽 화면 크기 */
        val MAIN_LANDSCAPE = DpSize(841.dp, 701.dp)
    }
}

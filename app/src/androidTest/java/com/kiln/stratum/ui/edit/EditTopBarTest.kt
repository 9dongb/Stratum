package com.kiln.stratum.ui.edit

import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.intl.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.layout.hideStatusBar
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.workBarContentPx
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 편집 화면 상단 바와 위쪽 카메라 구멍(사용자 피드백 10차). 버튼의 보이는 부분(아이콘·글자)이 구멍에 걸리지 않으면, 버튼 여백만 걸려도
 * 바를 구멍 아래로 내리지 않는다. 한국어('내보내기')는 버튼 줄이 넓어 커버(Pixel_Fold 접은 화면) 위 가운데 구멍에 실행 취소 버튼의 여백이 걸렸다.
 * 위쪽 카메라 구멍이 있는 기기에서만 돈다. 화면 크기는 기기 그대로다(구멍 자리가 창 좌표라).
 * 앱처럼 상태 표시줄을 숨기고 본다(보이면 상태 표시줄 높이와 구멍 높이가 같아 내려간 것을 알 수 없다).
 */
@RunWith(AndroidJUnit4::class)
class EditTopBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun buttonMarginsOverTheCameraHole_doNotPushTheBarDown_inKorean() {
        composeRule.runOnUiThread { hideStatusBar(composeRule.activity.window) }
        composeRule.waitUntil(5_000) {
            composeRule.runOnUiThread { composeRule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) == false }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("ko-KR"))) {
                StratumTheme {
                    EditScreen(state = EditorState.Empty, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
                }
            }
        }
        composeRule.waitForIdle()
        val insets = composeRule.runOnIdle { composeRule.activity.window.decorView.rootWindowInsets }
        val holes = insets.displayCutout?.boundingRects.orEmpty().filter { it.top == 0 && !it.isEmpty }
        assumeTrue("needs a camera hole at the top of the screen", holes.isNotEmpty())

        val bar = composeRule.onNodeWithTag(EditScreenTags.TOP_BAR).fetchSemanticsNode()
        val density = bar.layoutInfo.density.density
        // 버튼의 보이는 부분(양옆 12dp 안쪽)이 구멍에서 4dp 넘게 떨어져 있을 때만 본다(이 기기·글자에서)
        val inset = 12 * density
        val margin = 4 * density
        val clear = listOf(EditScreenTags.UNDO, EditScreenTags.REDO, EditScreenTags.EXPORT).all { tag ->
            val button = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()
            holes.all { hole -> button.right - inset + margin <= hole.left || button.left + inset - margin >= hole.right }
        }
        assumeTrue("a button's icon or text is under the camera hole", clear)

        // 바는 내용 줄(상태 표시줄 높이)만큼이다: 구멍 높이만큼 내려가지 않는다
        assertEquals(workBarContentPx(insets, density), bar.layoutBoundsInWindow().height, 1f)
    }
}

package com.kiln.stratum.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 홈 위 줄과 카메라 구멍(사용자 피드백 10차 7: 커버 홈 헤더가 구멍만큼 높아졌다).
 * 위쪽 카메라 구멍이 있는 기기(Pixel_Fold 접은 화면)에서만 돈다: 구멍에 걸리던 버튼이 구멍 왼쪽으로 비켜, 어느 버튼의 글자·아이콘도
 * 구멍 아래에 있지 않다. 그래서 바를 구멍 아래로 내리지 않는다. 화면 크기는 기기 그대로다(구멍 자리가 창 좌표라).
 */
@RunWith(AndroidJUnit4::class)
class HomeTopBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cameraHoleAtTheTop_noButtonSitsUnderIt() {
        composeRule.setContent { StratumTheme { HomeScreen(onOpenPhoto = {}, onCreateCanvas = { _, _, _, _ -> }) } }
        composeRule.waitForIdle()
        val holes = composeRule.runOnIdle {
            composeRule.activity.window.decorView.rootWindowInsets.displayCutout?.boundingRects.orEmpty().filter { it.top == 0 && !it.isEmpty }
        }
        assumeTrue("needs a camera hole at the top of the screen", holes.isNotEmpty())

        val bar = composeRule.onNodeWithTag(HomeTags.TOP_BAR).fetchSemanticsNode()
        val density = bar.layoutInfo.density.density
        // 버튼의 보이는 부분(양옆 12dp 안쪽)이 구멍과 4dp보다 가까우면 안 된다
        val inset = 12 * density
        val margin = 4 * density
        for (tag in listOf(HomeTags.SELECT, HomeTags.OPEN_PHOTO, HomeTags.NEW_CANVAS, HomeTags.SETTINGS)) {
            val button = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()
            val shownLeft = button.left + inset
            val shownRight = button.right - inset
            for (hole in holes) {
                assertTrue("$tag $button sits under the camera hole $hole", shownRight + margin <= hole.left || shownLeft - margin >= hole.right)
            }
        }
        // 제목도 구멍에 걸리지 않는다
        val title = composeRule.onNodeWithTag(HomeTags.TITLE).fetchSemanticsNode().layoutBoundsInWindow()
        for (hole in holes) assertTrue("title $title sits under $hole", title.right <= hole.left || title.left >= hole.right)
    }
}

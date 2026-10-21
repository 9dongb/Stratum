package com.kiln.stratum.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 홈 화면 빈 안내의 두 버튼이 접히는 선이 지나도 갈라지지 않고 가운데에 나란히 있는지 확인한다(사용자 피드백 11차 1). */
@RunWith(AndroidJUnit4::class)
class HomeScreenHingeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 화면이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().layoutBoundsInWindow()

    @Test
    fun buttons_stayTogether_evenAcrossAVerticalHinge() {
        // Pixel_Fold 안쪽 화면 크기
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(841.dp, 701.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme { HomeScreen(onOpenPhoto = {}, onCreateCanvas = { _, _, _, _ -> }) }
                }
            }
        }
        // 프로젝트가 없을 때 가운데에 보이는 두 버튼
        val openPhoto = composeRule.onNodeWithTag(HomeTags.EMPTY_OPEN_PHOTO)
        val newCanvas = composeRule.onNodeWithTag(HomeTags.EMPTY_NEW_CANVAS)
        val before = openPhoto.bounds() to newCanvas.bounds()

        // 두 버튼 사이 한가운데를 화면 위에서 아래까지 세로로 지나가는 힌지(두께 0인 선). 전에는 두 버튼을 선 양쪽으로 갈라 놓았다.
        val centerX = ((before.first.right + before.second.left) / 2).roundToInt()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }

        assertEquals(before, openPhoto.bounds() to newCanvas.bounds())
        // 같은 폭으로 나란히 화면 가운데에 있다
        assertEquals(before.first.width, before.second.width, 2f)
        assertEquals(screen.center.x, (before.first.left + before.second.right) / 2, 2f)
    }
}

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
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 홈 화면의 두 버튼이 펼친 화면 가운데의 접히는 부분(힌지)을 피해 양쪽으로 갈라지는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class HomeScreenHingeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 화면이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().layoutBoundsInWindow()

    @Test
    fun buttons_splitAroundVerticalHinge() {
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

        // 두 버튼 사이 한가운데를 화면 위에서 아래까지 세로로 지나가는 힌지(두께 0인 선).
        // 힌지가 없을 때의 간격(12dp)은 띠(양쪽 16dp)보다 좁아서, 갈라 놓지 않으면 두 버튼 모두 띠에 걸린다.
        val centerX = ((openPhoto.bounds().right + newCanvas.bounds().left) / 2).roundToInt()
        val screenHeight = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow().bottom.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screenHeight, isVertical = true)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        assertTrue(openPhoto.bounds().right <= centerX)
        assertTrue(newCanvas.bounds().left >= centerX)
    }
}

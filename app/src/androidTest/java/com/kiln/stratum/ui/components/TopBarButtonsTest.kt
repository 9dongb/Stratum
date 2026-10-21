package com.kiln.stratum.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.ui.theme.stratumColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 작업 화면 상단 바의 낮은 버튼(사용자 피드백 10차 6): 누르는 자리는 가로 48dp × 바 높이다(사용자 결정). */
@RunWith(AndroidJUnit4::class)
class TopBarButtonsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val colors = stratumColors(dark = true)

    private fun px(dp: Dp): Float = with(composeRule.density) { dp.toPx() }

    @Test
    fun iconButton_isBarHigh_andRunsOnClick_onlyWhenEnabled() {
        var clicks by mutableIntStateOf(0)
        var enabled by mutableStateOf(true)
        composeRule.setContent {
            StratumTheme {
                TopBarIconButton(onClick = { clicks++ }, height = 28.dp, enabled = enabled, modifier = Modifier.testTag(TAG)) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = null)
                }
            }
        }

        composeRule.onNodeWithTag(TAG).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(28.dp)
        composeRule.onNodeWithTag(TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }

        enabled = false
        composeRule.onNodeWithTag(TAG).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun toggleButton_turnsOnAndOff() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            StratumTheme {
                TopBarIconToggleButton(checked = checked, onCheckedChange = { checked = it }, height = 28.dp, modifier = Modifier.testTag(TAG)) {
                    Icon(painterResource(R.drawable.ic_layers), contentDescription = null)
                }
            }
        }

        composeRule.onNodeWithTag(TAG).assertIsOff().performClick()
        composeRule.onNodeWithTag(TAG).assertIsOn()
        composeRule.onNodeWithTag(TAG).performClick()
        composeRule.onNodeWithTag(TAG).assertIsOff()
    }

    @Test
    fun checkedToggle_drawsTheSelectedSquareInsideTheBar() {
        composeRule.setContent {
            StratumTheme {
                TopBarIconToggleButton(checked = true, onCheckedChange = {}, height = 28.dp, modifier = Modifier.testTag(TAG)) {
                    Icon(painterResource(R.drawable.ic_layers), contentDescription = null)
                }
            }
        }

        composeRule.onNodeWithTag(TAG).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(28.dp)
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        val selected = colors.selectedBackground.toArgb()
        // 아이콘을 비킨 세로줄: 바 위아래 2dp씩은 비우고 그 안(바 높이 − 4dp, 모서리 둥긂)을 선택 배경으로 칠한다
        val x = (image.width / 2 - px(16.dp)).toInt()
        val rows = (0 until image.height).filter { image.getPixel(x, it) == selected }
        assertTrue("no selected square", rows.isNotEmpty())
        assertTrue("top gap ${rows.first()}", rows.first() >= px(2.dp) - 1)
        assertTrue("bottom gap ${rows.last()}", rows.last() <= image.height - px(2.dp))
        assertTrue("square height ${rows.size}", rows.size >= px(16.dp))
    }

    @Test
    fun accentButton_isFourDpLowerThanTheBar_andDoesNotTakeA48dpRow() {
        composeRule.setContent {
            StratumTheme {
                Row(Modifier.testTag(ROW)) {
                    TopBarAccentButton(onClick = {}, height = 28.dp, modifier = Modifier.testTag(TAG)) { Text("Export") }
                }
            }
        }

        // 바 높이 28dp에서 보이는 칸·누르는 높이는 24dp이고, 줄도 48dp로 늘어나지 않는다
        composeRule.onNodeWithTag(TAG).assertHeightIsEqualTo(24.dp)
        composeRule.onNodeWithTag(ROW).assertHeightIsEqualTo(24.dp)
    }

    @Test
    fun accentButton_inATallBar_staysAtTheAppButtonHeight() {
        composeRule.setContent {
            StratumTheme { TopBarAccentButton(onClick = {}, height = 42.dp, modifier = Modifier.testTag(TAG)) { Text("Export") } }
        }

        composeRule.onNodeWithTag(TAG).assertHeightIsEqualTo(30.dp)
    }

    private companion object {
        const val TAG = "barButton"
        const val ROW = "barRow"
    }
}

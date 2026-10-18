package com.kiln.stratum.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.ui.theme.stratumColors
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 앱 부품(결정 기록 '디자인(시안)')이 촘촘하게 보이면서도 누르는 영역 48dp를 지키는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class StratumComponentsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val colors = stratumColors(dark = true)

    private fun px(dp: Dp): Float = with(composeRule.density) { dp.toPx() }

    /** 찍기 전에 화면이 다 그려지기를 기다린다(느린 에뮬레이터에서 첫 화면을 찍으면 시간이 넘을 수 있다) */
    private fun shot(tag: String): Bitmap {
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    }

    /** 그림의 [x] 세로줄에서 [color]인 픽셀 수(가운데 평평한 칸의 높이) */
    private fun Bitmap.rowsOf(color: Int, x: Int = width / 2): Int = (0 until height).count { getPixel(x, it) == color }

    private fun assertAbout(what: String, expected: Float, actual: Int, tolerance: Float = 3f) {
        assertTrue("$what: expected about $expected px, was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun accentButton_isADenseBlueBlock_withA48dpTouchArea() {
        composeRule.setContent {
            StratumTheme { AccentButton(onClick = {}, modifier = Modifier.testTag(TAG)) { Text("Export") } }
        }

        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(48.dp)
        // 보이는 파란 칸은 30dp
        assertAbout("accent height", px(StratumDimens.ButtonHeight), shot(TAG).rowsOf(colors.accent.toArgb(), x = px(8.dp).toInt()))
    }

    @Test
    fun quietButton_keepsTheTouchArea_andRunsOnClick() {
        var clicks by mutableIntStateOf(0)
        composeRule.setContent {
            StratumTheme { QuietButton(onClick = { clicks++ }, modifier = Modifier.testTag(TAG)) { Text("Cancel") } }
        }

        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(48.dp).performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun segment_isDense_andTheChosenOneUsesTheSelectedColors() {
        composeRule.setContent {
            StratumTheme {
                StratumSegmentedRow(Modifier.width(240.dp)) {
                    StratumSegment(selected = true, onClick = {}, index = 0, count = 2, modifier = Modifier.testTag(TAG)) { Text("Add") }
                    StratumSegment(selected = false, onClick = {}, index = 1, count = 2) { Text("Subtract") }
                }
            }
        }

        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(48.dp)
        // 고른 칸은 선택 배경이고, 보이는 높이는 28dp(테두리 1dp씩 뺌)
        val chosen = shot(TAG).rowsOf(colors.selectedBackground.toArgb(), x = px(10.dp).toInt())
        assertAbout("segment height", px(StratumDimens.ControlHeight) - 2 * px(StratumDimens.Divider), chosen)
    }

    @Test
    fun selectBox_isADenseFieldBox_andOpensOnClick() {
        var clicks by mutableIntStateOf(0)
        composeRule.setContent {
            StratumTheme { SelectBox(text = "Normal", onClick = { clicks++ }, modifier = Modifier.testTag(TAG)) }
        }

        // 누르기 전에 찍는다(누른 뒤에는 번지는 표시가 사라지는 동안 색이 섞인다)
        val box = shot(TAG).rowsOf(colors.fieldBackground.toArgb(), x = px(5.dp).toInt())
        assertAbout("select box height", px(StratumDimens.ControlHeight) - 2 * px(StratumDimens.Divider), box)

        composeRule.onNodeWithTag(TAG).assertTouchHeightIsEqualTo(48.dp).performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun selectedIconButton_drawsARoundedSquare_notACircle() {
        composeRule.setContent {
            StratumTheme {
                StratumIconButton(onClick = {}, selected = true, modifier = Modifier.testTag(TAG)) {
                    Icon(painterResource(R.drawable.ic_brush), contentDescription = null)
                }
            }
        }

        composeRule.onNodeWithTag(TAG).assertTouchWidthIsEqualTo(48.dp)
        val image = shot(TAG)
        val selected = colors.selectedBackground.toArgb()
        // 40dp 사각형: 가장자리 가까운 세로줄(모서리를 비킨 곳)도 거의 끝까지 선택 배경이다. 동그라미라면 짧다.
        val nearEdge = image.rowsOf(selected, x = (image.width / 2 - px(16.dp)).toInt())
        assertAbout("square height near the edge", px(StratumDimens.ToolSquare), nearEdge, tolerance = px(4.dp))
    }

    @Test
    fun slider_changesValue_andFillsFromTheCenter() {
        var value by mutableFloatStateOf(0f)
        composeRule.setContent {
            StratumTheme {
                Column {
                    StratumSlider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = -1f..1f,
                        centered = true,
                        modifier = Modifier.width(300.dp).testTag(TAG)
                    )
                }
            }
        }

        composeRule.onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        composeRule.runOnIdle { assertEquals(0.5f, value, 0.01f) }
        val image = shot(TAG)
        val middle = image.height / 2
        val fill = colors.sliderFill.toArgb()
        val track = colors.sliderTrack.toArgb()
        // 가운데(0)와 손잡이(0.5) 사이는 채움, 가운데 왼쪽은 빈 트랙
        assertEquals(fill, image.getPixel(image.width * 5 / 8, middle))
        assertEquals(track, image.getPixel(image.width * 3 / 8, middle))
    }

    private companion object {
        const val TAG = "part"
    }
}

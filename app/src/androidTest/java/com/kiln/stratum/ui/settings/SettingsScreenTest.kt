package com.kiln.stratum.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.settings.PreviewQuality
import com.kiln.stratum.settings.SettingsValues
import com.kiln.stratum.ui.home.HomeScreen
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.touchBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 설정 화면: 커버의 한 줄 목록, 메인의 2단, 값 바꾸기, 힌지 피하기, 홈의 설정 버튼을 확인한다. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val values = mutableStateOf(SettingsValues())

    /** 가짜 힌지. 테스트 중에 바꾸면 화면이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())
    private var backs = 0

    private fun show(size: DpSize) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        SettingsScreen(
                            values = values.value,
                            onChange = { change -> values.value = change(values.value) },
                            onBack = { backs++ },
                            versionName = "1.2.3",
                            modifier = Modifier.testTag(SCREEN)
                        )
                    }
                }
            }
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)

    @Test
    fun cover_showsEveryCategoryInOneList() {
        show(COVER)

        for (category in SettingsCategory.entries) {
            composeRule.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasText(string(category.label)))
            composeRule.onNodeWithText(string(category.label)).assertIsDisplayed()
        }
        // 2단의 분류 줄과 오른쪽 칸은 없다
        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.EXPORT)).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.DETAIL).assertDoesNotExist()
    }

    @Test
    fun changingValues_isSentRightAway() {
        show(COVER)

        composeRule.onNodeWithTag(SettingsTags.QUALITY).performSemanticsAction(SemanticsActions.SetProgress) { it(55f) }
        composeRule.runOnIdle { assertEquals(55, values.value.export.quality) }

        // PNG는 품질 대신 손실 없음 안내를 보인다
        composeRule.onNodeWithTag(SettingsTags.format(ExportFormat.PNG)).performClick()
        composeRule.onNodeWithTag(SettingsTags.QUALITY).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.export_lossless)).assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTags.size(ExportSize.LongEdge(2048))).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTags.preview(PreviewQuality.LIGHT)).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTags.TWO_FINGER_UNDO).performScrollTo().performClick()

        composeRule.onNodeWithTag(SettingsTags.preview(PreviewQuality.LIGHT)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.TWO_FINGER_UNDO).assertIsOff()
        composeRule.runOnIdle {
            val changed = values.value
            assertEquals(ExportFormat.PNG, changed.export.format)
            assertEquals(ExportSize.LongEdge(2048), changed.export.size)
            assertEquals(PreviewQuality.LIGHT, changed.previewQuality)
            assertFalse(changed.twoFingerTapUndo)
        }
    }

    @Test
    fun main_showsTheChosenCategoryOnTheRight() {
        show(MAIN_LANDSCAPE)

        // 처음에는 내보내기
        composeRule.onNodeWithTag(SettingsTags.format(ExportFormat.JPEG)).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.EXPORT)).assertIsSelected()

        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.GESTURES)).performClick()
        composeRule.onNodeWithTag(SettingsTags.TWO_FINGER_UNDO).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.format(ExportFormat.JPEG)).assertDoesNotExist()

        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.ABOUT)).performClick()
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.LICENSES).performClick()
        composeRule.onNodeWithText(string(R.string.settings_licenses_text), substring = true).assertIsDisplayed()
    }

    @Test
    fun back_goesBack() {
        show(COVER)

        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun mainLandscape_splitsAroundTheVerticalHinge() {
        show(MAIN_LANDSCAPE)
        val screen = composeRule.onNodeWithTag(SCREEN).fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = screen.top.roundToInt(), right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        for (category in SettingsCategory.entries) {
            composeRule.onNodeWithTag(SettingsTags.category(category)).performClick()
            composeRule.assertClickablesAvoid(hinge)
        }
        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.EXPORT)).performClick()
        assertAvoids(SettingsTags.QUALITY, hinge)
    }

    @Test
    fun mainPortrait_movesItemsBelowTheHorizontalHinge() {
        show(MAIN_PORTRAIT)
        // 힌지 없이 크기 버튼 줄이 놓인 높이에 가로 힌지를 둔다. 비켜 놓지 않으면 그 줄이 띠에 걸린다.
        val row = composeRule.onNodeWithTag(SettingsTags.size(ExportSize.Original)).fetchSemanticsNode().layoutBoundsInWindow()
        val screen = composeRule.onNodeWithTag(SCREEN).fetchSemanticsNode().layoutBoundsInWindow()
        val y = row.center.y.roundToInt()
        val hinge = Hinge(left = screen.left.roundToInt(), top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        assertAvoids(SettingsTags.QUALITY, hinge)
        // 비켜 놓은 줄은 띠 아래에 있고, 스크롤하지 않아도 보인다
        val moved = composeRule.onNodeWithTag(SettingsTags.size(ExportSize.Original)).assertIsDisplayed().fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$moved should be below $y", moved.top > y)

        for (category in SettingsCategory.entries) {
            composeRule.onNodeWithTag(SettingsTags.category(category)).performClick()
            composeRule.assertClickablesAvoid(hinge)
        }
    }

    @Test
    fun mainPortrait_categoryRowsAvoidTheHorizontalHinge() {
        show(MAIN_PORTRAIT)
        val row = composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.PREVIEW)).fetchSemanticsNode().layoutBoundsInWindow()
        val screen = composeRule.onNodeWithTag(SCREEN).fetchSemanticsNode().layoutBoundsInWindow()
        val y = row.center.y.roundToInt()
        val hinge = Hinge(left = screen.left.roundToInt(), top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.ABOUT)).performClick()
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
    }

    @Test
    fun homeSettingsButton_opensSettings() {
        var opened = 0
        composeRule.setContent {
            StratumTheme { HomeScreen(onOpenPhoto = {}, onCreateCanvas = { _, _, _ -> }, onOpenSettings = { opened++ }) }
        }

        composeRule.onNodeWithContentDescription(string(R.string.action_settings)).performClick()

        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    /** [tag] 노드(슬라이더처럼 누르기 동작이 없는 것)의 터치 영역이 [hinge]의 띠 밖에 있는지 */
    private fun assertAvoids(tag: String, hinge: Hinge) {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        val bounds = node.touchBoundsInWindow()
        val (start, end) = if (hinge.isVertical) bounds.left to bounds.right else bounds.top to bounds.bottom
        assertTrue("$bounds overlaps hinge band $band", end <= band.start + 1 || start >= band.end - 1)
    }

    private companion object {
        const val SCREEN = "settingsScreen"

        /** 커버 화면 정도(Compact) */
        val COVER = DpSize(400.dp, 700.dp)

        /** 메인 가로(Expanded): Pixel_Fold 안쪽 화면 크기 */
        val MAIN_LANDSCAPE = DpSize(841.dp, 701.dp)

        /** 메인 세로(Medium) */
        val MAIN_PORTRAIT = DpSize(701.dp, 841.dp)
    }
}

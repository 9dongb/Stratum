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
import androidx.compose.ui.test.assertIsNotSelected
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
import com.kiln.stratum.settings.ThemeMode
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 설정 화면: 커버의 한 줄 목록, 메인의 2단(분류 칸 폭, 접히는 선과 상관없음), 값 바꾸기, 홈의 설정 버튼을 확인한다. */
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
        composeRule.onNodeWithTag(SettingsTags.THREE_FINGER).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTags.theme(ThemeMode.LIGHT)).performScrollTo().performClick()

        composeRule.onNodeWithTag(SettingsTags.preview(PreviewQuality.LIGHT)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.theme(ThemeMode.LIGHT)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.theme(ThemeMode.DARK)).assertIsNotSelected()
        composeRule.onNodeWithTag(SettingsTags.TWO_FINGER_UNDO).assertIsOff()
        composeRule.onNodeWithTag(SettingsTags.THREE_FINGER).assertIsOff()
        composeRule.runOnIdle {
            val changed = values.value
            assertEquals(ExportFormat.PNG, changed.export.format)
            assertEquals(ExportSize.LongEdge(2048), changed.export.size)
            assertEquals(PreviewQuality.LIGHT, changed.previewQuality)
            assertFalse(changed.twoFingerTapUndo)
            assertFalse(changed.threeFingerGestures)
            assertEquals(ThemeMode.LIGHT, changed.themeMode)
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
        composeRule.onNodeWithTag(SettingsTags.THREE_FINGER).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.format(ExportFormat.JPEG)).assertDoesNotExist()

        composeRule.onNodeWithTag(SettingsTags.category(SettingsCategory.ABOUT)).performClick()
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.LICENSES).performClick()
        composeRule.onNodeWithText(string(R.string.settings_licenses_text), substring = true).assertIsDisplayed()
        // 앱에 넣은 MI-GAN 모델의 MIT 고지(결정 기록 'AI 제거·AI 선택')
        composeRule.onNodeWithText(string(R.string.settings_licenses_mit_text), substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun back_goesBack() {
        show(COVER)

        composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()

        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun mainLandscape_theCategoryPaneHasItsOwnWidth_andAHingeChangesNothing() {
        // 사용자 피드백 11차 1: 전에는 2단을 접히는 선 양쪽에 반씩 놓아 가운데가 비었다
        show(MAIN_LANDSCAPE)
        val list = composeRule.onNodeWithTag(SettingsTags.LIST).fetchSemanticsNode()
        val detail = composeRule.onNodeWithTag(SettingsTags.DETAIL).fetchSemanticsNode().layoutBoundsInWindow()
        val dp = with(list.layoutInfo.density) { 1.dp.toPx() }
        // 분류 칸은 280dp(이름표는 안쪽 여백 8dp씩을 뺀 줄에 있어 264dp), 항목 칸이 나머지를 쓴다
        assertEquals(264 * dp, list.layoutBoundsInWindow().width, 2 * dp)
        assertTrue("detail $detail should be wider than the list", detail.width > list.layoutBoundsInWindow().width)
        val quality = composeRule.onNodeWithTag(SettingsTags.QUALITY).fetchSemanticsNode().layoutBoundsInWindow()

        val screen = composeRule.onNodeWithTag(SCREEN).fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = screen.top.roundToInt(), right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }

        assertEquals(quality, composeRule.onNodeWithTag(SettingsTags.QUALITY).fetchSemanticsNode().layoutBoundsInWindow())
    }

    @Test
    fun mainPortrait_aHorizontalHinge_doesNotMoveTheItems() {
        // 사용자 피드백 11차 1: 전에는 선에 걸리는 줄을 띠 아래로 비켜 놓아 줄 사이가 벌어졌다
        show(MAIN_PORTRAIT)
        val tags = listOf(SettingsTags.QUALITY, SettingsTags.size(ExportSize.Original), SettingsTags.category(SettingsCategory.PREVIEW))
        val before = tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().layoutBoundsInWindow() }
        val screen = composeRule.onNodeWithTag(SCREEN).fetchSemanticsNode().layoutBoundsInWindow()
        val y = before[1].center.y.roundToInt()

        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = screen.left.roundToInt(), top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)) }

        assertEquals(before, tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().layoutBoundsInWindow() })
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

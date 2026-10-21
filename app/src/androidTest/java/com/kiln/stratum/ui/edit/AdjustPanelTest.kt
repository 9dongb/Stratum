package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.components.SLIDER_THUMB_TAG
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 보정 패널을 세 배치(커버 탭, 메인 세로 아래 패널의 탭, 메인 가로 오른쪽 패널의 칸)에서 확인한다.
 * 엔진 대신 값만 들고 있는 가짜([FakeAdjust])를 써서, 슬라이더·곡선·버튼이 보내는 값을 본다.
 */
@RunWith(AndroidJUnit4::class)
class AdjustPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()


    /** ViewModel처럼 끄는 값과 기록된 값을 들고, 받은 일을 센다 */
    private class FakeAdjust : AdjustActions {
        var committed by mutableStateOf(Adjustments.NONE)
        var live by mutableStateOf<Adjustments?>(null)
        var previews = 0
        var commits = 0
        var records = 0

        override fun preview(change: (Adjustments) -> Adjustments) {
            previews++
            live = change(live ?: committed)
        }

        override fun commit() {
            val value = live ?: return
            commits++
            committed = value
            live = null
        }

        override fun record(change: (Adjustments) -> Adjustments) {
            records++
            committed = change(live ?: committed)
            live = null
        }
    }

    private val fake = FakeAdjust()
    private val document = PixelSize(400, 300)
    private val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)

    private fun ready(adjustments: Adjustments): EditorState.Ready =
        EditorState.Ready(
            PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, adjustments)),
            activeLayer = LayerId(0)
        )

    private fun show(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready(fake.committed),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        liveAdjustments = { fake.live },
                        adjust = fake
                    )
                }
            }
        }
    }

    /** [item] 줄의 슬라이더 */
    private fun slider(item: AdjustItem) =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(hasTestTag(EditScreenTags.adjustSlider(item))))

    private fun openCompactAdjust() {
        show(411, 800)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Adjust)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()
    }

    @Test
    fun compact_chipPicksTheSlider_andSettingItRecordsOnce() {
        openCompactAdjust()
        // 처음에는 노출 하나만 보인다
        composeRule.onNodeWithTag(EditScreenTags.adjustSlider(AdjustItem.EXPOSURE)).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.adjustSlider(AdjustItem.CONTRAST)).assertDoesNotExist()

        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CONTRAST)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CONTRAST)).assertIsSelected()
        // 화면 읽기 기능처럼 값을 정한다: 바꾸고(미리 보기) 바로 놓는다(기록)
        slider(AdjustItem.CONTRAST).performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        composeRule.runOnIdle {
            assertEquals(Adjustments(contrast = 0.4f), fake.committed)
            assertNull(fake.live)
            assertEquals(1, fake.commits)
        }
        // 숫자는 -100..100으로 보인다
        slider(AdjustItem.CONTRAST).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "+40"))
    }

    @Test
    fun compact_draggingPreviewsManyTimes_butRecordsOnce() {
        openCompactAdjust()

        slider(AdjustItem.EXPOSURE).performTouchInput {
            swipe(start = centerLeft + Offset(width * 0.5f, 0f), end = centerRight, durationMillis = 400)
        }

        composeRule.runOnIdle {
            assertTrue("previews=${fake.previews}", fake.previews > 1)
            assertEquals(1, fake.commits)
            assertTrue("exposure=${fake.committed.exposure}", fake.committed.exposure > 1.5f)
        }
    }

    @Test
    fun doubleTapOnTheName_resetsThatItemOnly() {
        fake.committed = Adjustments(exposure = 0.5f, contrast = 0.2f, saturation = 0.3f)
        openCompactAdjust()

        composeRule.onNodeWithTag(EditScreenTags.adjustSlider(AdjustItem.EXPOSURE), useUnmergedTree = true)
            .performTouchInput { doubleClick(centerLeft + Offset(20f, 0f)) }
        composeRule.runOnIdle { assertEquals(Adjustments(contrast = 0.2f, saturation = 0.3f), fake.committed) }

        // 칩을 바꾸면 같은 줄이 다른 항목이 된다. 그 항목을 초기화해야 한다.
        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CONTRAST)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.adjustSlider(AdjustItem.CONTRAST), useUnmergedTree = true)
            .performTouchInput { doubleClick(centerLeft + Offset(20f, 0f)) }
        composeRule.runOnIdle { assertEquals(Adjustments(saturation = 0.3f), fake.committed) }
    }

    /** [item] 줄 슬라이더의 원(손잡이). 누르는 자리는 원 둘레 48dp라 원 가운데를 누르면 원을 잡는다. */
    private fun thumb(item: AdjustItem) =
        composeRule.onNode(hasTestTag(SLIDER_THUMB_TAG) and hasAnyAncestor(hasTestTag(EditScreenTags.adjustSlider(item))), useUnmergedTree = true)

    private fun thumbCenterX(item: AdjustItem): Float = thumb(item).fetchSemanticsNode().layoutBoundsInWindow().center.x

    @Test
    fun doubleTapOnTheThumb_resetsThatItem_andOneTapChangesNothing() {
        fake.committed = Adjustments(exposure = 0.5f, contrast = 0.2f)
        openCompactAdjust()

        // 원을 한 번 톡: 누른 자리로 튀지 않고 아무것도 기록하지 않는다
        thumb(AdjustItem.EXPOSURE).performTouchInput { click(center + Offset(6f, 0f)) }
        // 다음 두 번 톡과 이어지지 않게 두 번 톡 시간을 넘긴다
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle {
            assertEquals(0, fake.previews)
            assertEquals(0, fake.commits)
            assertEquals(0, fake.records)
        }

        thumb(AdjustItem.EXPOSURE).performTouchInput { doubleClick(center) }

        composeRule.runOnIdle {
            assertEquals(Adjustments(contrast = 0.2f), fake.committed)
            // 초기화 한 번만 기록한다(누른 자리로 튄 값이 따로 기록되지 않는다)
            assertEquals(1, fake.records)
            assertEquals(0, fake.commits)
        }
    }

    @Test
    fun draggingTheThumb_followsTheFinger_withoutJumpingToIt() {
        fake.committed = Adjustments(exposure = 0.5f)
        openCompactAdjust()
        val before = thumbCenterX(AdjustItem.EXPOSURE)

        // 원 가운데에서 조금 오른쪽을 잡고 오른쪽으로 끈다
        val drag = 120f
        thumb(AdjustItem.EXPOSURE).performTouchInput {
            down(center + Offset(10f, 0f))
            moveBy(Offset(drag / 2, 0f))
            moveBy(Offset(drag / 2, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertTrue("exposure ${fake.committed.exposure}", fake.committed.exposure > 0.5f)
            // 놓을 때 한 번 기록한다
            assertEquals(1, fake.commits)
        }
        // 잡은 자리(+10px)로 튀지 않고 원이 손가락이 끈 만큼 옮겨졌다(값은 0.01 단위라 1~2px 어긋날 수 있다)
        assertEquals(before + drag, thumbCenterX(AdjustItem.EXPOSURE), 3f)
    }

    @Test
    fun mainPanel_doubleTapOnTheThumb_resetsToo() {
        fake.committed = Adjustments(saturation = -0.4f)
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.ADJUST)).performClick()

        slider(AdjustItem.SATURATION).performScrollTo()
        thumb(AdjustItem.SATURATION).performTouchInput { doubleClick(center) }

        composeRule.runOnIdle {
            assertEquals(Adjustments.NONE, fake.committed)
            assertEquals(1, fake.records)
        }
    }

    @Test
    fun resetAll_isEnabledOnlyWhenSomethingChanged() {
        openCompactAdjust()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_RESET_ALL).assertIsNotEnabled()

        slider(AdjustItem.EXPOSURE).performSemanticsAction(SemanticsActions.SetProgress) { it(-0.5f) }
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_RESET_ALL).assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(Adjustments.NONE, fake.committed)
            assertEquals(1, fake.records)
        }
    }

    @Test
    fun curve_tapAddsAPoint_andDeleteRemovesIt() {
        openCompactAdjust()
        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CURVE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.CURVE_DELETE_POINT).assertIsNotEnabled()

        // 그래프 가운데를 톡: 곡선 위(대각선)에 점이 하나 생기고 기록된다
        composeRule.onNodeWithTag(EditScreenTags.CURVE_EDITOR).performTouchInput { click(center) }
        composeRule.runOnIdle {
            val points = fake.committed.curve.points
            assertEquals(3, points.size)
            assertEquals(points[1].x, points[1].y, 0.02f)
            assertEquals(1, fake.commits)
        }

        // 더한 점이 골라져 있어 지울 수 있다
        composeRule.onNodeWithTag(EditScreenTags.CURVE_DELETE_POINT).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(ToneCurve.IDENTITY, fake.committed.curve) }
    }

    @Test
    fun curve_draggingUpBendsTheCurve() {
        openCompactAdjust()
        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CURVE)).performScrollTo().performClick()

        // 가운데에서 위로 끌면 그 자리에 점을 더해 올린다(중간 밝기를 밝게)
        composeRule.onNodeWithTag(EditScreenTags.CURVE_EDITOR).performTouchInput {
            swipe(start = center, end = center - Offset(0f, height * 0.25f), durationMillis = 300)
        }

        composeRule.runOnIdle {
            val curve = fake.committed.curve
            assertEquals(3, curve.points.size)
            assertTrue("curve=$curve", curve.valueAt(0.5f) > 0.6f)
            assertEquals(1, fake.commits)
        }
        composeRule.onNodeWithTag(EditScreenTags.CURVE_RESET).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(ToneCurve.IDENTITY, fake.committed.curve) }
    }

    @Test
    fun medium_adjustPanelShowsAllSliders() {
        show(700, 900)

        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.ADJUST)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.ADJUST_PANEL) and hasAnyAncestor(hasTestTag(EditScreenTags.FLOATING_PANEL)))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_RESET_ALL).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.adjustSlider(AdjustItem.SATURATION)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.CURVE_EDITOR).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun medium_railToolKeepsTheAdjustPanel_andShowsItsStrip() {
        show(700, 900)
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.ADJUST)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()

        // 도구 막대에서 도구를 골라도 패널은 남고(직접 끄기 전까지 유지), 옵션은 도구 막대 오른쪽 띠에 보인다(결정 기록 '떠 있는 레이어·조정 패널')
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertIsDisplayed()
    }

    @Test
    fun expanded_cropHidesTheAdjustPanel() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.ADJUST)).performClick()
        slider(AdjustItem.EXPOSURE).assertIsEnabled()

        // 자르기 중에는 보정을 막는다(기록하면 자르기 테두리가 처음으로 돌아가므로): 패널을 숨기고 버튼을 끈다
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.ADJUST)).assertIsNotEnabled()
    }

    @Test
    fun compactItem_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready(fake.committed),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        liveAdjustments = { fake.live },
                        adjust = fake
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Adjust)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CURVE)).performScrollTo().performClick()

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(EditScreenTags.CURVE_EDITOR).assertIsDisplayed()
    }

    @Test
    fun curveWithManyPoints_isShownAsIs() {
        // 점이 여럿인 곡선도 그대로 그리고, 가운데 점을 골라 지울 수 있다
        fake.committed = Adjustments(curve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.2f), CurvePoint(0.7f, 0.8f), CurvePoint(1f, 1f))))
        openCompactAdjust()
        composeRule.onNodeWithTag(EditScreenTags.adjustChip(AdjustItem.CURVE)).performScrollTo().performClick()

        // (0.3, 0.2) 점을 톡 눌러 고른다. 그래프는 점 반지름만큼 안쪽에 그려진다.
        composeRule.onNodeWithTag(EditScreenTags.CURVE_EDITOR).performTouchInput {
            val inset = 8.dp.toPx()
            click(Offset(inset + (width - inset * 2) * 0.3f, inset + (height - inset * 2) * 0.8f))
        }
        composeRule.onNodeWithTag(EditScreenTags.CURVE_DELETE_POINT).assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(0.7f, 0.8f), CurvePoint(1f, 1f)), fake.committed.curve.points)
            // 점을 고르기만 한 것은 기록하지 않았다
            assertEquals(0, fake.commits)
        }
    }
}

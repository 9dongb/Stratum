package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 이동 도구(사용자 피드백 7차, 결정 기록 '이동 도구')의 자리·안내와, 캔버스 끌기가 옮기기를 문서 좌표로 보내는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class MoveToolTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 받은 일과 그 문서 좌표 */
    private val calls = mutableListOf<String>()
    private val points = mutableListOf<Pair<Float, Float>>()

    private val recorder = object : MoveActions {
        override fun begin(x: Float, y: Float) {
            calls += "begin"
            points += x to y
        }

        override fun move(x: Float, y: Float) {
            if (calls.lastOrNull() != "move") calls += "move"
            points += x to y
        }

        override fun end() {
            calls += "end"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides emptyList()) {
                    StratumTheme {
                        EditScreen(
                            state = EditorState.Ready(
                                PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                                document,
                                layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
                                activeLayer = LayerId(0)
                            ),
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {},
                            moveActions = recorder
                        )
                    }
                }
            }
        }
    }

    private fun text(id: Int) = composeRule.activity.getString(id)

    @Test
    fun main_moveIsTheTopTool_withoutAStripOrAHint() {
        show(930, 700)
        val move = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Move)).fetchSemanticsNode().layoutBoundsInWindow()
        val transform = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).fetchSemanticsNode().layoutBoundsInWindow()
        // 사용자 요청: 도구 막대 맨 위
        assertTrue("$move should be above $transform", move.bottom <= transform.top + 1f)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Move)).performClick()

        // 고를 옵션이 없어 띠가 없고, 도구를 고를 때 뜨던 안내 알림도 없다(사용자 피드백 8차 1-7)
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
    }

    @Test
    fun cover_moveIsTheFirstInTheMoreRow_withoutAnOptionsSheet() {
        show(411, 800)
        // 커버 탭 줄에는 없고(사용자 지정 탭: 자르기·선택·브러시·리터칭·유동화·더보기), 더보기 줄 맨 앞이다
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Move)).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        val move = composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Move)).fetchSemanticsNode().layoutBoundsInWindow()
        val eraser = composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Eraser)).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$move should be left of $eraser", move.right <= eraser.left + 1f)
        composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Move)).performClick()

        // 고를 옵션이 없어 옵션 칸을 띄우지 않고(설명 줄도 뺌, 사용자 피드백 8차 1-7), 한 줄 막대에는 도구 이름만 보인다(결정 기록 '커버 화면 개편')
        composeRule.onNodeWithTag(EditScreenTags.MOVE_OVERLAY).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.COVER_OPTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.BAR_HINT).assertTextEquals(composeRule.activity.getString(R.string.tool_move_short))
    }

    @Test
    fun dragging_sendsTheMoveInDocumentCoordinates() {
        show(411, 800)
        composeRule.chooseCoverTool(EditTool.Move)

        composeRule.onNodeWithTag(EditScreenTags.MOVE_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 30f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("begin", "move", "end"), calls)
            val (x, y) = points.first()
            assertTrue("start $x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
            val (lastX, lastY) = points.last()
            assertTrue("end $lastX, $lastY", lastX > x && lastY > y)
        }
    }

    @Test
    fun aSecondFinger_dropsTheMove() {
        show(411, 800)
        composeRule.chooseCoverTool(EditTool.Move)

        composeRule.onNodeWithTag(EditScreenTags.MOVE_OVERLAY).performTouchInput {
            down(0, center)
            moveBy(0, Offset(60f, 0f))
            down(1, center + Offset(0f, 120f))
            moveBy(1, Offset(0f, 40f))
            up(0)
            up(1)
        }

        composeRule.runOnIdle { assertEquals("cancel", calls.last()) }
    }
}

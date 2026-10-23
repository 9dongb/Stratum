package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 이동 도구(사용자 피드백 7차, 결정 기록 '이동 도구')의 자리·안내와, 캔버스 끌기가 옮기기를 문서 좌표로 보내는지,
 * 띠·막대의 돌리기·뒤집기 버튼(사용자 피드백 12차 2)이 방향을 보내는지 확인한다.
 */
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

        override fun orient(orientation: Orientation) {
            calls += "orient $orientation"
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
    fun main_moveIsTheTopTool_withTurnCellsInTheStrip_andNoHint() {
        show(930, 700)
        val move = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Move)).fetchSemanticsNode().layoutBoundsInWindow()
        val transform = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).fetchSemanticsNode().layoutBoundsInWindow()
        // 사용자 요청: 도구 막대 맨 위
        assertTrue("$move should be above $transform", move.bottom <= transform.top + 1f)

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Move)).performClick()

        // 띠에 고른 레이어 돌리기·뒤집기 칸 넷(사용자 피드백 12차 2). 안내 알림은 없다(8차 1-7).
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        for (turn in LayerTurns) composeRule.onNodeWithTag(EditScreenTags.strip(turn.key)).performClick()
        composeRule.runOnIdle {
            assertEquals(LayerTurns.map { "orient ${it.orientation}" }, calls)
            assertEquals(
                listOf(Orientation.ROTATE_COUNTERCLOCKWISE, Orientation.ROTATE_CLOCKWISE, Orientation.FLIP_HORIZONTAL, Orientation.FLIP_VERTICAL),
                LayerTurns.map { it.orientation }
            )
        }
    }

    @Test
    fun cover_moveIsTheFirstTab_andTheBarHasTheTurnChips() {
        show(411, 800)
        // 커버 탭 줄 맨 앞이다(2026-10-23 사용자 요청: "자르기 도구 위치에 이동 도구를"). 더보기 줄에는 없다.
        val move = composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Move)).fetchSemanticsNode().layoutBoundsInWindow()
        val select = composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Select)).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$move should be left of $select", move.right <= select.left + 1f)
        // 도구를 고르지 않았으면 더보기 줄이 늘 보인다(2026-10-23)
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Move)).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Move)).performClick()

        // 한 줄 막대에 돌리기·뒤집기 칩 넷(사용자 피드백 12차 2, 전에는 도구 이름만). 화면 읽기는 '레이어를 …'로 읽는다.
        composeRule.onNodeWithTag(EditScreenTags.MOVE_OVERLAY).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.BAR_HINT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar(LayerTurns.first().key))
            .assertContentDescriptionEquals(text(R.string.layer_rotate_left))
        for (turn in LayerTurns) composeRule.onNodeWithTag(EditScreenTags.bar(turn.key)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(LayerTurns.map { "orient ${it.orientation}" }, calls) }
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

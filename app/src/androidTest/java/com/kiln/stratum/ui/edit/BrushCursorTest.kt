package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 칠하는 손가락 자리의 브러시 커서(결정 기록 '브러시 커서')가 브러시·지우개·명암·리터칭에서 보이고 사라지는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class BrushCursorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(tool: EditTool) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
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
                        onChooseSize = {}
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(tool)).performScrollTo().performClick()
    }

    private fun cursorShowsWhilePainting(overlayTag: String) {
        val overlay = composeRule.onNodeWithTag(overlayTag)
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()

        overlay.performTouchInput { down(center) }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertExists()
        overlay.performTouchInput { moveBy(Offset(60f, 0f)) }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertExists()

        overlay.performTouchInput { up() }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()
    }

    @Test
    fun brush_showsTheCursorWhileTheFingerIsDown() {
        show(EditTool.Brush)
        cursorShowsWhilePainting(EditScreenTags.STROKE_OVERLAY)
    }

    @Test
    fun eraser_showsTheCursor() {
        show(EditTool.Eraser)
        cursorShowsWhilePainting(EditScreenTags.STROKE_OVERLAY)
    }

    @Test
    fun tone_showsTheCursor() {
        show(EditTool.Tone)
        cursorShowsWhilePainting(EditScreenTags.TONE_OVERLAY)
    }

    @Test
    fun retouch_showsTheCursorWhilePainting() {
        // 처음 하위 도구는 스팟 복구라 가져올 곳을 정하지 않고 바로 칠한다
        show(EditTool.Retouch)
        cursorShowsWhilePainting(EditScreenTags.RETOUCH_OVERLAY)
    }

    @Test
    fun aSecondFinger_hidesTheCursor() {
        show(EditTool.Brush)
        val overlay = composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY)

        overlay.performTouchInput { down(0, center) }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertExists()
        overlay.performTouchInput { down(1, center + Offset(120f, 0f)) }

        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()
        overlay.performTouchInput {
            up(1)
            up(0)
        }
    }

    @Test
    fun holdingStill_handsTheFingerToTheEyedropper_andHidesTheCursor() {
        show(EditTool.Brush)
        val overlay = composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY)

        overlay.performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(2f, 0f))
        }

        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()
        overlay.performTouchInput { up() }
    }
}

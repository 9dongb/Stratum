package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.captureToImage
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
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 크기·단단함 슬라이더를 끄는 동안 캔버스에 보이는 브러시 크기 원(그 크기·배율, 끄는 동안만 보임)을 확인한다. */
@RunWith(AndroidJUnit4::class)
class SizePreviewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 1000×1000 문서. 정사각형 캔버스에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)

    private fun hex(color: Int) = Integer.toHexString(color)

    /** [size]·[hardness]의 크기 원을 300dp 정사각형 캔버스에 맞춤 보기로 그려 찍은 그림 */
    private fun drawPreview(size: Float, hardness: Float?): Bitmap {
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(300.dp)) {
                    SizePreviewOverlay(view = { CanvasView.Fit }, document = document, size = size, hardness = hardness, modifier = Modifier.fillMaxSize())
                }
            }
        }
        return composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).captureToImage().asAndroidBitmap()
    }

    private fun dpToPx(dp: Float): Int =
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).fetchSemanticsNode().layoutInfo.density.run { dp.dp.roundToPx() }

    @Test
    fun overlay_drawsTheBrushDiameterAtTheViewScale_inTheMiddle() {
        val shot = drawPreview(size = 500f, hardness = 1f)
        val fit = fitRect(shot.width, shot.height, document)
        // 지름 500px을 맞춤 배율로 그린 반지름
        val radius = (250.0 * fit.width / document.width).roundToInt()
        val centerX = shot.width / 2
        val centerY = shot.height / 2
        val margin = dpToPx(3f)

        val ring = shot.getPixel(centerX + radius, centerY)
        assertTrue(hex(ring), AndroidColor.red(ring) > 230 && AndroidColor.green(ring) > 230 && AndroidColor.blue(ring) > 230)
        assertTrue(hex(shot.getPixel(centerX, centerY + radius)), AndroidColor.red(shot.getPixel(centerX, centerY + radius)) > 230)
        // 단단함 100%는 안쪽 원이 없다. 원 안팎은 그리지 않아 뒤(창 바탕)가 그대로 보인다.
        val background = shot.getPixel(1, 1)
        assertEquals(hex(background), hex(shot.getPixel(centerX, centerY)))
        assertEquals(hex(background), hex(shot.getPixel(centerX + radius - margin, centerY)))
        assertEquals(hex(background), hex(shot.getPixel(centerX + radius + margin, centerY)))
    }

    @Test
    fun softBrush_alsoDrawsTheHardnessRing() {
        val shot = drawPreview(size = 500f, hardness = 0.5f)
        val fit = fitRect(shot.width, shot.height, document)
        val radius = 250.0 * fit.width / document.width

        // 단단함 자리(반지름의 절반)에 옅은 원이 있다
        val background = shot.getPixel(1, 1)
        assertTrue(shot.getPixel(shot.width / 2 + (radius / 2).roundToInt(), shot.height / 2) != background)
    }

    @Test
    fun tinyBrush_isStillVisible() {
        val shot = drawPreview(size = 1f, hardness = 1f)

        assertTrue(shot.getPixel(shot.width / 2, shot.height / 2) != shot.getPixel(1, 1))
    }

    /** 브러시를 고른 편집 화면([widthDp]×[heightDp]) */
    private fun showEditScreen(widthDp: Int, heightDp: Int) {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
                }
            }
        }
    }

    /** [sliderTag] 슬라이더를 누른 채 끄는 동안 크기 원이 보이고, 손을 떼면 바로 사라진다 */
    private fun dragShowsTheSizeUntilRelease(sliderTag: String) {
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()

        composeRule.onNodeWithTag(sliderTag).performTouchInput {
            down(center)
            moveBy(Offset(width / 8f, 0f))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertExists()

        composeRule.onNodeWithTag(sliderTag).performTouchInput { up() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun cover_brushSizeAndHardness_showTheSizeWhileDragging_andHideItOnRelease() {
        showEditScreen(411, 800)
        composeRule.chooseCoverTool(EditTool.Brush)

        dragShowsTheSizeUntilRelease(EditScreenTags.BRUSH_SIZE)
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performScrollTo()
        dragShowsTheSizeUntilRelease(EditScreenTags.BRUSH_HARDNESS)
    }

    /** 도구 막대로 [tool]을 고르고, 띠의 [cells] 칸(칸 이름 → 슬라이더 이름표)마다 옵션 창을 열어 끄는 동안만 크기 원이 보이는지 본다 */
    private fun stripSlidersShowTheSize(tool: EditTool, vararg cells: Pair<String, String>) {
        composeRule.onNodeWithTag(EditScreenTags.railTool(tool)).performScrollTo().performClick()
        for ((cell, tag) in cells) {
            composeRule.onNodeWithTag(EditScreenTags.strip(cell)).performScrollTo().performClick()
            dragShowsTheSizeUntilRelease(tag)
        }
    }

    @Test
    fun mainPortrait_retouchAndToneSizeAndHardness_showTheSize() {
        showEditScreen(700, 900)

        stripSlidersShowTheSize(EditTool.Retouch, "size" to EditScreenTags.RETOUCH_SIZE, "hardness" to EditScreenTags.RETOUCH_HARDNESS)
        stripSlidersShowTheSize(EditTool.Tone, "size" to EditScreenTags.TONE_SIZE, "hardness" to EditScreenTags.TONE_HARDNESS)
    }

    @Test
    fun mainLandscape_eraserSizeAndHardness_showTheSize() {
        showEditScreen(930, 700)

        stripSlidersShowTheSize(EditTool.Eraser, "size" to EditScreenTags.BRUSH_SIZE, "hardness" to EditScreenTags.BRUSH_HARDNESS)
    }

    @Test
    fun flyoutClosedMidDrag_doesNotLeaveTheSizeBehind() {
        showEditScreen(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).performTouchInput {
            down(center)
            moveBy(Offset(width / 8f, 0f))
        }
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertExists()

        // 손을 떼지 않은 채 뒤로 가기로 옵션 창이 닫혀도 크기 원은 사라진다
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()
    }

    @Test
    fun liquifySizeSlider_showsTheSize() {
        val preview = PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    LiquifyMode(
                        LiquifyState(preview, document, LiquifyTool.PUSH, size = 150f, strength = 0.5f, canUndo = false, changed = false),
                        LiquifyActions.None
                    )
                }
            }
        }

        dragShowsTheSizeUntilRelease(EditScreenTags.LIQUIFY_SIZE)
    }
}

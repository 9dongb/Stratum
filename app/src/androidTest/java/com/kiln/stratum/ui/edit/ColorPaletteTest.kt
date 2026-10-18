package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.ui.layout.HINGE_AWARE_DIALOG_CARD
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 브러시·글자의 색 줄(지금 색·최근 색)과 색 팔레트(결정 기록 '색 팔레트·최근 색')를 커버·메인 배치에서 확인한다.
 * 고른 색은 엔진 대신 받은 일로 본다.
 */
@RunWith(AndroidJUnit4::class)
class ColorPaletteTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())
    private var brush by mutableStateOf(BrushSettings(size = 40f, color = BLACK))
    private var textStyle by mutableStateOf(TextStyle(size = 60f, color = BLACK))

    /** 고른 색(대상, 색) */
    private val chosen = mutableListOf<Pair<ColorTarget, Int>>()

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
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
                            brush = brush,
                            onBrush = { brush = it },
                            textStyle = textStyle,
                            onTextStyle = { textStyle = it },
                            recentColors = RECENT,
                            onChooseColor = { target, color -> chosen += target to color }
                        )
                    }
                }
            }
        }
    }

    private fun openCoverBrush() {
        show(411, 800)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Brush)).performClick()
    }

    private fun shownHex(): String =
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_HEX).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun hexOf(color: Int) = String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun assertFullyVisible(interaction: SemanticsNodeInteraction) {
        val coordinates = interaction.fetchSemanticsNode().layoutInfo.coordinates
        assertEquals("visible height", coordinates.size.height.toFloat(), coordinates.boundsInWindow().height, 1f)
    }

    @Test
    fun colorRow_showsTheCurrentColorAndRecentColors_andARecentColorIsChosen() {
        openCoverBrush()

        composeRule.onNodeWithTag(EditScreenTags.COLOR_CURRENT).assertIsDisplayed()
        // 지금 색(검정)과 같은 최근 색은 골라진 것으로 보인다
        composeRule.onNodeWithTag(EditScreenTags.recentColor(1)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.recentColor(2)).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(listOf(ColorTarget.BRUSH to WHITE), chosen) }
    }

    @Test
    fun palette_picksFromTheSquareAndTheHue_andOkChoosesThatColor() {
        openCoverBrush()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_CURRENT).performClick()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE).assertIsDisplayed()
        assertEquals(hexOf(BLACK), shownHex())

        // 오른쪽 위(채도·밝기 가장 높음)를 누르고 색상을 파랑(240°)으로
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_SQUARE).performTouchInput { click(Offset(width - 1f, 1f)) }
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_HUE).performSemanticsAction(SemanticsActions.SetProgress) { it(240f) }
        val hex = shownHex()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_OK).performClick()

        composeRule.onNodeWithTag(EditScreenTags.PALETTE).assertDoesNotExist()
        composeRule.runOnIdle {
            val (target, color) = chosen.single()
            assertEquals(ColorTarget.BRUSH, target)
            assertEquals(hex, hexOf(color))
            assertTrue(hex, Color.blue(color) > 240 && Color.red(color) < 10 && Color.green(color) < 10)
        }
    }

    @Test
    fun cancel_keepsTheColor_andOriginalUndoesThePick() {
        openCoverBrush()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_CURRENT).performClick()

        composeRule.onNodeWithTag(EditScreenTags.PALETTE_SQUARE).performTouchInput { click(center) }
        assertNotEquals(hexOf(BLACK), shownHex())
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_ORIGINAL).performClick()
        assertEquals(hexOf(BLACK), shownHex())

        composeRule.onNodeWithTag(EditScreenTags.PALETTE_SQUARE).performTouchInput { click(center) }
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_CANCEL).performClick()

        composeRule.onNodeWithTag(EditScreenTags.PALETTE).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(chosen.isEmpty()) }
    }

    @Test
    fun text_usesTheSameColorRow() {
        show(411, 800)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Text)).performScrollTo().performClick()

        composeRule.onNodeWithTag(EditScreenTags.recentColor(0)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_CURRENT).performClick()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_OK).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ColorTarget.TEXT to BLUE, ColorTarget.TEXT to BLACK), chosen) }
    }

    @Test
    fun mainPortrait_paletteFitsAboveTheHinge() {
        // 폴드8 메인 세로처럼 가운데를 가로지르는 힌지가 있으면 창이 위쪽 절반에 들어간다
        show(701, 841)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerY = screen.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = centerY, right = screen.right.roundToInt(), bottom = centerY, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.COLOR_CURRENT).performClick()

        composeRule.onNodeWithTag(HINGE_AWARE_DIALOG_CARD).assertExists()
        // 스크롤하지 않아도 네모·색상 띠·새 색·원래 색·16진 코드가 다 보이고, 누르는 것은 모두 힌지를 피한다
        for (tag in listOf(EditScreenTags.PALETTE_SQUARE, EditScreenTags.PALETTE_HUE, EditScreenTags.PALETTE_NEW, EditScreenTags.PALETTE_ORIGINAL, EditScreenTags.PALETTE_HEX)) {
            assertFullyVisible(composeRule.onNodeWithTag(tag))
        }
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_OK).assertIsDisplayed()
        composeRule.assertClickablesAvoid(hinge)
        val square = composeRule.onNodeWithTag(EditScreenTags.PALETTE_SQUARE).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$square crosses the hinge at $centerY", square.bottom <= centerY)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLUE = 0xFF1E88E5.toInt()
        val RECENT = listOf(BLUE, BLACK, WHITE)
    }
}

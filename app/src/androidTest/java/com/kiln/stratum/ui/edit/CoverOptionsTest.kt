package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 닫힌 화면 공통 규칙 ①(결정 기록 '닫힌 화면 도구 옵션'): 도구·조정·레이어 탭을 골라도 옵션 칸은 캔버스 위에 겹쳐 뜨고,
 * 캔버스 크기(보던 이미지의 크기·자리)는 그대로다. 자르기(새 창)는 빠진다.
 */
@RunWith(AndroidJUnit4::class)
class CoverOptionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(1000, 1000)
    private val canvasSizes = mutableListOf<Pair<Int, Int>>()

    private fun show() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { w, h -> canvasSizes += w to h }, onViewport = {}, onChooseSize = {})
                }
            }
        }
    }

    private fun tab(tool: EditTool) = composeRule.chooseCoverTool(tool)

    private fun canvasBounds() = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutBoundsInWindow()

    @Test
    fun toolsAdjustAndLayers_overlayTheCanvas_andKeepItsSize() {
        show()
        // 첫 화면이 자리 잡은 뒤(상단 바가 카메라 구멍을 피해 내려오는 등)부터 본다
        composeRule.waitForIdle()
        composeRule.runOnIdle { canvasSizes.clear() }
        val before = canvasBounds()
        composeRule.onNodeWithTag(EditScreenTags.COVER_OPTIONS).assertDoesNotExist()

        // 도구 탭, 상단 바의 조정·레이어 버튼(결정 기록 '커버 화면 개편'), 레이어를 끄면 다시 고른 도구, 다른 도구
        val steps = listOf<Pair<String, () -> Unit>>(
            "select" to { tab(EditTool.Select) },
            "adjust" to { composeRule.togglePanel(MainPanel.ADJUST) },
            "layers" to { composeRule.togglePanel(MainPanel.LAYERS) },
            "layers off" to { composeRule.togglePanel(MainPanel.LAYERS) },
            "brush" to { tab(EditTool.Brush) }
        )
        for ((name, step) in steps) {
            step()
            composeRule.waitForIdle()
            assertEquals("$name canvas", before, canvasBounds())
            // 옵션 칸은 캔버스 아래쪽 위에 겹쳐 있다
            val sheet = composeRule.onNodeWithTag(EditScreenTags.COVER_OPTIONS).fetchSemanticsNode().layoutBoundsInWindow()
            assertTrue("$name sheet $sheet should overlap $before", sheet.top < before.bottom && sheet.bottom <= before.bottom + 1f)
        }
        // 탭을 닫아도 그대로다(하위 도구가 있는 선택은 다시 누르면 메뉴라, 하위 도구가 없는 브러시로 닫는다)
        tab(EditTool.Brush)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.COVER_OPTIONS).assertDoesNotExist()
        assertEquals(before, canvasBounds())
        // 탭을 고르고 바꾸고 닫는 동안 캔버스 크기가 한 번도 바뀌지 않았다(축소본을 다시 그리지 않음)
        composeRule.runOnIdle { assertTrue("canvas sizes $canvasSizes", canvasSizes.isEmpty()) }
    }

    @Test
    fun crop_isANewWindow_soTheCanvasFitsAboveItsOptions() {
        show()
        val before = canvasBounds()
        tab(EditTool.Transform)
        composeRule.waitForIdle()
        val cropping = canvasBounds()
        assertTrue("$cropping should be shorter than $before", cropping.height < before.height)
    }
}

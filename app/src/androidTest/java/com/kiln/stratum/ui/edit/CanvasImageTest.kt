package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 캔버스가 투명한 곳에 체크 무늬를 깔고 바깥에 테두리를 두르는지, 화면을 찍어 확인한다. */
@RunWith(AndroidJUnit4::class)
class CanvasImageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun hex(color: Int) = Integer.toHexString(color)

    /**
     * 두 색이 채널마다 [tolerance] 안으로 같은지. 1px 선은 캔버스 크기에 따라 픽셀 경계에 걸쳐 바탕과 조금 섞일 수 있다
     * (커버 화면 개편으로 캔버스 높이가 바뀌어 테두리가 채널마다 1 밝게 섞였다).
     */
    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 3) {
        val near = listOf(24, 16, 8, 0).all { shift -> kotlin.math.abs((expected shr shift and 0xff) - (actual shr shift and 0xff)) <= tolerance }
        assertEquals(hex(expected), if (near) hex(expected) else hex(actual))
    }

    @Test
    fun transparentCanvas_showsCheckerboardInsideAndOutlineOutside() {
        // 완전히 투명한 축소본(가로로 긴 100×50)
        val document = PixelSize(100, 50)
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        var outline = Color.Unspecified
        var background = Color.Unspecified
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    outline = StratumTheme.colors.canvasOutline
                    background = StratumTheme.colors.workspace
                    EditScreen(
                        state = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {}
                    )
                }
            }
        }

        val shot = composeRule.onNodeWithTag(EditScreenTags.CANVAS).captureToImage().asAndroidBitmap()
        val fit = fitRect(shot.width, shot.height, document)
        val middleX = fit.left + fit.width / 2

        // 캔버스 안: 왼쪽 위 첫 칸은 밝은 칸이다
        assertEquals(hex(CanvasOverlayColors.CheckerLight.toArgb()), hex(shot.getPixel(fit.left + 1, fit.top + 1)))
        // 가로로 다음 칸은 어두운 칸이다(칸 크기 8dp는 캔버스 폭보다 작다)
        val square = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutInfo.density
            .run { CheckerSquare.roundToPx() }
        assertEquals(hex(CanvasOverlayColors.CheckerDark.toArgb()), hex(shot.getPixel(fit.left + square + 1, fit.top + 1)))
        // 캔버스 바로 위 바깥은 테두리, 더 바깥은 캔버스 배경이다
        assertColorNear(outline.toArgb(), shot.getPixel(middleX, fit.top - 1))
        assertEquals(hex(background.toArgb()), hex(shot.getPixel(middleX, fit.top / 2)))
    }

    @Test
    fun magnifiedOriginal_showsCrispPixels() {
        // 빨강·파랑 두 픽셀짜리 원본을 800%로 본다. 가장 가까운 픽셀로 그려야 경계에 섞인 색이 없다.
        val document = PixelSize(2, 1)
        val preview = Bitmap.createBitmap(intArrayOf(AndroidColor.RED, AndroidColor.BLUE), 2, 1, Bitmap.Config.ARGB_8888)
        val view = CanvasView.Free(scale = 8.0, centerX = 1.0, centerY = 0.5)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(200.dp, 100.dp).testTag("canvas")) {
                    CanvasImage(
                        state = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document),
                        view = { view },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val shot = composeRule.onNodeWithTag("canvas").captureToImage().asAndroidBitmap()
        val transform = view.transform(PixelSize(shot.width, shot.height), document)
        val y = transform.toScreenY(0.5).roundToInt()
        val redStart = transform.toScreenX(0.0).roundToInt()
        val boundary = transform.toScreenX(1.0).roundToInt()
        val blueEnd = transform.toScreenX(2.0).roundToInt()
        for (x in redStart until boundary) assertEquals("x=$x", hex(AndroidColor.RED), hex(shot.getPixel(x, y)))
        for (x in boundary until blueEnd) assertEquals("x=$x", hex(AndroidColor.BLUE), hex(shot.getPixel(x, y)))
    }

    @Test
    fun turnedPreview_isDrawnTurned_withCrispPixels() {
        // 자르기·회전 화면(사용자 피드백 8차 1): 빨강·파랑 두 픽셀짜리 원본을 픽셀을 다시 그리지 않고 돌려 보인다
        val document = PixelSize(2, 1)
        val preview = Bitmap.createBitmap(intArrayOf(AndroidColor.RED, AndroidColor.BLUE), 2, 1, Bitmap.Config.ARGB_8888)
        var orientation by mutableStateOf(Orientation.ROTATE_CLOCKWISE)
        // 보기는 돌린 모습의 문서 기준이다(시계 방향이면 1×2)
        var view by mutableStateOf<CanvasView>(CanvasView.Free(scale = 8.0, centerX = 0.5, centerY = 1.0))
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(100.dp, 200.dp).testTag("canvas")) {
                    CanvasImage(
                        state = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document),
                        view = { view },
                        modifier = Modifier.fillMaxSize(),
                        orientation = orientation
                    )
                }
            }
        }

        // 시계 방향: 왼쪽(빨강)이 위, 오른쪽(파랑)이 아래로 간다
        var shot = composeRule.onNodeWithTag("canvas").captureToImage().asAndroidBitmap()
        var transform = view.transform(PixelSize(shot.width, shot.height), orientation.targetSize(document))
        val x = transform.toScreenX(0.5).roundToInt()
        for (y in transform.toScreenY(0.0).roundToInt() until transform.toScreenY(1.0).roundToInt()) {
            assertEquals("y=$y", hex(AndroidColor.RED), hex(shot.getPixel(x, y)))
        }
        for (y in transform.toScreenY(1.0).roundToInt() until transform.toScreenY(2.0).roundToInt()) {
            assertEquals("y=$y", hex(AndroidColor.BLUE), hex(shot.getPixel(x, y)))
        }

        // 좌우 뒤집기: 파랑이 왼쪽, 빨강이 오른쪽
        composeRule.runOnIdle {
            orientation = Orientation.FLIP_HORIZONTAL
            view = CanvasView.Free(scale = 8.0, centerX = 1.0, centerY = 0.5)
        }
        composeRule.waitForIdle()
        shot = composeRule.onNodeWithTag("canvas").captureToImage().asAndroidBitmap()
        transform = view.transform(PixelSize(shot.width, shot.height), document)
        val y = transform.toScreenY(0.5).roundToInt()
        for (column in transform.toScreenX(0.0).roundToInt() until transform.toScreenX(1.0).roundToInt()) {
            assertEquals("x=$column", hex(AndroidColor.BLUE), hex(shot.getPixel(column, y)))
        }
        for (column in transform.toScreenX(1.0).roundToInt() until transform.toScreenX(2.0).roundToInt()) {
            assertEquals("x=$column", hex(AndroidColor.RED), hex(shot.getPixel(column, y)))
        }
    }

    @Test
    fun zoomedIn_drawsNothingOutsideTheCanvas() {
        // 확대해서 문서 왼쪽 끝과 아래 끝이 캔버스 바로 밖에 오면, 문서 테두리 선과 선택 윤곽선이 캔버스 밖(펼친 화면의 도구 막대·아래 빈 줄)에
        // 그려지면 안 된다(사용자 피드백: 도구 막대 위에 선이 겹쳐 보였다). 바탕을 한 색으로 칠해 두고 캔버스 밖 픽셀이 그대로인지 본다.
        val document = PixelSize(100, 100)
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.GRAY) }
        // 문서 전체를 고른 선택 영역: 윤곽선이 문서 네 변을 따라간다
        val whole = SelectionOutline(listOf(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f, 0f, 0f)))
        val ready = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document, selection = whole)
        val viewState = CanvasViewState(CanvasView.Fit)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(300.dp, 260.dp).background(Color(BACKDROP)).testTag("frame")) {
                    CanvasViewport(
                        ready,
                        viewState,
                        Modifier.padding(start = 60.dp, top = 20.dp, end = 20.dp, bottom = 40.dp).fillMaxSize().testTag("canvas")
                    )
                }
            }
        }
        val canvasSize = composeRule.onNodeWithTag("canvas").fetchSemanticsNode().size
        // 문서 한 픽셀이 화면 8픽셀이고, 문서 왼쪽 끝은 캔버스 왼쪽에서, 아래 끝은 캔버스 아래에서 10픽셀 밖이다(위·오른쪽 끝은 멀리 밖)
        val scale = 8.0
        composeRule.runOnIdle {
            viewState.view = CanvasView.Free(
                scale = scale,
                centerX = (canvasSize.width / 2.0 + 10) / scale,
                centerY = document.height - (canvasSize.height / 2.0 + 10) / scale
            )
        }
        composeRule.waitForIdle()

        val shot = composeRule.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        val frame = composeRule.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        val canvas = composeRule.onNodeWithTag("canvas").fetchSemanticsNode().boundsInRoot
        val left = (canvas.left - frame.left).roundToInt()
        val top = (canvas.top - frame.top).roundToInt()
        val right = (canvas.right - frame.left).roundToInt()
        val bottom = (canvas.bottom - frame.top).roundToInt()
        val pixels = IntArray(shot.width * shot.height)
        shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
        var leaked = 0
        var first: String? = null
        for (y in 0 until shot.height) {
            for (x in 0 until shot.width) {
                if (x in left until right && y in top until bottom) continue
                val color = pixels[y * shot.width + x]
                if (color != BACKDROP) {
                    leaked++
                    if (first == null) first = "($x, $y) ${hex(color)}"
                }
            }
        }
        assertEquals("canvas $left..$right × $top..$bottom, first leaked pixel $first", 0, leaked)
        // 캔버스 안은 문서(회색)가 다 덮는다
        assertEquals(hex(AndroidColor.GRAY), hex(shot.getPixel((left + right) / 2, (top + bottom) / 2)))
    }

    private companion object {
        /** 캔버스 밖 바탕색(테두리·윤곽선 색과 다른 초록) */
        val BACKDROP = 0xFF00A000.toInt()
    }
}

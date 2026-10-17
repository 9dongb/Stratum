package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
                    outline = MaterialTheme.colorScheme.outlineVariant
                    background = MaterialTheme.colorScheme.surfaceContainerLowest
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
        assertEquals(hex(CheckerLight.toArgb()), hex(shot.getPixel(fit.left + 1, fit.top + 1)))
        // 가로로 다음 칸은 어두운 칸이다(칸 크기 8dp는 캔버스 폭보다 작다)
        val square = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutInfo.density
            .run { CheckerSquare.roundToPx() }
        assertEquals(hex(CheckerDark.toArgb()), hex(shot.getPixel(fit.left + square + 1, fit.top + 1)))
        // 캔버스 바로 위 바깥은 테두리, 더 바깥은 캔버스 배경이다
        assertEquals(hex(outline.toArgb()), hex(shot.getPixel(middleX, fit.top - 1)))
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
}

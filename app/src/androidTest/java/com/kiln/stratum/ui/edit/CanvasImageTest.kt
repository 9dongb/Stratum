package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
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
                        state = EditorState.Ready(preview, document),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
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
}

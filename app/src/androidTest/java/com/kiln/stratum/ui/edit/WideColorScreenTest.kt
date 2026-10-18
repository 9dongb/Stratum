package com.kiln.stratum.ui.edit

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 넓은 색(P3) 문서를 여는 동안 창을 넓은 색 모드로 두고, 아니면 되돌리는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class WideColorScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(400, 300)
    private val preview = PreviewImage(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 0))

    @Test
    fun p3Document_putsTheWindowInWideColorMode_andSrgbPutsItBack() {
        var colorSpace by mutableStateOf(DocumentColorSpace.DISPLAY_P3)
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = EditorState.Ready(preview, document, colorSpace = colorSpace),
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {}
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, composeRule.activity.window.colorMode)
            colorSpace = DocumentColorSpace.SRGB
        }
        composeRule.runOnIdle { assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, composeRule.activity.window.colorMode) }
    }
}

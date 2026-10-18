package com.kiln.stratum.ui.edit

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.ScreenGainmap
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 넓은 색(P3) 문서를 여는 동안 창을 넓은 색 모드로, HDR 문서는 HDR 모드로 두고, 아니면 되돌리는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class WideColorScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val documentSize = PixelSize(400, 300)
    private val preview = PreviewImage(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(documentSize, 0))

    @Test
    fun p3Document_putsTheWindowInWideColorMode_andSrgbPutsItBack() {
        var colorSpace by mutableStateOf(DocumentColorSpace.DISPLAY_P3)
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = EditorState.Ready(preview, documentSize, colorSpace = colorSpace),
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

    /** 게인맵(1/4 크기, 값 200)이 붙은 회색 Ultra HDR JPEG를 들여온 문서 */
    private fun hdrDocument(): Document {
        val base = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val contents = Bitmap.createBitmap(100, 75, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.argb(200, 0, 0, 0)) }
        base.gainmap = Gainmap(contents).apply {
            setRatioMax(4f, 4f, 4f)
            displayRatioForFullHdr = 4f
        }
        val bytes = ByteArrayOutputStream().also { check(base.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        return (PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), "Background") as ImportResult.Success).document
    }

    @Test
    fun hdrDocument_putsTheWindowInHdrMode() {
        val document = hdrDocument()
        val cache = LayerPreviewCache()
        val image = checkNotNull(cache.render(document, level = 0))
        val gainmap = checkNotNull(cache.screenGainmap(document, level = 0))
        val size = PixelSize(document.width, document.height)
        var shown by mutableStateOf<ScreenGainmap?>(gainmap)
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = EditorState.Ready(PreviewImage(image, PreviewLevels.wholeRegion(size, 0)), size, hdr = true, gainmap = shown),
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {}
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(ActivityInfo.COLOR_MODE_HDR, composeRule.activity.window.colorMode)
            shown = null
        }
        composeRule.runOnIdle { assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, composeRule.activity.window.colorMode) }
    }
}

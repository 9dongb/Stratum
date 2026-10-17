package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerAdjustments
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.PhotoImporter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 레이어 패널의 작은 그림(레이어별 축소본을 줄인 것)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class LayerThumbnailTest {

    /** [color] 한 가지로 칠한 [width]×[height] 사진을 앱과 같은 방법으로 불러온다 */
    private fun photo(width: Int, height: Int, color: Int): Document {
        val bitmap = Bitmap.createBitmap(IntArray(width * height) { color }, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    /** 아래는 빨간 사진, 가운데는 빈 레이어, 맨 위는 숨긴(불투명도 0) 초록 레이어 */
    private fun document(): Document {
        val base = photo(1024, 768, Color.rgb(220, 30, 30))
        val green = photo(1024, 768, Color.rgb(30, 200, 60)).layers[0].content as RasterContent
        val withLayers = AddLayer("Empty", 1).applyTo(AddLayer("Hidden", 1).applyTo(base))
        // 번호 1은 '숨김', 2는 '빈 레이어'다(위치 1에 차례로 넣어 2가 아래로 간다)
        val hidden = withLayers.copy(layers = withLayers.layers.map { if (it.id == LayerId(1)) it.copy(content = green) else it })
        return SetLayerOpacity(LayerId(1), 0f).applyTo(hidden)
    }

    private fun centerOf(bitmap: Bitmap): Int = bitmap.copy(Bitmap.Config.ARGB_8888, false).getPixel(bitmap.width / 2, bitmap.height / 2)

    @Test
    fun thumbnails_keepTheShape_andShowHiddenButNotEmptyLayers() {
        val cache = LayerPreviewCache()
        val document = document()

        val thumbnails = cache.thumbnails(document, level = 1)

        assertEquals(setOf(LayerId(0), LayerId(1)), thumbnails.keys)
        val base = thumbnails.getValue(LayerId(0))
        assertEquals(LayerPreviewCache.THUMBNAIL_EDGE, base.width)
        assertEquals(LayerPreviewCache.THUMBNAIL_EDGE * 3 / 4, base.height)
        val red = centerOf(base)
        assertTrue(Integer.toHexString(red), abs(Color.red(red) - 220) <= 2 && abs(Color.green(red) - 30) <= 2)
        // 숨긴 레이어도 제 픽셀을 보인다(불투명도는 빼고 그린다)
        val green = centerOf(thumbnails.getValue(LayerId(1)))
        assertTrue(Integer.toHexString(green), abs(Color.green(green) - 200) <= 2 && Color.alpha(green) == 255)
    }

    @Test
    fun thumbnails_areReused_untilPixelsOrAdjustmentsChange() {
        val cache = LayerPreviewCache()
        val document = document()
        val first = cache.thumbnails(document, level = 1).getValue(LayerId(0))

        // 불투명도만 바꾸면 그대로 쓴다
        assertSame(first, cache.thumbnails(SetLayerOpacity(LayerId(0), 0.5f).applyTo(document), level = 1).getValue(LayerId(0)))

        // 보정을 바꾸면 보정을 건 그림을 새로 만든다(채도 -100: 회색)
        val gray = cache.thumbnails(SetLayerAdjustments(LayerId(0), Adjustments(saturation = -1f)).applyTo(document), level = 1)
            .getValue(LayerId(0))
        assertNotSame(first, gray)
        val pixel = centerOf(gray)
        assertTrue(Integer.toHexString(pixel), abs(Color.red(pixel) - Color.green(pixel)) <= 2)
        assertFalse(gray.isRecycled)
    }
}

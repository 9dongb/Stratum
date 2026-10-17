package com.kiln.stratum.engine.export

import com.kiln.stratum.engine.export.ExportFormat.JPEG
import com.kiln.stratum.engine.export.ExportFormat.PNG
import com.kiln.stratum.engine.export.ExportFormat.WEBP
import com.kiln.stratum.engine.export.ExportSize.LongEdge
import com.kiln.stratum.engine.export.ExportSize.Original
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportSettingsTest {

    @Test
    fun fitLongEdge_keepsTheProportions() {
        assertEquals(PixelSize(2048, 1536), ExportSizes.fitLongEdge(PixelSize(4000, 3000), 2048))
        assertEquals(PixelSize(1536, 2048), ExportSizes.fitLongEdge(PixelSize(3000, 4000), 2048))
        assertEquals(PixelSize(1080, 1080), ExportSizes.fitLongEdge(PixelSize(3000, 3000), 1080))
        // 짧은 변은 반올림한다: 3001 × 2048 / 4001 = 1536.1…, 2999 × 2048 / 4001 = 1535.1…
        assertEquals(PixelSize(2048, 1536), ExportSizes.fitLongEdge(PixelSize(4001, 3001), 2048))
        assertEquals(PixelSize(2048, 1535), ExportSizes.fitLongEdge(PixelSize(4001, 2999), 2048))
        // 아주 긴 사진도 짧은 변이 1px 아래로 줄지 않는다
        assertEquals(PixelSize(1080, 1), ExportSizes.fitLongEdge(PixelSize(16000, 2), 1080))
    }

    @Test
    fun pixelsOf_originalIsTheDocumentSize() {
        val document = PixelSize(4000, 3000)

        assertEquals(document, ExportSizes.pixelsOf(document, Original))
        assertEquals(PixelSize(1080, 810), ExportSizes.pixelsOf(document, LongEdge(1080)))
    }

    @Test
    fun choices_hidePresetsNotSmallerThanTheOriginal() {
        assertEquals(
            listOf(Original, LongEdge(3840), LongEdge(2048), LongEdge(1080)),
            ExportSizes.choices(PixelSize(8160, 6120), JPEG)
        )
        // 긴 변이 프리셋과 같으면 그 프리셋은 원본과 같아서 숨긴다
        assertEquals(listOf(Original, LongEdge(1080)), ExportSizes.choices(PixelSize(1536, 2048), PNG))
        assertEquals(listOf(Original), ExportSizes.choices(PixelSize(1080, 720), WEBP))
    }

    @Test
    fun webp_hidesTheOriginalOverItsLimit() {
        val panorama = PixelSize(20000, 3000)

        assertEquals(listOf(LongEdge(3840), LongEdge(2048), LongEdge(1080)), ExportSizes.choices(panorama, WEBP))
        assertEquals(Original, ExportSizes.choices(panorama, JPEG).first())
        assertEquals(Original, ExportSizes.choices(PixelSize(16383, 100), WEBP).first())
    }

    @Test
    fun resolve_fallsBackToTheLargestChoice() {
        // 큰 사진에서 고른 2048은 작은 사진에는 없어서 원본으로 돌아간다
        assertEquals(Original, ExportSizes.resolve(PixelSize(1500, 1000), JPEG, LongEdge(2048)))
        assertEquals(LongEdge(1080), ExportSizes.resolve(PixelSize(1500, 1000), JPEG, LongEdge(1080)))
        // WebP 한도를 넘는 원본은 가장 큰 프리셋이 된다
        assertEquals(LongEdge(3840), ExportSizes.resolve(PixelSize(20000, 3000), WEBP, Original))
    }

    @Test
    fun settings_checkTheQualityAndTheFormatLimit() {
        assertThrows(IllegalArgumentException::class.java) { ExportSettings(JPEG, 0, PixelSize(10, 10)) }
        assertThrows(IllegalArgumentException::class.java) { ExportSettings(JPEG, 101, PixelSize(10, 10)) }
        assertThrows(IllegalArgumentException::class.java) { ExportSettings(WEBP, 90, PixelSize(16384, 10)) }
        // PNG는 한 변 한도가 없다
        ExportSettings(PNG, DEFAULT_EXPORT_QUALITY, PixelSize(20000, 10))
    }

    @Test
    fun estimatedBytes_addTheEncoderToTheBitmap() {
        // 50MP(8160×6120): 비트맵 4B, JPEG 압축기 3B, WebP 압축기 6.5B(픽셀당)에 GPU 묶음을 더한다
        val size = PixelSize(8160, 6120)
        val pixels = size.pixelCount

        assertEquals(pixels * 4 + EXPORT_GPU_BYTES, ExportSettings(PNG, 90, size).estimatedBytes)
        assertEquals(pixels * 7 + EXPORT_GPU_BYTES, ExportSettings(JPEG, 90, size).estimatedBytes)
        assertEquals(pixels * 21 / 2 + EXPORT_GPU_BYTES, ExportSettings(WEBP, 90, size).estimatedBytes)
    }
}

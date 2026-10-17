package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportChoiceTest {

    @Test
    fun default_isOriginalJpegAtNinety() {
        val document = PixelSize(4000, 3000)

        assertEquals(ExportSettings(ExportFormat.JPEG, 90, document), ExportChoice().settingsFor(document))
    }

    @Test
    fun longEdge_fitsTheDocument() {
        val choice = ExportChoice(ExportFormat.PNG, 80, ExportSize.LongEdge(2048))

        assertEquals(PixelSize(2048, 1536), choice.settingsFor(PixelSize(4000, 3000)).size)
        assertEquals(PixelSize(1536, 2048), choice.settingsFor(PixelSize(3000, 4000)).size)
    }

    @Test
    fun unavailableSize_fallsBackForThisDocumentOnly() {
        val choice = ExportChoice(ExportFormat.WEBP, 75, ExportSize.LongEdge(2048))

        // 작은 사진에는 2048이 없어서 원본으로 내보낸다. 고른 것 자체는 그대로다.
        assertEquals(ExportSize.Original, choice.sizeFor(PixelSize(1500, 1000)))
        assertEquals(PixelSize(1500, 1000), choice.settingsFor(PixelSize(1500, 1000)).size)
        assertEquals(ExportSize.LongEdge(2048), choice.size)
        // WebP 한도를 넘는 원본은 가장 큰 프리셋으로
        assertEquals(PixelSize(3840, 576), ExportChoice(ExportFormat.WEBP).settingsFor(PixelSize(20000, 3000)).size)
    }

    @Test
    fun quality_mustBeInRange() {
        assertThrows(IllegalArgumentException::class.java) { ExportChoice(quality = 0) }
    }
}

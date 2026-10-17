package com.kiln.stratum.export

import com.kiln.stratum.engine.export.ExportFormat
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNameTest {

    @Test
    fun fileName_isTheTimeAndTheFormatExtension() {
        val time = LocalDateTime.of(2026, 10, 18, 9, 5, 7)

        assertEquals("Stratum_20261018_090507.jpg", exportFileName(time, ExportFormat.JPEG))
        assertEquals("Stratum_20261018_090507.png", exportFileName(time, ExportFormat.PNG))
        assertEquals("Stratum_20261018_090507.webp", exportFileName(time, ExportFormat.WEBP))
    }
}

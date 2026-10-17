package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.export.DEFAULT_EXPORT_QUALITY
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.export.ExportSizes
import com.kiln.stratum.engine.export.MAX_EXPORT_QUALITY
import com.kiln.stratum.engine.export.MIN_EXPORT_QUALITY
import com.kiln.stratum.engine.raster.PixelSize

/**
 * 내보내기 창에서 고른 것: 형식, 품질, 크기(원본 또는 긴 변 px).
 * 문서 크기와 상관없는 규칙이라, ViewModel이 기억해 두었다가 다른 문서에도 그대로 쓴다(앱을 끌 때까지).
 */
data class ExportChoice(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = DEFAULT_EXPORT_QUALITY,
    val size: ExportSize = ExportSize.Original
) {
    init {
        require(quality in MIN_EXPORT_QUALITY..MAX_EXPORT_QUALITY) { "Quality must be within 1..100: $quality" }
    }

    /** [document]에 쓸 크기. 고른 크기를 쓸 수 없으면(작은 사진이라 그 프리셋이 없거나 형식 한도를 넘음) 쓸 수 있는 가장 큰 크기다. */
    fun sizeFor(document: PixelSize): ExportSize = ExportSizes.resolve(document, format, size)

    /** [document]를 내보낼 설정 */
    fun settingsFor(document: PixelSize): ExportSettings =
        ExportSettings(format, quality, ExportSizes.pixelsOf(document, sizeFor(document)))
}

/** 내보낼 곳 */
enum class ExportTarget {
    /** 갤러리의 사진/Stratum 폴더 */
    GALLERY,

    /** 안드로이드 공유 창. 갤러리에는 남기지 않는다. */
    SHARE
}

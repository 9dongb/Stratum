package com.kiln.stratum.engine.export

import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.max
import kotlin.math.roundToInt

/** 품질의 범위와 기본값. 품질은 JPEG·WebP에만 쓴다. */
const val MIN_EXPORT_QUALITY = 1
const val MAX_EXPORT_QUALITY = 100
const val DEFAULT_EXPORT_QUALITY = 90

/** 내보낼 비트맵의 픽셀당 바이트(ARGB_8888) */
private const val BITMAP_BYTES_PER_PIXEL = 4.0

/** 내보낼 크기로 그리는 동안 GPU 묶음들이 쓰는 메모리(어림). 묶음 하나를 그리고 읽어 오는 데 드는 비트맵들이다. */
internal const val EXPORT_GPU_BYTES = 40L * 1024 * 1024

/**
 * 내보내기 설정. 문서를 [size]로 그려 [format]으로 압축한다.
 * [quality]는 JPEG·WebP에만 쓰고 PNG는 무시한다. [size]는 형식의 한 변 한도 안이어야 한다.
 */
data class ExportSettings(val format: ExportFormat, val quality: Int, val size: PixelSize) {

    init {
        require(quality in MIN_EXPORT_QUALITY..MAX_EXPORT_QUALITY) { "Quality must be within 1..100: $quality" }
        require(size.width <= format.maxSide && size.height <= format.maxSide) { "$size is too large for $format" }
    }

    /**
     * 내보내는 동안 쓸 메모리(바이트)의 어림. 내보낼 크기의 비트맵 한 장과 압축기, GPU 묶음을 더한다.
     * 50MP 사진이면 PNG 약 240MB, JPEG 약 390MB, WebP 약 565MB다.
     */
    val estimatedBytes: Long
        get() = (size.pixelCount * (BITMAP_BYTES_PER_PIXEL + format.encoderBytesPerPixel)).toLong() + EXPORT_GPU_BYTES
}

/**
 * 내보낼 크기 고르기. 원본이나 긴 변 픽셀이다.
 * 문서 크기가 바뀌어도(편집하거나 다른 사진을 열어도) 같은 고르기를 쓸 수 있게 픽셀 크기가 아니라 규칙으로 둔다.
 */
sealed interface ExportSize {

    /** 문서 크기 그대로 */
    data object Original : ExportSize

    /** 긴 변을 [pixels]로 줄이고 짧은 변은 비율대로 */
    data class LongEdge(val pixels: Int) : ExportSize {
        init {
            require(pixels > 0) { "Long edge must be positive: $pixels" }
        }
    }
}

/** 내보낼 크기 계산(순수 계산). */
object ExportSizes {

    /** 긴 변 프리셋(px). 큰 것부터다. 4K 화면, 웹·메신저에서 흔한 크기, SNS에서 흔한 크기다. */
    val LONG_EDGE_PRESETS: List<Int> = listOf(3840, 2048, 1080)

    /** [size] 고르기로 [document]를 내보낼 때의 픽셀 크기 */
    fun pixelsOf(document: PixelSize, size: ExportSize): PixelSize =
        when (size) {
            ExportSize.Original -> document
            is ExportSize.LongEdge -> fitLongEdge(document, size.pixels)
        }

    /** [document]의 긴 변을 [longEdge]로 맞춘 크기. 짧은 변은 비율대로 반올림하고 1px보다 작아지지 않는다(크기 변경 창과 같다). */
    fun fitLongEdge(document: PixelSize, longEdge: Int): PixelSize =
        if (document.width >= document.height) {
            PixelSize(longEdge, scaled(document.height, longEdge, document.width))
        } else {
            PixelSize(scaled(document.width, longEdge, document.height), longEdge)
        }

    /**
     * [document]를 [format]으로 내보낼 때 고를 수 있는 크기. 큰 것부터다.
     * 원본은 형식의 한도 안일 때만 있고(WebP는 한 변 16383px까지), 프리셋은 원본보다 작은 것만 있다. 비어 있지 않다.
     */
    fun choices(document: PixelSize, format: ExportFormat): List<ExportSize> {
        val longEdge = max(document.width, document.height)
        val presets = LONG_EDGE_PRESETS
            .filter { it < longEdge && it <= format.maxSide }
            .map { ExportSize.LongEdge(it) }
        return if (longEdge <= format.maxSide) listOf(ExportSize.Original) + presets else presets
    }

    /** [wanted]를 그대로 고를 수 있으면 그것, 아니면(작은 사진이라 그 프리셋이 없거나 형식 한도를 넘음) 고를 수 있는 가장 큰 크기 */
    fun resolve(document: PixelSize, format: ExportFormat, wanted: ExportSize): ExportSize {
        val choices = choices(document, format)
        return if (wanted in choices) wanted else choices.first()
    }

    private fun scaled(side: Int, longEdge: Int, documentLongEdge: Int): Int =
        max(1, (side.toLong() * longEdge / documentLongEdge.toDouble()).roundToInt())
}

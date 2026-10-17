package com.kiln.stratum.engine.importing

import com.kiln.stratum.engine.raster.PixelSize

/** 선택 창 없이 바로 여는 사진의 최대 화소 수. 폴드8 50MP 사진(8160×6120, 약 4994만 화소)은 바로 열린다. */
const val LARGE_PHOTO_PIXELS: Long = 50_000_000L

/**
 * 사진을 어떻게 열지 정한 결과.
 *
 * @property original 원본 크기
 * @property originalFits 원본 크기로 열 수 있는지. 메모리 예산과 해독기 한도 안이어야 한다.
 * @property reducedSampleSize 줄여서 열 때의 단계(2, 4, 8…: 1/2, 1/4, 1/8 크기). 예산·한도 안에서 줄일 방법이 없으면 null
 */
data class ImportPlan(
    val original: PixelSize,
    val originalFits: Boolean,
    val reducedSampleSize: Int?
) {
    /** 줄여서 열 때의 대략적인 크기. 실제 크기는 해독기에 따라 1픽셀 다를 수 있다. */
    val reducedSize: PixelSize?
        get() = reducedSampleSize?.let { ImportSizing.sampledSize(original, it) }

    /** 사용자에게 고르게 해야 하는지. 50MP보다 크거나, 원본 크기로는 열 수 없을 때다. */
    val needsChoice: Boolean
        get() = original.pixelCount > LARGE_PHOTO_PIXELS || !originalFits

    /** 원본으로도, 줄여서도 열 수 없는지(메모리 부족). */
    val cannotOpen: Boolean
        get() = !originalFits && reducedSampleSize == null
}

/**
 * 사진을 열 때 필요한 메모리와 줄이기 단계를 계산한다. 실제 해독은 platform 패키지의 PhotoImporter가 한다.
 *
 * 줄일 때는 1/2, 1/4처럼 반씩 줄인다. 해독기가 해독하면서 바로 줄일 수 있어,
 * 원본 크기를 한 번 다 풀고 줄이는 것보다 메모리가 덜 든다.
 */
object ImportSizing {

    /** 해독한 사진이 픽셀 하나에 쓸 수 있는 바이트 수. PNG·AVIF는 16비트로 풀릴 수 있어 8바이트로 본다. */
    fun bytesPerPixel(mimeType: String?): Int =
        when (mimeType?.lowercase()) {
            "image/png", "image/avif" -> 8
            else -> 4
        }

    /**
     * 해독기가 사진 하나를 푸는 동안 쓰는 메모리(바이트).
     * 방향 정보(EXIF)로 돌려 보여 주는 사진은 해독기가 같은 크기의 임시 버퍼를 하나 더 쓴다(에뮬레이터에서 확인).
     * 미리 알 수 없으므로 방향 정보를 담을 수 있는 형식은 두 배로 본다. PNG·GIF는 거의 담지 않아 한 배로 본다.
     */
    fun decodeBytes(size: PixelSize, mimeType: String?): Long {
        val rotationFactor = when (mimeType?.lowercase()) {
            "image/png", "image/gif" -> 1
            else -> 2
        }
        return size.pixelCount * bytesPerPixel(mimeType) * rotationFactor
    }

    /**
     * 사진 하나를 여는 동안 잠깐 필요한 최대 메모리(바이트).
     * 해독본과 타일이 함께 있을 때가 가장 크고, 방향을 돌릴 때 드는 임시 버퍼와 여유를 더해 해독본의 2.5배로 잡는다.
     */
    fun peakBytes(size: PixelSize, bytesPerPixel: Int): Long = size.pixelCount * bytesPerPixel * 5 / 2

    /** [sampleSize](1, 2, 4…)로 줄였을 때의 대략적인 크기. 나머지는 올림한다. */
    fun sampledSize(size: PixelSize, sampleSize: Int): PixelSize {
        require(sampleSize >= 1 && (sampleSize and (sampleSize - 1)) == 0) {
            "Sample size must be a power of two: $sampleSize"
        }
        return PixelSize(ceilDiv(size.width, sampleSize), ceilDiv(size.height, sampleSize))
    }

    /**
     * [original] 크기의 사진을 쓸 수 있는 메모리 [budgetBytes] 안에서 어떻게 열지 정한다.
     * [mimeType]은 사진 형식(예: image/jpeg)이고, 모르면 null이다.
     * [maxDecodeBytes]는 해독기가 사진 하나를 푸는 동안 쓸 수 있는 최대 메모리다. 안드로이드 버전마다 다르다.
     */
    fun plan(
        original: PixelSize,
        mimeType: String?,
        budgetBytes: Long,
        maxDecodeBytes: Long = Long.MAX_VALUE
    ): ImportPlan {
        val bytesPerPixel = bytesPerPixel(mimeType)
        val fits = { size: PixelSize ->
            peakBytes(size, bytesPerPixel) <= budgetBytes && decodeBytes(size, mimeType) <= maxDecodeBytes
        }
        val originalFits = fits(original)
        // 1/2부터 시작해, 50MP 이하이면서 예산·한도 안에 드는 가장 큰 크기를 찾는다
        var sampleSize = 2
        while (sampleSize <= MAX_SAMPLE_SIZE) {
            val size = sampledSize(original, sampleSize)
            if (size.pixelCount <= LARGE_PHOTO_PIXELS && fits(size)) {
                return ImportPlan(original, originalFits, sampleSize)
            }
            sampleSize *= 2
        }
        return ImportPlan(original, originalFits, reducedSampleSize = null)
    }

    /** 가장 많이 줄이는 단계(1/1024). 이보다 줄여야 할 만큼 메모리가 없으면 열지 않는다. */
    private const val MAX_SAMPLE_SIZE = 1024

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}

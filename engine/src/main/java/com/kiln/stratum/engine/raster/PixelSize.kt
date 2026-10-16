package com.kiln.stratum.engine.raster

/** 가로·세로 픽셀 크기. */
data class PixelSize(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "Size must be positive: ${width}x$height" }
    }

    /** 전체 화소 수. 큰 사진은 Int를 넘을 수 있어 Long으로 센다. */
    val pixelCount: Long
        get() = width.toLong() * height

    /** 백만 화소(MP) 단위로 반올림한 크기. 화면에 'N MP'로 보여 줄 때 쓰고, 1보다 작으면 1이다. */
    val megapixels: Int
        get() = maxOf(1L, (pixelCount + 500_000) / 1_000_000).toInt()
}

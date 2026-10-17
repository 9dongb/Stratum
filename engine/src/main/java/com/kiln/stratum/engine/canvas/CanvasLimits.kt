package com.kiln.stratum.engine.canvas

import com.kiln.stratum.engine.importing.LARGE_PHOTO_PIXELS
import com.kiln.stratum.engine.raster.PixelSize

/** 새 캔버스 한 변의 최대 픽셀 수. WebP로 내보낼 수 있는 가장 큰 크기다. */
const val MAX_CANVAS_SIDE = 16383

/** 새 캔버스의 최대 화소 수. 사진을 선택 창 없이 바로 여는 크기(폴드8 50MP 사진)와 같다. */
const val MAX_CANVAS_PIXELS: Long = LARGE_PHOTO_PIXELS

/** 새 캔버스 크기를 검사한 결과. */
enum class CanvasSizeCheck {
    /** 만들 수 있다. */
    OK,

    /** 가로나 세로가 1~[MAX_CANVAS_SIDE] 밖이다. */
    SIDE_OUT_OF_RANGE,

    /** 두 변은 범위 안이지만 전체 화소 수가 [MAX_CANVAS_PIXELS]를 넘는다. */
    TOO_MANY_PIXELS
}

/** 새 캔버스로 만들 수 있는 크기. 화면은 입력 칸을 검사할 때, 엔진은 만들기 전에 쓴다. */
object CanvasLimits {

    /** [width]×[height] 캔버스를 만들 수 있는지. */
    fun check(width: Int, height: Int): CanvasSizeCheck = check(width, height, MAX_CANVAS_PIXELS)

    /**
     * 지금 [current] 크기 이미지를 [width]×[height]로 바꿀 수 있는지. 한도는 새 캔버스와 같되,
     * 지금 이미지가 이미 [MAX_CANVAS_PIXELS]보다 크면(원본 크기로 연 큰 사진) 지금 크기까지는 된다.
     */
    fun checkResize(current: PixelSize, width: Int, height: Int): CanvasSizeCheck =
        check(width, height, maxOf(MAX_CANVAS_PIXELS, current.pixelCount))

    private fun check(width: Int, height: Int, maxPixels: Long): CanvasSizeCheck =
        when {
            width !in 1..MAX_CANVAS_SIDE || height !in 1..MAX_CANVAS_SIDE -> CanvasSizeCheck.SIDE_OUT_OF_RANGE
            width.toLong() * height > maxPixels -> CanvasSizeCheck.TOO_MANY_PIXELS
            else -> CanvasSizeCheck.OK
        }
}

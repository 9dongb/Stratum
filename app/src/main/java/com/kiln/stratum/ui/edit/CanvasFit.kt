package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.roundToInt

/** 화면 안의 사각형(픽셀, 정수). */
data class FitRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * [areaWidth]×[areaHeight] 영역 안에 [document]를 비율 그대로 가장 크게 맞춘 사각형. 가운데에 놓는다.
 *
 * 문서가 영역보다 작으면 키워서 맞춘다. 1×16383처럼 아주 길쭉한 문서도 보이도록 각 변은 최소 1px이다.
 * 정수 픽셀에 맞춰야 캔버스 둘레의 테두리와 체크 무늬가 흐려지지 않는다.
 */
fun fitRect(areaWidth: Int, areaHeight: Int, document: PixelSize): FitRect {
    if (areaWidth <= 0 || areaHeight <= 0) return FitRect(0, 0, 0, 0)
    val scale = minOf(areaWidth.toDouble() / document.width, areaHeight.toDouble() / document.height)
    val width = (document.width * scale).roundToInt().coerceIn(1, areaWidth)
    val height = (document.height * scale).roundToInt().coerceIn(1, areaHeight)
    return FitRect((areaWidth - width) / 2, (areaHeight - height) / 2, width, height)
}

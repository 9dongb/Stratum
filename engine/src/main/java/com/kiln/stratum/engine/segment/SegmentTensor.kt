package com.kiln.stratum.engine.segment

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 분할 모델(SAM 2.1 Tiny 변환본)의 입력 만들기(결정 기록 'AI 제거·AI 선택').
 * 그림 계산은 입력 크기(1024²) 배열에서만 한다(Kotlin 반복은 1024px 이하).
 */
object SegmentTensor {

    /** 점 하나의 길이 */
    const val POINT_LENGTH = 256

    /** 프롬프트 상수(`sam2_prompt.bin`)의 길이: 위치 무늬 행렬(x 128 | y 128), 양의 점, 빈 점 */
    const val CONSTANTS_LENGTH = 3 * POINT_LENGTH

    /** ImageNet 평균(R·G·B, 0..1 눈금). 변환본이 이 정규화로 학습된 그대로다. */
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)

    /** ImageNet 표준편차 */
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * 불투명한 RGBA 바이트(픽셀마다 R·G·B·A, 줄마다 이어 붙인 [size]²)를 모델 입력(1×3×[size]² NCHW)으로.
     * 값은 (색 / 255 - 평균) / 표준편차다. 알파는 보지 않는다(부르는 쪽이 흰 바탕에 합쳐 넘긴다).
     */
    fun input(rgba: ByteArray, size: Int = SegmentModel.INPUT_SIZE): FloatArray {
        val plane = size * size
        require(rgba.size == plane * 4) { "RGBA must be ${size}x$size: ${rgba.size} bytes" }
        val input = FloatArray(3 * plane)
        for (p in 0 until plane) {
            for (c in 0 until 3) input[c * plane + p] = ((rgba[p * 4 + c].toInt() and 0xFF) / 255f - MEAN[c]) / STD[c]
        }
        return input
    }

    /**
     * 점 둘(탭한 곳과 빈 점, 2×[POINT_LENGTH])의 프롬프트. 탭 자리 ([x], [y])는 입력 그림([size]²)의 픽셀 좌표다.
     * 탭한 곳은 위치 무늬(sin·cos)에 양의 점을 더하고, 빈 점은 상수 그대로다(SAM의 PromptEncoder와 같은 계산).
     * [constants]는 `sam2_prompt.bin`의 값(float32 리틀엔디언 [CONSTANTS_LENGTH]개)이다.
     */
    fun points(constants: FloatArray, x: Float, y: Float, size: Int = SegmentModel.INPUT_SIZE): FloatArray {
        require(constants.size == CONSTANTS_LENGTH) { "Prompt constants must have $CONSTANTS_LENGTH values: ${constants.size}" }
        val half = POINT_LENGTH / 2
        // 픽셀 가운데를 -1..1로
        val cx = 2.0 * ((x + 0.5) / size) - 1.0
        val cy = 2.0 * ((y + 0.5) / size) - 1.0
        val points = FloatArray(2 * POINT_LENGTH)
        for (k in 0 until half) {
            val projection = 2.0 * PI * (cx * constants[k] + cy * constants[half + k])
            points[k] = (sin(projection) + constants[POINT_LENGTH + k]).toFloat()
            points[half + k] = (cos(projection) + constants[POINT_LENGTH + half + k]).toFloat()
        }
        constants.copyInto(points, POINT_LENGTH, 2 * POINT_LENGTH, CONSTANTS_LENGTH)
        return points
    }

    /** 문서 좌표 [value](길이 [length]인 축)를 입력 그림 좌표로. 입력은 문서 전체를 [size]로 늘여 맞춘 것이다. */
    fun toInput(value: Float, length: Int, size: Int = SegmentModel.INPUT_SIZE): Float = value * size / length
}

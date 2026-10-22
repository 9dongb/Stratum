package com.kiln.stratum.engine.face

/**
 * 얼굴 모델의 입력 만들기. 모델 입력 크기(256² 이하) 배열에서만 Kotlin으로 돈다(CLAUDE.md 2장).
 * 값의 범위는 모델 파일의 메타데이터(정규화 평균·표준편차)대로다: 얼굴 찾기 127.5·127.5(-1..1), 특징점 0·255(0..1).
 */
object FaceTensor {

    /**
     * ARGB 픽셀([pixels], `Bitmap.getPixels`의 [size]², 줄마다 이어 붙임)을 NHWC RGB(1×[size]²×3)로. 0..255를 [low]..[high]로 늘인다.
     * 알파는 보지 않는다(부르는 쪽이 바탕에 합쳐 넘긴다).
     */
    fun input(pixels: IntArray, size: Int, low: Float, high: Float): FloatArray {
        require(pixels.size == size * size) { "Pixels must be ${size}x$size: ${pixels.size}" }
        val scale = (high - low) / 255f
        val input = FloatArray(pixels.size * 3)
        for (p in pixels.indices) {
            val color = pixels[p]
            input[3 * p] = (color shr 16 and 0xFF) * scale + low
            input[3 * p + 1] = (color shr 8 and 0xFF) * scale + low
            input[3 * p + 2] = (color and 0xFF) * scale + low
        }
        return input
    }

    /** 얼굴 찾기 입력(192², -1..1) */
    fun detectorInput(pixels: IntArray): FloatArray = input(pixels, FaceModel.DETECTOR_SIZE, -1f, 1f)

    /** 특징점 입력(256², 0..1) */
    fun landmarkInput(pixels: IntArray): FloatArray = input(pixels, FaceModel.LANDMARK_SIZE, 0f, 1f)
}

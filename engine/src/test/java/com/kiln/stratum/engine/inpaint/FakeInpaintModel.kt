package com.kiln.stratum.engine.inpaint

/**
 * 테스트용 채우기 모델. 남길 곳은 그 색 그대로 돌려주고, 채울 곳은 남길 곳의 평균색으로 채운다.
 * 불린 횟수를 세고, [failure]가 있으면 그것을 던진다.
 */
class FakeInpaintModel(
    override val size: Int = InpaintPlan.MODEL_SIZE,
    private val failure: Exception? = null
) : InpaintModel {

    var calls = 0
        private set

    override fun fill(input: FloatArray): FloatArray {
        calls++
        failure?.let { throw it }
        val plane = size * size
        require(input.size == InpaintModel.INPUT_CHANNELS * plane) { "Input must be 1x4x${size}x$size" }
        val mean = FloatArray(3)
        var known = 0
        for (p in 0 until plane) {
            if (input[p] <= 0f) continue
            known++
            for (c in 0 until 3) mean[c] += input[(c + 1) * plane + p]
        }
        if (known > 0) for (c in 0 until 3) mean[c] /= known
        return FloatArray(InpaintModel.OUTPUT_CHANNELS * plane) { i ->
            val c = i / plane
            val p = i % plane
            if (input[p] > 0f) input[(c + 1) * plane + p] else mean[c]
        }
    }
}

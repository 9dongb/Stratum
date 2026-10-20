package com.kiln.stratum

import com.kiln.stratum.engine.inpaint.InpaintModel

/**
 * 에뮬레이터 테스트용 채우기 모델(engine/src/test의 것과 같다). 남길 곳은 그 색 그대로, 채울 곳은 남길 곳의 평균색으로 채운다.
 * [offset]을 모든 출력에 더해(-1..1 눈금) 밝기가 어긋난 모델을 흉내 낸다. 불린 횟수를 세고, [failure]가 있으면 그것을 던진다.
 */
class FakeInpaintModel(
    override val size: Int = 512,
    private val offset: Float = 0f,
    private val failure: Throwable? = null,
    override val pendingLoadBytes: Long = 0L
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
            (if (input[p] > 0f) input[(c + 1) * plane + p] else mean[c]) + offset
        }
    }
}

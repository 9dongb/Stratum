package com.kiln.stratum

import com.kiln.stratum.engine.skin.SkinModel

/**
 * 시험용 피부 분할 모델: 입력(256², 0..1)의 빨간 픽셀(빨강이 밝고 초록·파랑이 어두움)을 얼굴 피부로, 나머지를 바탕으로 본다.
 * [FakeFaceModel]이 빨간 덩어리를 얼굴로 보므로 둘을 함께 쓰면 그 덩어리가 피부가 된다. [failure]가 있으면 그것을 던진다(쓸 수 없는 기기 흉내).
 */
class FakeSkinModel : SkinModel {

    @Volatile
    var failure: Exception? = null

    @Volatile
    var calls = 0
        private set

    @Volatile
    var releases = 0
        private set

    override fun segment(input: FloatArray): FloatArray {
        failure?.let { throw it }
        calls++
        val out = FloatArray(SkinModel.OUTPUT_VALUES) { -5f }
        for (cell in 0 until SkinModel.INPUT_SIZE * SkinModel.INPUT_SIZE) {
            val red = input[3 * cell]
            val skin = red > 0.6f && red - input[3 * cell + 1] > 0.4f && red - input[3 * cell + 2] > 0.4f
            out[cell * SkinModel.CLASS_COUNT + if (skin) SkinModel.FACE_SKIN else SkinModel.BACKGROUND] = 5f
        }
        return out
    }

    override fun release() {
        releases++
    }
}

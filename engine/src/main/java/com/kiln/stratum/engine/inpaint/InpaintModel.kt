package com.kiln.stratum.engine.inpaint

/**
 * 구멍을 둘레에 어울리게 채우는 모델(인페인팅, 결정 기록 'AI 제거·AI 선택').
 *
 * 입력은 1×4×[size]×[size]이고 채널이 먼저다(NCHW).
 * - 0채널: 남길 곳 마스크 - 0.5. 마스크는 1이 남길 곳, 0이 채울 곳이다.
 * - 1~3채널: RGB(-1..1, 픽셀 값 / 127.5 - 1) × 마스크.
 *
 * 출력은 1×3×[size]×[size] RGB(-1..1)이고, 채울 곳의 값만 쓴다.
 * 한 번 부르는 동안은 멈출 수 없다. 테스트는 가짜 모델로 바꿔 끼운다.
 */
interface InpaintModel {

    /** 입력·출력 한 변(픽셀) */
    val size: Int

    /** 아직 불러오지 않았으면 처음 쓸 때 더 쓰는 메모리의 어림(바이트). 불러왔으면 0 */
    val pendingLoadBytes: Long get() = 0L

    /** [input]의 구멍을 채운 그림을 돌려준다. 이 기기에서 모델을 쓸 수 없으면 [InpaintUnavailableException]을 던진다. */
    fun fill(input: FloatArray): FloatArray

    /** 미리 불러 둔다(기다리지 않는다) */
    fun prepare() {}

    /** 내려놓는다. 다음에 쓰면 다시 불러온다(기다리지 않는다). */
    fun release() {}

    companion object {
        /** 입력 채널 수(마스크 하나와 RGB) */
        const val INPUT_CHANNELS = 4

        /** 출력 채널 수(RGB) */
        const val OUTPUT_CHANNELS = 3
    }
}

/** 이 기기에서 채우기 모델을 쓸 수 없다(불러오기나 실행이 안 됨). */
class InpaintUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

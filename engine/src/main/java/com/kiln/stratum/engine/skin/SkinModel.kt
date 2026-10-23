package com.kiln.stratum.engine.skin

/**
 * 피부 보정의 피부 분할 모델(결정 기록 '피부 보정'): 미디어파이프 셀피 멀티클래스(256×256). 모델 파일만 LiteRT로 돌린다.
 *
 * - 입력은 [INPUT_SIZE]² RGB(1×256×256×3 NHWC, 값 0..1)다. −1..1로 넣으면 엉뚱한 '그 밖' 덩어리가 생긴다(0단계 맥 시험).
 * - 결과는 칸마다 갈래 [CLASS_COUNT]개의 로짓(1×256×256×6, 소프트맥스 전)이다.
 *   갈래 차례는 [BACKGROUND]·[HAIR]·[BODY_SKIN]·[FACE_SKIN]·[CLOTHES]·[OTHERS]이고, 얼굴 피부에는 귀가 든다.
 *
 * 한 번 부르는 동안은 멈출 수 없다. 테스트는 가짜 모델로 바꿔 끼운다.
 */
interface SkinModel {

    /** 아직 불러오지 않았으면 처음 쓸 때 더 쓰는 메모리의 어림(바이트). 불러왔으면 0 */
    val pendingLoadBytes: Long get() = 0L

    /** [input](1×[INPUT_SIZE]²×3 NHWC, 0..1)의 갈래 로짓(칸마다 [CLASS_COUNT]개). 이 기기에서 모델을 쓸 수 없으면 [SkinUnavailableException]을 던진다. */
    fun segment(input: FloatArray): FloatArray

    /** 미리 불러 둔다(기다리지 않는다) */
    fun prepare() {}

    /** 내려놓는다. 다음에 쓰면 다시 불러온다(기다리지 않는다). */
    fun release() {}

    companion object {
        /** 입력 한 변(px) */
        const val INPUT_SIZE = 256

        /** 갈래 수 */
        const val CLASS_COUNT = 6

        const val BACKGROUND = 0
        const val HAIR = 1
        const val BODY_SKIN = 2
        const val FACE_SKIN = 3
        const val CLOTHES = 4
        const val OTHERS = 5

        /** 입력 값 수 */
        const val INPUT_VALUES = INPUT_SIZE * INPUT_SIZE * 3

        /** 결과 값 수 */
        const val OUTPUT_VALUES = INPUT_SIZE * INPUT_SIZE * CLASS_COUNT
    }
}

/** 이 기기에서 피부 분할 모델을 쓸 수 없다(불러오기나 실행이 안 됨). */
class SkinUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

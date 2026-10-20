package com.kiln.stratum.engine.segment

/**
 * 탭한 곳의 물체 모양을 찾는 모델(AI 선택, 결정 기록 'AI 제거·AI 선택'). SAM 2.1 Tiny를 쓴다.
 * 사진마다 한 번 읽고([encode], 몇백 ms), 탭마다 모양을 찾는다([decode], 수십 ms).
 *
 * - 입력 그림은 [INPUT_SIZE]² RGB(1×3×1024² NCHW)다. 문서 전체를 가로·세로 따로 늘여 맞추고, [SegmentTensor.input]이 값을 만든다.
 * - 탭 자리는 입력 그림의 픽셀 좌표(0..[INPUT_SIZE])다.
 * - 결과는 후보 [MASK_COUNT]개의 [MASK_SIZE]² 로짓(줄마다 이어 붙임)이고, 0보다 크면 물체다. 후보마다 입력 그림 전체(곧 문서 전체)를 덮는다.
 *   어느 후보를 쓸지는 [SegmentMask.choose]가 정한다.
 *
 * 한 번 부르는 동안은 멈출 수 없다. 테스트는 가짜 모델로 바꿔 끼운다.
 */
interface SegmentModel {

    /** 아직 불러오지 않았으면 처음 쓸 때 더 쓰는 메모리의 어림(바이트). 불러왔으면 0 */
    val pendingLoadBytes: Long get() = 0L

    /** [image](1×3×[INPUT_SIZE]² NCHW)를 읽은 특징. 이 기기에서 모델을 쓸 수 없으면 [SegmentUnavailableException]을 던진다. */
    fun encode(image: FloatArray): SegmentFeatures

    /** [features]에서 입력 그림 좌표 ([x], [y])를 탭한 물체의 후보 로짓([MASK_COUNT]×[MASK_SIZE]²) */
    fun decode(features: SegmentFeatures, x: Float, y: Float): FloatArray

    /** 미리 불러 둔다(기다리지 않는다) */
    fun prepare() {}

    /** 내려놓는다. 다음에 쓰면 다시 불러온다(기다리지 않는다). 읽어 둔 특징은 그대로 쓸 수 있다. */
    fun release() {}

    companion object {
        /** 입력 그림 한 변(px) */
        const val INPUT_SIZE = 1024

        /** 결과 로짓 한 변(칸) */
        const val MASK_SIZE = 256

        /** 탭마다 내는 후보 수 */
        const val MASK_COUNT = 3
    }
}

/** [SegmentModel.encode]가 사진 한 장에서 읽은 특징. 모델마다 담는 것이 다르다. */
interface SegmentFeatures

/** 이 기기에서 분할 모델을 쓸 수 없다(불러오기나 실행이 안 됨). */
class SegmentUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

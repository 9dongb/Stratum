package com.kiln.stratum.engine.face

/**
 * 얼굴 인식 보정이 쓰는 두 모델(결정 기록 '얼굴 인식 보정'): 미디어파이프 BlazeFace 먼 거리용(얼굴 찾기)과 FaceMesh V2(특징점 478개).
 * 모델 파일만 LiteRT로 돌린다(미디어파이프 라이브러리는 쓰지 않음). 앞뒤 처리는 [FaceSearch]가 한다.
 *
 * - 얼굴 찾기 입력은 [DETECTOR_SIZE]² RGB(1×192×192×3 NHWC, 값 -1..1)이고, 결과는 앵커 [ANCHOR_COUNT]개마다
 *   상자·눈 등 [ANCHOR_VALUES]개(앵커에서 떨어진 입력 픽셀)와 얼굴 점수 로짓이다([FaceDetectionDecoder]가 푼다).
 * - 특징점 입력은 [LANDMARK_SIZE]² RGB(1×256×256×3 NHWC, 값 0..1)이고, 결과는 [LANDMARK_COUNT]점의 x·y·z(자른 그림의 픽셀)와
 *   얼굴 있음 로짓이다(맥에서 실제 얼굴 20 안팎, 얼굴이 아닌 곳 -9~2를 쟀다).
 *
 * 한 번 부르는 동안은 멈출 수 없다. 테스트는 가짜 모델로 바꿔 끼운다.
 */
interface FaceModel {

    /** 아직 불러오지 않았으면 처음 쓸 때 더 쓰는 메모리의 어림(바이트). 불러왔으면 0 */
    val pendingLoadBytes: Long get() = 0L

    /** [input](1×[DETECTOR_SIZE]²×3 NHWC, -1..1)에서 찾은 얼굴 후보. 이 기기에서 모델을 쓸 수 없으면 [FaceUnavailableException]을 던진다. */
    fun detect(input: FloatArray): DetectorOutput

    /** [input](1×[LANDMARK_SIZE]²×3 NHWC, 0..1)의 특징점. 이 기기에서 모델을 쓸 수 없으면 [FaceUnavailableException]을 던진다. */
    fun landmarks(input: FloatArray): LandmarkOutput

    /** 미리 불러 둔다(기다리지 않는다) */
    fun prepare() {}

    /** 내려놓는다. 다음에 쓰면 다시 불러온다(기다리지 않는다). */
    fun release() {}

    companion object {
        /** 얼굴 찾기 입력 한 변(px) */
        const val DETECTOR_SIZE = 192

        /** 특징점 입력 한 변(px) */
        const val LANDMARK_SIZE = 256

        /** 얼굴 찾기 앵커 수(48×48 칸, 칸마다 하나) */
        const val ANCHOR_COUNT = 2304

        /** 앵커 하나의 값: 상자 가운데 x·y, 폭·높이, 점 여섯(눈 둘·코끝·입·귀 둘)의 x·y */
        const val ANCHOR_VALUES = 16

        /** 특징점 수(얼굴 468 + 눈동자 10) */
        const val LANDMARK_COUNT = 478
    }
}

/** 얼굴 찾기 모델의 결과: 앵커마다 [FaceModel.ANCHOR_VALUES]개 값([boxes])과 점수 로짓([scores]) */
class DetectorOutput(val boxes: FloatArray, val scores: FloatArray) {
    init {
        require(boxes.size == FaceModel.ANCHOR_COUNT * FaceModel.ANCHOR_VALUES) { "Unexpected box values: ${boxes.size}" }
        require(scores.size == FaceModel.ANCHOR_COUNT) { "Unexpected scores: ${scores.size}" }
    }
}

/** 특징점 모델의 결과: 점마다 x·y·z(자른 그림의 픽셀, [points])와 얼굴 있음 로짓([presence]) */
class LandmarkOutput(val points: FloatArray, val presence: Float) {
    init {
        require(points.size == FaceModel.LANDMARK_COUNT * 3) { "Unexpected landmark values: ${points.size}" }
    }
}

/** 이 기기에서 얼굴 모델을 쓸 수 없다(불러오기나 실행이 안 됨). */
class FaceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

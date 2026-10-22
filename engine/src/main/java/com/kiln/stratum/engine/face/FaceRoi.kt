package com.kiln.stratum.engine.face

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 그림 좌표(왼쪽 위가 0, 픽셀 [i]는 i..i+1)의 돌린 정사각형. 모델 입력은 이 정사각형을 잘라 입력 크기로 맞춘 것이다.
 *
 * 자른 그림의 비율 좌표 (a, b)(0..1, 왼쪽 위가 0)는 그림 점 (가운데) + [side] × R([angle])·(a − 0.5, b − 0.5)다.
 * [angle]은 라디안이고, 그림 좌표는 아래가 +y라 화면에서 시계 방향이 +다. 미디어파이프의 돌린 사각형(NormalizedRect)과 같은 뜻이다.
 */
data class FaceRoi(val centerX: Float, val centerY: Float, val side: Float, val angle: Float = 0f) {

    private val cosine = cos(angle.toDouble())
    private val sine = sin(angle.toDouble())

    /** 자른 그림의 비율 좌표 ([a], [b])의 그림 x */
    fun imageX(a: Float, b: Float): Float = (centerX + side * (cosine * (a - 0.5) - sine * (b - 0.5))).toFloat()

    /** 자른 그림의 비율 좌표 ([a], [b])의 그림 y */
    fun imageY(a: Float, b: Float): Float = (centerY + side * (sine * (a - 0.5) + cosine * (b - 0.5))).toFloat()

    /** 그림 점 ([x], [y])의 자른 그림 비율 좌표 a([imageX]의 반대) */
    fun cropA(x: Float, y: Float): Float = (0.5 + (cosine * (x - centerX) + sine * (y - centerY)) / side).toFloat()

    /** 그림 점 ([x], [y])의 자른 그림 비율 좌표 b([imageY]의 반대) */
    fun cropB(x: Float, y: Float): Float = (0.5 + (-sine * (x - centerX) + cosine * (y - centerY)) / side).toFloat()

    companion object {
        /** 찾은 상자·특징점 범위를 넓히는 배율(미디어파이프 얼굴 특징점 그래프의 RectTransformation과 같다) */
        const val SCALE = 1.5f

        /**
         * 그림([width]×[height]) 전체를 비율 그대로 담는 정사각형(한 변은 긴 변, 짧은 쪽의 남는 곳은 그림 밖).
         * [quarterTurns]만큼 90°씩 돌려 읽는다(옆으로 누운 얼굴 찾기).
         */
        fun letterbox(width: Int, height: Int, quarterTurns: Int = 0): FaceRoi =
            FaceRoi(width / 2f, height / 2f, max(width, height).toFloat(), (quarterTurns * PI / 2).toFloat())

        /** 찾은 얼굴 상자의 가운데에 긴 변의 [SCALE]배 정사각형. 두 눈(점 0 → 1)이 가로가 되게 돌린다. */
        fun fromDetection(detection: FaceDetection): FaceRoi = FaceRoi(
            centerX = (detection.left + detection.right) / 2f,
            centerY = (detection.top + detection.bottom) / 2f,
            side = max(detection.right - detection.left, detection.bottom - detection.top) * SCALE,
            angle = angleOf(detection.keypoints[0], detection.keypoints[1], detection.keypoints[2], detection.keypoints[3])
        )

        /**
         * 특징점을 모두 덮는 상자(그림 축에 맞춘 상자)의 가운데에 긴 변의 [SCALE]배 정사각형.
         * 두 눈꼬리(점 33 → 263)가 가로가 되게 돌린다(미디어파이프가 다음 장면을 자르는 방법과 같다).
         */
        fun fromLandmarks(face: FaceLandmarks): FaceRoi {
            val bounds = face.bounds()
            val start = FaceMeshIndex.RIGHT_EYE_OUTER
            val end = FaceMeshIndex.LEFT_EYE_OUTER
            return FaceRoi(
                centerX = (bounds[0] + bounds[2]) / 2f,
                centerY = (bounds[1] + bounds[3]) / 2f,
                side = max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * SCALE,
                angle = angleOf(face.x(start), face.y(start), face.x(end), face.y(end))
            )
        }

        /** 점 ([x0], [y0])에서 ([x1], [y1])로 가는 방향이 가로(+x)에서 돈 각(라디안, 시계 방향 +) */
        private fun angleOf(x0: Float, y0: Float, x1: Float, y1: Float): Float = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()).toFloat()
    }
}

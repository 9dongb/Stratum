package com.kiln.stratum.engine.face

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 찾은 얼굴 하나: 특징점 [FaceModel.LANDMARK_COUNT]개의 x·y([points], 점마다 이어 붙임)와 얼굴 있음 로짓([presence]).
 * 좌표는 문서 좌표(앱에 넘길 때) 또는 찾은 그림의 좌표(찾는 동안)다. 점 번호는 [FaceMeshIndex]다.
 * 한 번 만들면 바꾸지 않는다(배열을 바꾸지 않는다).
 */
class FaceLandmarks(val points: FloatArray, val presence: Float) {

    init {
        require(points.size == FaceModel.LANDMARK_COUNT * 2) { "Expected ${FaceModel.LANDMARK_COUNT} points: ${points.size / 2}" }
    }

    fun x(index: Int): Float = points[2 * index]

    fun y(index: Int): Float = points[2 * index + 1]

    /** 두 눈동자 가운데 사이 거리 */
    val eyeDistance: Float
        get() = hypot(x(FaceMeshIndex.LEFT_IRIS) - x(FaceMeshIndex.RIGHT_IRIS), y(FaceMeshIndex.LEFT_IRIS) - y(FaceMeshIndex.RIGHT_IRIS))

    /** 두 눈동자 가운데의 가운데 x */
    val eyeCenterX: Float
        get() = (x(FaceMeshIndex.LEFT_IRIS) + x(FaceMeshIndex.RIGHT_IRIS)) / 2f

    /** 두 눈동자 가운데의 가운데 y */
    val eyeCenterY: Float
        get() = (y(FaceMeshIndex.LEFT_IRIS) + y(FaceMeshIndex.RIGHT_IRIS)) / 2f

    /** 모든 점이 숫자인지(모델이 NaN을 내면 아니다) */
    val isFinite: Boolean
        get() = points.all { it.isFinite() } && presence.isFinite()

    /** 모든 점을 덮는 상자: 왼쪽, 위, 오른쪽, 아래 */
    fun bounds(): FloatArray {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            left = min(left, x(i))
            top = min(top, y(i))
            right = max(right, x(i))
            bottom = max(bottom, y(i))
        }
        return floatArrayOf(left, top, right, bottom)
    }

    /** 점 ([px], [py])가 얼굴 윤곽([FaceMeshIndex.FACE_OVAL]) 안인지 */
    fun contains(px: Float, py: Float): Boolean {
        val oval = FaceMeshIndex.FACE_OVAL
        var inside = false
        var j = oval.last()
        for (i in oval) {
            val xi = x(i)
            val yi = y(i)
            val xj = x(j)
            val yj = y(j)
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** 가로 [scaleX], 세로 [scaleY]배 한 것(찾은 그림 좌표 → 문서 좌표) */
    fun scaled(scaleX: Float, scaleY: Float): FaceLandmarks =
        FaceLandmarks(FloatArray(points.size) { points[it] * if (it % 2 == 0) scaleX else scaleY }, presence)
}

package com.kiln.stratum.engine.liquify

/**
 * 유동화 격자([LiquifyMesh])에 합치는 매끈한 변형 하나(얼굴 보정의 부위 등). [LiquifyMesh]처럼 거꾸로 찾는다:
 * 결과 자리 (x, y)가 원래 그림의 (x, y) + 변위를 읽는다. 상자([left]..[bottom], 문서 픽셀) 밖의 변위는 0이다.
 * 한 번 만들면 바꾸지 않아 여러 스레드에서 함께 읽는다.
 */
interface MeshWarp {
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float

    /** 상자 안의 점 ([x], [y])의 변위를 [out](0: x, 1: y)에 담는다 */
    fun displacement(x: Float, y: Float, out: FloatArray)

    /** 점 ([x], [y])가 상자 안인지 */
    fun covers(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

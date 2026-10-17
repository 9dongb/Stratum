package com.kiln.stratum.engine.adjust

import kotlin.math.sqrt

/** 노출 슬라이더의 한쪽 끝(EV). -2..2 EV다. */
const val MAX_EXPOSURE = 2f

/**
 * 레이어 하나의 기본 보정 값(비파괴, 결정 기록 '기본 보정'). 픽셀은 바꾸지 않고, 그릴 때마다 이 값대로 보정한다.
 * 노출은 -2..2 EV, 나머지는 -1..1(화면에는 -100..100)이고 0이면 그대로다. 곡선은 RGB 전체에 적용한다.
 *
 * 보정 순서(그릴 때, [AdjustmentMath]와 GPU 셰이더가 같다):
 * 노출 → 색온도(선형 빛) → 그림자·하이라이트 → 밝기 → 대비 → 곡선 → 채도
 */
data class Adjustments(
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val curve: ToneCurve = ToneCurve.IDENTITY
) {
    init {
        require(exposure in -MAX_EXPOSURE..MAX_EXPOSURE) { "Exposure out of range: $exposure" }
        for (value in listOf(temperature, shadows, highlights, brightness, contrast, saturation)) {
            require(value in -1f..1f) { "Adjustment out of range: $value" }
        }
    }

    /** 바꾸는 것이 없는지. 곡선은 처음 모양(대각선 두 점)일 때만 그대로로 본다. */
    val isIdentity: Boolean
        get() = exposure == 0f && temperature == 0f && shadows == 0f && highlights == 0f &&
            brightness == 0f && contrast == 0f && saturation == 0f && curve.isIdentity

    /** -0을 0으로 맞춘 값. 그대로 두면 같은 값인데도 다른 보정으로 비교된다(data class의 Float 비교). */
    fun normalized(): Adjustments =
        copy(
            exposure = exposure + 0f,
            temperature = temperature + 0f,
            shadows = shadows + 0f,
            highlights = highlights + 0f,
            brightness = brightness + 0f,
            contrast = contrast + 0f,
            saturation = saturation + 0f
        )

    companion object {
        /** 보정 없음 */
        val NONE = Adjustments()
    }
}

/** 곡선의 조정점 하나(입력 [x], 출력 [y], 둘 다 0..1) */
data class CurvePoint(val x: Float, val y: Float)

/**
 * 곡선(RGB 전체). 조정점([points])을 지나는 단조 3차 곡선(Fritsch–Carlson)이다.
 * 점 사이에서 넘치거나 반대로 꺾이지 않는다(이웃한 두 점이 오르면 그 사이도 오른다). 첫 점 앞과 마지막 점 뒤는 그 점의 값이다.
 * 점은 2..[MAX_POINTS]개이고 입력(x)이 커지는 순서다.
 */
data class ToneCurve(val points: List<CurvePoint>) {

    init {
        require(points.size in 2..MAX_POINTS) { "Curve needs 2..$MAX_POINTS points: ${points.size}" }
        for (point in points) require(point.x in 0f..1f && point.y in 0f..1f) { "Curve point out of range: $point" }
        for (index in 1 until points.size) require(points[index].x > points[index - 1].x) { "Curve points must go right: $points" }
    }

    /** 처음 모양(왼쪽 아래와 오른쪽 위 두 점)인지 */
    val isIdentity: Boolean
        get() = points == IDENTITY.points

    /** 점마다의 기울기(Fritsch–Carlson). 단조로운 구간은 단조롭게 지난다. */
    private val tangents: FloatArray by lazy { tangentsOf(points) }

    /** 입력 [x](0..1)의 출력(0..1) */
    fun valueAt(x: Float): Float {
        val first = points.first()
        val last = points.last()
        if (x <= first.x) return first.y
        if (x >= last.x) return last.y
        var index = 0
        while (points[index + 1].x < x) index++
        val p0 = points[index]
        val p1 = points[index + 1]
        val width = p1.x - p0.x
        val t = (x - p0.x) / width
        val t2 = t * t
        val t3 = t2 * t
        // 3차 에르미트 곡선
        val value = (2 * t3 - 3 * t2 + 1) * p0.y +
            (t3 - 2 * t2 + t) * width * tangents[index] +
            (-2 * t3 + 3 * t2) * p1.y +
            (t3 - t2) * width * tangents[index + 1]
        return value.coerceIn(0f, 1f)
    }

    /** 입력을 [TABLE_SIZE]칸으로 나눈 표(칸 i는 입력 i/255). GPU 셰이더가 텍스처로 읽고, 그 사이는 직선으로 잇는다. */
    fun table(): FloatArray = FloatArray(TABLE_SIZE) { valueAt(it / (TABLE_SIZE - 1f)) }

    companion object {
        const val MAX_POINTS = 16
        const val TABLE_SIZE = 256

        /** 처음 모양: 바꾸지 않는 대각선 */
        val IDENTITY = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))

        private fun tangentsOf(points: List<CurvePoint>): FloatArray {
            val count = points.size
            val secants = FloatArray(count - 1) { (points[it + 1].y - points[it].y) / (points[it + 1].x - points[it].x) }
            val tangents = FloatArray(count)
            tangents[0] = secants[0]
            tangents[count - 1] = secants[count - 2]
            for (index in 1 until count - 1) {
                tangents[index] = if (secants[index - 1] * secants[index] > 0f) (secants[index - 1] + secants[index]) / 2f else 0f
            }
            // 넘치지 않게 기울기를 줄인다
            for (index in 0 until count - 1) {
                val secant = secants[index]
                if (secant == 0f) {
                    tangents[index] = 0f
                    tangents[index + 1] = 0f
                    continue
                }
                val alpha = tangents[index] / secant
                val beta = tangents[index + 1] / secant
                val length = alpha * alpha + beta * beta
                if (length > 9f) {
                    val tau = 3f / sqrt(length)
                    tangents[index] = tau * alpha * secant
                    tangents[index + 1] = tau * beta * secant
                }
            }
            return tangents
        }
    }
}

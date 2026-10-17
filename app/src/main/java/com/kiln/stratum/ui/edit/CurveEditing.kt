package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import kotlin.math.hypot

/**
 * 곡선 편집기의 점 고르기·더하기·옮기기·지우기. 곡선 모양 자체는 엔진의 [ToneCurve]가 정한다.
 *
 * 그래프 좌표는 픽셀이고 왼쪽 위가 (0, 0)이다. 곡선 값은 아래가 0, 위가 1이다.
 */
object CurveEditing {

    /** 이웃한 두 점의 입력(x) 사이 최소 간격. 점이 겹치면 곡선을 만들 수 없다(입력이 커지는 순서여야 한다). */
    const val MIN_GAP = 0.02f

    /**
     * 그래프의 ([x], [y]) 픽셀에서 [radius] 픽셀 안에 있는 가장 가까운 점의 번호. 없으면 -1.
     * 그래프 크기는 [width]×[height] 픽셀이다.
     */
    fun hitTest(curve: ToneCurve, x: Float, y: Float, width: Float, height: Float, radius: Float): Int {
        var nearest = -1
        var nearestDistance = radius
        curve.points.forEachIndexed { index, point ->
            val distance = hypot(point.x * width - x, (1f - point.y) * height - y)
            if (distance <= nearestDistance) {
                nearest = index
                nearestDistance = distance
            }
        }
        return nearest
    }

    /**
     * 입력 [x](0..1)에 곡선 위의 점을 더한 곡선과 그 점의 번호. 점을 더해도 곡선 모양은 거의 그대로다.
     * 점이 가득하거나([ToneCurve.MAX_POINTS]) 이웃 점과 [MIN_GAP]보다 가까우면 null.
     */
    fun addPoint(curve: ToneCurve, x: Float): Pair<ToneCurve, Int>? {
        if (curve.points.size >= ToneCurve.MAX_POINTS) return null
        val clamped = x.coerceIn(0f, 1f)
        if (curve.points.any { kotlin.math.abs(it.x - clamped) < MIN_GAP }) return null
        val index = curve.points.indexOfFirst { it.x > clamped }.let { if (it < 0) curve.points.size else it }
        val points = curve.points.toMutableList().apply { add(index, CurvePoint(clamped, curve.valueAt(clamped))) }
        return ToneCurve(points) to index
    }

    /**
     * [index] 점을 ([x], [y])로 옮긴 곡선. 입력은 이웃 점 사이([MIN_GAP]만큼 안쪽)로, 출력은 0..1로 묶는다.
     * 양 끝 점도 옮길 수 있다(검은 점·흰 점을 안쪽으로).
     */
    fun movePoint(curve: ToneCurve, index: Int, x: Float, y: Float): ToneCurve {
        val points = curve.points
        require(index in points.indices) { "No point $index in $points" }
        val low = if (index == 0) 0f else points[index - 1].x + MIN_GAP
        val high = if (index == points.lastIndex) 1f else points[index + 1].x - MIN_GAP
        val moved = CurvePoint(x.coerceIn(low, high), y.coerceIn(0f, 1f))
        return ToneCurve(points.toMutableList().apply { set(index, moved) })
    }

    /** 입력이 [x]에 가장 가까운 점의 번호. 점을 더할 수 없는 곳을 눌렀을 때 그 점을 잡는다. */
    fun nearestByInput(curve: ToneCurve, x: Float): Int =
        curve.points.indices.minBy { kotlin.math.abs(curve.points[it].x - x) }

    /** [index] 점을 지운 곡선. 양 끝 점은 지울 수 없어 null이다(곡선에는 점이 둘 이상 있어야 한다). */
    fun removePoint(curve: ToneCurve, index: Int): ToneCurve? {
        if (!canRemove(curve, index)) return null
        return ToneCurve(curve.points.toMutableList().apply { removeAt(index) })
    }

    /** [index] 점을 지울 수 있는지(양 끝이 아닌 점) */
    fun canRemove(curve: ToneCurve, index: Int): Boolean = index in 1 until curve.points.lastIndex
}

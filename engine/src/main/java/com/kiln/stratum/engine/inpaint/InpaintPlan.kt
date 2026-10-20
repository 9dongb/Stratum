package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 지울 곳 덩어리마다 모델에 넣을 정사각형 창을 정한다(문서 좌표, 결정 기록 'AI 제거·AI 선택').
 *
 * 창은 구멍을 다 담고 둘레를 [MIN_MARGIN] 이상 두르며, 구멍이 창 넓이의 약 [TARGET_HOLE_FRACTION]가 되는 크기다.
 * 모델은 둘레를 보고 구멍을 채우므로 둘레가 넉넉해야 한다. 길쭉한 구멍(전선 등)은 긴 쪽으로 겹치는 창 여러 개로 나눈다.
 * 상수는 폴드8에서 써 보고 고칠 수 있게 여기에 모은다.
 */
object InpaintPlan {

    /** 모델 입력 한 변(px). 창을 이 크기로 줄이거나 키워 넣는다. */
    const val MODEL_SIZE = 512

    /** 계획용 마스크(덩어리 찾기)의 긴 변 최대 칸 수 */
    const val PLAN_MAX_EDGE = 1024

    /** 구멍 둘레에 두를 최소 여백(문서 px) */
    const val MIN_MARGIN = 128

    /** 창 넓이에서 구멍이 차지하면 좋은 몫. 어도비 연구의 자동 자르기(약 25%)와 IOPaint(구멍 + 128px)의 사이다. */
    const val TARGET_HOLE_FRACTION = 0.35

    /** 길쭉한 구멍을 나눌 때 보통 넘지 않는 창 수 */
    const val MAX_WINDOWS = 8

    /** 쓰기 몫이 1에서 0으로 내려가는 폭(모델 px). 넓힌 띠 안에서만 섞는다. */
    const val FEATHER = 2f

    /** 밝기·색을 맞출 때 '원래 - 모델' 차이를 재는 띠의 폭(모델 px). 넓힌 구멍 바로 바깥이다. */
    const val MATCH_RING = 8f

    /** 이보다 투명한 픽셀(알파 0..255)은 색을 모르는 곳으로 둔다. 검은 색이 둘레로 들어가지 않게 한다. */
    const val ALPHA_KNOWN = 8

    /** 범위 [width]×[height]를 반씩 줄여 긴 변이 [PLAN_MAX_EDGE] 이하가 되는 가장 덜 줄인 단계(한 칸 = 2^단계 px) */
    fun planLevel(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "Bad area: ${width}x$height" }
        var level = 0
        while (max(width, height) > PLAN_MAX_EDGE shl level) level++
        return level
    }

    /** 모델 한 픽셀이 문서 [scale] 픽셀일 때 구멍을 넓힐 폭(모델 px). 문서에서 4px 이상이고 큰 창일수록 넓다. */
    fun dilation(scale: Double): Float {
        require(scale > 0.0) { "Bad scale: $scale" }
        return max(3.0, ceil(4.0 / scale)).toFloat()
    }

    /**
     * 구멍 덩어리 하나([hole]: 문서 범위, [area]: 구멍 넓이 px²)의 창들. 모두 [document] 안에 있다.
     * 보통 하나이고, 길쭉하면 긴 쪽으로 여백만큼 겹치는 창 여러 개다(보통 [MAX_WINDOWS] 이하, 문서가 아주 길쭉하면 더 될 수 있다).
     */
    fun windows(hole: PixelRect, area: Long, document: PixelSize, modelSize: Int = MODEL_SIZE): List<CropWindow> {
        require(hole.width > 0 && hole.height > 0 && area > 0) { "Empty hole: $hole, area $area" }
        val shortSide = min(document.width, document.height)
        val horizontal = hole.width >= hole.height
        val long = if (horizontal) hole.width else hole.height
        val short = if (horizontal) hole.height else hole.width
        val fromArea = sqrt(area / TARGET_HOLE_FRACTION)
        // 짧은 쪽을 다 담는 창, 긴 쪽까지 다 담는 창
        val sideways = maxOf(modelSize.toDouble(), fromArea, (short + 2 * MIN_MARGIN).toDouble())
        val whole = maxOf(modelSize.toDouble(), fromArea, (long + 2 * MIN_MARGIN).toDouble())
        if (whole <= 2 * sideways && whole <= shortSide) {
            return listOf(place(hole, ceil(whole).toInt(), document, modelSize))
        }
        // 길쭉하다: 긴 쪽을 같은 길이로 나눠 조각마다 창 하나
        val size = min(shortSide.toDouble(), maxOf(sideways, ceil(long.toDouble() / MAX_WINDOWS) + 2 * MIN_MARGIN)).toInt()
        val step = size - 2 * MIN_MARGIN
        if (step <= 0) return listOf(place(hole, size, document, modelSize))
        val count = (long + step - 1) / step
        return List(count) { index ->
            val from = long * index / count
            val to = long * (index + 1) / count
            val piece = if (horizontal) {
                PixelRect(hole.left + from, hole.top, hole.left + to, hole.bottom)
            } else {
                PixelRect(hole.left, hole.top + from, hole.right, hole.top + to)
            }
            place(piece, size, document, modelSize)
        }
    }

    /**
     * [target] 가운데에 한 변 [wanted]인 창을 놓고 문서 안으로 민다. 한 변은 문서 짧은 변을 넘지 않게 정한다.
     * 반씩 줄여 읽을 단계를 정하고, 왼쪽·위와 한 변을 그 단계의 칸(2^단계 px)에 맞춘다(축소본을 칸 경계에서 읽는다).
     * 칸에 맞추느라 오른쪽·아래가 문서 끝을 칸 하나보다 적게 넘을 수 있다. 그 부분은 없는 픽셀이라, 읽을 때 끝 픽셀을 늘인다.
     */
    private fun place(target: PixelRect, wanted: Int, document: PixelSize, modelSize: Int): CropWindow {
        val fitted = min(wanted, min(document.width, document.height))
        val level = levelFor(fitted, modelSize)
        val cell = 1 shl level
        val size = alignUp(fitted, cell)
        val left = start((target.left + target.right) / 2.0, size, document.width, cell)
        val top = start((target.top + target.bottom) / 2.0, size, document.height, cell)
        return CropWindow(left, top, size, level)
    }

    /** 가운데 [center]에 길이 [size]를 놓고, 칸에 맞춘 문서 끝 안으로 민 뒤 칸에 맞춘 시작 자리 */
    private fun start(center: Double, size: Int, length: Int, cell: Int): Int {
        val end = alignUp(length, cell)
        val wanted = (center - size / 2.0).roundToInt().coerceIn(0, max(0, end - size))
        return wanted / cell * cell
    }

    /** 한 변 [size]를 반씩 줄여 [modelSize] 이상, 두 배 이하가 되는 단계(작은 창은 0) */
    private fun levelFor(size: Int, modelSize: Int): Int {
        var level = 0
        while (size shr (level + 1) >= modelSize) level++
        return level
    }

    private fun alignUp(value: Int, cell: Int): Int = (value + cell - 1) / cell * cell
}

/**
 * 모델에 넣을 문서의 정사각형 영역. 왼쪽 위([left], [top])와 한 변 [size](px).
 * [level]만큼 반씩 줄여 읽으면 한 변이 모델 크기 이상, 두 배 이하가 된다(문서가 모델보다 작으면 [level]은 0).
 * 왼쪽·위와 한 변은 2^[level]의 배수다. 오른쪽·아래는 문서 끝을 2^[level]보다 적게 넘을 수 있다.
 */
data class CropWindow(val left: Int, val top: Int, val size: Int, val level: Int) {

    init {
        require(left >= 0 && top >= 0 && size > 0 && level >= 0) { "Bad window: $this" }
    }

    /** 문서 범위 */
    val bounds: PixelRect
        get() = PixelRect(left, top, left + size, top + size)

    /** [level]로 줄여 읽은 한 변(px) */
    val levelSize: Int
        get() = size shr level

    /** 모델 한 픽셀이 덮는 문서 픽셀 수 */
    fun scale(modelSize: Int = InpaintPlan.MODEL_SIZE): Double = size.toDouble() / modelSize
}

package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 자르기 비율. 자유, 원본, 그리고 세로가 기본인 비율이다(사용자 피드백 10차: "세로가 기본 값이게", 결정 기록 '자르기·회전·뒤집기·크기 변경').
 * 납작해지는 순서(1:1 → 9:16)이고, 가로·세로 바꾸기(↔)를 누르면 가로(5:4·4:3·3:2·16:9)가 된다.
 * [width]:[height]가 0:0이면 정해진 숫자가 없는 비율(자유·원본)이다.
 * [code]는 되살리기 저장에 쓰는 바뀌지 않는 번호다(앞 판의 순서 번호와 같고, 없어진 비율은 자유로 되살린다).
 */
enum class CropRatio(val width: Int, val height: Int, val code: Int) {
    FREE(0, 0, 0),
    ORIGINAL(0, 0, 1),
    SQUARE(1, 1, 2),
    PORTRAIT_4_5(4, 5, 3),
    PORTRAIT_3_4(3, 4, 8),
    PORTRAIT_2_3(2, 3, 9),
    STORY_9_16(9, 16, 4)
}

/** 자르기 테두리에서 끌 수 있는 곳. 모서리 넷, 변 넷, 그리고 안쪽(테두리 옮기기)이다. */
enum class CropHandle {
    TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, MOVE;

    val movesLeft: Boolean
        get() = this == TOP_LEFT || this == LEFT || this == BOTTOM_LEFT

    val movesRight: Boolean
        get() = this == TOP_RIGHT || this == RIGHT || this == BOTTOM_RIGHT

    val movesTop: Boolean
        get() = this == TOP_LEFT || this == TOP || this == TOP_RIGHT

    val movesBottom: Boolean
        get() = this == BOTTOM_LEFT || this == BOTTOM || this == BOTTOM_RIGHT

    val isCorner: Boolean
        get() = (movesLeft || movesRight) && (movesTop || movesBottom)
}

/**
 * 자르기 테두리(문서 픽셀 좌표). 끄는 동안 부드럽게 움직이도록 실수로 두고, 적용할 때 정수 픽셀로 반올림한다([toPixelRect]).
 * 테두리는 늘 사진 안에 있다. 한 번 만들면 바꾸지 않는 값이고, 끌거나 비율을 바꾸면 새 테두리를 돌려준다.
 *
 * @property ratio 고른 비율
 * @property swapped 가로·세로를 바꿨는지. 비율이 있으면 비율을 뒤집고(4:5 → 5:4), 자유면 테두리 모양을 뒤집는다.
 */
data class CropFrame(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val ratio: CropRatio = CropRatio.FREE,
    val swapped: Boolean = false
) {
    init {
        require(right > left && bottom > top) { "Crop frame must not be empty: $this" }
    }

    val width: Double
        get() = right - left

    val height: Double
        get() = bottom - top

    private val centerX: Double
        get() = (left + right) / 2

    private val centerY: Double
        get() = (top + bottom) / 2

    /** [document]에서 이 테두리가 지키는 가로÷세로. 자유면 null이다. */
    fun aspect(document: PixelSize): Double? {
        val base = when (ratio) {
            CropRatio.FREE -> return null
            CropRatio.ORIGINAL -> document.width.toDouble() / document.height
            else -> ratio.width.toDouble() / ratio.height
        }
        return if (swapped) 1 / base else base
    }

    /** 비율을 [newRatio]로 바꾼다. 사진 안에 그 비율로 가장 크게, 지금 가운데에 맞춘다. 자유로 바꾸면 테두리는 그대로다. */
    fun withRatio(newRatio: CropRatio, document: PixelSize): CropFrame {
        val next = copy(ratio = newRatio)
        return next.aspect(document)?.let { next.fitted(it, document) } ?: next
    }

    /** 가로·세로를 바꾼다. 비율이 있으면 뒤집은 비율로 다시 맞추고, 자유면 테두리 모양을 지금 가운데에서 뒤집는다. */
    fun swapped(document: PixelSize): CropFrame {
        val next = copy(swapped = !swapped)
        next.aspect(document)?.let { return next.fitted(it, document) }
        return next.centeredAt(
            centerX,
            centerY,
            min(height, document.width.toDouble()),
            min(width, document.height.toDouble()),
            document
        )
    }

    /**
     * [handle]을 문서 픽셀로 ([dx], [dy])만큼 끈 테두리. 사진 밖으로 나가지 않고, 짧은 변이 [minSide]보다 작아지지 않는다.
     * 비율이 있으면 모서리는 맞은편 모서리를 기준으로 키우거나 줄이고, 변은 그 변과 수직인 쪽을 가운데에 두고 비율을 지킨다.
     */
    fun dragged(handle: CropHandle, dx: Double, dy: Double, document: PixelSize, minSide: Double): CropFrame {
        val w = document.width.toDouble()
        val h = document.height.toDouble()
        // 사진이 아주 작으면 사진 크기까지만 지킨다
        val least = minSide.coerceIn(MIN_PIXELS, min(w, h))
        if (handle == CropHandle.MOVE) {
            val newLeft = (left + dx).coerceIn(0.0, w - width)
            val newTop = (top + dy).coerceIn(0.0, h - height)
            return copy(left = newLeft, top = newTop, right = newLeft + width, bottom = newTop + height)
        }
        // 이미 그보다 작은 테두리(더 확대해서 줄였던 것)는 끄는 순간 커지지 않게, 지금 크기까지만 지킨다
        val aspect = aspect(document) ?: run {
            val leastX = min(least, width)
            val leastY = min(least, height)
            return copy(
                left = if (handle.movesLeft) (left + dx).coerceIn(0.0, right - leastX) else left,
                right = if (handle.movesRight) (right + dx).coerceIn(left + leastX, w) else right,
                top = if (handle.movesTop) (top + dy).coerceIn(0.0, bottom - leastY) else top,
                bottom = if (handle.movesBottom) (bottom + dy).coerceIn(top + leastY, h) else bottom
            )
        }
        // 짧은 변이 [least]이 되는 폭
        val leastWidth = min(if (aspect >= 1) least * aspect else least, width)
        return if (handle.isCorner) {
            draggedCorner(handle, dx, dy, aspect, leastWidth, w, h)
        } else {
            draggedEdge(handle, dx, dy, aspect, leastWidth, w, h)
        }
    }

    /** 비율을 지키며 모서리를 끈다. 맞은편 모서리는 그대로 두고, 손가락이 더 멀리 간 쪽을 따라 크기를 정한다. */
    private fun draggedCorner(
        handle: CropHandle,
        dx: Double,
        dy: Double,
        aspect: Double,
        leastWidth: Double,
        w: Double,
        h: Double
    ): CropFrame {
        val anchorX = if (handle.movesRight) left else right
        val anchorY = if (handle.movesBottom) top else bottom
        val cornerX = (if (handle.movesRight) right else left) + dx
        val cornerY = (if (handle.movesBottom) bottom else top) + dy
        val proposedWidth = max(0.0, if (handle.movesRight) cornerX - anchorX else anchorX - cornerX)
        val proposedHeight = max(0.0, if (handle.movesBottom) cornerY - anchorY else anchorY - cornerY)
        val roomX = if (handle.movesRight) w - anchorX else anchorX
        val roomY = if (handle.movesBottom) h - anchorY else anchorY
        val most = min(roomX, roomY * aspect)
        val newWidth = max(proposedWidth, proposedHeight * aspect).coerceIn(min(leastWidth, most), most)
        val newHeight = newWidth / aspect
        val newLeft = if (handle.movesRight) anchorX else anchorX - newWidth
        val newTop = if (handle.movesBottom) anchorY else anchorY - newHeight
        return copy(left = newLeft, top = newTop, right = newLeft + newWidth, bottom = newTop + newHeight)
    }

    /** 비율을 지키며 변을 끈다. 맞은편 변은 그대로 두고, 다른 쪽은 지금 가운데에 두되 사진 밖으로 나가면 옮긴다. */
    private fun draggedEdge(
        handle: CropHandle,
        dx: Double,
        dy: Double,
        aspect: Double,
        leastWidth: Double,
        w: Double,
        h: Double
    ): CropFrame {
        if (handle.movesLeft || handle.movesRight) {
            val anchorX = if (handle.movesRight) left else right
            val proposed = if (handle.movesRight) right + dx - anchorX else anchorX - (left + dx)
            val most = min(if (handle.movesRight) w - anchorX else anchorX, h * aspect)
            val newWidth = proposed.coerceIn(min(leastWidth, most), most)
            val newHeight = newWidth / aspect
            val newLeft = if (handle.movesRight) anchorX else anchorX - newWidth
            val newTop = (centerY - newHeight / 2).coerceIn(0.0, h - newHeight)
            return copy(left = newLeft, top = newTop, right = newLeft + newWidth, bottom = newTop + newHeight)
        }
        val anchorY = if (handle.movesBottom) top else bottom
        val proposed = if (handle.movesBottom) bottom + dy - anchorY else anchorY - (top + dy)
        val most = min(if (handle.movesBottom) h - anchorY else anchorY, w / aspect)
        val newHeight = proposed.coerceIn(min(leastWidth / aspect, most), most)
        val newWidth = newHeight * aspect
        val newTop = if (handle.movesBottom) anchorY else anchorY - newHeight
        val newLeft = (centerX - newWidth / 2).coerceIn(0.0, w - newWidth)
        return copy(left = newLeft, top = newTop, right = newLeft + newWidth, bottom = newTop + newHeight)
    }

    /**
     * 보이는 사진을 [step] 방향으로 돌리거나 뒤집을 때(자르기·회전 화면, 사용자 피드백 8차 1) 사진과 함께 옮긴 테두리.
     * [document]는 돌리기 전에 보이던 사진 크기다. 가로·세로가 바뀌는 회전이면 고정 비율(4:5 등)은 가로세로를 바꿔(4:5 → 5:4)
     * 돌린 테두리 모양과 맞춘다. 자유는 비율이 없고, 원본은 돌린 사진의 비율을 따르므로 그대로다.
     */
    fun turned(step: Orientation, document: PixelSize): CropFrame {
        val w = document.width.toDouble()
        val h = document.height.toDouble()
        val (x0, y0) = step.mapPoint(left, top, w, h)
        val (x1, y1) = step.mapPoint(right, bottom, w, h)
        val fixedRatio = ratio != CropRatio.FREE && ratio != CropRatio.ORIGINAL
        return copy(
            left = min(x0, x1),
            top = min(y0, y1),
            right = max(x0, x1),
            bottom = max(y0, y1),
            swapped = if (step.swapsAxes && fixedRatio) !swapped else swapped
        )
    }

    /**
     * 자르기 명령에 넘길 정수 사각형. 반올림해도 사진 안에 있고, 변은 1픽셀 이상이다.
     * 비율이 있으면 폭을 반올림한 뒤 높이를 비율로 다시 구해, 비율이 1픽셀 안에서 맞는다.
     */
    fun toPixelRect(document: PixelSize): PixelRect {
        val aspect = aspect(document)
        var w = width.roundToInt().coerceIn(1, document.width)
        var h = if (aspect == null) height.roundToInt() else (w / aspect).roundToInt()
        if (h > document.height) {
            h = document.height
            if (aspect != null) w = (h * aspect).roundToInt().coerceIn(1, document.width)
        }
        h = h.coerceAtLeast(1)
        val l = left.roundToInt().coerceIn(0, document.width - w)
        val t = top.roundToInt().coerceIn(0, document.height - h)
        return PixelRect(l, t, l + w, t + h)
    }

    /** 가운데 ([cx], [cy])에 [w]×[h] 테두리를 놓고, 사진 밖으로 나가지 않게 옮긴다. */
    private fun centeredAt(cx: Double, cy: Double, w: Double, h: Double, document: PixelSize): CropFrame {
        val newLeft = (cx - w / 2).coerceIn(0.0, document.width - w)
        val newTop = (cy - h / 2).coerceIn(0.0, document.height - h)
        return copy(left = newLeft, top = newTop, right = newLeft + w, bottom = newTop + h)
    }

    /** 가로÷세로가 [aspect]인 테두리를 사진 안에 가장 크게, 지금 가운데에 맞춘다. */
    private fun fitted(aspect: Double, document: PixelSize): CropFrame {
        val w = min(document.width.toDouble(), document.height * aspect)
        return centeredAt(centerX, centerY, w, w / aspect, document)
    }

    /** 화면 회전·접기·펼치기 뒤에도 남기도록 숫자 목록으로 바꾼다. */
    fun toSaved(): List<Double> =
        listOf(left, top, right, bottom, ratio.code.toDouble(), if (swapped) 1.0 else 0.0)

    companion object {
        /** 테두리 짧은 변의 가장 작은 크기(문서 픽셀). */
        const val MIN_PIXELS = 1.0

        /** [document] 전체를 덮는 자유 비율 테두리. 자르기를 시작할 때 쓴다. */
        fun whole(document: PixelSize): CropFrame =
            CropFrame(0.0, 0.0, document.width.toDouble(), document.height.toDouble())

        /** [toSaved]로 바꾼 목록에서 되살린다. */
        fun fromSaved(saved: List<Double>): CropFrame =
            CropFrame(
                left = saved[0],
                top = saved[1],
                right = saved[2],
                bottom = saved[3],
                ratio = CropRatio.entries.firstOrNull { it.code == saved[4].toInt() } ?: CropRatio.FREE,
                swapped = saved[5] != 0.0
            )
    }
}

/**
 * 화면 좌표 ([x], [y])에서 잡히는 테두리 부분. 테두리의 화면 사각형([left]~[bottom])에서 [reach] 안이면 잡힌다.
 * 모서리를 먼저, 그다음 변, 그다음 안쪽(옮기기) 순서로 본다. 테두리가 아주 작아 둘이 겹치면 더 가까운 쪽이다.
 * 아무것도 아니면 null이다.
 */
fun cropHandleAt(x: Double, y: Double, left: Double, top: Double, right: Double, bottom: Double, reach: Double): CropHandle? {
    val nearLeft = abs(x - left) <= reach
    val nearRight = abs(x - right) <= reach
    val nearTop = abs(y - top) <= reach
    val nearBottom = abs(y - bottom) <= reach
    val inX = x >= left - reach && x <= right + reach
    val inY = y >= top - reach && y <= bottom + reach
    // 양쪽 변에 다 가까우면(테두리가 좁으면) 더 가까운 변이다
    val onLeft = nearLeft && (!nearRight || abs(x - left) <= abs(x - right))
    val onRight = nearRight && !onLeft
    val onTop = nearTop && (!nearBottom || abs(y - top) <= abs(y - bottom))
    val onBottom = nearBottom && !onTop
    return when {
        onLeft && onTop -> CropHandle.TOP_LEFT
        onRight && onTop -> CropHandle.TOP_RIGHT
        onLeft && onBottom -> CropHandle.BOTTOM_LEFT
        onRight && onBottom -> CropHandle.BOTTOM_RIGHT
        onLeft && inY -> CropHandle.LEFT
        onRight && inY -> CropHandle.RIGHT
        onTop && inX -> CropHandle.TOP
        onBottom && inX -> CropHandle.BOTTOM
        x > left && x < right && y > top && y < bottom -> CropHandle.MOVE
        else -> null
    }
}

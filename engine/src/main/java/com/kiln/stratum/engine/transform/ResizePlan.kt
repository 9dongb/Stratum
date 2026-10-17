package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.preview.MAX_PREVIEW_LEVEL
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import kotlin.math.ceil
import kotlin.math.floor

/** 크기를 바꿀 때 한 번에 그리는 대상 묶음의 한 변(픽셀). 타일 4×4장이고, 원본 쪽 영역도 GPU가 다루기 쉬운 크기로 묶인다. */
const val RESIZE_BLOCK_SIZE = 4 * TILE_SIZE

/** 보간에 쓰는 이웃의 반지름(축소본 픽셀). 바이큐빅은 2이고, 반올림을 위해 하나 더 둔다. */
private const val RESAMPLE_MARGIN = 3

/**
 * 이미지 크기를 [source]에서 [target]으로 바꾸는 계획(순수 계산).
 *
 * 줄일 때는 먼저 축마다 정확히 반씩 줄여([levelX]·[levelY]번, 2×2 평균) 남은 배율을 0.5보다 크고 1 이하로 만들고,
 * 마지막에 바이큐빅으로 한 번 보간한다. 그래야 크게 줄여도 계단(앨리어싱)이 생기지 않는다. 키울 때는 바이큐빅만 쓴다.
 * 대상은 [RESIZE_BLOCK_SIZE] 묶음씩 그리고, 묶음마다 보간에 필요한 원본 둘레까지 함께 읽어 묶음 경계에 이음매가 없다.
 */
class ResizePlan(val source: PixelSize, val target: PixelSize) {

    /** 가로 배율(대상 ÷ 원본). 1보다 크면 키운다. */
    val scaleX: Double = target.width.toDouble() / source.width

    /** 세로 배율(대상 ÷ 원본). */
    val scaleY: Double = target.height.toDouble() / source.height

    /** 가로를 정확히 반씩 줄이는 횟수 */
    val levelX: Int = halvings(source.width, target.width)

    /** 세로를 정확히 반씩 줄이는 횟수 */
    val levelY: Int = halvings(source.height, target.height)

    /** 대상 묶음들(대상 픽셀 좌표). 위 줄부터, 한 줄 안에서는 왼쪽부터 대상을 빈틈없이 한 번씩 덮는다. */
    fun blocks(): List<PixelRect> {
        val blocks = ArrayList<PixelRect>()
        for (top in 0 until target.height step RESIZE_BLOCK_SIZE) {
            for (left in 0 until target.width step RESIZE_BLOCK_SIZE) {
                blocks += PixelRect(
                    left,
                    top,
                    minOf(left + RESIZE_BLOCK_SIZE, target.width),
                    minOf(top + RESIZE_BLOCK_SIZE, target.height)
                )
            }
        }
        return blocks
    }

    /**
     * 대상 묶음 [block]을 그리는 데 필요한 원본 사각형(원본 픽셀 좌표). 보간에 쓰는 둘레 여유를 더하고 원본 안으로 자른다.
     * 여유는 반씩 줄인 단계의 픽셀로 [RESAMPLE_MARGIN]개다.
     */
    fun sourceRect(block: PixelRect): PixelRect {
        val marginX = RESAMPLE_MARGIN.toDouble() * (1 shl levelX)
        val marginY = RESAMPLE_MARGIN.toDouble() * (1 shl levelY)
        return PixelRect(
            left = floor(block.left / scaleX - marginX).toInt().coerceIn(0, source.width),
            top = floor(block.top / scaleY - marginY).toInt().coerceIn(0, source.height),
            right = ceil(block.right / scaleX + marginX).toInt().coerceIn(0, source.width),
            bottom = ceil(block.bottom / scaleY + marginY).toInt().coerceIn(0, source.height)
        )
    }

    companion object {
        /**
         * [source] 픽셀을 [target] 픽셀로 줄일 때 정확히 반씩 줄이는 횟수.
         * 지금 크기가 [target]보다 크고, 반으로 줄인 크기(올림)가 아직 [target] 이상인 동안 줄인다. 키울 때는 0이다.
         */
        fun halvings(source: Int, target: Int): Int {
            require(source > 0 && target > 0) { "Sizes must be positive: $source -> $target" }
            var level = 0
            while (
                level < MAX_PREVIEW_LEVEL &&
                ceilDiv(source, 1 shl level) > target &&
                ceilDiv(source, 1 shl (level + 1)) >= target
            ) {
                level++
            }
            return level
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = ((value.toLong() + divisor - 1) / divisor).toInt()
    }
}

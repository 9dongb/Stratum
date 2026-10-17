package com.kiln.stratum.engine.preview

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE

/** 축소본의 긴 변 최대 픽셀. 대부분의 GPU가 문제없이 다루는 텍스처 크기다. */
const val MAX_PREVIEW_EDGE = 4096

/** 축소본을 그릴 때 한 번에 다루는 타일 묶음의 한 변(타일 수). 8×8 타일은 2048px이고, 한 번에 GPU에 올리는 양이 약 16MB다. */
const val PREVIEW_BLOCK_TILES = 8

/** 묶음 한 변의 픽셀 수. */
private const val PREVIEW_BLOCK_SIZE = PREVIEW_BLOCK_TILES * TILE_SIZE

/** 가장 많이 줄이는 단계. 묶음 하나(2048px)가 1픽셀이 되는 1/2048이다. */
const val MAX_PREVIEW_LEVEL = 11

/**
 * 부분 그림을 화면에서 키워 보여도 되는 정도. 1.25배까지는 같은 단계로 두어,
 * 조금 확대할 때마다 다시 그리지 않고 메모리도 덜 쓴다.
 */
const val DETAIL_MAX_MAGNIFICATION = 1.25

/**
 * 축소본에서 타일 묶음 하나가 놓이는 자리.
 *
 * 묶음은 문서의 타일 [firstColumn]열·[firstRow]행부터 [columns]×[rows]개 타일이다.
 * [left]·[top]·[width]·[height]는 문서 안 사각형(픽셀)이고, `preview*`는 그려 낼 축소본 안 사각형이다.
 * 축소본 안 자리는 그리는 영역([PreviewRegion])의 왼쪽 위를 기준으로 한다. 문서 전체를 그리면 축소본의 왼쪽 위다.
 */
data class PreviewBlock(
    val firstColumn: Int,
    val firstRow: Int,
    val columns: Int,
    val rows: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val previewLeft: Int,
    val previewTop: Int,
    val previewWidth: Int,
    val previewHeight: Int
)

/**
 * 단계 [level](1/2^level 크기)로 그릴 문서 안 영역 [rect]. 문서 전체를 그리는 축소본도 이것의 한 경우다.
 *
 * [rect]의 왼쪽·위는 단계 격자(max(타일 크기, 2^level) 픽셀)에 맞춰져 있다. 그래서 일부만 그려도
 * 전체 축소본의 같은 자리와 픽셀이 똑같다. 오른쪽·아래는 격자에 맞거나 문서 끝이다.
 */
data class PreviewRegion(val level: Int, val rect: PixelRect) {

    /** 이 영역이 전체 축소본에서 시작하는 자리. 격자에 맞춰져 있어 나누어떨어진다. */
    val previewLeft: Int
        get() = rect.left shr level

    val previewTop: Int
        get() = rect.top shr level

    /** 이 영역을 그린 축소본의 크기. 문서 끝에 닿으면 나머지를 올림한다. */
    val previewWidth: Int
        get() = shrink(rect.right, level) - previewLeft

    val previewHeight: Int
        get() = shrink(rect.bottom, level) - previewTop
}

/**
 * 화면용 축소본의 크기를 정한다.
 *
 * 단계 k의 축소본은 문서의 1/2^k 크기다. 반씩만 줄이면 GPU의 1/2 축소가 정확히 2×2 평균이라
 * 화질이 고르고, 묶음 경계가 정수 픽셀에 맞아 이음매가 생기지 않는다.
 */
object PreviewLevels {

    /**
     * 화면 배율 [scale](문서 픽셀 하나가 화면 픽셀 몇 개인지)로 보여 줄 때 쓸 단계.
     * 화면에서 [maxMagnification]배보다 크게 키워 보이지 않는 가장 작은 축소본을 고른다.
     */
    fun levelForScale(scale: Double, maxMagnification: Double = 1.0): Int {
        require(scale > 0) { "Scale must be positive: $scale" }
        var level = 0
        // 한 단계 더 줄여도 그 축소본을 화면에서 [maxMagnification]배 이하로 키워 보이면 더 줄인다
        while (level < MAX_PREVIEW_LEVEL && scale * (1L shl (level + 1)) <= maxMagnification) level++
        return level
    }

    /**
     * [document] 크기의 문서를 [canvas] 크기(픽셀) 화면에 맞춰 보여 줄 때 쓸 단계.
     * 화면에 보이는 크기보다 작아지지 않는 가장 작은 축소본을 고른다. 긴 변은 [MAX_PREVIEW_EDGE] 이하로 한다.
     */
    fun levelFor(document: PixelSize, canvas: PixelSize): Int {
        // 화면에 맞췄을 때의 배율. 1보다 크면 문서가 화면보다 작아 줄이지 않는다.
        val fitScale = minOf(
            canvas.width.toDouble() / document.width,
            canvas.height.toDouble() / document.height
        )
        var level = levelForScale(fitScale)
        while (level < MAX_PREVIEW_LEVEL && longEdge(previewSize(document, level)) > MAX_PREVIEW_EDGE) level++
        return level
    }

    /** [level] 단계 축소본의 크기. 나머지는 올림해서 끝 픽셀도 남긴다. */
    fun previewSize(document: PixelSize, level: Int): PixelSize {
        requireLevel(level)
        return PixelSize(shrink(document.width, level), shrink(document.height, level))
    }

    /** [document] 전체를 [level] 단계로 그리는 영역. */
    fun wholeRegion(document: PixelSize, level: Int): PreviewRegion {
        requireLevel(level)
        return PreviewRegion(level, PixelRect.of(document))
    }

    /**
     * [rect]를 담는, [level] 단계로 그릴 영역. 문서 밖은 자르고 단계 격자에 맞춰 넓힌다.
     * 문서와 겹치는 곳이 없으면 null.
     */
    fun region(document: PixelSize, level: Int, rect: PixelRect): PreviewRegion? {
        requireLevel(level)
        val inside = rect.intersect(PixelRect.of(document)) ?: return null
        val grid = maxOf(TILE_SIZE, 1 shl level)
        return PreviewRegion(
            level,
            PixelRect(
                left = inside.left / grid * grid,
                top = inside.top / grid * grid,
                right = minOf(ceilTo(inside.right, grid), document.width),
                bottom = minOf(ceilTo(inside.bottom, grid), document.height)
            )
        )
    }

    /** [document] 크기 문서를 [level] 단계로 줄일 때의 타일 묶음들. 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다. */
    fun blocks(document: PixelSize, level: Int): List<PreviewBlock> = blocks(wholeRegion(document, level))

    /**
     * [region]을 그릴 때의 타일 묶음들. 영역의 왼쪽 위부터 [PREVIEW_BLOCK_TILES]×[PREVIEW_BLOCK_TILES] 타일씩 나눈다.
     * 축소본 안 자리는 영역 축소본의 왼쪽 위를 기준으로 한다.
     */
    fun blocks(region: PreviewRegion): List<PreviewBlock> {
        val level = region.level
        val rect = region.rect
        val blocks = ArrayList<PreviewBlock>()
        // 영역의 왼쪽·위는 격자(타일 크기 이상)에 맞춰져 있어, 묶음도 타일 경계에서 시작한다
        for (top in rect.top until rect.bottom step PREVIEW_BLOCK_SIZE) {
            for (left in rect.left until rect.right step PREVIEW_BLOCK_SIZE) {
                val right = minOf(left + PREVIEW_BLOCK_SIZE, rect.right)
                val bottom = minOf(top + PREVIEW_BLOCK_SIZE, rect.bottom)
                blocks += PreviewBlock(
                    firstColumn = left / TILE_SIZE,
                    firstRow = top / TILE_SIZE,
                    columns = (right - left + TILE_SIZE - 1) / TILE_SIZE,
                    rows = (bottom - top + TILE_SIZE - 1) / TILE_SIZE,
                    left = left,
                    top = top,
                    width = right - left,
                    height = bottom - top,
                    previewLeft = (left shr level) - region.previewLeft,
                    previewTop = (top shr level) - region.previewTop,
                    previewWidth = shrink(right, level) - (left shr level),
                    previewHeight = shrink(bottom, level) - (top shr level)
                )
            }
        }
        return blocks
    }

    /**
     * 화면 배율 [scale]로 문서의 [visible] 영역을 볼 때, 선명하게 다시 그릴 영역.
     * 전체 축소본(단계 [baseLevel])으로 충분하면(화면에 맞춘 정도면) null이다.
     *
     * 보이는 영역에 가로·세로 1/8씩 여유를 두어, 조금 옮겨도 다시 그리지 않게 한다.
     * 그린 축소본의 한 변이 [MAX_PREVIEW_EDGE]를 넘으면 한 단계 거칠게 그린다.
     */
    fun detailRegion(document: PixelSize, baseLevel: Int, scale: Double, visible: PixelRect): PreviewRegion? {
        var level = levelForScale(scale, DETAIL_MAX_MAGNIFICATION)
        if (level >= baseLevel) return null
        val inside = visible.intersect(PixelRect.of(document)) ?: return null
        val marginX = inside.width / 8
        val marginY = inside.height / 8
        val wanted = PixelRect(inside.left - marginX, inside.top - marginY, inside.right + marginX, inside.bottom + marginY)
        while (level < baseLevel) {
            val region = region(document, level, wanted) ?: return null
            if (region.previewWidth <= MAX_PREVIEW_EDGE && region.previewHeight <= MAX_PREVIEW_EDGE) return region
            level++
        }
        return null
    }

    /**
     * 지금 있는 부분 그림 [current]를 새로 계획한 [planned] 대신 그대로 써도 되는지.
     * 같은 단계이고, 문서 안에서 보이는 곳([visible])을 모두 덮으면 다시 그리지 않는다.
     */
    fun canReuse(current: PreviewRegion, planned: PreviewRegion, document: PixelSize, visible: PixelRect): Boolean {
        if (current.level != planned.level) return false
        val inside = visible.intersect(PixelRect.of(document)) ?: return true
        return inside in current.rect
    }

    private fun requireLevel(level: Int) {
        require(level in 0..MAX_PREVIEW_LEVEL) { "Preview level out of range: $level" }
    }

    private fun longEdge(size: PixelSize): Int = maxOf(size.width, size.height)

    /** [value]를 [grid]의 배수로 올림한다. */
    private fun ceilTo(value: Int, grid: Int): Int = (value + grid - 1) / grid * grid
}

/** [value]를 1/2^[level]로 줄인다. 나머지는 올림한다. */
private fun shrink(value: Int, level: Int): Int = ((value.toLong() + (1L shl level) - 1) shr level).toInt()

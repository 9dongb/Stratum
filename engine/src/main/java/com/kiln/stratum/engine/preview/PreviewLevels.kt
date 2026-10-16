package com.kiln.stratum.engine.preview

import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid

/** 축소본의 긴 변 최대 픽셀. 대부분의 GPU가 문제없이 다루는 텍스처 크기다. */
const val MAX_PREVIEW_EDGE = 4096

/** 축소본을 그릴 때 한 번에 다루는 타일 묶음의 한 변(타일 수). 8×8 타일은 2048px이고, 한 번에 GPU에 올리는 양이 약 16MB다. */
const val PREVIEW_BLOCK_TILES = 8

/** 묶음 한 변의 픽셀 수. */
private const val PREVIEW_BLOCK_SIZE = PREVIEW_BLOCK_TILES * TILE_SIZE

/** 가장 많이 줄이는 단계. 묶음 하나(2048px)가 1픽셀이 되는 1/2048이다. */
const val MAX_PREVIEW_LEVEL = 11

/**
 * 축소본에서 타일 묶음 하나가 놓이는 자리.
 *
 * 묶음은 문서의 타일 [firstColumn]열·[firstRow]행부터 [columns]×[rows]개 타일이다.
 * [left]·[top]·[width]·[height]는 문서 안 사각형(픽셀)이고, `preview*`는 축소본 안 사각형이다.
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
 * 화면용 축소본의 크기를 정한다.
 *
 * 단계 k의 축소본은 문서의 1/2^k 크기다. 반씩만 줄이면 GPU의 1/2 축소가 정확히 2×2 평균이라
 * 화질이 고르고, 묶음 경계가 정수 픽셀에 맞아 이음매가 생기지 않는다.
 */
object PreviewLevels {

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
        var level = 0
        // 한 단계 더 줄여도 화면에 보이는 크기 이상이면 더 줄인다
        while (level < MAX_PREVIEW_LEVEL && fitScale * (1L shl (level + 1)) <= 1.0) level++
        while (level < MAX_PREVIEW_LEVEL && longEdge(previewSize(document, level)) > MAX_PREVIEW_EDGE) level++
        return level
    }

    /** [level] 단계 축소본의 크기. 나머지는 올림해서 끝 픽셀도 남긴다. */
    fun previewSize(document: PixelSize, level: Int): PixelSize {
        require(level in 0..MAX_PREVIEW_LEVEL) { "Preview level out of range: $level" }
        return PixelSize(shrink(document.width, level), shrink(document.height, level))
    }

    /** [document] 크기 문서를 [level] 단계로 줄일 때의 타일 묶음들. 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다. */
    fun blocks(document: PixelSize, level: Int): List<PreviewBlock> {
        require(level in 0..MAX_PREVIEW_LEVEL) { "Preview level out of range: $level" }
        val grid = TileGrid(document.width, document.height)
        val blockColumns = (grid.columns + PREVIEW_BLOCK_TILES - 1) / PREVIEW_BLOCK_TILES
        val blockRows = (grid.rows + PREVIEW_BLOCK_TILES - 1) / PREVIEW_BLOCK_TILES
        return List(blockColumns * blockRows) { index ->
            val blockColumn = index % blockColumns
            val blockRow = index / blockColumns
            val left = blockColumn * PREVIEW_BLOCK_SIZE
            val top = blockRow * PREVIEW_BLOCK_SIZE
            val width = minOf(PREVIEW_BLOCK_SIZE, document.width - left)
            val height = minOf(PREVIEW_BLOCK_SIZE, document.height - top)
            // 묶음의 왼쪽·위는 2048의 배수라 1/2^11까지 정수로 나누어떨어진다
            val previewLeft = left shr level
            val previewTop = top shr level
            PreviewBlock(
                firstColumn = blockColumn * PREVIEW_BLOCK_TILES,
                firstRow = blockRow * PREVIEW_BLOCK_TILES,
                columns = minOf(PREVIEW_BLOCK_TILES, grid.columns - blockColumn * PREVIEW_BLOCK_TILES),
                rows = minOf(PREVIEW_BLOCK_TILES, grid.rows - blockRow * PREVIEW_BLOCK_TILES),
                left = left,
                top = top,
                width = width,
                height = height,
                previewLeft = previewLeft,
                previewTop = previewTop,
                previewWidth = shrink(left + width, level) - previewLeft,
                previewHeight = shrink(top + height, level) - previewTop
            )
        }
    }

    private fun longEdge(size: PixelSize): Int = maxOf(size.width, size.height)

    /** [value]를 1/2^[level]로 줄인다. 나머지는 올림한다. */
    private fun shrink(value: Int, level: Int): Int = ((value.toLong() + (1L shl level) - 1) shr level).toInt()
}

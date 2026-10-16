package com.kiln.stratum.engine.raster

/** 캔버스 안에서 타일 한 장이 차지하는 사각형(픽셀). 오른쪽·아래 끝 타일은 캔버스 밖 부분을 잘라 낸 크기다. */
data class TileBounds(val left: Int, val top: Int, val width: Int, val height: Int)

/** [width]×[height] 캔버스를 [TILE_SIZE] 타일로 나눈 배치. */
class TileGrid(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "Canvas size must be positive: ${width}x$height" }
    }

    /** 가로 타일 수. 끝에 남는 픽셀도 타일 한 장을 차지한다. */
    val columns: Int = (width + TILE_SIZE - 1) / TILE_SIZE

    /** 세로 타일 수. */
    val rows: Int = (height + TILE_SIZE - 1) / TILE_SIZE

    /** 캔버스를 덮는 모든 타일 자리. 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다. */
    fun keys(): List<TileKey> =
        List(columns * rows) { index -> TileKey(column = index % columns, row = index / columns) }

    /** [key] 타일이 캔버스 안에서 차지하는 사각형. */
    fun bounds(key: TileKey): TileBounds {
        require(key.column < columns && key.row < rows) { "Tile outside grid: $key in ${columns}x$rows" }
        val left = key.column * TILE_SIZE
        val top = key.row * TILE_SIZE
        return TileBounds(left, top, minOf(TILE_SIZE, width - left), minOf(TILE_SIZE, height - top))
    }
}

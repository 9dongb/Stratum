package com.kiln.stratum.engine.raster

/** 캔버스 안에서 타일 한 장이 차지하는 사각형(픽셀). 오른쪽·아래 끝 타일은 캔버스 밖 부분을 잘라 낸 크기다. */
data class TileBounds(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * [width]×[height] 캔버스를 [TILE_SIZE] 타일로 나눈 배치. 레이어 타일은 캔버스 밖 자리에도 있을 수 있다(결정 기록 '캔버스 밖 픽셀').
 * 캔버스 밖 자리는 [contains]가 거짓이고, [bounds]는 캔버스 밖 자리에서 실수를 바로 드러내게 예외를 던진다.
 */
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

    /** [key]가 캔버스를 덮는 자리인지 */
    fun contains(key: TileKey): Boolean = key.column in 0 until columns && key.row in 0 until rows

    /** [key] 타일이 캔버스 안에서 차지하는 사각형. 캔버스 밖 자리면 예외를 던진다. */
    fun bounds(key: TileKey): TileBounds {
        require(contains(key)) { "Tile outside grid: $key in ${columns}x$rows" }
        val left = key.column * TILE_SIZE
        val top = key.row * TILE_SIZE
        return TileBounds(left, top, minOf(TILE_SIZE, width - left), minOf(TILE_SIZE, height - top))
    }

    /** [key] 타일이 캔버스 안에서 차지하는 사각형(문서 좌표). 캔버스 밖 자리면 null. */
    fun canvasRect(key: TileKey): PixelRect? {
        if (!contains(key)) return null
        val bounds = bounds(key)
        return PixelRect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
    }

    /**
     * 한 가지 색 타일이 [key] 자리에서 담는 크기: 캔버스 안 자리는 캔버스 안쪽 크기(끝 자리는 잘린 크기), 캔버스 밖 자리는 꽉 찬 타일.
     * 한 가지 색 새 캔버스·불러온 한 가지 색 타일이 이 크기라, 옮겨도 캔버스 밖에 없던 색이 새어 나오지 않는다.
     */
    fun solidShape(key: TileKey): PixelSize =
        if (contains(key)) bounds(key).let { PixelSize(it.width, it.height) } else PixelSize(TILE_SIZE, TILE_SIZE)

    /**
     * 편집이 [key] 자리 타일을 다시 만들 때의 크기: 캔버스 안 자리는 캔버스 안쪽 크기와 옛 타일([old]) 크기 중 큰 쪽, 캔버스 밖 자리는 옛 타일 크기.
     * 편집은 캔버스 안쪽만 고치고 그 밖은 옛 타일 그대로 둔다(끝 타일이 담은 캔버스 밖 픽셀을 지우지 않게, 결정 기록 '캔버스 밖 픽셀').
     */
    fun rebuildSize(key: TileKey, old: Tile?): PixelSize {
        if (!contains(key)) {
            requireNotNull(old) { "Nothing to rebuild outside the grid: $key" }
            return PixelSize(old.width, old.height)
        }
        val inside = bounds(key)
        return PixelSize(maxOf(inside.width, old?.width ?: 0), maxOf(inside.height, old?.height ?: 0))
    }

    /** 마스크 타일 [old]를 [key] 자리에서 다시 만들 때의 크기([rebuildSize]와 같은 규칙, [MaskTile.FULL]은 꽉 찬 타일) */
    fun rebuildSize(key: TileKey, old: com.kiln.stratum.engine.selection.MaskTile?): PixelSize {
        if (!contains(key)) {
            requireNotNull(old) { "Nothing to rebuild outside the grid: $key" }
            return PixelSize(old.width, old.height)
        }
        val inside = bounds(key)
        return PixelSize(maxOf(inside.width, old?.width ?: 0), maxOf(inside.height, old?.height ?: 0))
    }
}

/** 문서 좌표 ([x], [y]) 픽셀이 있는 타일 자리(캔버스 밖 포함, 내림 나눗셈) */
fun tileAt(x: Int, y: Int): TileKey = TileKey(Math.floorDiv(x, TILE_SIZE), Math.floorDiv(y, TILE_SIZE))

/** [key] 자리 꽉 찬 타일의 사각형(문서 좌표) */
fun tileSquare(key: TileKey): PixelRect {
    val left = key.column * TILE_SIZE
    val top = key.row * TILE_SIZE
    return PixelRect(left, top, left + TILE_SIZE, top + TILE_SIZE)
}

/** 문서 좌표 [rect]에 걸친 타일 자리들(캔버스 밖 포함, 내림 나눗셈). 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다. 빈 사각형이면 비었다. */
fun tileKeysIn(rect: PixelRect): List<TileKey> {
    if (rect.width == 0 || rect.height == 0) return emptyList()
    val first = tileAt(rect.left, rect.top)
    val last = tileAt(rect.right - 1, rect.bottom - 1)
    val keys = ArrayList<TileKey>((last.column - first.column + 1) * (last.row - first.row + 1))
    for (row in first.row..last.row) {
        for (column in first.column..last.column) keys += TileKey(column, row)
    }
    return keys
}

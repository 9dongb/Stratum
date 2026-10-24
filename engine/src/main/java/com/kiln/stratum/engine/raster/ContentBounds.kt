package com.kiln.stratum.engine.raster

/**
 * 레이어 내용(알파가 0이 아닌 픽셀)을 모두 담는 가장 작은 사각형(문서 좌표, 캔버스 밖 포함). 모두 투명하면 null.
 * 레이어 돌리기·뒤집기(사용자 피드백 12차 2)의 축(이 사각형의 가운데)을 정한다. 캔버스 밖으로 밀려난 픽셀도 레이어 내용이다(포토샵의 레이어 범위).
 *
 * 빈 타일은 건너뛰고, 가장 바깥 타일 줄(왼쪽·오른쪽 열, 위·아래 행)부터 안쪽으로 그 줄의 타일만 읽다가 보이는 픽셀이 있는 줄에서 멈춘다.
 * 한 가지 색 타일은 읽지 않고, 타일 안에서도 바깥에서 안으로 읽다가 처음 보이는 픽셀에서 멈춘다. 그래서 사진처럼 꽉 찬 레이어는
 * 바깥 타일들의 가장자리 픽셀만 보고 끝나고, 큰 사진을 다 돌지 않는다(결정 기록 '레이어 돌리기·뒤집기').
 * 타일마다 담은 크기만 읽는다. [checkCanceled]는 타일을 읽을 때마다 부른다.
 */
fun Raster.contentBounds(checkCanceled: () -> Unit = {}): PixelRect? {
    val keys = tiles.keys
    if (keys.isEmpty()) return null
    // 타일마다 보이는 픽셀을 담는 사각형(타일 안 좌표). 바깥 줄을 볼 때 한 번씩만 읽는다.
    val found = HashMap<TileKey, PixelRect?>()
    fun opaque(key: TileKey): PixelRect? {
        if (found.containsKey(key)) return found[key]
        checkCanceled()
        return tiles.getValue(key).opaqueBounds().also { found[key] = it }
    }
    val columns = keys.groupBy { it.column }.toSortedMap()
    val rows = keys.groupBy { it.row }.toSortedMap()
    val left = columns.entries.firstNotNullOfOrNull { (column, inColumn) ->
        inColumn.mapNotNull { opaque(it)?.left }.minOrNull()?.let { column * TILE_SIZE + it }
    } ?: return null
    val right = columns.entries.reversed().firstNotNullOf { (column, inColumn) ->
        inColumn.mapNotNull { opaque(it)?.right }.maxOrNull()?.let { column * TILE_SIZE + it }
    }
    val top = rows.entries.firstNotNullOf { (row, inRow) ->
        inRow.mapNotNull { opaque(it)?.top }.minOrNull()?.let { row * TILE_SIZE + it }
    }
    val bottom = rows.entries.reversed().firstNotNullOf { (row, inRow) ->
        inRow.mapNotNull { opaque(it)?.bottom }.maxOrNull()?.let { row * TILE_SIZE + it }
    }
    return PixelRect(left, top, right, bottom)
}

/**
 * [width]×[height] 안에서 [opaque]인 픽셀을 모두 담는 가장 작은 사각형. 없으면 null.
 * 위·아래 줄, 왼쪽·오른쪽 열 순서로 바깥에서 안으로 읽다가 처음 보이는 픽셀에서 멈춘다.
 */
internal inline fun opaqueRect(width: Int, height: Int, opaque: (x: Int, y: Int) -> Boolean): PixelRect? {
    var top = -1
    rows@ for (y in 0 until height) {
        for (x in 0 until width) {
            if (opaque(x, y)) {
                top = y
                break@rows
            }
        }
    }
    if (top < 0) return null
    var bottom = top
    rows@ for (y in height - 1 downTo top) {
        for (x in 0 until width) {
            if (opaque(x, y)) {
                bottom = y
                break@rows
            }
        }
    }
    var left = 0
    columns@ for (x in 0 until width) {
        for (y in top..bottom) {
            if (opaque(x, y)) {
                left = x
                break@columns
            }
        }
    }
    var right = left
    columns@ for (x in width - 1 downTo left) {
        for (y in top..bottom) {
            if (opaque(x, y)) {
                right = x
                break@columns
            }
        }
    }
    return PixelRect(left, top, right + 1, bottom + 1)
}

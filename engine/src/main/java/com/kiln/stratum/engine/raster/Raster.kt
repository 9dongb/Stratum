package com.kiln.stratum.engine.raster

/**
 * 레이어 하나의 픽셀을 타일 단위로 담는다. 타일이 없는 자리는 완전 투명이다.
 *
 * 한 번 만들면 바꾸지 않고, [withTiles]로 바뀐 타일만 교체한 새 래스터를 만든다.
 * 바뀌지 않은 타일은 같은 객체를 함께 쓰므로, 이전 상태를 보관해도 메모리는 바뀐 타일만큼만 든다.
 * 비교(equals)는 같은 객체인지만 본다.
 *
 * 크기는 래스터가 아니라 문서가 관리한다. 타일은 캔버스 밖 자리에도 있을 수 있고, 타일마다 담은 픽셀은 모두 진짜 내용이다
 * (결정 기록 '캔버스 밖 픽셀'). 그리기·내보내기처럼 캔버스 안만 보는 쪽이 캔버스 안쪽만 읽는다.
 */
class Raster private constructor(
    /** 저장된 타일들. 여기에 없는 자리는 투명이다. */
    val tiles: Map<TileKey, Tile>
) {

    /**
     * [changes]의 타일로 바꾼 새 래스터를 돌려준다. 값이 `null`인 자리는 비운다(투명).
     * 바뀌는 것이 없으면 자기 자신을 그대로 돌려준다.
     */
    fun withTiles(changes: Map<TileKey, Tile?>): Raster {
        if (changes.all { (key, tile) -> tiles[key] === tile }) return this
        val updated = tiles.toMutableMap()
        for ((key, tile) in changes) {
            if (tile == null) updated.remove(key) else updated[key] = tile
        }
        return Raster(updated)
    }

    /** [rect](픽셀 좌표, 캔버스 밖 포함)에 걸친 타일이 하나라도 있는지. 없으면 그 영역은 완전 투명이다. */
    fun hasTilesIn(rect: PixelRect): Boolean = tileKeysIn(rect).any { tiles.containsKey(it) }

    /** [key] 자리 타일이 담은 사각형(문서 좌표). 그 자리에 타일이 없으면 null. */
    fun tileRect(key: TileKey): PixelRect? {
        val tile = tiles[key] ?: return null
        val left = key.column * TILE_SIZE
        val top = key.row * TILE_SIZE
        return PixelRect(left, top, left + tile.width, top + tile.height)
    }

    /** 담은 타일들의 사각형을 모두 담는 사각형(문서 좌표, 타일이 담은 크기 단위의 어림). 타일이 없으면 null. */
    fun extent(): PixelRect? {
        if (tiles.isEmpty()) return null
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (key in tiles.keys) {
            val rect = tileRect(key)!!
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
        }
        return PixelRect(left, top, right, bottom)
    }

    /** 캔버스([grid]) 밖에 담은 픽셀이 있는지: 캔버스 밖 자리의 타일, 또는 끝 자리에서 캔버스 안쪽보다 크게 담은 타일 */
    fun hasOffCanvas(grid: TileGrid): Boolean =
        tiles.any { (key, tile) ->
            val inside = grid.canvasRect(key) ?: return@any true
            tile.width > inside.width || tile.height > inside.height
        }

    companion object {
        /** 타일이 하나도 없는(완전 투명한) 래스터. */
        val EMPTY = Raster(emptyMap())

        /**
         * [grid]의 모든 자리에 [tile](한 가지 색의 꽉 찬 타일)을 놓은 래스터. 한 가지 색으로 채운 새 캔버스에 쓴다.
         * 오른쪽·아래 끝 자리는 캔버스 안쪽 크기로 자른 타일이다([TileGrid.solidShape], 결정 기록 '캔버스 밖 픽셀'). 크기마다 한 장이라
         * 타일 몇 장만 메모리를 쓰고, 나중에 칠하면 그 자리 타일만 새로 생긴다. 자를 메모리가 모자라면 [OutOfMemoryError]를 던진다.
         */
        fun filled(grid: TileGrid, tile: Tile): Raster {
            val shapes = HashMap<PixelSize, Tile>()
            return Raster(grid.keys().associateWith { key -> grid.solidShape(key).let { shapes.getOrPut(it) { tile.cropped(it.width, it.height) } } })
        }
    }
}

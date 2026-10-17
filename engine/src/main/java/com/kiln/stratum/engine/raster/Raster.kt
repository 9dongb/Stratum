package com.kiln.stratum.engine.raster

/**
 * 레이어 하나의 픽셀을 타일 단위로 담는다. 타일이 없는 자리는 완전 투명이다.
 *
 * 한 번 만들면 바꾸지 않고, [withTiles]로 바뀐 타일만 교체한 새 래스터를 만든다.
 * 바뀌지 않은 타일은 같은 객체를 함께 쓰므로, 이전 상태를 보관해도 메모리는 바뀐 타일만큼만 든다.
 * 비교(equals)는 같은 객체인지만 본다.
 *
 * 크기는 래스터가 아니라 문서가 관리한다. 오른쪽·아래 끝 타일에서 캔버스 밖 부분은 쓰지 않는다.
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

    /** [rect](픽셀 좌표)에 걸친 타일이 하나라도 있는지. 없으면 그 영역은 완전 투명이다. */
    fun hasTilesIn(rect: PixelRect): Boolean {
        if (rect.width == 0 || rect.height == 0) return false
        for (row in rect.top / TILE_SIZE..(rect.bottom - 1) / TILE_SIZE) {
            for (column in rect.left / TILE_SIZE..(rect.right - 1) / TILE_SIZE) {
                if (tiles.containsKey(TileKey(column, row))) return true
            }
        }
        return false
    }

    companion object {
        /** 타일이 하나도 없는(완전 투명한) 래스터. */
        val EMPTY = Raster(emptyMap())

        /**
         * [grid]의 모든 자리에 같은 [tile] 객체를 놓은 래스터. 한 가지 색으로 채운 새 캔버스에 쓴다.
         * 타일 한 장만 메모리를 쓰고, 나중에 칠하면 그 자리 타일만 새로 생긴다.
         * 오른쪽·아래 끝 자리에도 꽉 찬 타일이 놓이고, 캔버스 밖 부분은 쓰지 않는다.
         */
        fun filled(grid: TileGrid, tile: Tile): Raster = Raster(grid.keys().associateWith { tile })
    }
}

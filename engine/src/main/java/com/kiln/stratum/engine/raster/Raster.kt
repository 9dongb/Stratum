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

    companion object {
        /** 타일이 하나도 없는(완전 투명한) 래스터. */
        val EMPTY = Raster(emptyMap())
    }
}

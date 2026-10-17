package com.kiln.stratum.engine.selection

import com.kiln.stratum.engine.raster.TileKey

/** 새로 그린 모양을 지금 선택 영역과 합치는 방법 */
enum class SelectionMode {
    /** 새 선택: 앞 선택 영역을 버리고 모양만 고른다 */
    REPLACE,

    /** 더하기: 앞 선택 영역에 모양을 더한다 */
    ADD,

    /** 빼기: 앞 선택 영역에서 모양을 뺀다 */
    SUBTRACT,

    /** 교차: 앞 선택 영역과 모양이 겹치는 곳만 남긴다 */
    INTERSECT
}

/**
 * 픽셀을 가진 선택 영역 타일끼리의 계산. 알파 비트맵을 그려 합치는 플랫폼 쪽이 맡는다(픽셀을 Kotlin으로 하나씩 돌지 않는다).
 * 넘기는 타일은 [MaskTile.FULL]이 아니다. 결과가 모두 0이면 null, 모두 255면 [MaskTile.FULL]을 돌려준다.
 */
interface MaskTileOps {
    /** 합: a + b - ab(둘 중 하나라도 고른 곳) */
    fun union(a: MaskTile, b: MaskTile): MaskTile?

    /** 빼기: a × (1 - b) */
    fun subtract(a: MaskTile, b: MaskTile): MaskTile?

    /** 곱: a × b */
    fun intersect(a: MaskTile, b: MaskTile): MaskTile?

    /** 뒤집기: 1 - a */
    fun invert(a: MaskTile): MaskTile?
}

/**
 * [old] 선택 영역에 새 모양 [shape](타일마다의 알파, 없는 자리는 0)를 [mode]로 합친다.
 * 다 고른 타일·빈 자리는 계산 없이 정하고, 둘 다 픽셀을 가진 타일만 [ops]로 그린다.
 */
fun combineSelection(old: Selection, shape: Map<TileKey, MaskTile>, mode: SelectionMode, ops: MaskTileOps): Selection =
    when (mode) {
        SelectionMode.REPLACE -> Selection.of(shape)
        SelectionMode.ADD -> old.withTiles(
            shape.mapValues { (key, tile) ->
                val before = old.tiles[key]
                when {
                    before == null -> tile
                    before.isFull || tile.isFull -> MaskTile.FULL
                    else -> ops.union(before, tile) ?: before
                }
            }
        )
        SelectionMode.SUBTRACT -> old.withTiles(
            shape.mapNotNull { (key, tile) ->
                val before = old.tiles[key] ?: return@mapNotNull null
                key to when {
                    tile.isFull -> null
                    before.isFull -> ops.invert(tile)
                    else -> ops.subtract(before, tile)
                }
            }.toMap()
        )
        SelectionMode.INTERSECT -> Selection.of(
            old.tiles.mapNotNull { (key, before) ->
                val tile = shape[key] ?: return@mapNotNull null
                val kept = when {
                    before.isFull -> tile
                    tile.isFull -> before
                    else -> ops.intersect(before, tile)
                }
                kept?.let { key to it }
            }.toMap()
        )
    }

package com.kiln.stratum.engine.selection

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileAt

/**
 * 선택 영역 타일 한 장의 알파(0~255)가 놓이는 곳. 한 번 만들면 바꾸지 않는다.
 * 앱에서는 힙 밖 알파 비트맵(platform 패키지), 테스트에서는 배열([HeapMaskPixels])에 담는다.
 */
interface MaskPixels {
    /** 타일 안 좌표([x], [y])의 알파. 좌표는 담은 크기([width]×[height]) 안이다. */
    fun alphaAt(x: Int, y: Int): Int

    /** 담은 크기(왼쪽 위부터, 1~[TILE_SIZE]). 기본은 꽉 찬 타일이다. 그 밖은 0(가림)이다. */
    val width: Int
        get() = TILE_SIZE

    val height: Int
        get() = TILE_SIZE

    /** 차지하는 메모리(바이트). 기본은 타일 한 장(픽셀당 1바이트)이다. */
    val byteCount: Int
        get() = TILE_PIXEL_COUNT
}

/** 자바 힙의 배열에 담는 알파. 테스트와 작은 데이터용이다. 받은 배열을 복사해 둔다. */
internal class HeapMaskPixels(alphas: ByteArray) : MaskPixels {
    private val alphas = alphas.copyOf()

    init {
        require(alphas.size == TILE_PIXEL_COUNT) { "Mask tile needs $TILE_PIXEL_COUNT values, got ${alphas.size}" }
    }

    override fun alphaAt(x: Int, y: Int): Int = alphas[y * TILE_SIZE + x].toInt() and 0xFF

    /** 알파 배열의 복사본. 그릴 때 잠깐 비트맵으로 바꾸는 데 쓴다. */
    fun toByteArray(): ByteArray = alphas.copyOf()
}

/**
 * 선택 영역 타일 한 장. 다 고른 타일은 픽셀 없이 [FULL] 하나를 함께 쓴다. 비교(equals)는 같은 객체인지만 본다.
 */
class MaskTile private constructor(internal val pixels: MaskPixels?) {

    /** 타일 전체(꽉 찬 타일 자리 모두)를 다 골랐는지 */
    val isFull: Boolean
        get() = pixels == null

    /** 담은 크기(왼쪽 위부터). [FULL]은 꽉 찬 타일이다. 그 밖은 0(가림)이다. */
    val width: Int
        get() = pixels?.width ?: TILE_SIZE

    val height: Int
        get() = pixels?.height ?: TILE_SIZE

    /** 차지하는 메모리(바이트). [FULL]은 0이다. */
    val byteCount: Int
        get() = pixels?.byteCount ?: 0

    /** 타일 안 좌표([x], [y])의 알파. 담은 크기 밖이면 0이다. */
    fun alphaAt(x: Int, y: Int): Int {
        require(x in 0 until TILE_SIZE && y in 0 until TILE_SIZE) { "Pixel outside tile: ($x, $y)" }
        val stored = pixels ?: return 255
        return if (x < stored.width && y < stored.height) stored.alphaAt(x, y) else 0
    }

    companion object {
        /** 다 고른 타일. 모든 자리가 이 객체 하나를 함께 쓴다. */
        val FULL = MaskTile(null)

        /** 이미 만들어 둔 [pixels]로 타일을 만든다. [pixels]는 이후에 바뀌면 안 된다. */
        fun of(pixels: MaskPixels): MaskTile = MaskTile(pixels)

        /** [alphas]를 복사해 타일을 만든다(테스트). 한 줄씩 위에서 아래로 놓인 [TILE_PIXEL_COUNT]개다. */
        fun of(alphas: ByteArray): MaskTile = MaskTile(HeapMaskPixels(alphas))
    }
}

/**
 * 선택 영역: 문서 크기의 알파 마스크. 0은 고르지 않음, 255는 다 고름, 그 사이는 반쯤 고름(부드러운 가장자리)이다.
 *
 * 한 번 만들면 바꾸지 않고, 바뀐 타일만 바꾼 새 선택 영역을 만든다([withTiles]). 실행 취소 기록이 앞 상태와 타일을 함께 쓴다.
 * 타일이 없는 자리는 0이고, [MaskTile.FULL] 자리는 255다. 경계가 지나가는 타일만 픽셀을 가진다.
 * 비교(equals)는 같은 객체인지만 본다. 오른쪽·아래 끝 타일에서 문서 밖 부분은 읽지 않는다.
 */
class Selection private constructor(val tiles: Map<TileKey, MaskTile>) {

    /** 고른 픽셀이 하나도 없는지 */
    val isEmpty: Boolean
        get() = tiles.isEmpty()

    /** 픽셀을 가진 타일들의 메모리(바이트). 다 고른 타일은 세지 않는다. */
    val byteCount: Long
        get() = tiles.values.distinct().sumOf { it.byteCount.toLong() }

    /** 문서 좌표 ([x], [y])의 알파(캔버스 밖 자리도, 레이어 마스크는 캔버스 밖 픽셀 자리에 타일이 있을 수 있다) */
    fun alphaAt(x: Int, y: Int): Int {
        val tile = tiles[tileAt(x, y)] ?: return 0
        return tile.alphaAt(Math.floorMod(x, TILE_SIZE), Math.floorMod(y, TILE_SIZE))
    }

    /** 고른 타일들을 담는 사각형(문서 [size] 안). 비었으면 null. 타일 단위라 실제로 고른 곳보다 넓을 수 있다. */
    fun tileBounds(size: PixelSize): PixelRect? {
        if (tiles.isEmpty()) return null
        val left = tiles.keys.minOf { it.column } * TILE_SIZE
        val top = tiles.keys.minOf { it.row } * TILE_SIZE
        val right = (tiles.keys.maxOf { it.column } + 1) * TILE_SIZE
        val bottom = (tiles.keys.maxOf { it.row } + 1) * TILE_SIZE
        return PixelRect(left, top, right, bottom).intersect(PixelRect.of(size))
    }

    /** [changes]의 타일로 바꾼 새 선택 영역. 값이 null인 자리는 비운다(0). 바뀌는 것이 없으면 자기 자신이다. */
    fun withTiles(changes: Map<TileKey, MaskTile?>): Selection {
        if (changes.all { (key, tile) -> tiles[key] === tile }) return this
        val updated = tiles.toMutableMap()
        for ((key, tile) in changes) {
            if (tile == null) updated.remove(key) else updated[key] = tile
        }
        return Selection(updated)
    }

    companion object {
        /** 아무것도 고르지 않은 선택 영역 */
        val EMPTY = Selection(emptyMap())

        /** 문서 [size] 전체를 고른 선택 영역(모두 선택) */
        fun all(size: PixelSize): Selection = Selection(TileGrid(size.width, size.height).keys().associateWith { MaskTile.FULL })

        /** [tiles]로 만든다. 문서 밖 자리는 넣지 않는다. */
        fun of(tiles: Map<TileKey, MaskTile>): Selection = if (tiles.isEmpty()) EMPTY else Selection(tiles.toMap())
    }
}

package com.kiln.stratum.engine.raster

/** 타일 한 변의 픽셀 수. 프로젝트 파일 형식에 들어가기 전까지만 바꿀 수 있다. */
const val TILE_SIZE = 256

/** 타일 한 장이 가진 픽셀 수. */
const val TILE_PIXEL_COUNT = TILE_SIZE * TILE_SIZE

/** 캔버스를 타일로 나눴을 때 타일 한 장의 위치(열, 행). */
data class TileKey(val column: Int, val row: Int) {
    init {
        require(column >= 0 && row >= 0) { "Tile key must not be negative: ($column, $row)" }
    }
}

/**
 * 가로·세로 [TILE_SIZE] 픽셀짜리 타일 한 장.
 *
 * 한 번 만들면 바꾸지 않는다. 여러 문서 상태가 같은 타일 객체를 함께 쓰므로,
 * 픽셀을 바꾸려면 새 타일을 만든다. 비교(equals)는 같은 객체인지만 본다.
 *
 * 픽셀은 [pixels]에 담는다. [pixelAt]이 돌려주는 형식은 `0xAARRGGBB`이고 알파를 미리 곱하지 않는다.
 * 힙 밖 비트맵은 알파를 미리 곱한 형식으로 저장하므로, 반투명 픽셀은 읽을 때 반올림 차이가 날 수 있다.
 */
class Tile private constructor(internal val pixels: TilePixels) {

    /** 타일 안 좌표([x], [y])의 픽셀. */
    fun pixelAt(x: Int, y: Int): Int {
        require(x in 0 until TILE_SIZE && y in 0 until TILE_SIZE) { "Pixel outside tile: ($x, $y)" }
        return pixels.pixelAt(x, y)
    }

    companion object {
        /** [pixels]를 복사해서 타일을 만든다. 크기는 [TILE_PIXEL_COUNT]이고, 한 줄씩 위에서 아래로 놓인다. */
        fun of(pixels: IntArray): Tile = Tile(HeapTilePixels(pixels))

        /** 힙 밖 비트맵처럼 엔진이 이미 만들어 둔 [pixels]로 타일을 만든다. [pixels]는 이후에 바뀌면 안 된다. */
        internal fun of(pixels: TilePixels): Tile = Tile(pixels)
    }
}

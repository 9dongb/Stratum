package com.kiln.stratum.engine.raster

/**
 * 타일 한 장의 픽셀이 실제로 놓이는 곳. 한 번 만들면 바꾸지 않는다.
 *
 * 자바 힙의 배열([HeapTilePixels])이나, 힙 밖 네이티브 메모리의 안드로이드 비트맵(platform 패키지)에 담는다.
 * 큰 사진의 원본 픽셀은 힙에 다 들어가지 않으므로 힙 밖에 둔다(CLAUDE.md 4장).
 */
interface TilePixels {
    /**
     * 타일 안 좌표([x], [y])의 픽셀. 형식은 `0xAARRGGBB`이고 알파를 미리 곱하지 않는다.
     * 좌표가 0 until [TILE_SIZE] 안인지는 [Tile]이 확인하고 넘긴다.
     */
    fun pixelAt(x: Int, y: Int): Int
}

/** 자바 힙의 배열에 담는 픽셀. 테스트와 작은 데이터용이다. 받은 배열을 복사해 두므로 나중에 바뀌지 않는다. */
internal class HeapTilePixels(pixels: IntArray) : TilePixels {

    private val pixels: IntArray = pixels.copyOf()

    init {
        require(pixels.size == TILE_PIXEL_COUNT) {
            "Tile needs $TILE_PIXEL_COUNT pixels, got ${pixels.size}"
        }
    }

    override fun pixelAt(x: Int, y: Int): Int = pixels[y * TILE_SIZE + x]
}

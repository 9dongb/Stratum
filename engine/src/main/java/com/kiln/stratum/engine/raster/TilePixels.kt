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
     * 좌표가 담은 크기([width]×[height]) 안인지는 [Tile]이 확인하고 넘긴다.
     */
    fun pixelAt(x: Int, y: Int): Int

    /** 담은 크기(왼쪽 위부터, 1~[TILE_SIZE]). 기본은 꽉 찬 타일이다. */
    val width: Int
        get() = TILE_SIZE

    val height: Int
        get() = TILE_SIZE

    /** 픽셀이 차지하는 메모리(바이트). 기본은 꽉 찬 타일 한 장(ARGB 4바이트)이다. */
    val byteCount: Int
        get() = TILE_PIXEL_COUNT * Int.SIZE_BYTES

    /**
     * 타일 안쪽 [width]×[height]에서 알파가 0이 아닌 픽셀을 모두 담는 가장 작은 사각형(타일 안 좌표). 모두 투명하면 null.
     * 기본은 [pixelAt]으로 하나씩 읽는다(힙 타일). 비트맵 타일은 픽셀을 한 번에 꺼내 읽는다.
     */
    fun opaqueBounds(width: Int, height: Int): PixelRect? = opaqueRect(width, height) { x, y -> pixelAt(x, y) ushr 24 != 0 }

    /**
     * 왼쪽 위 [width]×[height]만 담은 픽셀(줄일 때만, [Tile.cropped]). 기본은 [pixelAt]으로 읽어 힙 배열에 담는다(테스트·작은 데이터).
     * 비트맵 타일은 비트맵을 잘라 담는다.
     */
    fun cropped(width: Int, height: Int): TilePixels =
        HeapTilePixels(IntArray(TILE_PIXEL_COUNT) { i -> if (i % TILE_SIZE < width && i / TILE_SIZE < height) pixelAt(i % TILE_SIZE, i / TILE_SIZE) else 0 }, width, height)
}

/**
 * 자바 힙의 배열에 담는 픽셀. 테스트와 작은 데이터용이다. 받은 배열을 복사해 두므로 나중에 바뀌지 않는다.
 * 배열은 늘 꽉 찬 타일 크기이고, 왼쪽 위 [width]×[height]만 담는다(그 밖은 0으로 지운다).
 */
internal class HeapTilePixels(pixels: IntArray, override val width: Int = TILE_SIZE, override val height: Int = TILE_SIZE) : TilePixels {

    private val pixels: IntArray

    init {
        require(pixels.size == TILE_PIXEL_COUNT) {
            "Tile needs $TILE_PIXEL_COUNT pixels, got ${pixels.size}"
        }
        require(width in 1..TILE_SIZE && height in 1..TILE_SIZE) { "Tile size out of range: ${width}x$height" }
        this.pixels = if (width == TILE_SIZE && height == TILE_SIZE) {
            pixels.copyOf()
        } else {
            IntArray(TILE_PIXEL_COUNT) { i -> if (i % TILE_SIZE < width && i / TILE_SIZE < height) pixels[i] else 0 }
        }
    }

    override fun pixelAt(x: Int, y: Int): Int = pixels[y * TILE_SIZE + x]

    /** 픽셀 배열의 복사본(꽉 찬 타일 크기, 담은 크기 밖은 0). 화면용 축소본을 그릴 때 잠깐 비트맵으로 바꾸는 데 쓴다. */
    fun toIntArray(): IntArray = pixels.copyOf()
}

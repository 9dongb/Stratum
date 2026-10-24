package com.kiln.stratum.engine.raster

/** 타일 한 변의 픽셀 수. 프로젝트 파일 형식에 들어가기 전까지만 바꿀 수 있다. */
const val TILE_SIZE = 256

/** 타일 한 장이 가진 픽셀 수. */
const val TILE_PIXEL_COUNT = TILE_SIZE * TILE_SIZE

/**
 * 캔버스를 타일로 나눴을 때 타일 한 장의 위치(열, 행). 캔버스 밖(음수, 열·줄 수 이상)일 수 있다: 이동 도구가 캔버스 밖으로 밀어낸 픽셀도 레이어에 남는다
 * (사용자 피드백 13차 1, 결정 기록 '캔버스 밖 픽셀').
 */
data class TileKey(val column: Int, val row: Int)

/**
 * 가로·세로 [TILE_SIZE] 픽셀 자리의 타일 한 장. 왼쪽 위부터 [width]×[height]만 담고, 그 밖은 투명이다.
 *
 * 담은 픽셀은 모두 진짜 내용이다(투명일 수는 있다). 그래서 캔버스 안 끝 자리 타일은 캔버스 안쪽 크기만 담거나, 캔버스 밖으로 밀려난 픽셀을 담으면
 * 그만큼 더 담는다(결정 기록 '캔버스 밖 픽셀'). 한 번 만들면 바꾸지 않는다. 여러 문서 상태가 같은 타일 객체를 함께 쓰므로,
 * 픽셀을 바꾸려면 새 타일을 만든다. 비교(equals)는 같은 객체인지만 본다.
 *
 * 픽셀은 [pixels]에 담는다. [pixelAt]이 돌려주는 형식은 `0xAARRGGBB`이고 알파를 미리 곱하지 않는다.
 * 힙 밖 비트맵은 알파를 미리 곱한 형식으로 저장하므로, 반투명 픽셀은 읽을 때 반올림 차이가 날 수 있다.
 *
 * @property solidColor 담은 픽셀이 모두 한 가지 색이면 그 색(`0xAARRGGBB`). 모르면 null.
 *   한 가지 색 새 캔버스의 타일이 이렇다. 돌리거나 잘라도 같은 색이라, 새로 만들지 않고 그대로 쓴다(크기가 다르면 잘라 쓴다).
 */
class Tile private constructor(internal val pixels: TilePixels, val solidColor: Int?) {

    /** 담은 가로 크기(1~[TILE_SIZE], 왼쪽부터). 그 오른쪽은 투명이다. */
    val width: Int
        get() = pixels.width

    /** 담은 세로 크기(1~[TILE_SIZE], 위부터). 그 아래는 투명이다. */
    val height: Int
        get() = pixels.height

    /** 픽셀이 차지하는 메모리(바이트). 실행 취소 기록의 메모리 한도를 셀 때 쓴다. */
    val byteCount: Int
        get() = pixels.byteCount

    /** 타일 안 좌표([x], [y])의 픽셀. 담은 크기 밖이면 투명(0)이다. */
    fun pixelAt(x: Int, y: Int): Int {
        require(x in 0 until TILE_SIZE && y in 0 until TILE_SIZE) { "Pixel outside tile: ($x, $y)" }
        return if (x < width && y < height) pixels.pixelAt(x, y) else 0
    }

    /**
     * 왼쪽 위 [width]×[height]만 담은 타일. 한 가지 색이면 그 색도 남긴다. 크기가 같으면 자기 자신이다.
     * 담은 크기보다 크게는 만들 수 없다. 메모리가 모자라면 [OutOfMemoryError]를 던진다.
     */
    fun cropped(width: Int, height: Int): Tile {
        require(width in 1..this.width && height in 1..this.height) { "Can't crop a ${this.width}x${this.height} tile to ${width}x$height" }
        if (width == this.width && height == this.height) return this
        return Tile(pixels.cropped(width, height), solidColor)
    }

    /**
     * 담은 픽셀에서 알파가 0이 아닌 픽셀을 모두 담는 가장 작은 사각형(타일 안 좌표). 모두 투명하면 null.
     * 한 가지 색 타일은 읽지 않는다([contentBounds]).
     */
    fun opaqueBounds(): PixelRect? =
        when (val color = solidColor) {
            null -> pixels.opaqueBounds(width, height)
            else -> if (color ushr 24 != 0) PixelRect(0, 0, width, height) else null
        }

    companion object {
        /**
         * [pixels]를 복사해서 타일을 만든다. 크기는 [TILE_PIXEL_COUNT]이고, 한 줄씩 위에서 아래로 놓인다.
         * 왼쪽 위 [width]×[height]만 담는다(그 밖의 값은 버린다).
         */
        fun of(pixels: IntArray, width: Int = TILE_SIZE, height: Int = TILE_SIZE): Tile = Tile(HeapTilePixels(pixels, width, height), solidColor = null)

        /** [argb] 한 가지 색으로 채운 타일(자바 힙, 왼쪽 위 [width]×[height]). 테스트와 작은 데이터용이다. */
        fun solid(argb: Int, width: Int = TILE_SIZE, height: Int = TILE_SIZE): Tile =
            Tile(HeapTilePixels(IntArray(TILE_PIXEL_COUNT) { argb }, width, height), solidColor = argb)

        /**
         * 힙 밖 비트맵처럼 엔진이 이미 만들어 둔 [pixels]로 타일을 만든다. [pixels]는 이후에 바뀌면 안 된다.
         * [solidColor]는 [pixels] 전체가 그 한 가지 색일 때만 준다.
         */
        internal fun of(pixels: TilePixels, solidColor: Int? = null): Tile = Tile(pixels, solidColor)
    }
}

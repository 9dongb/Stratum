package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile

/**
 * 테스트용 타일 합치기. 자바 힙 배열에 픽셀을 하나씩 옮긴다(느려서 테스트에만 쓴다).
 * 앱에서는 플랫폼 코드가 안드로이드 복사로 같은 일을 한다.
 */
class HeapTileComposer : TileComposer {

    /** 새로 만든 타일 수. 그대로 쓴 타일은 세지 않는다. */
    var composedCount = 0
        private set

    override fun compose(width: Int, height: Int, pieces: List<TilePiece>, orientation: Orientation): Tile {
        composedCount++
        val pixels = IntArray(TILE_PIXEL_COUNT)
        for (piece in pieces) {
            val rect = piece.part.targetRect
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    val source = piece.part.sourcePixel(x, y, orientation)
                    pixels[y * TILE_SIZE + x] = piece.tile.pixelAt(source.x, source.y)
                }
            }
        }
        return Tile.of(pixels)
    }
}

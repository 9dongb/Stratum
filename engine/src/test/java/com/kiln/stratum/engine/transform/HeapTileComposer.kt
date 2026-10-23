package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.selection.MaskTile

/**
 * 테스트용 타일 합치기. 자바 힙 배열에 픽셀을 하나씩 옮긴다(느려서 테스트에만 쓴다).
 * 앱에서는 플랫폼 코드가 안드로이드 복사로 같은 일을 한다.
 */
class HeapTileComposer : TileComposer {

    /** 새로 만든 타일 수. 그대로 쓴 타일은 세지 않는다. */
    var composedCount = 0
        private set

    /** 새로 만든 마스크 타일 수. 그대로 쓴 타일은 세지 않는다. */
    var composedMaskCount = 0
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

    override fun composeMask(width: Int, height: Int, pieces: List<MaskPiece>, orientation: Orientation): MaskTile? {
        composedMaskCount++
        val alphas = ByteArray(TILE_PIXEL_COUNT)
        for (piece in pieces) {
            val rect = piece.part.targetRect
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    val source = piece.part.sourcePixel(x, y, orientation)
                    alphas[y * TILE_SIZE + x] = piece.tile.alphaAt(source.x, source.y).toByte()
                }
            }
        }
        // 캔버스 안쪽이 다 0이면 비우고, 다 255면 다 보이는 타일이다(플랫폼 코드와 같다)
        val inside = (0 until height).flatMap { y -> (0 until width).map { x -> alphas[y * TILE_SIZE + x].toInt() and 0xFF } }
        return when {
            inside.all { it == 0 } -> null
            inside.all { it == 255 } -> MaskTile.FULL
            else -> MaskTile.of(alphas)
        }
    }
}

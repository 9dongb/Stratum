package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TilePixels

/**
 * 안드로이드 비트맵 한 장에 담은 타일 픽셀. 비트맵 픽셀은 자바 힙 밖 네이티브 메모리에 있다.
 *
 * [bitmap]은 바꿀 수 없는(immutable) ARGB_8888 비트맵이고, 알파를 미리 곱한 형식으로 저장된다.
 * 오른쪽·아래 끝 타일은 캔버스 안쪽 크기만 담고, 그 밖은 투명으로 읽는다.
 * 여러 문서 상태와 실행 취소 기록이 같은 타일을 함께 쓰므로 recycle()하지 않는다.
 */
internal class BitmapTilePixels(val bitmap: Bitmap) : TilePixels {

    init {
        require(!bitmap.isMutable) { "Tile bitmap must be immutable" }
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "Tile bitmap must be ARGB_8888: ${bitmap.config}" }
        require(bitmap.width in 1..TILE_SIZE && bitmap.height in 1..TILE_SIZE) {
            "Tile bitmap too large: ${bitmap.width}x${bitmap.height}"
        }
    }

    // getPixel은 알파를 미리 곱하지 않은 값으로 바꿔 돌려준다
    override fun pixelAt(x: Int, y: Int): Int =
        if (x < bitmap.width && y < bitmap.height) bitmap.getPixel(x, y) else 0
}

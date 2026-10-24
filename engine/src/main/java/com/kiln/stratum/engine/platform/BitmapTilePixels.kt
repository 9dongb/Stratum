package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TilePixels
import com.kiln.stratum.engine.raster.opaqueRect
import java.nio.ByteBuffer

/**
 * 안드로이드 비트맵 한 장에 담은 타일 픽셀. 비트맵 픽셀은 자바 힙 밖 네이티브 메모리에 있다.
 *
 * [bitmap]은 바꿀 수 없는(immutable) ARGB_8888 비트맵이고, 알파를 미리 곱한 형식으로 저장된다. 담은 크기는 비트맵 크기다.
 * 오른쪽·아래 끝 타일은 캔버스 안쪽 크기만 담거나(사진·한 가지 색 캔버스), 캔버스 밖으로 밀려난 픽셀만큼 더 담는다(결정 기록 '캔버스 밖 픽셀').
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

    override val width: Int
        get() = bitmap.width

    override val height: Int
        get() = bitmap.height

    // 비트맵의 왼쪽 위를 잘라 바꿀 수 없는 복사본으로 담는다(안드로이드 내부 복사)
    override fun cropped(width: Int, height: Int): TilePixels {
        val part = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        try {
            return BitmapTilePixels(part.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError("No memory to crop a tile"))
        } finally {
            if (part !== bitmap) part.recycle()
        }
    }

    // 끝 타일은 캔버스 안쪽 크기만 담아 꽉 찬 타일보다 작다
    override val byteCount: Int
        get() = bitmap.allocationByteCount

    // 픽셀을 한 번에 꺼내(줄마다 rowBytes, 픽셀마다 알파를 곱한 R·G·B·A) 알파 바이트만 본다. 알파는 곱해도 그대로다.
    // 꺼낼 자리는 스레드마다 하나를 다시 쓴다(지운 자리가 넓은 큰 레이어는 타일을 수백 장 읽어, 장마다 새로 만들면 메모리를 많이 버린다).
    override fun opaqueBounds(width: Int, height: Int): PixelRect? {
        val w = minOf(width, bitmap.width)
        val h = minOf(height, bitmap.height)
        if (w <= 0 || h <= 0) return null
        val bytes = scratch.get()?.takeIf { it.size >= bitmap.byteCount } ?: ByteArray(TILE_BYTES).also { scratch.set(it) }
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        val rowBytes = bitmap.rowBytes
        return opaqueRect(w, h) { x, y -> bytes[y * rowBytes + x * 4 + 3].toInt() != 0 }
    }

    private companion object {
        /** 꽉 찬 타일 하나의 픽셀 바이트 수 */
        const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4

        /** [opaqueBounds]가 픽셀을 꺼내는 자리(스레드마다 하나) */
        val scratch = ThreadLocal<ByteArray>()
    }
}

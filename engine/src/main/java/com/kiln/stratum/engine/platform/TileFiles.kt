package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.project.UnreadableProjectException
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 타일 한 장을 파일로 쓰고 읽는다.
 *
 * 비트맵에 담긴 값(알파를 미리 곱한 RGBA)을 그대로 압축(Deflate, 손실 없음)해 쓴다. 그래서 반투명 픽셀도 값이 바뀌지 않는다.
 * getPixel처럼 알파를 미리 곱하지 않은 값으로 바꾸면 반투명 픽셀에서 반올림이 생긴다(설계 메모 '자동 저장').
 * 형식: 표지(STRT), 판, 가로, 세로, 압축 전 바이트 수, 압축한 픽셀
 */
internal object TileFiles {

    /** 'STRT' */
    private const val MAGIC = 0x53545254
    private const val VERSION = 1

    /** [tile]을 [file]에 쓴다. 끝 타일은 담긴 크기(캔버스 안쪽)만 쓴다. */
    fun write(file: File, tile: Tile) {
        val temporary = ArrayList<Bitmap>()
        try {
            val bitmap = CanvasTileComposer.bitmapOf(tile, temporary)
            val pixels = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixels)
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(bitmap.width)
                out.writeInt(bitmap.height)
                out.writeInt(pixels.capacity())
                // 빨리 쓰는 쪽을 고른다. 사진은 어차피 크게 줄지 않는다.
                val deflater = Deflater(Deflater.BEST_SPEED)
                try {
                    val compressed = DeflaterOutputStream(out, deflater)
                    compressed.write(pixels.array())
                    compressed.finish()
                } finally {
                    deflater.end()
                }
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /** [file]의 타일. 망가졌으면 [UnreadableProjectException], 메모리가 없으면 [NotEnoughMemoryException]이다. */
    fun read(file: File): Tile =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) throw UnreadableProjectException("Not a tile: ${file.name}")
            val version = input.readInt()
            if (version != VERSION) throw UnreadableProjectException("Unsupported tile version $version")
            val width = input.readInt()
            val height = input.readInt()
            val byteCount = input.readInt()
            if (width !in 1..TILE_SIZE || height !in 1..TILE_SIZE) throw UnreadableProjectException("Broken tile size ${width}x$height")
            try {
                val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    if (byteCount != scratch.byteCount) throw UnreadableProjectException("Broken tile length $byteCount")
                    val pixels = ByteArray(byteCount)
                    val inflater = Inflater()
                    try {
                        DataInputStream(InflaterInputStream(input, inflater)).readFully(pixels)
                    } finally {
                        inflater.end()
                    }
                    scratch.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                    // 타일 비트맵은 바꿀 수 없어야 한다. 그렇게 하는 공개 함수가 없어 복사본을 만든다.
                    val bitmap = scratch.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
                    Tile.of(BitmapTilePixels(bitmap))
                } finally {
                    scratch.recycle()
                }
            } catch (e: OutOfMemoryError) {
                throw NotEnoughMemoryException(e)
            }
        }
}

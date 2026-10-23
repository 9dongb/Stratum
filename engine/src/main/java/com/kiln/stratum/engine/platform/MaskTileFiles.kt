package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.project.UnreadableProjectException
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.selection.MaskTile
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
 * 레이어 마스크 타일 한 장을 파일로 쓰고 읽는다(결정 기록 '피부 보정'의 1c). 픽셀 하나에 1바이트(알파)다.
 * 픽셀 타일 파일([TileFiles])과 같은 방식(값 그대로, Deflate)이고 표지가 다르다. 다 보이는 타일은 파일 없이 목차에만 적는다.
 * 형식: 표지(STRA), 판, 가로, 세로, 압축 전 바이트 수, 압축한 알파
 */
internal object MaskTileFiles {

    /** 'STRA' */
    private const val MAGIC = 0x53545241
    private const val VERSION = 1

    /** [tile](다 보이는 타일이 아닌 것)을 [file]에 쓴다. 끝 타일은 담긴 크기(캔버스 안쪽)만 쓴다. */
    fun write(file: File, tile: MaskTile) {
        val temporary = ArrayList<Bitmap>()
        try {
            val bitmap = checkNotNull(MaskTiles.bitmapOf(tile, temporary)) { "Full mask tiles are not written to files" }
            val alphas = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(alphas)
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(bitmap.width)
                out.writeInt(bitmap.height)
                out.writeInt(alphas.capacity())
                val deflater = Deflater(Deflater.BEST_SPEED)
                try {
                    val compressed = DeflaterOutputStream(out, deflater)
                    compressed.write(alphas.array())
                    compressed.finish()
                } finally {
                    deflater.end()
                }
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /** [file]의 마스크 타일. 망가졌으면 [UnreadableProjectException], 메모리가 없으면 [NotEnoughMemoryException]이다. */
    fun read(file: File): MaskTile =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) throw UnreadableProjectException("Not a mask tile: ${file.name}")
            val version = input.readInt()
            if (version != VERSION) throw UnreadableProjectException("Unsupported mask tile version $version")
            val width = input.readInt()
            val height = input.readInt()
            val byteCount = input.readInt()
            if (width !in 1..TILE_SIZE || height !in 1..TILE_SIZE) throw UnreadableProjectException("Broken mask tile size ${width}x$height")
            try {
                val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                try {
                    if (byteCount != scratch.byteCount) throw UnreadableProjectException("Broken mask tile length $byteCount")
                    val alphas = ByteArray(byteCount)
                    val inflater = Inflater()
                    try {
                        DataInputStream(InflaterInputStream(input, inflater)).readFully(alphas)
                    } finally {
                        inflater.end()
                    }
                    scratch.copyPixelsFromBuffer(ByteBuffer.wrap(alphas))
                    // 마스크 타일 비트맵은 바꿀 수 없어야 한다(AlphaMaskPixels). 그렇게 하는 공개 함수가 없어 복사본을 만든다.
                    val bitmap = scratch.copy(Bitmap.Config.ALPHA_8, false) ?: throw NotEnoughMemoryException()
                    MaskTile.of(AlphaMaskPixels(bitmap))
                } finally {
                    scratch.recycle()
                }
            } catch (e: OutOfMemoryError) {
                throw NotEnoughMemoryException(e)
            }
        }
}

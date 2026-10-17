package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.importing.ImportFailure
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import java.io.IOException

/** 사진 파일의 머리 정보. 픽셀을 풀지 않고 읽는다. */
data class PhotoInfo(val size: PixelSize, val mimeType: String?)

/**
 * 사진 파일을 원본 해상도 문서로 불러온다.
 *
 * 해독한 픽셀을 256px 타일마다 바꿀 수 없는 비트맵으로 나눠 담는다(자바 힙 밖, CLAUDE.md 4장).
 * 원본 파일은 읽기만 하고 바꾸지 않는다. 오래 걸리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object PhotoImporter {

    /** 픽셀을 풀지 않고 사진의 크기와 형식만 읽는다. 읽을 수 없으면 null. */
    fun readInfo(source: ImageDecoder.Source): PhotoInfo? {
        var info: PhotoInfo? = null
        try {
            ImageDecoder.decodeBitmap(source) { _, imageInfo, _ ->
                info = PhotoInfo(PixelSize(imageInfo.size.width, imageInfo.size.height), imageInfo.mimeType)
                // 머리 정보만 필요하므로 픽셀을 풀기 전에 멈춘다.
                // 이 콜백은 픽셀을 풀기 전에 불리고, 던진 예외는 그대로 밖으로 나온다(SDK 소스로 확인).
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
            // 바라던 대로 머리 정보만 읽고 멈췄다
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return info
    }

    /**
     * [source] 사진을 [layerName] 레이어 하나짜리 문서로 불러온다.
     *
     * [sampleSize]가 2, 4…이면 해독하면서 1/2, 1/4 크기로 줄인다.
     * [checkCanceled]는 타일 사이마다 부르며, 취소됐으면 예외를 던지게 한다(예: 코루틴의 ensureActive).
     * 그 예외는 잡지 않고 그대로 내보낸다.
     */
    fun import(
        source: ImageDecoder.Source,
        layerName: String,
        sampleSize: Int = 1,
        checkCanceled: () -> Unit = {}
    ): ImportResult {
        require(sampleSize >= 1 && (sampleSize and (sampleSize - 1)) == 0) {
            "Sample size must be a power of two: $sampleSize"
        }
        val decoded = try {
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // 타일로 나누려면 CPU에서 읽을 수 있어야 하므로, GPU 전용(하드웨어) 비트맵이 아닌 소프트웨어 비트맵으로 푼다
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // 1단계는 8비트 sRGB로 다룬다(progress.md 결정 기록 '사진 불러오기')
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
            }
        } catch (_: OutOfMemoryError) {
            return ImportResult.Failure(ImportFailure.NOT_ENOUGH_MEMORY)
        } catch (_: IOException) {
            return ImportResult.Failure(ImportFailure.UNREADABLE)
        } catch (_: SecurityException) {
            return ImportResult.Failure(ImportFailure.UNREADABLE)
        }
        try {
            val raster = splitIntoTiles(decoded, checkCanceled)
                ?: return ImportResult.Failure(ImportFailure.NOT_ENOUGH_MEMORY)
            return ImportResult.Success(Document.create(decoded.width, decoded.height, layerName, raster))
        } catch (_: OutOfMemoryError) {
            return ImportResult.Failure(ImportFailure.NOT_ENOUGH_MEMORY)
        } finally {
            // 해독본은 타일로 옮긴 뒤에는 쓰지 않으므로 바로 메모리를 돌려준다(타일은 돌려주지 않는다)
            decoded.recycle()
        }
    }

    /**
     * [decoded]를 타일로 나눈 래스터. 타일 비트맵을 만들 메모리가 없으면 null.
     *
     * 타일로 나누는 일은 안드로이드 내부 복사라 CPU로 한다(progress.md 결정 기록 '타일 나누기는 CPU 복사').
     */
    private fun splitIntoTiles(decoded: Bitmap, checkCanceled: () -> Unit): Raster? {
        val grid = TileGrid(decoded.width, decoded.height)
        // 타일 한 장을 그릴 작업용 비트맵. 다시 쓰면서 타일마다 바꿀 수 없는 복사본을 만든다.
        // 비트맵을 바꿀 수 없게 하는 공개 함수(setImmutable)가 없어서 copy(…, false)로 복사본을 만든다.
        val scratch = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(scratch)
            // 덮어쓰기라 앞 타일이 남아 있어도 새 픽셀로 바뀐다. 16비트로 풀린 사진도 여기서 8비트 sRGB가 된다.
            val paint = Paint().apply { blendMode = BlendMode.SRC }
            val from = Rect()
            val to = Rect()
            val tiles = HashMap<TileKey, Tile>()
            for (key in grid.keys()) {
                checkCanceled()
                val bounds = grid.bounds(key)
                from.set(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
                to.set(0, 0, bounds.width, bounds.height)
                canvas.drawBitmap(decoded, from, to, paint)
                val full = bounds.width == TILE_SIZE && bounds.height == TILE_SIZE
                // 끝 타일은 캔버스 안쪽 크기만 담는다
                val part = if (full) scratch else Bitmap.createBitmap(scratch, 0, 0, bounds.width, bounds.height)
                val tileBitmap = part.copy(Bitmap.Config.ARGB_8888, false)
                if (part !== scratch) part.recycle()
                if (tileBitmap == null) return null
                tiles[key] = Tile.of(BitmapTilePixels(tileBitmap))
            }
            return Raster.EMPTY.withTiles(tiles)
        } finally {
            scratch.recycle()
        }
    }

    /** 머리 정보만 읽고 해독을 멈추려고 던지는 예외. 밖으로 나가지 않으므로 호출 위치는 기록하지 않는다. */
    private class HeaderRead : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}

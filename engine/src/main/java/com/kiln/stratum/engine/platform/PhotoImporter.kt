package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.HdrGainmap
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

/** 문서에 넣으려고 푼 사진([PhotoImporter.decodeLayer]): [size] 크기 그림의 타일 */
internal class DecodedImage(val raster: Raster, val size: PixelSize)

/**
 * 사진 파일을 원본 해상도 문서로 불러온다.
 *
 * 해독한 픽셀을 256px 타일마다 바꿀 수 없는 비트맵으로 나눠 담는다(자바 힙 밖, CLAUDE.md 4장).
 * 8비트로 풀고, sRGB보다 넓은 색 사진은 Display P3 문서로, 나머지는 sRGB 문서로 만든다(결정 기록 '사진 불러오기'·'넓은 색').
 * HDR 사진(Ultra HDR 등 게인맵이 있는 사진)은 안드로이드 14 이상에서 게인맵을 사진 레이어에 지닌다(결정 기록 'HDR').
 * 원본 파일은 읽기만 하고 바꾸지 않는다. 오래 걸리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object PhotoImporter {

    /**
     * 이 기기의 해독기가 사진 하나를 푸는 동안 쓸 수 있는 최대 메모리(바이트). ImportSizing.plan에 넘긴다.
     *
     * API 37(CINNAMON_BUN)부터 ImageDecoder는 기본으로 약 0.5GiB까지만 쓰고, 넘으면 IOException을 던진다(SDK 소스로 확인).
     * 이 한도를 바꾸는 함수(setAllocationLimit)는 아직 앱에 공개되지 않았다. 그 전 버전에는 한도가 없다.
     */
    val decodeLimitBytes: Long
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) 536_900_000L else Long.MAX_VALUE

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
        val (decoded, colorSpace) = try {
            decodeSoftware(source, sampleSize, colorSpace = null)
        } catch (e: DecodeFailure) {
            return failure(e.reason, e.cause)
        }
        try {
            val raster = splitIntoTiles(decoded, colorSpace, checkCanceled)
                ?: return failure(ImportFailure.NOT_ENOUGH_MEMORY, null)
            val document = Document.create(decoded.width, decoded.height, layerName, raster).copy(colorSpace = colorSpace)
            val hdr = if (Gainmaps.supported) Gainmaps.read(decoded) else null
            if (hdr == null) return ImportResult.Success(document)
            val (info, contents) = hdr
            val gainmap = Gainmaps.split(contents, checkCanceled) ?: return failure(ImportFailure.NOT_ENOUGH_MEMORY, null)
            val photo = document.layers[0].copy(gainmap = HdrGainmap(contents.width, contents.height, gainmap))
            return ImportResult.Success(document.copy(layers = listOf(photo), hdr = info))
        } catch (e: OutOfMemoryError) {
            return failure(ImportFailure.NOT_ENOUGH_MEMORY, e)
        } finally {
            // 해독본은 타일로 옮긴 뒤에는 쓰지 않으므로 바로 메모리를 돌려준다(타일은 돌려주지 않는다)
            decoded.recycle()
        }
    }

    /**
     * [source] 사진을 [colorSpace] 값의 타일로 푼다(편집 중인 문서에 새 레이어로 넣을 때, [InsertImageLayer]). 게인맵(HDR)은 읽지 않는다.
     * [sampleSize]가 2, 4…이면 해독하면서 줄인다. [checkCanceled]는 타일 사이마다 부른다.
     * 사진을 읽지 못하면 [PhotoUnreadableException], 메모리가 모자라면 [NotEnoughMemoryException]을 던진다.
     */
    internal fun decodeLayer(
        source: ImageDecoder.Source,
        colorSpace: DocumentColorSpace,
        sampleSize: Int,
        checkCanceled: () -> Unit
    ): DecodedImage {
        val (decoded, _) = try {
            decodeSoftware(source, sampleSize, colorSpace)
        } catch (e: DecodeFailure) {
            Log.w(TAG, "Photo insert failed: ${e.reason}", e.cause)
            throw if (e.reason == ImportFailure.NOT_ENOUGH_MEMORY) NotEnoughMemoryException(e.cause) else PhotoUnreadableException(e.cause)
        }
        try {
            val raster = splitIntoTiles(decoded, colorSpace, checkCanceled) ?: throw NotEnoughMemoryException()
            return DecodedImage(raster, PixelSize(decoded.width, decoded.height))
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        } finally {
            decoded.recycle()
        }
    }

    /**
     * [source]를 소프트웨어 비트맵으로 푼다(타일로 나누려면 CPU에서 읽을 수 있어야 해서 GPU 전용 비트맵이 아니다).
     * [colorSpace]를 주면 그 값으로 풀고(사진 넣기: 문서 색 공간), 없으면 sRGB보다 넓은 색 사진(P3·Adobe RGB·BT.2020 등)은 Display P3로,
     * 나머지는 sRGB로 푼다(결정 기록 '넓은 색'). 푼 비트맵과 그 값의 색 공간을 돌려주고, 풀지 못하면 [DecodeFailure]를 던진다.
     */
    private fun decodeSoftware(source: ImageDecoder.Source, sampleSize: Int, colorSpace: DocumentColorSpace?): Pair<Bitmap, DocumentColorSpace> {
        var chosen = colorSpace ?: DocumentColorSpace.SRGB
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (colorSpace == null && DocumentColors.isWiderThanSrgb(info.colorSpace)) chosen = DocumentColorSpace.DISPLAY_P3
                decoder.setTargetColorSpace(DocumentColors.androidColorSpace(chosen))
                if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
            }
        } catch (e: OutOfMemoryError) {
            throw DecodeFailure(ImportFailure.NOT_ENOUGH_MEMORY, e)
        } catch (e: IOException) {
            // 해독기 안의 메모리 부족과 한도 초과도 IOException으로 온다(에뮬레이터에서 확인)
            val outOfMemory = e.message?.let { "out of memory" in it || "allocation limit" in it } == true
            throw DecodeFailure(if (outOfMemory) ImportFailure.NOT_ENOUGH_MEMORY else ImportFailure.UNREADABLE, e)
        } catch (e: SecurityException) {
            throw DecodeFailure(ImportFailure.UNREADABLE, e)
        }
        return bitmap to chosen
    }

    /**
     * [decoded]를 타일로 나눈 래스터. 타일 비트맵을 만들 메모리가 없으면 null.
     * 타일 값은 [colorSpace]의 값이고, 꼬리표는 늘 sRGB다(엔진 안의 모든 비트맵과 같다, [DocumentColors]).
     *
     * 타일로 나누는 일은 안드로이드 내부 복사라 CPU로 한다(progress.md 결정 기록 '타일 나누기는 CPU 복사').
     */
    private fun splitIntoTiles(decoded: Bitmap, colorSpace: DocumentColorSpace, checkCanceled: () -> Unit): Raster? {
        val grid = TileGrid(decoded.width, decoded.height)
        // 타일 한 장을 그릴 작업용 비트맵(문서 색 공간). 다시 쓰면서 타일마다 바꿀 수 없는 복사본을 만든다.
        // 비트맵을 바꿀 수 없게 하는 공개 함수(setImmutable)가 없어서 copy(…, false)로 복사본을 만든다.
        val scratch = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888, true, DocumentColors.androidColorSpace(colorSpace))
        val tileColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        try {
            val canvas = Canvas(scratch)
            // 덮어쓰기라 앞 타일이 남아 있어도 새 픽셀로 바뀐다. 16비트로 풀린 사진도 여기서 문서 색 공간의 8비트 값이 된다.
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
                // P3 값은 그대로 두고 꼬리표만 sRGB로 바꾼다(sRGB 문서는 이미 sRGB)
                tileBitmap.setColorSpace(tileColorSpace)
                tiles[key] = Tile.of(BitmapTilePixels(tileBitmap))
            }
            return Raster.EMPTY.withTiles(tiles)
        } finally {
            scratch.recycle()
        }
    }

    /** 불러오기 실패. 화면에는 [reason]만 보이므로, 까닭을 찾을 수 있게 [cause]를 로그에 남긴다. */
    private fun failure(reason: ImportFailure, cause: Throwable?): ImportResult.Failure {
        Log.w(TAG, "Photo import failed: $reason", cause)
        return ImportResult.Failure(reason)
    }

    private const val TAG = "PhotoImporter"

    /** 사진을 풀지 못한 까닭([reason])과 그 예외. [decodeSoftware]가 던지고 밖으로 나가지 않는다. */
    private class DecodeFailure(val reason: ImportFailure, override val cause: Throwable) : RuntimeException(cause)

    /** 머리 정보만 읽고 해독을 멈추려고 던지는 예외. 밖으로 나가지 않으므로 호출 위치는 기록하지 않는다. */
    private class HeaderRead : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}

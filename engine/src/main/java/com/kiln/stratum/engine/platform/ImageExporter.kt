package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.transform.ResizePlan
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * 문서를 내보낼 크기로 그리고([render]), 형식대로 압축한다([RenderedImage.writeTo]). 문서는 바꾸지 않는다.
 *
 * - 보통은 크기 변경과 같은 묶음 그리기([ImageResizer.renderBlock])로 보이는 레이어를 모두 GPU에서 합성하고 보간한다.
 *   원본 크기면 보간이 값을 바꾸지 않는다. 합성은 축소본과 같다(보정, 불투명도, 혼합 모드).
 * - 원본 크기이고 보이는 레이어가 하나(불투명도 1)이며 투명을 담는 형식이면, 타일을 안드로이드 내부 복사로 그대로 베낀다.
 *   사진 하나를 PNG로 내보내면 픽셀이 원본과 같다. 투명한 바탕 위의 레이어 하나는 혼합 모드와 상관없이 그 레이어 그대로다.
 * - JPEG는 투명을 담지 못한다. 그냥 압축하면 투명한 곳이 검게 되므로(소스 확인) 흰 바탕 위에 합성한다.
 *
 * 묶음은 GPU에서 읽어 와 내보낼 크기의 비트맵 한 장에 모은다. 조각을 이어 압축하는 공개 기능이 없어 한 장이 필요하다.
 * GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object ImageExporter {

    /**
     * [document]를 [settings]의 크기로 그린 이미지. 다 쓰면 [RenderedImage.close]로 메모리를 돌려준다.
     * 메모리가 모자라거나 GPU가 그리지 못하면 [NotEnoughMemoryException]을 던진다.
     * [checkCanceled]는 묶음 사이마다 부르며, 취소됐으면 예외를 던지게 한다. 그 예외는 그대로 내보낸다.
     */
    fun render(document: Document, settings: ExportSettings, checkCanceled: () -> Unit = {}): RenderedImage {
        val size = settings.size
        val bitmap = try {
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        try {
            // 새 비트맵은 투명이다. JPEG는 흰 바탕부터 깐다.
            val background = if (settings.format.keepsTransparency) null else Color.WHITE
            if (background != null) bitmap.eraseColor(background)
            val exact = exactRaster(document, settings)
            if (exact != null) {
                copyTiles(exact, bitmap, checkCanceled)
            } else {
                renderBlocks(document, settings.size, background, bitmap, checkCanceled)
            }
            // 다 그린 뒤에 꼬리표를 문서 색 공간으로 바꾼다(그리기 전에 바꾸면 sRGB 꼬리표의 타일이 변환된다)
            DocumentColors.tagForEncoding(bitmap, document.colorSpace)
            // HDR 문서를 JPEG로 내보내면 게인맵을 붙여 Ultra HDR로 쓴다(안드로이드 14 이상, PNG·WebP는 압축기가 담지 못한다)
            if (settings.format == ExportFormat.JPEG && Gainmaps.supported) attachGainmap(document, bitmap, checkCanceled)
            return RenderedImage(bitmap, settings)
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw NotEnoughMemoryException(e)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    /**
     * 내보낼 그림 [into]에 문서의 게인맵(레이어 게인맵을 겹친 것, [GainmapComposer])을 붙인다. HDR 문서가 아니면 그대로다.
     * 게인맵 크기는 사진의 게인맵과 같은 비율로 내보낼 크기에 맞춘다. 그리지 못하면 게인맵 없이(보통 사진으로) 내보낸다.
     */
    private fun attachGainmap(document: Document, into: Bitmap, checkCanceled: () -> Unit) {
        val info = document.hdr ?: return
        val source = document.layers.firstNotNullOfOrNull { it.gainmap } ?: return
        val width = (into.width * source.width.toDouble() / document.width).roundToInt().coerceAtLeast(1)
        val height = (into.height * source.height.toDouble() / document.height).roundToInt().coerceAtLeast(1)
        val composed = GainmapComposer.compose(document, width, height, checkCanceled) ?: return
        val contents = if (info.singleChannel) Gainmaps.toSingleChannel(composed).also { composed.recycle() } else composed
        into.gainmap = Gainmaps.toAndroid(info, contents)
    }

    /**
     * 타일을 그대로 베껴도 되면 그 레이어의 래스터.
     * 원본 크기, 보이는 레이어 하나(불투명도 1, 보정 없음), 투명을 담는 형식일 때다.
     */
    private fun exactRaster(document: Document, settings: ExportSettings): Raster? {
        if (settings.size != PixelSize(document.width, document.height) || !settings.format.keepsTransparency) return null
        val visible = document.layers.filter { it.opacity > 0f }
        val layer = visible.singleOrNull()?.takeIf { it.opacity == 1f && it.adjustments.isIdentity } ?: return null
        return (layer.content as? RasterContent)?.raster
    }

    /** [raster]의 타일을 [into]의 같은 자리에 그대로 베낀다. 끝 타일의 캔버스 밖 부분은 비트맵 밖이라 잘린다. */
    private fun copyTiles(raster: Raster, into: Bitmap, checkCanceled: () -> Unit) {
        val canvas = Canvas(into)
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, tile) in raster.tiles) {
                checkCanceled()
                val source = CanvasTileComposer.bitmapOf(tile, temporary)
                canvas.drawBitmap(
                    source,
                    (key.column * TILE_SIZE).toFloat(),
                    (key.row * TILE_SIZE).toFloat(),
                    CanvasTileComposer.CopyPaint
                )
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /**
     * 대상 묶음마다 [document]를 GPU로 합성·보간해 [into]에 모은다.
     * 레이어에 타일이 하나도 없는 묶음(완전 투명)은 그리지 않고 바탕을 그대로 둔다.
     */
    private fun renderBlocks(
        document: Document,
        target: PixelSize,
        background: Int?,
        into: Bitmap,
        checkCanceled: () -> Unit
    ) {
        val plan = ResizePlan(PixelSize(document.width, document.height), target)
        val rasters = document.layers
            .filter { it.opacity > 0f }
            .mapNotNull { (it.content as? RasterContent)?.raster }
        val canvas = Canvas(into)
        for (block in plan.blocks()) {
            checkCanceled()
            val sourceRect = plan.sourceRect(block)
            if (rasters.none { it.hasTilesIn(sourceRect) }) continue
            val pixels = ImageResizer.renderBlock(document, plan, block, sourceRect, background)
                ?: throw NotEnoughMemoryException()
            try {
                // 바탕까지 합성한 값이라 그대로 덮어쓴다
                canvas.drawBitmap(pixels, block.left.toFloat(), block.top.toFloat(), CanvasTileComposer.CopyPaint)
            } finally {
                pixels.recycle()
            }
        }
    }
}

/**
 * 내보낼 크기로 그린 이미지. [writeTo]로 압축해 쓰고, 다 쓰면 [close]한다.
 * 압축은 안드로이드 `Bitmap.compress`다. 사진 정보(EXIF)는 쓰지 않는다. 문서 색 공간(sRGB·Display P3)의 색 정보가 붙는다.
 * HDR 문서의 JPEG는 게인맵이 붙어 Ultra HDR JPEG가 된다(안드로이드 14 이상).
 */
class RenderedImage internal constructor(
    private val bitmap: Bitmap,
    val settings: ExportSettings
) : Closeable {

    /**
     * [settings]의 형식으로 압축해 [out]에 쓴다. 쓰지 못하면 [IOException]을 던진다.
     *
     * 압축은 중간에 멈출 수 없다. 대신 [checkCanceled]를 압축기가 조각을 쓸 때마다 불러서,
     * 취소됐으면 남은 쓰기를 건너뛰고 그 예외를 그대로 내보낸다.
     * PNG는 쓰면서 압축해 곧 멈추고, JPEG·WebP는 끝에 몰아 쓰므로 압축이 끝난 뒤 멈춘다(소스 확인).
     * [out]은 닫지 않는다.
     */
    fun writeTo(out: OutputStream, checkCanceled: () -> Unit = {}) {
        val format = when (settings.format) {
            ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
            ExportFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
        }
        // 압축기는 쓰기에서 난 예외를 지우고 실패(false)만 알린다. 그래서 취소인지 다시 확인한다.
        if (!bitmap.compress(format, settings.quality, CancelCheckingStream(out, checkCanceled))) {
            checkCanceled()
            throw IOException("Couldn't encode ${settings.format} ${settings.size}")
        }
    }

    /** 이미지 메모리를 돌려준다. */
    override fun close() {
        bitmap.recycle()
    }
}

/** 쓰기 전에 [checkCanceled]를 부르는 스트림. 압축기는 4KB씩 쓴다. 한 바이트씩 넘기지 않게 묶음 쓰기를 그대로 넘긴다. */
private class CancelCheckingStream(out: OutputStream, private val checkCanceled: () -> Unit) : FilterOutputStream(out) {

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkCanceled()
        out.write(b, off, len)
    }
}

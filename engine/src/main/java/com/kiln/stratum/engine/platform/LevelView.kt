package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import java.nio.ByteBuffer

/**
 * 문서의 한 영역을 어떤 단계(1/2^[level])로 그린 소프트웨어 그림. 리터칭의 가져올 곳 찾기와 밝기·색 맞추기가 읽고,
 * AI 제거도 모델에 넣을 창을 이것으로 읽는다(결정 기록 'AI 제거·AI 선택').
 * 쓸 곳은 그림의 ([offsetX], [offsetY])부터 [width]×[height]이고, 그 왼쪽 위는 문서에서 ([left], [top])이다(둘 다 단계 칸에 맞춤).
 * 쓸 곳은 모두 문서 안이다.
 */
internal class LevelView(
    val bitmap: Bitmap,
    val level: Int,
    val left: Int,
    val top: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int
) {
    /** 단계 칸 하나의 문서 픽셀 수 */
    val cell: Int
        get() = 1 shl level

    /** 픽셀 바이트(줄마다 [rowBytes], 픽셀마다 R·G·B·A, 알파를 곱한 값). 처음 읽을 때 한 번 꺼낸다. */
    val bytes: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.array()
    }

    val rowBytes: Int
        get() = bitmap.rowBytes

    /** 쓸 곳의 ([x], [y]) 픽셀이 [bytes]에서 시작하는 자리 */
    fun byteIndex(x: Int, y: Int): Int = (y + offsetY) * rowBytes + (x + offsetX) * 4

    /** 문서 좌표([documentX], [documentY], 단계 칸에 맞춘 것)의 칸이 [bytes]에서 시작하는 자리. 쓸 곳 밖이면 -1. */
    fun byteIndexAt(documentX: Int, documentY: Int): Int {
        if (documentX < left || documentY < top) return -1
        val x = (documentX - left) / cell
        val y = (documentY - top) / cell
        return if (x < width && y < height) byteIndex(x, y) else -1
    }

    /**
     * 쓸 곳에서 획이 칠하는 곳(픽셀마다). 자국마다 반지름 [radius]보다 [dilate] 칸 넓은 원을 그려 본다.
     * 안드로이드 그리기(알파 그림)로 그리고 알파를 읽는다.
     */
    fun footprint(dabs: List<StrokePoint>, radius: Float, dilate: Float): BooleanArray {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        try {
            val canvas = Canvas(mask)
            val paint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            val scale = 1f / cell
            for (dab in dabs) canvas.drawCircle((dab.x - left) * scale, (dab.y - top) * scale, radius * scale + dilate, paint)
            val buffer = ByteBuffer.allocate(mask.rowBytes * height)
            mask.copyPixelsToBuffer(buffer)
            val alpha = buffer.array()
            return BooleanArray(width * height) { i -> alpha[(i / width) * mask.rowBytes + i % width].toInt() != 0 }
        } finally {
            mask.recycle()
        }
    }

    fun recycle() = bitmap.recycle()

    companion object {
        /** [raster]의 [wanted] 영역(문서 밖은 자름)을, 긴 변이 [maxEdge] 이하가 되는 가장 덜 줄인 단계로 그린다([at]). */
        fun of(raster: Raster, size: PixelSize, wanted: PixelRect, maxEdge: Int, checkCanceled: () -> Unit): LevelView? {
            val inside = wanted.intersect(PixelRect.of(size)) ?: return null
            var level = 0
            while (maxOf(inside.width, inside.height) > maxEdge shl level) level++
            return at(raster, size, inside, level, checkCanceled)
        }

        /**
         * [raster]의 [area](문서 밖은 자름)를 [level] 단계로 그린다. 쓸 곳의 왼쪽 위는 단계 칸에 맞춘다. 문서에 닿지 않으면 null.
         * 줄이지 않으면(0단계) 타일을 그대로 베끼고(소프트웨어, GPU를 기다리지 않는다), 줄이면 GPU로 축소본처럼 그린다.
         */
        fun at(raster: Raster, size: PixelSize, area: PixelRect, level: Int, checkCanceled: () -> Unit): LevelView? {
            val inside = area.intersect(PixelRect.of(size)) ?: return null
            val cell = 1 shl level
            val left = inside.left / cell * cell
            val top = inside.top / cell * cell
            if (level == 0) {
                val rect = PixelRect(left, top, inside.right, inside.bottom)
                return LevelView(Retouch.compose(raster, size, rect), 0, left, top, 0, 0, rect.width, rect.height)
            }
            val region = PreviewLevels.region(size, level, PixelRect(left, top, inside.right, inside.bottom)) ?: return null
            val drawn = PreviewRenderer.renderRegion(Document.create(size.width, size.height, "", raster), region, checkCanceled)
                ?: throw NotEnoughMemoryException()
            val bitmap = try {
                drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
            } finally {
                drawn.recycle()
            }
            val offsetX = (left shr level) - region.previewLeft
            val offsetY = (top shr level) - region.previewTop
            val width = minOf(ceilDiv(inside.right - left, cell), bitmap.width - offsetX)
            val height = minOf(ceilDiv(inside.bottom - top, cell), bitmap.height - offsetY)
            if (width <= 0 || height <= 0) {
                bitmap.recycle()
                return null
            }
            return LevelView(bitmap, level, left, top, offsetX, offsetY, width, height)
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }
}

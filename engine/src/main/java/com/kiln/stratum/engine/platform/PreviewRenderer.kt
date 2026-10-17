package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewBlock
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.HeapTilePixels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.TilePixels
import kotlin.math.roundToInt

/**
 * 문서를 화면용 축소본으로 그린다(GPU).
 *
 * 타일 8×8개 묶음마다 레이어를 아래부터 반 크기로 그리고, 필요한 단계까지 그 묶음을 다시 반씩 줄인 뒤
 * 마지막에 묶음들을 이어 붙인다. 그릴 때마다 [Picture]에 기록하고 하드웨어 비트맵으로 만들어 GPU가 그리게 한다.
 * 한 번에 GPU에 올리는 타일이 묶음 하나(약 16MB)뿐이라, 50MP 사진(약 200MB)을 통째로 올리지 않는다.
 *
 * 반씩 줄이면 GPU의 1/2 축소가 정확히 2×2 평균이라 화질이 고르고, 타일·묶음 경계가 정수 픽셀에 맞아 이음매가 없다.
 * 이번에는 표준 합성과 불투명도만 쓴다. 혼합 모드는 레이어 합성 작업에서 더한다.
 * GPU 작업이 끝날 때까지 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object PreviewRenderer {

    /** 타일을 줄여 그릴 때 쓰는 붓. 이웃 픽셀을 섞어(쌍선형) 줄인다. */
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * [document]를 [level] 단계(1/2^level 크기)로 그린 하드웨어 비트맵. GPU가 그리지 못하면 null.
     * [checkCanceled]는 묶음 사이마다 부르며, 취소됐으면 예외를 던지게 한다.
     */
    fun render(document: Document, level: Int, checkCanceled: () -> Unit = {}): Bitmap? {
        val size = PixelSize(document.width, document.height)
        val previewSize = PreviewLevels.previewSize(size, level)
        val blocks = PreviewLevels.blocks(size, level)
        val rendered = ArrayList<Pair<PreviewBlock, Bitmap>>(blocks.size)
        try {
            for (block in blocks) {
                checkCanceled()
                rendered += block to (renderBlock(document, block, level) ?: return null)
            }
            // 묶음이 하나뿐이면 그대로 축소본이다
            if (rendered.size == 1) return rendered.removeAt(0).second
            val picture = Picture()
            val canvas = picture.beginRecording(previewSize.width, previewSize.height)
            for ((block, bitmap) in rendered) {
                canvas.drawBitmap(bitmap, block.previewLeft.toFloat(), block.previewTop.toFloat(), null)
            }
            picture.endRecording()
            return drawOnGpu(picture, previewSize.width, previewSize.height)
        } finally {
            // GPU 그리기는 끝날 때까지 기다리므로, 이어 붙인 뒤에는 묶음 비트맵을 바로 돌려준다
            for ((_, bitmap) in rendered) bitmap.recycle()
        }
    }

    /** 묶음 하나를 [level] 단계로 그린다. 처음은 타일에서 바로 그리고, 그다음부터는 앞 결과를 반씩 줄인다. */
    private fun renderBlock(document: Document, block: PreviewBlock, level: Int): Bitmap? {
        var current = drawTiles(document, block, halve = level > 0) ?: return null
        var currentLevel = if (level > 0) 1 else 0
        while (currentLevel < level) {
            val next = halve(current)
            current.recycle()
            current = next ?: return null
            currentLevel++
        }
        return current
    }

    /** 묶음 안의 타일들을 레이어 아래부터 그린다. [halve]이면 반 크기로 그린다. */
    private fun drawTiles(document: Document, block: PreviewBlock, halve: Boolean): Bitmap? {
        val scale = if (halve) 0.5f else 1f
        val width = if (halve) (block.width + 1) / 2 else block.width
        val height = if (halve) (block.height + 1) / 2 else block.height
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        val target = RectF()
        // 힙에 담긴 타일(테스트·작은 데이터)은 그리는 동안만 비트맵으로 바꿔 쓴다
        val temporary = ArrayList<Bitmap>()
        try {
            for (layer in document.layers) {
                val raster = (layer.content as? RasterContent)?.raster ?: continue
                if (layer.opacity == 0f) continue
                // TODO(레이어 합성 작업): 혼합 모드. 지금은 모든 레이어를 표준으로 합성한다.
                // 불투명도가 1보다 작으면 레이어를 따로 그린 뒤 그만큼 투명하게 얹는다
                val saved = if (layer.opacity < 1f) {
                    canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (layer.opacity * 255).roundToInt())
                } else {
                    null
                }
                for (row in block.firstRow until block.firstRow + block.rows) {
                    for (column in block.firstColumn until block.firstColumn + block.columns) {
                        // 없는 타일은 투명이라 그리지 않는다
                        val tile = raster.tiles[TileKey(column, row)] ?: continue
                        val bitmap = bitmapOf(tile.pixels, temporary)
                        val left = (column - block.firstColumn) * TILE_SIZE * scale
                        val top = (row - block.firstRow) * TILE_SIZE * scale
                        target.set(left, top, left + bitmap.width * scale, top + bitmap.height * scale)
                        canvas.drawBitmap(bitmap, null, target, filterPaint)
                    }
                }
                if (saved != null) canvas.restoreToCount(saved)
            }
            picture.endRecording()
            return drawOnGpu(picture, width, height)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /** [bitmap]을 반 크기로 줄인다. 홀수 크기는 올림한다. */
    private fun halve(bitmap: Bitmap): Bitmap? {
        val width = (bitmap.width + 1) / 2
        val height = (bitmap.height + 1) / 2
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, bitmap.width / 2f, bitmap.height / 2f), filterPaint)
        picture.endRecording()
        return drawOnGpu(picture, width, height)
    }

    /** 타일 픽셀을 그릴 수 있는 비트맵으로. 힙 타일은 임시 비트맵을 만들어 [temporary]에 넣는다. */
    private fun bitmapOf(pixels: TilePixels, temporary: MutableList<Bitmap>): Bitmap =
        when (pixels) {
            is BitmapTilePixels -> pixels.bitmap
            is HeapTilePixels ->
                Bitmap.createBitmap(pixels.toIntArray(), TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
                    .also { temporary += it }
            else -> error("Unknown tile storage: ${pixels::class.java.name}")
        }

    /**
     * [picture]를 GPU로 그린 하드웨어 비트맵. 다 그릴 때까지 기다린다.
     * 함수에는 null이 아니라고 적혀 있지만, GPU가 실패하면 null을 돌려줄 수 있다(설계 검토에서 확인).
     */
    @Suppress("USELESS_CAST")
    private fun drawOnGpu(picture: Picture, width: Int, height: Int): Bitmap? =
        Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE) as Bitmap?
}

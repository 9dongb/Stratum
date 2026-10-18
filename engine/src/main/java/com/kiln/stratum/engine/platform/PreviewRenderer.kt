package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Shader
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewBlock
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.HeapTilePixels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.TilePixels
import kotlin.math.ceil

/**
 * 문서를 화면용 축소본으로 그린다(GPU).
 *
 * 타일 8×8개 묶음마다 레이어를 아래부터 반 크기로 그리고, 필요한 단계까지 그 묶음을 다시 반씩 줄인 뒤
 * 마지막에 묶음들을 이어 붙인다. 그릴 때마다 [Picture]에 기록하고 하드웨어 비트맵으로 만들어 GPU가 그리게 한다.
 * 한 번에 GPU에 올리는 타일이 묶음 하나(약 16MB)뿐이라, 50MP 사진(약 200MB)을 통째로 올리지 않는다.
 *
 * 반씩 줄이면 GPU의 1/2 축소가 정확히 2×2 평균이라 화질이 고르고, 타일·묶음 경계가 정수 픽셀에 맞아 이음매가 없다.
 * 홀수 크기를 반으로 줄이면 끝 픽셀이 반만 덮이는데, 그 픽셀은 가장자리 픽셀을 늘여 채운다([drawScaled]).
 * 레이어는 기본 보정을 걸어 그리고, 불투명도·혼합 모드(표준·곱하기·스크린·오버레이)로 아래에 얹는다.
 * GPU 작업이 끝날 때까지 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object PreviewRenderer {

    /**
     * [document]를 [level] 단계(1/2^level 크기)로 그린 하드웨어 비트맵. GPU가 그리지 못하면 null.
     * [checkCanceled]는 묶음 사이마다 부르며, 취소됐으면 예외를 던지게 한다.
     */
    fun render(document: Document, level: Int, checkCanceled: () -> Unit = {}): Bitmap? =
        renderRegion(document, PreviewLevels.wholeRegion(PixelSize(document.width, document.height), level), checkCanceled)

    /**
     * [document]의 [region] 부분만 그 단계로 그린 하드웨어 비트맵. 크기는 region.previewWidth×previewHeight다.
     * 확대해서 볼 때 보이는 곳만 선명하게 그리는 데 쓴다. 영역이 단계 격자에 맞춰져 있어,
     * 전체 축소본([render])의 같은 자리와 픽셀이 같다. GPU가 그리지 못하면 null.
     *
     * 한 번에 GPU에 넘기는 양은 묶음 하나(2048×2048px)다. GPU 작업은 화면 그리기와 같은 줄에서 돌아,
     * 큰 영역을 한꺼번에 넘기면 그동안 화면이 멈칫한다.
     */
    fun renderRegion(document: Document, region: PreviewRegion, checkCanceled: () -> Unit = {}): Bitmap? {
        val size = PixelSize(document.width, document.height)
        require(region.rect in PixelRect.of(size)) { "Region outside document: ${region.rect} in $size" }
        val level = region.level
        val blocks = PreviewLevels.blocks(region)
        val rendered = ArrayList<Pair<PreviewBlock, Bitmap>>(blocks.size)
        try {
            for (block in blocks) {
                checkCanceled()
                rendered += block to (renderBlock(document, block, level) ?: return null)
            }
            // 묶음이 하나뿐이면 그대로 결과다
            if (rendered.size == 1) return rendered.removeAt(0).second
            val picture = Picture()
            val canvas = picture.beginRecording(region.previewWidth, region.previewHeight)
            for ((block, bitmap) in rendered) {
                canvas.drawBitmap(bitmap, block.previewLeft.toFloat(), block.previewTop.toFloat(), null)
            }
            picture.endRecording()
            return drawOnGpu(picture, region.previewWidth, region.previewHeight)
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
        // 힙에 담긴 타일(테스트·작은 데이터)은 그리는 동안만 비트맵으로 바꿔 쓴다
        val temporary = ArrayList<Bitmap>()
        try {
            for (layer in document.layers) {
                val raster = (layer.content as? RasterContent)?.raster ?: continue
                if (!layer.isShown) continue
                // 보정 값이 있으면 타일마다 보정 셰이더를 건다(이웃 픽셀을 보지 않아 레이어 전체에 건 것과 같다)
                val adjust = AdjustShader.of(layer.adjustments)
                // 불투명도가 1보다 작거나 표준이 아니면 레이어를 따로 그린 뒤, 그 불투명도·혼합 모드로 아래에 얹는다.
                // 타일은 서로 겹치지 않아 타일마다 섞어도 되지만, 불투명도는 레이어 전체에 한 번 걸어야 한다.
                val saved = if (layer.needsOwnLayer) {
                    canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), layerPaint(layer))
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
                        drawScaled(canvas, bitmap, left, top, scale, scale, adjust)
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

    /** [bitmap]을 반 크기로 줄인다(정확히 2×2 평균). 홀수 크기는 올림한다. GPU가 그리지 못하면 null. */
    internal fun halve(bitmap: Bitmap): Bitmap? {
        val width = (bitmap.width + 1) / 2
        val height = (bitmap.height + 1) / 2
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        drawScaled(canvas, bitmap, 0f, 0f, 0.5f)
        picture.endRecording()
        return drawOnGpu(picture, width, height)
    }

    /**
     * [bitmap]을 한 축만 반으로 줄인다([horizontal]이면 가로). 다른 축은 그대로다. 홀수 크기는 올림한다.
     * 크기를 바꿀 때 가로·세로 배율이 달라, 한쪽만 더 줄여야 할 때 쓴다. 정확히 이웃 두 픽셀의 평균이다.
     */
    internal fun halveAxis(bitmap: Bitmap, horizontal: Boolean): Bitmap? {
        val width = if (horizontal) (bitmap.width + 1) / 2 else bitmap.width
        val height = if (horizontal) bitmap.height else (bitmap.height + 1) / 2
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        drawScaled(canvas, bitmap, 0f, 0f, if (horizontal) 0.5f else 1f, if (horizontal) 1f else 0.5f)
        picture.endRecording()
        return drawOnGpu(picture, width, height)
    }

    /**
     * [bitmap]을 [scale]배 크기로 ([left], [top])에 그린다. 이웃 픽셀을 섞어(쌍선형) 줄인다.
     *
     * 그리는 크기는 올림해서 끝 픽셀까지 덮는다. 홀수 크기를 반으로 줄이면 끝 픽셀이 반만 덮여서,
     * 사각형 그대로 그리면 그 줄이 투명하게 빠진다(에뮬레이터 테스트로 확인).
     * 그래서 셰이더로 그리고, 비트맵 밖은 가장자리 픽셀을 늘여(CLAMP) 채운다.
     * 짝수 크기 타일 안쪽은 지금처럼 정확히 2×2 평균이고, 늘인 픽셀은 끝 줄에만 쓰인다.
     */
    private fun drawScaled(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, scale: Float) =
        drawScaled(canvas, bitmap, left, top, scale, scale)

    /** [drawScaled]를 가로 [scaleX], 세로 [scaleY] 배로 그린다. [adjust]가 있으면 보정해서 그린다. */
    private fun drawScaled(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        scaleX: Float,
        scaleY: Float,
        adjust: AdjustShader? = null
    ) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply {
                setScale(scaleX, scaleY)
                postTranslate(left, top)
            })
        }
        val right = left + ceil(bitmap.width * scaleX)
        val bottom = top + ceil(bitmap.height * scaleY)
        canvas.drawRect(left, top, right, bottom, Paint().apply { this.shader = adjust?.wrap(shader) ?: shader })
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
    internal fun drawOnGpu(picture: Picture, width: Int, height: Int): Bitmap? =
        Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE) as Bitmap?
}

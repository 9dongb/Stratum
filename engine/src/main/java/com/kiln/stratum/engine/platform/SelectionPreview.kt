package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.selection.MaskContour
import com.kiln.stratum.engine.selection.Selection
import java.nio.ByteBuffer

/**
 * 선택 영역을 화면용으로 줄여 그린다: 끄는 동안의 획을 선택 영역 안으로 자르는 알파 그림과, 윤곽선(개미 행렬).
 * 줄여 그리기는 안드로이드 소프트웨어 그리기이고, 윤곽선은 줄인 마스크(긴 변 [OUTLINE_MAX_EDGE] 이하)에서 찾는다.
 */
object SelectionPreview {

    /** 윤곽선을 찾는 줄인 마스크의 긴 변 최대 픽셀 */
    const val OUTLINE_MAX_EDGE = 1024

    /**
     * [selection]의 [region]을 그 단계로 줄여 그린 알파 비트맵(바꿀 수 없음). 크기는 region.previewWidth×previewHeight.
     * 영역은 문서 안이라, 문서 끝 타일의 밖 부분은 그림 밖으로 잘린다.
     */
    fun mask(selection: Selection, region: PreviewRegion): Bitmap {
        val scale = 1f / (1 shl region.level)
        val picture = Picture()
        val canvas = picture.beginRecording(region.previewWidth, region.previewHeight)
        canvas.scale(scale, scale)
        canvas.translate(-region.rect.left.toFloat(), -region.rect.top.toFloat())
        val paint = Paint().apply { isFilterBitmap = true }
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, tile) in selection.tiles) {
                val rect = PixelRect(key.column * TILE_SIZE, key.row * TILE_SIZE, (key.column + 1) * TILE_SIZE, (key.row + 1) * TILE_SIZE)
                if (rect.intersect(region.rect) == null) continue
                val bitmap = MaskTiles.bitmapOf(tile, temporary)
                if (bitmap == null) {
                    canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
                } else {
                    canvas.drawBitmap(bitmap, rect.left.toFloat(), rect.top.toFloat(), paint)
                }
            }
            picture.endRecording()
            return Bitmap.createBitmap(picture, region.previewWidth, region.previewHeight, Bitmap.Config.ALPHA_8)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /**
     * [selection]의 [region] 안 윤곽선(문서 좌표, 점들 x0, y0, x1, y1, …). [MaskContour]로 찾는다.
     * [region]은 윤곽선 하나에 쓸 만큼 작아야 한다(긴 변 [OUTLINE_MAX_EDGE] 이하가 되는 단계).
     */
    fun outline(selection: Selection, region: PreviewRegion): List<FloatArray> {
        if (selection.isEmpty) return emptyList()
        val bitmap = mask(selection, region)
        val width = bitmap.width
        val height = bitmap.height
        val alphas = try {
            alphasOf(bitmap)
        } finally {
            bitmap.recycle()
        }
        val scale = (1 shl region.level).toFloat()
        val left = region.rect.left.toFloat()
        val top = region.rect.top.toFloat()
        return MaskContour.trace(alphas, width, height).map { line ->
            FloatArray(line.size) { i -> if (i % 2 == 0) left + line[i] * scale else top + line[i] * scale }
        }
    }

    /** 문서 전체 윤곽선에 쓸 단계: 긴 변이 [OUTLINE_MAX_EDGE] 이하가 되는 가장 작은 단계 */
    fun outlineLevel(size: PixelSize): Int {
        var level = 0
        while (true) {
            val preview = PreviewLevels.previewSize(size, level)
            if (maxOf(preview.width, preview.height) <= OUTLINE_MAX_EDGE) return level
            level++
        }
    }

    /** 알파 비트맵의 픽셀(한 줄씩, 줄 끝 여백 없이) */
    internal fun alphasOf(bitmap: Bitmap): ByteArray {
        val rows = ByteArray(bitmap.rowBytes * bitmap.height)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rows))
        if (bitmap.rowBytes == bitmap.width) return rows
        val packed = ByteArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) System.arraycopy(rows, y * bitmap.rowBytes, packed, y * bitmap.width, bitmap.width)
        return packed
    }
}

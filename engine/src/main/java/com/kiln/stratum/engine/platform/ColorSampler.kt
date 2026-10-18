package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Color
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.TILE_SIZE
import kotlin.math.floor

/**
 * 스포이드(결정 기록 '스포이드'): 문서 한 점에 보이는 색을 고른다.
 *
 * 보이는 레이어를 모두(보정·불투명도·혼합 모드 반영, 숨긴 레이어는 빠짐) 원본 해상도로, 그 점이 든 타일 한 장만 GPU로 그려 읽는다
 * (축소본·내보내기와 같은 그리기, [PreviewRenderer.renderRegion]). 화면 코드는 픽셀을 읽지 않는다(CLAUDE.md 3장).
 * GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object ColorSampler {

    /**
     * 문서 좌표 ([x], [y])에 보이는 색(불투명한 sRGB `0xAARRGGBB`). 반투명한 곳은 알파를 빼고 색만 쓴다.
     * 투명한 곳이거나 문서 밖이거나 GPU가 그리지 못하면 null.
     * P3 문서는 sRGB로 바꾼다(브러시·글자 색은 sRGB로 고르고 칠할 때 문서 색으로 바꾼다).
     */
    fun sample(document: Document, x: Float, y: Float): Int? {
        val column = floor(x).toInt()
        val row = floor(y).toInt()
        if (column !in 0 until document.width || row !in 0 until document.height) return null
        // 그리는 영역은 타일 격자에서 시작해야 해서(PreviewRenderer), 그 점이 든 타일 한 장(256px)을 그려 그 자리를 읽는다
        val left = column / TILE_SIZE * TILE_SIZE
        val top = row / TILE_SIZE * TILE_SIZE
        val tile = PixelRect(left, top, minOf(left + TILE_SIZE, document.width), minOf(top + TILE_SIZE, document.height))
        val drawn = PreviewRenderer.renderRegion(document, PreviewRegion(0, tile)) ?: return null
        val pixel = try {
            val readable = drawn.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            try {
                readable.getPixel(column - left, row - top)
            } finally {
                readable.recycle()
            }
        } finally {
            drawn.recycle()
        }
        if (Color.alpha(pixel) == 0) return null
        // getPixel은 알파를 곱하지 않은 값이다. 브러시·글자 색은 알파를 쓰지 않으므로 불투명하게 둔다.
        return DocumentColors.pickedColor(pixel or OPAQUE, document.colorSpace)
    }

    private const val OPAQUE = 0xFF000000.toInt()
}

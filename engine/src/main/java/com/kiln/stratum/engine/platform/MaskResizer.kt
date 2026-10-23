package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Shader
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.transform.ResizePlan

/**
 * 레이어 마스크의 크기를 레이어 픽셀과 같은 방법으로 바꾼다(이미지 크기 변경, 결정 기록 '피부 보정'의 1c).
 * 마스크 알파를 흰색 픽셀 타일(알파를 미리 곱한 흰색)로 옮겨 [ImageResizer]로 바꾼 뒤 알파만 다시 꺼낸다.
 * 그래서 마스크 경계가 픽셀과 꼭 같이 줄거나 커진다. 다 보이는 타일은 한 가지 색 타일 한 장으로 옮겨 메모리를 쓰지 않는다.
 * GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
internal object MaskResizer {

    /** [mask](문서 [plan].source 크기)를 [plan]대로 바꾼 새 마스크. 메모리가 모자라면 [NotEnoughMemoryException]. */
    fun resize(mask: Selection, plan: ResizePlan, checkCanceled: () -> Unit): Selection {
        if (mask.isEmpty) return mask
        val resized = ImageResizer.resize(toRaster(mask, plan.source, checkCanceled), plan, checkCanceled)
        return fromRaster(resized, plan.target, checkCanceled)
    }

    /** 흰색 한 가지 색 타일(다 보이는 마스크 타일 자리가 함께 쓴다) */
    private val WHITE = Tile.solid(Color.WHITE)

    /** 마스크 알파를 흰색 픽셀(알파를 미리 곱한 값이라 (a, a, a, a))로 옮긴 래스터 */
    private fun toRaster(mask: Selection, size: PixelSize, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, Tile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, tile) in mask.tiles) {
                checkCanceled()
                val alpha = MaskTiles.bitmapOf(tile, temporary)
                if (alpha == null) {
                    tiles[key] = WHITE
                    continue
                }
                val bounds = grid.bounds(key)
                val picture = Picture()
                val canvas = picture.beginRecording(bounds.width, bounds.height)
                canvas.drawColor(Color.WHITE)
                MaskTiles.drawAlpha(canvas, alpha, BlendMode.DST_IN)
                picture.endRecording()
                tiles[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)))
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 래스터의 알파만 꺼낸 마스크(문서 [size]). 다 0인 타일은 비우고 다 255면 다 보이는 타일이다. */
    private fun fromRaster(raster: Raster, size: PixelSize, checkCanceled: () -> Unit): Selection {
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, MaskTile>()
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, tile) in raster.tiles) {
                checkCanceled()
                if (tile.solidColor == Color.WHITE) {
                    tiles[key] = MaskTile.FULL
                    continue
                }
                val bounds = grid.bounds(key)
                val pixels = CanvasTileComposer.bitmapOf(tile, temporary)
                val alpha = MaskTiles.draw(bounds.width, bounds.height) { canvas ->
                    canvas.drawRect(0f, 0f, bounds.width.toFloat(), bounds.height.toFloat(), Paint().apply {
                        shader = BitmapShader(pixels, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
                        blendMode = BlendMode.SRC
                    })
                }
                if (alpha != null) tiles[key] = alpha
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Selection.of(tiles)
    }
}

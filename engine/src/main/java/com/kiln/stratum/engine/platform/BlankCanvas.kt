package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.util.Log
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.canvas.CanvasLimits
import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid

/**
 * 빈 새 캔버스 문서를 만든다.
 *
 * 투명 배경은 타일을 두지 않고, 한 가지 색은 그 색으로 칠한 타일 한 장(자바 힙 밖 비트맵)을 모든 자리가 함께 쓴다.
 * 그래서 캔버스 크기와 상관없이 메모리를 거의 쓰지 않는다.
 */
object BlankCanvas {

    /**
     * [width]×[height] 캔버스를 [fill]로 채운 문서. 첫 레이어의 이름은 [layerName]이다.
     * 크기는 [CanvasLimits] 안이어야 한다. 타일 비트맵을 만들 메모리가 없으면 null.
     */
    fun create(width: Int, height: Int, fill: CanvasFill, layerName: String): Document? {
        require(CanvasLimits.check(width, height) == CanvasSizeCheck.OK) { "Canvas size not allowed: ${width}x$height" }
        val raster = when (fill) {
            CanvasFill.Transparent -> Raster.EMPTY
            is CanvasFill.Solid -> {
                val tile = try {
                    solidTile(fill.argb)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Not enough memory for a canvas tile", e)
                    null
                } ?: return null
                Raster.filled(TileGrid(width, height), tile)
            }
        }
        return Document.create(width, height, layerName, raster)
    }

    /** [argb] 한 색으로 칠한 꽉 찬 타일. 바꿀 수 없는 복사본을 만들 메모리가 없으면 null. 프로젝트를 불러올 때도 쓴다. */
    internal fun solidTile(argb: Int): Tile? {
        val scratch = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            // 안드로이드 내부 채우기라 CPU로 픽셀을 하나씩 돌지 않는다
            scratch.eraseColor(argb)
            // 비트맵을 바꿀 수 없게 하는 공개 함수가 없어서 copy(…, false)로 복사본을 만든다
            val bitmap = scratch.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            // 한 가지 색이라고 표시해 두면, 돌리거나 잘라도 이 타일 한 장을 그대로 쓴다
            return Tile.of(BitmapTilePixels(bitmap), solidColor = argb)
        } finally {
            scratch.recycle()
        }
    }

    private const val TAG = "BlankCanvas"
}

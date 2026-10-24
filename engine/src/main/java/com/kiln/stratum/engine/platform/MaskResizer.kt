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
import com.kiln.stratum.engine.raster.TILE_SIZE
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

    /**
     * [mask](문서 [plan].source 크기)를 [plan]대로 바꾼 새 마스크. 메모리가 모자라면 [NotEnoughMemoryException].
     * [pixels]는 마스크가 딸린 레이어의 픽셀이다: 캔버스 밖 픽셀이 있으면 다 보이는 타일이 그 픽셀 자리까지 덮게 바꾼다(결정 기록 '캔버스 밖 픽셀').
     * [endless]면 조정 레이어의 바깥이 보이는 마스크다(캔버스 밖의 타일 없는 곳이 보임, [com.kiln.stratum.engine.transform.MaskTransform]).
     * 그대로 바꾸면 캔버스 밖 마스크 타일 둘레의 빈 곳을 0(투명)으로 읽어 바이큐빅이 가장자리로 번지므로(가장자리가 다 보이지 않게 됨),
     * 뒤집어(가린 곳이 흰색) 바꾼 뒤 다시 뒤집는다: 뒤집으면 빈 곳이 정말 0이다(검토에서 고침).
     */
    fun resize(mask: Selection, plan: ResizePlan, checkCanceled: () -> Unit, pixels: Raster? = null, endless: Boolean = false): Selection {
        if (mask.isEmpty) return mask
        if (endless) {
            val resized = ImageResizer.resize(invertedRaster(mask, plan.source, checkCanceled), plan, checkCanceled)
            return fromInverted(resized, plan.target, checkCanceled)
        }
        val resized = ImageResizer.resize(toRaster(mask, plan.source, pixels, checkCanceled), plan, checkCanceled)
        return fromRaster(resized, checkCanceled)
    }

    /**
     * 끝이 없는 마스크를 뒤집은 알파(255 - 알파)를 흰색 픽셀로 옮긴 래스터. 캔버스 안의 타일 없는 자리(가림)는 흰색(캔버스 안쪽 크기),
     * 다 보이는 타일은 비우고(뒤집으면 0), 경계가 있는 타일은 담은 크기만큼 뒤집는다. 캔버스 밖의 타일 없는 곳(보임)은 뒤집으면 0이라 비운다.
     */
    private fun invertedRaster(mask: Selection, size: PixelSize, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, Tile?>()
        val whites = HashMap<PixelSize, Tile>()
        val temporary = ArrayList<Bitmap>()
        try {
            for (key in grid.keys()) {
                if (key in mask.tiles) continue
                val shape = grid.bounds(key).let { PixelSize(it.width, it.height) }
                tiles[key] = whites.getOrPut(shape) { WHITE.cropped(shape.width, shape.height) }
            }
            for ((key, tile) in mask.tiles) {
                checkCanceled()
                val alpha = MaskTiles.bitmapOf(tile, temporary) ?: continue
                val picture = Picture()
                val canvas = picture.beginRecording(alpha.width, alpha.height)
                canvas.drawColor(Color.WHITE)
                MaskTiles.drawAlpha(canvas, alpha, BlendMode.DST_OUT)
                picture.endRecording()
                tiles[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, alpha.width, alpha.height, Bitmap.Config.ARGB_8888)))
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /**
     * [invertedRaster]를 바꾼 [raster]를 다시 뒤집은 끝이 없는 마스크(문서 [size]). 타일이 없는 캔버스 자리는 다 보이고, 타일은 꽉 찬 크기로 뒤집는다
     * (담지 않은 곳은 캔버스 밖이라 보임). 다 가린 타일은 캔버스 밖으로 담은 곳이 있으면(캔버스 밖·끝 자리) 0인 타일로 적어 두고,
     * 캔버스 밖의 다 보이는 타일은 바깥과 같아 두지 않는다([com.kiln.stratum.engine.transform.MaskTransform]과 같은 규칙).
     */
    private fun fromInverted(raster: Raster, size: PixelSize, checkCanceled: () -> Unit): Selection {
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, MaskTile>()
        val temporary = ArrayList<Bitmap>()
        try {
            for (key in grid.keys()) {
                if (key !in raster.tiles) tiles[key] = MaskTile.FULL
            }
            for ((key, tile) in raster.tiles) {
                checkCanceled()
                val inside = grid.canvasRect(key)
                val pixels = CanvasTileComposer.bitmapOf(tile, temporary)
                val alpha = MaskTiles.draw(TILE_SIZE, TILE_SIZE) { canvas ->
                    canvas.drawColor(Color.BLACK)
                    MaskTiles.drawAlpha(canvas, pixels, BlendMode.DST_OUT)
                }
                val kept = when {
                    alpha == null -> MaskTiles.empty.takeIf { inside == null || inside.width < TILE_SIZE || inside.height < TILE_SIZE }
                    alpha.isFull -> alpha.takeIf { inside != null }
                    else -> alpha
                }
                kept?.let { tiles[key] = it }
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Selection.of(tiles)
    }

    /** 흰색 한 가지 색 타일(다 보이는 마스크 타일 자리가 함께 쓴다) */
    private val WHITE = Tile.solid(Color.WHITE)

    /**
     * 마스크 알파를 흰색 픽셀(알파를 미리 곱한 값이라 (a, a, a, a))로 옮긴 래스터.
     * 다 보이는 타일은 흰색 한 가지 색 타일이고, 캔버스 안 자리는 캔버스 안쪽 크기(그 자리 [pixels] 타일이 캔버스 밖까지 담았으면 그만큼),
     * 캔버스 밖 자리는 그 자리 픽셀 타일 크기(없으면 꽉 찬 타일)다. 크기마다 한 장을 함께 쓴다. 경계가 있는 타일은 담은 크기 그대로다.
     */
    private fun toRaster(mask: Selection, size: PixelSize, pixels: Raster?, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, Tile?>()
        val whites = HashMap<PixelSize, Tile>()
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, tile) in mask.tiles) {
                checkCanceled()
                val alpha = MaskTiles.bitmapOf(tile, temporary)
                if (alpha == null) {
                    val under = pixels?.tiles?.get(key)
                    val shape = grid.canvasRect(key)?.let { PixelSize(maxOf(it.width, under?.width ?: 0), maxOf(it.height, under?.height ?: 0)) }
                        ?: under?.let { PixelSize(it.width, it.height) }
                        ?: PixelSize(TILE_SIZE, TILE_SIZE)
                    tiles[key] = whites.getOrPut(shape) { WHITE.cropped(shape.width, shape.height) }
                    continue
                }
                val picture = Picture()
                val canvas = picture.beginRecording(alpha.width, alpha.height)
                canvas.drawColor(Color.WHITE)
                MaskTiles.drawAlpha(canvas, alpha, BlendMode.DST_IN)
                picture.endRecording()
                tiles[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, alpha.width, alpha.height, Bitmap.Config.ARGB_8888)))
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 래스터의 알파만 꺼낸 마스크(타일마다 담은 크기 그대로). 다 0인 타일은 비우고 다 255면 다 보이는 타일이다. */
    private fun fromRaster(raster: Raster, checkCanceled: () -> Unit): Selection {
        val tiles = HashMap<TileKey, MaskTile>()
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, tile) in raster.tiles) {
                checkCanceled()
                if (tile.solidColor == Color.WHITE) {
                    tiles[key] = MaskTile.FULL
                    continue
                }
                val pixels = CanvasTileComposer.bitmapOf(tile, temporary)
                val alpha = MaskTiles.draw(tile.width, tile.height) { canvas ->
                    canvas.drawRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat(), Paint().apply {
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

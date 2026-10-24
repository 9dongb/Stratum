package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Picture
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileKeysIn
import com.kiln.stratum.engine.selection.Selection

/**
 * [layerId] 레이어에 브러시 획 하나를 그린다([erase]면 지운다). 원본 해상도에 바로 그린다(결정 기록 '원본 픽셀').
 *
 * 획이 닿는 타일만 새로 만들고 나머지는 앞 문서와 함께 쓴다. 빈 자리를 지우면 그대로 비워 둔다.
 * 선택 영역이 있으면 고른 곳에만 그린다(고르지 않은 타일은 건너뛴다).
 * 타일은 안드로이드 소프트웨어 그리기로 만든다(결정 기록 '브러시 기록은 안드로이드 내부 그리기'). 획이 닿지 않은 픽셀은 값이 그대로다.
 * 색([BrushSettings.color])은 sRGB로 고른 색이라, 문서 색 공간 값으로 바꿔 칠한다(P3 문서에서도 같은 색으로 보임).
 * 없는 레이어거나 문서에 닿지 않는 획이면 문서를 그대로 돌려준다.
 */
data class PaintStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val erase: Boolean
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: return document
        val colored = settings.copy(color = DocumentColors.paintColor(settings.color, document.colorSpace))
        val painted = try {
            BrushTiles.paint(raster, size, bounds, dabs, colored, erase, document.selection, checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (painted === raster) return document
        // 칠한 곳의 게인맵은 '밝히지 않음'으로 칠한다(칠한 색이 HDR로 밝아지지 않게). 지우개는 알파가 대신해 그대로 둔다.
        val info = document.hdr
        val gainmap = try {
            layer.gainmap?.let { if (erase || info == null) it else GainmapEdits.neutralize(it, size, dabs, settings, info, document.selection, checkCanceled) }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        val layers = document.layers.toMutableList().apply { set(index, layer.withPixels(painted, gainmap)) }
        return document.copy(layers = layers)
    }

    /** 획이 닿는 타일마다 새 타일 한 장(지우개는 있는 타일만) */
    override fun estimateNewBytes(document: Document): Long {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return 0L
        val raster = (layer.content as? RasterContent)?.raster ?: return 0L
        val bounds = StrokeDabs.bounds(dabs, settings.radius, PixelSize(document.width, document.height)) ?: return 0L
        val keys = BrushTiles.keysIn(bounds)
        val count = if (erase) keys.count { it in raster.tiles } else keys.size
        return count.toLong() * TILE_SIZE * TILE_SIZE * Int.SIZE_BYTES
    }
}

/** 브러시 획을 타일에 그린다(소프트웨어). */
internal object BrushTiles {

    /** [bounds](문서 안)에 닿는 타일 자리 */
    fun keysIn(bounds: PixelRect): List<TileKey> = tileKeysIn(bounds)

    /**
     * [raster]의 [bounds] 안 타일에 획을 그린 새 래스터. 바뀐 것이 없으면 [raster]를 그대로 돌려준다.
     * 타일마다 그 타일에 닿는 자국만 그린다. [selection]이 있으면 고른 곳에만 그린다. [checkCanceled]는 타일 사이마다 부른다.
     */
    fun paint(
        raster: Raster,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        erase: Boolean,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): Raster {
        val grid = TileGrid(size.width, size.height)
        val reach = settings.radius + 1f
        val changes = HashMap<TileKey, Tile?>()
        // 힙에 담긴 타일(테스트·작은 데이터)은 그리는 동안만 비트맵으로 바꿔 쓴다
        val temporary = ArrayList<Bitmap>()
        try {
            for (key in keysIn(bounds)) {
                checkCanceled()
                // 선택 영역이 있는데 이 자리를 하나도 고르지 않았으면 그릴 것이 없다
                val mask = if (selection == null) null else (selection.tiles[key] ?: continue)
                val old = raster.tiles[key]
                // 빈 자리는 지울 것이 없다
                if (old == null && erase) continue
                val tile = grid.bounds(key)
                val near = dabs.filter {
                    it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                        it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                }
                if (near.isEmpty()) continue
                // 끝 타일의 캔버스 밖 픽셀은 그대로 두고 캔버스 안쪽만 칠한다(결정 기록 '캔버스 밖 픽셀')
                changes[key] = TileRebuild.pixels(key, old, grid, temporary) { canvas ->
                    BrushPainter.drawStroke(
                        canvas, near, settings, erase,
                        scale = 1f,
                        left = tile.left.toFloat(),
                        top = tile.top.toFloat(),
                        width = tile.width.toFloat(),
                        height = tile.height.toFloat(),
                        clip = mask?.let { MaskTiles.bitmapOf(it, temporary) }
                    )
                }
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return if (changes.isEmpty()) raster else raster.withTiles(changes)
    }
}

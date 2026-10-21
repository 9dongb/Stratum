package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Picture
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey

/**
 * [layerId] 레이어를 바로 아래 레이어에 합친다(포토샵 '아래 레이어와 병합').
 *
 * 결과는 아래 레이어의 번호·이름·불투명도·혼합 모드를 지닌다. 아래 레이어의 보정과 위 레이어의 불투명도·혼합 모드·보정은
 * 픽셀에 굳힌다(결과의 보정은 없음). 없는 레이어이거나 맨 아래 레이어면 그대로 둔다.
 * 둘 중 하나라도 숨겼으면(눈을 끔) 그대로 둔다. 숨긴 내용을 모르고 합치거나 잃지 않게 한다(결정 기록 '레이어 보이기').
 */
data class MergeDown(val layerId: LayerId) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (!canMerge(document, index)) return document
        val lower = document.layers[index - 1]
        val merging = mergedLayers(document, index)
        val merged = LayerMerger.merge(document, merging, checkCanceled)
        // 게인맵도 같은 알파로 겹쳐 합친다(결정 기록 'HDR')
        val gainmap = GainmapEdits.merge(document, merging, checkCanceled)
        val layers = document.layers.toMutableList().apply {
            set(index - 1, lower.withPixels(merged, gainmap).copy(adjustments = Adjustments.NONE))
            removeAt(index)
        }
        return document.copy(layers = layers)
    }

    override fun estimateNewBytes(document: Document): Long {
        val index = document.indexOfLayer(layerId)
        return if (!canMerge(document, index)) 0L else LayerMerger.estimateNewBytes(mergedLayers(document, index))
    }

    /** [index] 레이어를 아래와 합칠 수 있는지: 맨 아래가 아니고 두 레이어가 다 보인다 */
    private fun canMerge(document: Document, index: Int): Boolean =
        index > 0 && document.layers[index].visible && document.layers[index - 1].visible

    /** 합칠 두 레이어. 아래 레이어의 불투명도·혼합 모드는 결과 레이어에 그대로 남으므로 픽셀에는 넣지 않는다(보정은 굳힌다). */
    private fun mergedLayers(document: Document, index: Int): List<Layer> =
        listOf(document.layers[index - 1].copy(opacity = 1f, blendMode = BlendMode.NORMAL), document.layers[index])
}

/**
 * 보이는(숨기지 않았고 불투명도가 0보다 큰) 레이어를 모두 가장 아래 보이는 레이어 하나로 합친다(포토샵 '보이는 레이어 병합').
 *
 * 결과는 그 레이어의 번호·이름을 지니고 표준·100%·보정 없음이라, 화면에 보이던 모습 그대로다.
 * 숨긴(눈을 끈·0%) 레이어는 제자리에 그대로 남는다. 보이는 레이어가 둘보다 적으면 그대로 둔다.
 */
data object MergeVisible : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val visible = document.layers.filter { it.isShown }
        if (visible.size < 2) return document
        val base = visible.first()
        val merged = LayerMerger.merge(document, visible, checkCanceled)
        val gainmap = GainmapEdits.merge(document, visible, checkCanceled)
        val result = base.withPixels(merged, gainmap).copy(opacity = 1f, blendMode = BlendMode.NORMAL, adjustments = Adjustments.NONE)
        val layers = document.layers.mapNotNull { layer ->
            when {
                layer.id == base.id -> result
                layer.isShown -> null
                else -> layer
            }
        }
        return document.copy(layers = layers)
    }

    override fun estimateNewBytes(document: Document): Long {
        val visible = document.layers.filter { it.isShown }
        return if (visible.size < 2) 0L else LayerMerger.estimateNewBytes(visible)
    }
}

/**
 * 레이어 여럿을 원본 해상도의 한 래스터로 합친다(아래부터 각자의 불투명도·혼합 모드·보정으로).
 *
 * 타일 자리마다, 그곳에 픽셀이 있는 보이는 레이어가 하나뿐이고 그 레이어가 그대로 보이면(불투명도 1, 보정 없음)
 * 그 타일 객체를 그대로 쓴다. 투명한 바탕 위에서는 혼합 모드와 상관없이 그 레이어 그대로이고, 값이 1/255도 바뀌지 않는다.
 * 나머지 자리는 1024px 묶음마다 합칠 레이어만 담은 문서를 축소본과 같은 방법(0단계, GPU)으로 그리고 읽어 와 타일로 나눈다.
 * GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
internal object LayerMerger {

    /** 묶음 한 변의 타일 수(1024px). 한 번에 GPU에 넘기는 양이다(크기 변경·유동화와 같다). */
    private const val BLOCK_TILES = 4

    /** [layers](아래부터)를 합친 래스터. 메모리가 모자라면 [NotEnoughMemoryException]을 던진다. */
    fun merge(document: Document, layers: List<Layer>, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(document.width, document.height)
        val (kept, render) = plan(layers)
        val tiles = HashMap<TileKey, Tile?>(kept)
        if (render.isEmpty()) return Raster.EMPTY.withTiles(tiles)
        val merging = Document(document.width, document.height, layers, document.nextLayerId)
        try {
            for (keys in render.groupBy { TileKey(it.column / BLOCK_TILES, it.row / BLOCK_TILES) }.values) {
                checkCanceled()
                val area = boundsOf(keys, grid)
                val drawn = PreviewRenderer.renderRegion(merging, PreviewRegion(0, area), checkCanceled) ?: throw NotEnoughMemoryException()
                val pixels = try {
                    drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
                } finally {
                    drawn.recycle()
                }
                try {
                    for (key in keys) tiles[key] = tileOf(pixels, area, key, grid)
                } finally {
                    pixels.recycle()
                }
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 합치면 새로 생길 메모리의 어림값: 다시 그릴 타일과 묶음 그림 한 장(GPU·읽어 온 것) */
    fun estimateNewBytes(layers: List<Layer>): Long {
        val render = plan(layers).second
        if (render.isEmpty()) return 0L
        val tileBytes = TILE_SIZE.toLong() * TILE_SIZE * Int.SIZE_BYTES
        val blockBytes = tileBytes * BLOCK_TILES * BLOCK_TILES * 2
        return render.size * tileBytes + blockBytes
    }

    /** 그대로 쓸 타일(자리 → 타일)과 다시 그릴 자리 */
    private fun plan(layers: List<Layer>): Pair<Map<TileKey, Tile>, List<TileKey>> {
        val shown = layers.filter { it.isShown }
        val rasters = shown.map { (it.content as RasterContent).raster }
        val kept = HashMap<TileKey, Tile>()
        val render = ArrayList<TileKey>()
        val keys = LinkedHashSet<TileKey>()
        for (raster in rasters) keys += raster.tiles.keys
        for (key in keys) {
            val present = shown.indices.filter { rasters[it].tiles[key] != null }
            val only = present.singleOrNull()
            if (only != null && shown[only].opacity == 1f && shown[only].adjustments.isIdentity) {
                kept[key] = checkNotNull(rasters[only].tiles[key])
            } else {
                render += key
            }
        }
        return kept to render
    }

    /** [keys] 타일들을 모두 덮는 문서 사각형(묶음 하나 안) */
    private fun boundsOf(keys: List<TileKey>, grid: TileGrid): PixelRect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = 0
        var bottom = 0
        for (key in keys) {
            val bounds = grid.bounds(key)
            left = minOf(left, bounds.left)
            top = minOf(top, bounds.top)
            right = maxOf(right, bounds.left + bounds.width)
            bottom = maxOf(bottom, bounds.top + bounds.height)
        }
        return PixelRect(left, top, right, bottom)
    }

    /** [area]를 그린 [pixels]에서 [key] 타일을 베껴 낸다(값 그대로, 끝 타일은 캔버스 안쪽 크기만) */
    private fun tileOf(pixels: Bitmap, area: PixelRect, key: TileKey, grid: TileGrid): Tile {
        val bounds = grid.bounds(key)
        val picture = Picture()
        val canvas = picture.beginRecording(bounds.width, bounds.height)
        canvas.drawBitmap(pixels, (area.left - bounds.left).toFloat(), (area.top - bounds.top).toFloat(), CanvasTileComposer.CopyPaint)
        picture.endRecording()
        // 소프트웨어로 그려 바꿀 수 없는 비트맵 하나가 나온다
        return Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)))
    }
}

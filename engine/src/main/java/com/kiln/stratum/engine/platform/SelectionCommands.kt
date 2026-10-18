package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.Paint
import android.graphics.Picture
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.ColorRegion
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.engine.selection.combineSelection
import java.nio.ByteBuffer
import java.nio.IntBuffer

/** 페더(경계 흐림)의 가장 큰 값(픽셀) */
const val MAX_FEATHER = 250f

/**
 * 모양(사각형·원형·올가미)으로 고른다. [mode]로 앞 선택 영역과 합치고, [feather] 픽셀만큼 경계를 흐린다.
 * 다 빠지거나 넓이가 없는 모양을 새로 고르면 선택이 풀린다(포토샵에서 끌지 않고 누르면 풀리는 것과 같다).
 */
data class SelectShape(val shape: SelectionShape, val mode: SelectionMode, val feather: Float = 0f) : CancellableCommand {

    init {
        require(feather in 0f..MAX_FEATHER) { "Feather out of range: $feather" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val size = PixelSize(document.width, document.height)
        val tiles = MaskTiles.shapeTiles(shape, feather, size, checkCanceled)
        return document.withSelection(combineSelection(document.selection ?: Selection.EMPTY, tiles, mode, MaskTiles))
    }

    /** 모양이 닿는 타일마다 알파 타일 한 장(1바이트 픽셀) */
    override fun estimateNewBytes(document: Document): Long {
        val margin = MaskTiles.featherMargin(feather)
        val bounds = shape.bounds ?: return 0L
        val area = PixelRect(bounds.left - margin, bounds.top - margin, bounds.right + margin, bounds.bottom + margin)
            .intersect(PixelRect.of(PixelSize(document.width, document.height))) ?: return 0L
        return tileCount(area) * TILE_PIXEL_COUNT
    }
}

/**
 * 자동 선택(마술봉): [layerId] 레이어에서 문서 좌표 ([x], [y])의 색과 비슷한 곳을 고른다([ColorRegion]).
 * 고른 레이어만 보정을 건 채 줄여 그린 그림(긴 변 [WAND_MAX_EDGE] 이하)에서 찾고, 원본 크기로 부드럽게 키운다.
 * 큰 사진은 경계가 줄인 만큼(50MP면 4픽셀 안쪽) 덜 정확하다(결정 기록 '선택 도구').
 */
data class SelectSimilarColor(
    val layerId: LayerId,
    val x: Float,
    val y: Float,
    val tolerance: Int,
    val contiguous: Boolean,
    val mode: SelectionMode
) : CancellableCommand {

    init {
        require(tolerance in 0..ColorRegion.MAX_TOLERANCE) { "Tolerance out of range: $tolerance" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        if (x < 0f || y < 0f || x >= document.width || y >= document.height) return document
        val size = PixelSize(document.width, document.height)
        val level = wandLevel(size)
        // 고른 레이어만, 불투명도·혼합 모드 없이(보정은 건 채) 그린다
        val layer = document.layers[index]
        val alone = document.copy(layers = listOf(layer.copy(opacity = 1f, blendMode = BlendMode.NORMAL)), selection = null)
        val drawn = PreviewRenderer.render(alone, level, checkCanceled) ?: throw NotEnoughMemoryException()
        val software = drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
        val width = software.width
        val height = software.height
        val pixels = IntArray(width * height)
        try {
            // 알파를 미리 곱한 값 그대로(투명한 곳은 색과 상관없이 0)
            software.copyPixelsToBuffer(IntBuffer.wrap(pixels))
        } finally {
            software.recycle()
        }
        checkCanceled()
        val seedX = (x.toInt() shr level).coerceIn(0, width - 1)
        val seedY = (y.toInt() shr level).coerceIn(0, height - 1)
        val picked = ColorRegion.select(pixels, width, height, seedX, seedY, tolerance, contiguous)
        val tiles = MaskTiles.upscaledTiles(picked, width, height, level, size, checkCanceled)
        return document.withSelection(combineSelection(document.selection ?: Selection.EMPTY, tiles, mode, MaskTiles))
    }

    /** 줄인 그림과, 경계 타일마다 알파 타일 한 장(어림: 문서 전체) */
    override fun estimateNewBytes(document: Document): Long {
        val size = PixelSize(document.width, document.height)
        val preview = PreviewLevels.previewSize(size, wandLevel(size))
        return preview.width.toLong() * preview.height * (Int.SIZE_BYTES * 2 + 2) + tileCount(PixelRect.of(size)) * TILE_PIXEL_COUNT / 4
    }

    companion object {
        /** 자동 선택이 찾는 줄인 그림의 긴 변 최대 픽셀 */
        const val WAND_MAX_EDGE = 2048

        /** 긴 변이 [WAND_MAX_EDGE] 이하가 되는 가장 작은 단계 */
        fun wandLevel(size: PixelSize): Int {
            var level = 0
            while (maxOf(PreviewLevels.previewSize(size, level).width, PreviewLevels.previewSize(size, level).height) > WAND_MAX_EDGE) level++
            return level
        }
    }
}

/** 모두 선택 */
data object SelectAll : EditCommand {
    override fun applyTo(document: Document): Document {
        val size = PixelSize(document.width, document.height)
        val current = document.selection
        // 이미 다 골랐으면 바뀌지 않는다
        if (current != null && current.tiles.size == TileGrid(size.width, size.height).keys().size && current.tiles.values.all { it.isFull }) return document
        return document.withSelection(Selection.all(size))
    }
}

/** 선택 해제 */
data object Deselect : EditCommand {
    override fun applyTo(document: Document): Document = document.withSelection(null)
}

/** 선택 반전: 고른 곳과 고르지 않은 곳을 바꾼다. 선택 영역이 없으면 바뀌지 않는다. */
data object InvertSelection : CancellableCommand {
    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val selection = document.selection ?: return document
        val grid = TileGrid(document.width, document.height)
        val tiles = HashMap<TileKey, MaskTile>()
        for (key in grid.keys()) {
            checkCanceled()
            when (val tile = selection.tiles[key]) {
                null -> tiles[key] = MaskTile.FULL
                else -> if (!tile.isFull) MaskTiles.invert(tile)?.let { tiles[key] = it }
            }
        }
        return document.withSelection(Selection.of(tiles))
    }

    override fun estimateNewBytes(document: Document): Long =
        (document.selection?.tiles?.values?.count { !it.isFull } ?: 0).toLong() * TILE_PIXEL_COUNT
}

/** [layerId] 레이어에서 고른 곳의 픽셀을 지운다(투명하게). 선택 영역은 그대로 둔다. */
data class DeleteSelected(val layerId: LayerId) : CancellableCommand {
    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val selection = document.selection ?: return document
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val cleared = SelectedPixels.cut(raster, selection, PixelSize(document.width, document.height), checkCanceled)
        if (cleared === raster) return document
        val layers = document.layers.toMutableList().apply { set(index, layer.copy(content = RasterContent(cleared))) }
        return document.copy(layers = layers)
    }

    override fun estimateNewBytes(document: Document): Long = SelectedPixels.partialTiles(document, layerId) * TILE_PIXEL_COUNT * Int.SIZE_BYTES
}

/**
 * [layerId] 레이어에서 고른 곳의 픽셀을 복사해 바로 위의 새 레이어([name])로 만든다([cut]이면 원래 자리에서 지운다).
 * 새 레이어는 원래 레이어의 불투명도·혼합 모드·보정을 그대로 가져가 같아 보인다. 끝나면 선택을 푼다(포토샵과 같다).
 * 복사할 픽셀이 없으면 바뀌지 않는다.
 */
data class CopySelectedToLayer(val layerId: LayerId, val name: String, val cut: Boolean) : CancellableCommand {
    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val selection = document.selection ?: return document
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val size = PixelSize(document.width, document.height)
        val copied = SelectedPixels.keep(raster, selection, size, checkCanceled)
        if (copied.tiles.isEmpty()) return document
        val newLayer = Layer(
            id = LayerId(document.nextLayerId),
            name = name,
            content = RasterContent(copied),
            opacity = layer.opacity,
            blendMode = layer.blendMode,
            adjustments = layer.adjustments,
            // 게인맵은 함께 쓴다. 고르지 않은 곳은 알파가 0이라 상관없다(결정 기록 'HDR').
            gainmap = layer.gainmap
        )
        val layers = document.layers.toMutableList()
        if (cut) layers[index] = layer.copy(content = RasterContent(SelectedPixels.cut(raster, selection, size, checkCanceled)))
        layers.add(index + 1, newLayer)
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1, selection = null)
    }

    override fun estimateNewBytes(document: Document): Long =
        SelectedPixels.partialTiles(document, layerId) * TILE_PIXEL_COUNT * Int.SIZE_BYTES * (if (cut) 2 else 1)
}

/** 레이어 픽셀을 선택 영역으로 자르거나 지운다(소프트웨어 그리기, 픽셀을 하나씩 돌지 않는다). */
internal object SelectedPixels {

    /** 고른 곳만 남긴 래스터. 다 고른 타일은 원래 타일을 그대로 쓴다. */
    fun keep(raster: Raster, selection: Selection, size: PixelSize, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, Tile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, mask) in selection.tiles) {
                checkCanceled()
                val tile = raster.tiles[key] ?: continue
                tiles[key] = if (mask.isFull) tile else masked(tile, mask, grid, key, AndroidBlendMode.DST_IN, temporary)
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 고른 곳을 지운 래스터. 다 고른 타일은 비운다. 바뀐 것이 없으면 [raster] 그대로다. */
    fun cut(raster: Raster, selection: Selection, size: PixelSize, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val changes = HashMap<TileKey, Tile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            for ((key, mask) in selection.tiles) {
                checkCanceled()
                val tile = raster.tiles[key] ?: continue
                changes[key] = if (mask.isFull) null else masked(tile, mask, grid, key, AndroidBlendMode.DST_OUT, temporary)
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return if (changes.isEmpty()) raster else raster.withTiles(changes)
    }

    /** 고른 레이어에서 경계 타일(픽셀이 있는 알파 타일과 겹치는 레이어 타일) 수 */
    fun partialTiles(document: Document, layerId: LayerId): Long {
        val selection = document.selection ?: return 0L
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return 0L
        val raster = (layer.content as? RasterContent)?.raster ?: return 0L
        return selection.tiles.count { (key, mask) -> !mask.isFull && key in raster.tiles }.toLong()
    }

    private fun masked(tile: Tile, mask: MaskTile, grid: TileGrid, key: TileKey, mode: AndroidBlendMode, temporary: MutableList<Bitmap>): Tile {
        val bounds = grid.bounds(key)
        val source = CanvasTileComposer.bitmapOf(tile, temporary)
        val alpha = checkNotNull(MaskTiles.bitmapOf(mask, temporary))
        val picture = Picture()
        val canvas = picture.beginRecording(bounds.width, bounds.height)
        canvas.drawBitmap(source, 0f, 0f, CanvasTileComposer.CopyPaint)
        MaskTiles.drawAlpha(canvas, alpha, mode)
        picture.endRecording()
        val bitmap = try {
            Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return Tile.of(BitmapTilePixels(bitmap))
    }
}

/** [area]에 닿는 타일 수 */
private fun tileCount(area: PixelRect): Long =
    ((area.right - 1) / TILE_SIZE - area.left / TILE_SIZE + 1).toLong() * ((area.bottom - 1) / TILE_SIZE - area.top / TILE_SIZE + 1)

/** 줄인 마스크 바이트를 문서 크기 알파 타일로 키운다(MaskTiles에 붙인 도우미) */
internal fun MaskTiles.upscaledTiles(alphas: ByteArray, width: Int, height: Int, level: Int, size: PixelSize, checkCanceled: () -> Unit): Map<TileKey, MaskTile> {
    val low = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    try {
        low.copyPixelsFromBuffer(ByteBuffer.wrap(alphas))
        val scale = (1 shl level).toFloat()
        val filter = Paint().apply { isFilterBitmap = true }
        val grid = TileGrid(size.width, size.height)
        val tiles = HashMap<TileKey, MaskTile>()
        for (key in grid.keys()) {
            checkCanceled()
            val tile = grid.bounds(key)
            // 이 타일이 쓰는 줄인 픽셀(키울 때 이웃 한 픽셀도 섞인다)
            val left = ((tile.left shr level) - 1).coerceAtLeast(0)
            val top = ((tile.top shr level) - 1).coerceAtLeast(0)
            val right = (((tile.left + tile.width - 1) shr level) + 1).coerceAtMost(width - 1)
            val bottom = (((tile.top + tile.height - 1) shr level) + 1).coerceAtMost(height - 1)
            var any = false
            var all = true
            for (y in top..bottom) {
                for (x in left..right) {
                    if (alphas[y * width + x] == 0.toByte()) all = false else any = true
                }
            }
            when {
                !any -> Unit
                all -> tiles[key] = MaskTile.FULL
                else -> draw(tile.width, tile.height) { canvas ->
                    canvas.translate(-tile.left.toFloat(), -tile.top.toFloat())
                    canvas.scale(scale, scale)
                    canvas.drawBitmap(low, 0f, 0f, filter)
                }?.let { tiles[key] = it }
            }
        }
        return tiles
    } finally {
        low.recycle()
    }
}

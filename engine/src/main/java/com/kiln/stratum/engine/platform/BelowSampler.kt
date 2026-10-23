package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Picture
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.CompositePlan
import com.kiln.stratum.engine.document.CompositeStep
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey

/**
 * 리터칭의 '아래 포함' 가져오기(포토샵 '현재 및 아래 샘플링', 결정 기록 '피부 보정'의 5단계): [layerId] 레이어와 그 아래(감싼 그룹들의
 * 보이기·불투명도 포함)를 합친 그림을 원본 해상도 타일로, 필요한 곳([sample])만 1024px 묶음씩 그린다(GPU). 그 위의 레이어는 넣지 않는다.
 *
 * - 그대로 보이는 레이어 하나뿐이면(빈 레이어 빼고, 불투명도 1·보정·마스크 없음) 그리지 않고 그 래스터를 그대로 읽는다.
 * - 여러 번 그리는 일(자동 잡티 제거)은 그 레이어에 그린 뒤 [painted]로 알린다. 그 레이어가 그대로 얹히면([stacksPlainly]) 같은 획을 이 그림에도
 *   그려 넘기고(알파를 곱한 색은 얹는 차례를 바꿔도 같다), 아니면 그린 묶음을 버려 다음에 바뀐 문서로 다시 그린다.
 */
internal class BelowSampler(document: Document, private val layerId: LayerId) {

    private val documentSize = PixelSize(document.width, document.height)
    private val grid = TileGrid(documentSize.width, documentSize.height)

    /** 그릴 문서: 그 레이어와 그 아래(감싼 그룹 포함). 그 레이어에 그리면 바꾼다. */
    private var below = document.copy(layers = LayerTree.below(document.layers, layerId, including = true), selection = null)

    /** 지금까지 그린 그림(그린 묶음의 타일만 있다) */
    private var current: Raster = Raster.EMPTY

    /** 그린 묶음(묶음 = 타일 4×4) */
    private val drawn = HashSet<TileKey>()

    /** 그대로 읽을 래스터(그대로 보이는 레이어 하나뿐일 때). 그 레이어에 그리면 더는 쓰지 않는다. */
    private var plain: Raster? = plainOf(below)

    /** 그 레이어가 맨 위에 그대로(불투명도 1·표준·보정·마스크 없음) 얹히는지. 그러면 그 레이어에 그린 획을 이 그림에도 그리면 된다. */
    val stacksPlainly: Boolean = CompositePlan.of(below).lastOrNull().let { step ->
        step is CompositeStep.Draw && step.layer.id == layerId && step.isPlain && step.blendMode == BlendMode.NORMAL
    }

    /** [rect](문서 픽셀)를 덮는 곳까지 그린 그림. GPU가 그리지 못하면 [NotEnoughMemoryException]. */
    fun sample(rect: PixelRect, checkCanceled: () -> Unit = {}): Raster {
        plain?.let { return it }
        val inside = rect.intersect(PixelRect.of(documentSize)) ?: return current
        val span = BLOCK_TILES * TILE_SIZE
        val added = HashMap<TileKey, Tile?>()
        for (row in inside.top / span..(inside.bottom - 1) / span) {
            for (column in inside.left / span..(inside.right - 1) / span) {
                val block = TileKey(column, row)
                if (!drawn.add(block)) continue
                checkCanceled()
                val area = PixelRect(column * span, row * span, minOf((column + 1) * span, documentSize.width), minOf((row + 1) * span, documentSize.height))
                render(area, added, checkCanceled)
            }
        }
        if (added.isNotEmpty()) current = current.withTiles(added)
        return current
    }

    /**
     * 그 레이어에 그렸다: 그 레이어의 새 래스터 [layer]. [stacksPlainly]면 같은 획을 이 그림에 그린 [sampled]를 넘기고, 아니면 null(그린 묶음을 버림).
     */
    fun painted(layer: Raster, sampled: Raster?) {
        below = below.copy(layers = below.layers.map { if (it.id == layerId) it.withPixels(layer, it.gainmap) else it })
        val wasPlain = plain != null
        plain = null
        if (sampled != null) {
            current = sampled
            // 그대로 읽던 래스터에 그렸으면 그 래스터 전체가 지금 그림이다
            if (wasPlain) drawn += allBlocks()
        } else {
            current = Raster.EMPTY
            drawn.clear()
        }
    }

    /** [area] 묶음을 그려 타일로 나눠 [into]에 넣는다(투명한 바탕) */
    private fun render(area: PixelRect, into: MutableMap<TileKey, Tile?>, checkCanceled: () -> Unit) {
        val region = PreviewLevels.region(documentSize, 0, area) ?: return
        val image = PreviewRenderer.renderRegion(below, region, checkCanceled) ?: throw NotEnoughMemoryException()
        try {
            val software = image.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
            try {
                for (row in region.rect.top / TILE_SIZE until (region.rect.bottom + TILE_SIZE - 1) / TILE_SIZE) {
                    for (column in region.rect.left / TILE_SIZE until (region.rect.right + TILE_SIZE - 1) / TILE_SIZE) {
                        val key = TileKey(column, row)
                        val bounds = grid.bounds(key)
                        val picture = Picture()
                        val canvas = picture.beginRecording(bounds.width, bounds.height)
                        canvas.drawBitmap(software, (region.rect.left - bounds.left).toFloat(), (region.rect.top - bounds.top).toFloat(), CanvasTileComposer.CopyPaint)
                        picture.endRecording()
                        into[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)))
                    }
                }
            } finally {
                software.recycle()
            }
        } finally {
            image.recycle()
        }
    }

    private fun allBlocks(): Set<TileKey> {
        val span = BLOCK_TILES * TILE_SIZE
        return buildSet {
            for (row in 0..(documentSize.height - 1) / span) for (column in 0..(documentSize.width - 1) / span) add(TileKey(column, row))
        }
    }

    companion object {
        /** 한 번에 그리는 묶음의 한 변(타일 수, 1024px) */
        private const val BLOCK_TILES = 4

        /** 묶음 하나를 그리는 동안의 메모리 어림(그린 그림·복사본·타일) */
        const val BLOCK_WORK_BYTES = 3L * BLOCK_TILES * TILE_SIZE * BLOCK_TILES * TILE_SIZE * 4

        /** 그대로 읽을 래스터: 빈 레이어를 뺀 단계가 그대로 보이는 픽셀 레이어 하나뿐이면 그 래스터 */
        private fun plainOf(document: Document): Raster? {
            val steps = CompositePlan.of(document).filter { it !is CompositeStep.Draw || it.raster.tiles.isNotEmpty() }
            val step = steps.singleOrNull() as? CompositeStep.Draw ?: return null
            return step.raster.takeIf { step.isPlain && step.layer.content is RasterContent }
        }
    }
}

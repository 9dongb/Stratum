package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection

/**
 * [layerId] 레이어에 레이어 마스크를 더한다(포토샵 '레이어 마스크 추가', 결정 기록 '피부 보정'의 1c).
 * 선택 영역이 있으면 그 모양(고른 곳만 보임)이고 선택을 푼다(포토샵과 같다). 없으면 다 보이는 마스크다(타일 한 장을 함께 써 메모리를 쓰지 않음).
 * 이미 마스크가 있거나 없는 레이어면 그대로 둔다.
 */
data class AddLayerMask(val layerId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return document
        // 그룹에는 마스크를 두지 않는다(픽셀·조정 레이어만, 결정 기록 '피부 보정'의 1g)
        if (layer.mask != null || layer.content is GroupContent) return document
        val mask = document.selection ?: Selection.all(PixelSize(document.width, document.height))
        return document.withLayer(layer.copy(mask = mask)).copy(selection = null)
    }
}

/** [layerId] 레이어의 마스크를 버린다(픽셀에 굳히지 않는다). 마스크가 없으면 그대로 둔다. */
data class DeleteLayerMask(val layerId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return document
        if (layer.mask == null) return document
        return document.withLayer(layer.copy(mask = null))
    }
}

/**
 * [layerId] 레이어의 마스크를 뒤집는다(보이던 곳은 가리고, 가린 곳은 보인다). 마스크가 없으면 그대로 둔다.
 * 피부 보정 그룹의 피부 마스크면 함께 쓰는 짝 레이어의 마스크도 뒤집는다([SkinRecord.maskPartnerOf]).
 */
data class InvertLayerMask(val layerId: LayerId) : CancellableCommand {
    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return document
        val mask = layer.mask ?: return document
        val inverted = try {
            MaskTiles.inverted(mask, PixelSize(document.width, document.height), checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return document.withMask(layer, inverted)
    }

    /** 경계 타일(픽셀이 있는 마스크 타일)마다 새 타일 한 장 */
    override fun estimateNewBytes(document: Document): Long {
        val mask = document.layers.getOrNull(document.indexOfLayer(layerId))?.mask ?: return 0L
        return mask.tiles.values.count { !it.isFull } * TILE_PIXEL_COUNT.toLong()
    }
}

/**
 * [layerId] 레이어의 마스크에 브러시 획 하나를 그린다([reveal]이면 보이게, 아니면 가리게, 결정 기록 '피부 보정'의 1c).
 * 픽셀 브러시와 같은 자국·단단함·불투명도이고([MaskPainter]), 선택 영역이 있으면 고른 곳에만 칠한다.
 * 마스크가 없는 레이어거나 문서에 닿지 않는 획이면 그대로 둔다.
 * 피부 보정 그룹의 피부 마스크면 함께 쓰는 짝 레이어(색 보정 ↔ 피부 정돈)의 마스크에도 같이 칠한다([SkinRecord.maskPartnerOf]).
 */
data class PaintLayerMask(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val reveal: Boolean
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return document
        val mask = layer.mask ?: return document
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: return document
        val painted = try {
            MaskPainter.paint(mask, size, bounds, dabs, settings, reveal, document.selection, checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return if (painted === mask) document else document.withMask(layer, painted)
    }

    /** 획이 닿는 타일마다 새 마스크 타일 한 장(픽셀 하나에 1바이트) */
    override fun estimateNewBytes(document: Document): Long {
        if (document.layers.getOrNull(document.indexOfLayer(layerId))?.mask == null) return 0L
        val bounds = StrokeDabs.bounds(dabs, settings.radius, PixelSize(document.width, document.height)) ?: return 0L
        return BrushTiles.keysIn(bounds).size * TILE_PIXEL_COUNT.toLong()
    }
}

/** [layer]를 같은 번호의 레이어 자리에 바꿔 넣은 문서 */
private fun Document.withLayer(layer: Layer): Document =
    copy(layers = layers.map { if (it.id == layer.id) layer else it })

/**
 * [layer]의 마스크를 [mask]로 바꾼 문서. 피부 보정 그룹의 피부 마스크를 함께 쓰는 짝 레이어가 있으면 그 마스크도 같이 바꿔
 * 계속 함께 쓴다(메모리도 한 벌, 결정 기록 '피부 보정'의 2c 화면).
 */
private fun Document.withMask(layer: Layer, mask: Selection): Document {
    val partner = SkinRecord.maskPartnerOf(layers, layer)?.id
    return copy(layers = layers.map { if (it.id == layer.id || it.id == partner) it.copy(mask = mask) else it })
}

/** 문서 [size] 전체에서 [mask]를 뒤집은 마스크(선택 영역 반전과 같다). 다 보이는 타일·빈 자리는 계산 없이 정한다. */
internal fun MaskTiles.inverted(mask: Selection, size: PixelSize, checkCanceled: () -> Unit): Selection {
    val tiles = HashMap<TileKey, MaskTile>()
    for (key in TileGrid(size.width, size.height).keys()) {
        checkCanceled()
        when (val tile = mask.tiles[key]) {
            null -> tiles[key] = MaskTile.FULL
            else -> if (!tile.isFull) invert(tile)?.let { tiles[key] = it }
        }
    }
    return Selection.of(tiles)
}

package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.engine.transform.RasterTransform
import com.kiln.stratum.engine.transform.TransformPlan

/** 문서 전체(모든 레이어)를 90° 돌린다. [clockwise]이면 시계 방향, 아니면 반시계 방향이다. */
data class RotateImage(val clockwise: Boolean) : CancellableCommand {
    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document =
        transform(
            document,
            TransformPlan.orient(document.size, if (clockwise) Orientation.ROTATE_CLOCKWISE else Orientation.ROTATE_COUNTERCLOCKWISE),
            checkCanceled
        )

    override fun estimateNewBytes(document: Document): Long = newTileBytes(document, PixelRect.of(document.size))
}

/** 문서 전체를 뒤집는다. [horizontal]이면 좌우로, 아니면 상하로 뒤집는다. */
data class FlipImage(val horizontal: Boolean) : CancellableCommand {
    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document =
        transform(
            document,
            TransformPlan.orient(document.size, if (horizontal) Orientation.FLIP_HORIZONTAL else Orientation.FLIP_VERTICAL),
            checkCanceled
        )

    override fun estimateNewBytes(document: Document): Long = newTileBytes(document, PixelRect.of(document.size))
}

/**
 * 문서 전체에서 [rect]만 남기고 바깥 픽셀은 버린다(결정 기록 '자르기·회전·뒤집기·크기 변경').
 * [documentSize]는 화면이 자르기를 계획한 문서 크기다. 그사이 문서 크기가 바뀌었으면(한발 늦은 명령) 그대로 둔다.
 */
data class CropImage(val rect: PixelRect, val documentSize: PixelSize) : CancellableCommand {

    init {
        require(rect.width > 0 && rect.height > 0) { "Crop rect must not be empty: $rect" }
        require(rect in PixelRect.of(documentSize)) { "Crop rect outside document: $rect in $documentSize" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        if (document.size != documentSize || rect == PixelRect.of(documentSize)) return document
        return transform(document, TransformPlan.crop(documentSize, rect), checkCanceled)
    }

    override fun estimateNewBytes(document: Document): Long =
        if (document.size == documentSize) newTileBytes(document, rect) else 0L
}

private val Document.size: PixelSize
    get() = PixelSize(width, height)

/**
 * [area]에 걸친 타일을 옮길 때 새로 생길 메모리의 어림값. 한 가지 색 타일은 그대로 쓰므로 세지 않는다.
 * 새 타일은 원본 타일보다 크지 않아, 원본 타일의 메모리를 더한다. 게인맵 타일은 작아 모두 더한다.
 */
private fun newTileBytes(document: Document, area: PixelRect): Long {
    var bytes = 0L
    for (layer in document.layers) {
        val raster = (layer.content as? RasterContent)?.raster ?: continue
        for ((key, tile) in raster.tiles) {
            if (tile.solidColor == null && key.overlaps(area)) bytes += tile.byteCount
        }
        layer.gainmap?.let { gainmap -> bytes += gainmap.raster.tiles.values.sumOf { it.byteCount.toLong() } }
    }
    return bytes
}

private fun TileKey.overlaps(area: PixelRect): Boolean {
    val left = column * TILE_SIZE
    val top = row * TILE_SIZE
    return left < area.right && left + TILE_SIZE > area.left && top < area.bottom && top + TILE_SIZE > area.top
}

/** [plan]대로 문서를 옮긴다. 타일을 만들 메모리가 없으면 [NotEnoughMemoryException]을 던진다. */
private fun transform(document: Document, plan: TransformPlan, checkCanceled: () -> Unit): Document =
    try {
        RasterTransform.apply(document, plan, CanvasTileComposer, checkCanceled)
    } catch (e: OutOfMemoryError) {
        throw NotEnoughMemoryException(e)
    }

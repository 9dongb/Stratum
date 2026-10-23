package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.contentBounds
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.transform.MaskTransform
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.engine.transform.RasterTransform
import com.kiln.stratum.engine.transform.TransformPlan
import java.util.IdentityHashMap

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
 * 문서 전체에서 [rect]만 남기고 바깥 픽셀은 버린 뒤, [orientation] 방향으로 돌리거나 뒤집는다(결정 기록 '자르기·회전·뒤집기·크기 변경').
 * 자르기·회전 화면의 ✓가 돌리기·뒤집기와 자르기를 실행 취소 한 단계로 함께 적용한다(사용자 피드백 8차 1). [rect]는 돌리기 전(원래 방향) 좌표다.
 * [documentSize]는 화면이 자르기를 계획한 문서 크기다. 그사이 문서 크기가 바뀌었으면(한발 늦은 명령) 그대로 둔다.
 */
data class CropImage(
    val rect: PixelRect,
    val documentSize: PixelSize,
    val orientation: Orientation = Orientation.IDENTITY
) : CancellableCommand {

    init {
        require(rect.width > 0 && rect.height > 0) { "Crop rect must not be empty: $rect" }
        require(rect in PixelRect.of(documentSize)) { "Crop rect outside document: $rect in $documentSize" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        if (document.size != documentSize) return document
        if (rect == PixelRect.of(documentSize) && orientation == Orientation.IDENTITY) return document
        return transform(document, TransformPlan.cropThenOrient(documentSize, rect, orientation), checkCanceled)
    }

    override fun estimateNewBytes(document: Document): Long =
        if (document.size == documentSize) newTileBytes(document, rect) else 0L
}

/**
 * [layer] 레이어의 픽셀을 ([dx], [dy]) 문서 픽셀만큼 옮긴다(이동 도구, 결정 기록 '이동 도구'). 문서 크기는 그대로이고,
 * 캔버스 밖으로 나간 부분은 버리며(놓을 때 잘림, 사용자 결정) 옮겨서 빈 자리는 투명이다.
 * 픽셀 값은 그대로 옮긴다(안드로이드 내부 복사, 결정 기록 '회전·뒤집기·자르기는 안드로이드 내부 복사'와 같다). 타일 크기의 배수만큼 옮기면 타일을 그대로 쓴다.
 * 게인맵(HDR)도 같은 비율만큼 옮기고, 레이어 마스크도 같이 옮긴다(결정 기록 '피부 보정'의 1c). 선택 영역·다른 레이어·레이어 속성은 그대로다.
 * 그 레이어가 없으면 그대로 둔다. 글자 레이어(사용자 피드백 8차 1-6)는 픽셀을 옮기지 않고 글자를 새 자리에 다시 그린다. 그래서 고칠 수 있는 채로 남고,
 * 캔버스 밖으로 나갔다가 돌아와도 글자가 남는다. 그룹이면 안의 레이어를 모두 옮긴다(결정 기록 '피부 보정'의 1g).
 */
data class TranslateLayer(val layer: LayerId, val dx: Int, val dy: Int) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        if (dx == 0 && dy == 0) return document
        val index = document.indexOfLayer(layer)
        if (index < 0) return document
        val plan = TransformPlan.translate(document.size, dx, dy)
        // 그룹은 안의 레이어를 모두 옮긴다(한 단계, 결정 기록 '피부 보정'의 1g). 함께 쓰던 마스크는 한 번 옮겨 계속 함께 쓴다(피부 마스크).
        val layers = document.layers.toMutableList()
        val masks = IdentityHashMap<Selection, Selection>()
        for (i in LayerTree.blockOf(document.layers, index)) {
            layers[i] = moved(document, document.layers[i], plan, masks, checkCanceled) ?: return document
        }
        return document.copy(layers = layers)
    }

    /** [target]을 [plan]대로 옮긴 레이어. 글자를 다시 그리지 못하면 null(문서를 그대로 둔다). */
    private fun moved(document: Document, target: Layer, plan: TransformPlan, masks: MutableMap<Selection, Selection>, checkCanceled: () -> Unit): Layer? {
        val raster = when (val content = target.content) {
            is RasterContent -> content.raster
            // 조정 레이어는 픽셀이 없어 마스크만 옮긴다(결정 기록 '피부 보정'의 1e). 피부 색이면 얼굴 자리도 함께(3단계).
            is AdjustmentContent -> return target.copy(mask = movedMask(target.mask, plan, masks, checkCanceled)).withSkinMapped(1f, plan::mapPoint)
            // 그룹은 그리는 것이 없다(안의 레이어를 따로 옮김). 피부 보정 그룹이면 기록의 얼굴 자리를 옮긴다.
            GroupContent -> return target.withSkinMapped(1f, plan::mapPoint)
        }
        target.text?.let { text ->
            val moved = text.moved(dx.toFloat(), dy.toFloat())
            val drawn = TextRenderer.draw(moved.text, moved.x, moved.y, moved.style, document, checkCanceled) ?: return null
            return target.withText(drawn.raster, drawn.spec).copy(mask = movedMask(target.mask, plan, masks, checkCanceled))
        }
        return try {
            val moved = RasterTransform.transform(raster, plan, CanvasTileComposer, checkCanceled)
            val gainmap = target.gainmap?.let { RasterTransform.translateGainmap(it, document.size, dx, dy, CanvasTileComposer, checkCanceled) }
            target.withPixels(moved, gainmap).copy(mask = movedMask(target.mask, plan, masks, checkCanceled))
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
    }

    override fun estimateNewBytes(document: Document): Long {
        val index = document.indexOfLayer(layer)
        if (index < 0) return 0L
        return LayerTree.blockOf(document.layers, index).sumOf { layerTileBytes(document, document.layers[it].id) }
    }
}

/**
 * [layer] 레이어만 [orientation] 방향으로 돌리거나 뒤집는다(이동 도구, 사용자 피드백 12차 2, 결정 기록 '레이어 돌리기·뒤집기').
 * 축은 레이어 내용(투명하지 않은 곳)을 담는 사각형의 가운데이고([Orientation.turnedOrigin]), 문서 크기는 그대로다.
 * 캔버스 밖으로 나간 부분은 버리고 빈 자리는 투명이다(옮기기와 같다). 픽셀 값은 그대로 옮긴다(안드로이드 내부 복사).
 * 게인맵(HDR)도 같은 비율로 돌리고, 레이어 마스크도 같은 계획으로 돌린다(내용 사각형 밖의 마스크는 가림, 그 자리 픽셀은 투명).
 * 선택 영역·다른 레이어·레이어 속성은 그대로다. 그 레이어가 없거나 다 투명하면 그대로 둔다.
 * 글자 레이어는 돌린 글자를 다시 그릴 수 없어 픽셀 레이어가 된다(화면이 먼저 '글자를 픽셀로 바꿀까요?'를 묻는다).
 */
data class OrientLayer(val layer: LayerId, val orientation: Orientation) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        if (orientation == Orientation.IDENTITY) return document
        val index = document.indexOfLayer(layer)
        if (index < 0) return document
        val target = document.layers[index]
        val raster = (target.content as? RasterContent)?.raster ?: return document
        val bounds = raster.contentBounds(document.size, checkCanceled) ?: return document
        val origin = orientation.turnedOrigin(bounds)
        return try {
            val plan = TransformPlan.place(document.size, bounds, orientation, document.size, origin.x, origin.y)
            val turned = RasterTransform.transform(raster, plan, CanvasTileComposer, checkCanceled)
            val gainmap = target.gainmap?.let {
                RasterTransform.orientGainmap(it, document.size, bounds, orientation, origin, CanvasTileComposer, checkCanceled)
            }
            val layers = document.layers.toMutableList()
            layers[index] = target.withPixels(turned, gainmap).copy(mask = movedMask(target.mask, plan, IdentityHashMap(), checkCanceled))
            document.copy(layers = layers)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
    }

    override fun estimateNewBytes(document: Document): Long = layerTileBytes(document, layer)
}

private val Document.size: PixelSize
    get() = PixelSize(width, height)

/** 레이어 마스크 [mask]를 픽셀과 같은 [plan]으로 옮긴 것(없으면 null). 메모리가 모자라면 [NotEnoughMemoryException]. */
/** [mask]를 [plan]대로 옮긴 마스크. [masks]에 이미 옮긴 같은 마스크가 있으면 그것을 함께 쓴다(피부 보정의 색 보정·피부 정돈이 함께 쓰는 마스크). */
private fun movedMask(mask: Selection?, plan: TransformPlan, masks: MutableMap<Selection, Selection>, checkCanceled: () -> Unit) =
    try {
        mask?.let { masks.getOrPut(it) { MaskTransform.transform(it, plan, CanvasTileComposer, checkCanceled) } }
    } catch (e: OutOfMemoryError) {
        throw NotEnoughMemoryException(e)
    }

/** 레이어 하나를 옮기거나 돌릴 때 새로 생길 수 있는 메모리: 그 레이어의 타일(한 가지 색 타일은 그대로 쓰므로 빼고)과 게인맵·마스크 타일 */
private fun layerTileBytes(document: Document, layer: LayerId): Long {
    val target = document.layers.getOrNull(document.indexOfLayer(layer)) ?: return 0L
    // 마스크는 픽셀이 없는 조정 레이어도 옮긴다(결정 기록 '피부 보정'의 1i)
    val maskBytes = target.mask?.tiles?.values?.sumOf { it.byteCount.toLong() } ?: 0L
    val raster = (target.content as? RasterContent)?.raster ?: return maskBytes
    val tiles = raster.tiles.values.sumOf { tile -> if (tile.solidColor == null) tile.byteCount.toLong() else 0L }
    return tiles + (target.gainmap?.raster?.tiles?.values?.sumOf { it.byteCount.toLong() } ?: 0L) + maskBytes
}

/**
 * [area]에 걸친 타일을 옮길 때 새로 생길 메모리의 어림값. 한 가지 색 타일은 그대로 쓰므로 세지 않는다.
 * 새 타일은 원본 타일보다 크지 않아, 원본 타일의 메모리를 더한다. 게인맵 타일은 작아 모두 더한다.
 */
private fun newTileBytes(document: Document, area: PixelRect): Long {
    var bytes = 0L
    for (layer in document.layers) {
        // 마스크 타일(픽셀 하나에 1바이트, 다 보이는 타일은 0). 픽셀이 없는 조정 레이어의 마스크도 센다.
        layer.mask?.let { mask -> bytes += mask.tiles.entries.sumOf { (key, tile) -> if (key.overlaps(area)) tile.byteCount.toLong() else 0L } }
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

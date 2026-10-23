package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection

/**
 * 레이어 마스크([com.kiln.stratum.engine.document.Layer.mask])를 레이어 픽셀과 같은 [TransformPlan]대로 옮긴다(결정 기록 '피부 보정'의 1c).
 * 픽셀과 같은 계획이라 마스크가 픽셀과 꼭 같이 따라간다.
 *
 * 마스크 밖(없는 타일)은 가린 곳(0)이라, 옮겨서 빈 자리도 가린다. 그 자리의 레이어 픽셀도 옮겨서 투명이므로 보이는 것은 같다.
 * 타일을 새로 만들지 않아도 되는 자리는 원본 마스크 타일 객체를 그대로 쓴다([RasterTransform]과 같은 규칙):
 * 다 보이는 타일([MaskTile.FULL]) 하나가 대상 자리를 다 덮을 때, 방향이 그대로이고 원본 타일 하나가 대상 자리를 꼭 맞게 덮을 때다.
 */
object MaskTransform {

    /** [mask]를 [plan]대로 옮긴 새 마스크. 원본 조각이 모두 0(타일 없음)인 자리는 대상도 비워 둔다. */
    fun transform(mask: Selection, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}): Selection {
        if (mask.isEmpty) return mask
        val sourceGrid = TileGrid(plan.sourceSize.width, plan.sourceSize.height)
        val tiles = HashMap<TileKey, MaskTile>()
        for (target in plan.targets) {
            checkCanceled()
            val pieces = target.parts.mapNotNull { part -> mask.tiles[part.source]?.let { MaskPiece(it, part) } }
            if (pieces.isEmpty()) continue
            val tile = reusable(target, pieces, plan, sourceGrid) ?: composer.composeMask(target.width, target.height, pieces, plan.orientation)
            if (tile != null) tiles[target.key] = tile
        }
        return Selection.of(tiles)
    }

    /** 새로 만들지 않고 그대로 쓸 수 있는 원본 마스크 타일. 없으면 null. */
    private fun reusable(target: TargetTile, pieces: List<MaskPiece>, plan: TransformPlan, sourceGrid: TileGrid): MaskTile? {
        val first = pieces.first().tile
        // 다 보이는 타일들이 빈틈없이 덮으면 어느 방향으로 옮겨도 다 보인다(옮기기는 빈 자리가 생길 수 있어 조각이 다 덮는지도 본다)
        if (first.isFull && target.isCovered && pieces.size == target.parts.size && pieces.all { it.tile.isFull }) return MaskTile.FULL
        if (plan.orientation != Orientation.IDENTITY) return null
        val part = pieces.singleOrNull()?.part ?: return null
        val whole = PixelRect(0, 0, target.width, target.height)
        val sourceBounds = sourceGrid.bounds(part.source)
        // 원본 타일의 캔버스 안쪽이 대상 타일의 캔버스 안쪽과 꼭 맞아야 한다(끝 타일의 마스크 그림은 캔버스 안쪽 크기다)
        val fits = part.sourceRect == whole && part.targetRect == whole &&
            sourceBounds.width == target.width && sourceBounds.height == target.height
        return if (fits) first else null
    }
}

package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection

/**
 * 레이어 마스크([com.kiln.stratum.engine.document.Layer.mask])를 레이어 픽셀과 같은 [TransformPlan]대로 옮긴다(결정 기록 '피부 보정'의 1c).
 * 픽셀과 같은 계획이라 마스크가 픽셀과 꼭 같이 따라간다.
 *
 * 옮겨서 빈 자리(어느 조각도 덮지 않는 곳)는 마스크의 '바깥'으로 채운다([revealsOutside]): 가장자리가 모두 보이는 마스크(다 보이는 마스크로
 * 시작한 것)는 보이게, 아니면 가린다(포토샵 마스크의 기본색과 같다, 결정 기록 '피부 보정'의 1i). 그래서 다 보이는 마스크의 조정 레이어를 옮겨도
 * 조정이 빈 자리에서 빠지지 않고, 픽셀 레이어는 빈 자리에 나중에 칠한 것이 보인다.
 * 타일을 새로 만들지 않아도 되는 자리는 원본 마스크 타일 객체를 그대로 쓴다([RasterTransform]과 같은 규칙):
 * 다 보이는 타일([MaskTile.FULL]) 하나가 대상 자리를 다 덮을 때, 방향이 그대로이고 원본 타일 하나가 대상 자리를 꼭 맞게 덮을 때다.
 */
object MaskTransform {

    /** [mask]를 [plan]대로 옮긴 새 마스크. 원본 조각이 모두 0(타일 없음)인 자리는 대상도 비워 두고, 빈 자리는 [revealsOutside]대로 채운다. */
    fun transform(mask: Selection, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}): Selection {
        if (mask.isEmpty) return mask
        val sourceGrid = TileGrid(plan.sourceSize.width, plan.sourceSize.height)
        val outside = revealsOutside(mask, plan.sourceSize)
        val tiles = HashMap<TileKey, MaskTile>()
        for (target in plan.targets) {
            checkCanceled()
            val pieces = target.parts.mapNotNull { part -> mask.tiles[part.source]?.let { MaskPiece(it, part) } }
            val vacated = if (outside) vacated(target, plan.orientation) else emptyList()
            if (pieces.isEmpty() && vacated.isEmpty()) continue
            val tile = reusable(target, pieces, outside, plan, sourceGrid) ?: composer.composeMask(target.width, target.height, pieces + vacated, plan.orientation)
            if (tile != null) tiles[target.key] = tile
        }
        return Selection.of(tiles)
    }

    /**
     * [mask]의 바깥(옮겨서 빈 자리)이 보이는지: 캔버스([size]) 가장자리의 마스크 타일이 모두 다 보이면 보인다.
     * 다 보이는 마스크(선택 영역 없이 더한 마스크·조정 레이어)는 가장자리가 보이고, 선택 영역 모양 마스크는 보통 가장자리가 가려 있다.
     * 포토샵 마스크의 기본색(다 보이기는 흰색, 선택 영역 모양은 검은색)을 따로 적어 두지 않고 어림한다.
     */
    fun revealsOutside(mask: Selection, size: PixelSize): Boolean {
        val grid = TileGrid(size.width, size.height)
        return grid.keys().all { key ->
            val border = key.column == 0 || key.row == 0 || key.column == grid.columns - 1 || key.row == grid.rows - 1
            !border || mask.tiles[key]?.isFull == true
        }
    }

    /** [target]에서 어느 조각도 덮지 않는 자리(옮겨서 빈 자리)를 다 보이게 채울 조각들. 조각들은 한 사각형(대상 타일 ∩ 놓은 자리)을 빈틈없이 덮는다. */
    private fun vacated(target: TargetTile, orientation: Orientation): List<MaskPiece> {
        if (target.parts.isEmpty()) return listOf(full(target.key, PixelRect(0, 0, target.width, target.height), orientation))
        val covered = target.parts.map { it.targetRect }.reduce { a, b ->
            PixelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }
        return listOf(
            PixelRect(0, 0, target.width, covered.top),
            PixelRect(0, covered.bottom, target.width, target.height),
            PixelRect(0, covered.top, covered.left, covered.bottom),
            PixelRect(covered.right, covered.top, target.width, covered.bottom)
        ).filter { it.width > 0 && it.height > 0 }.map { full(target.key, it, orientation) }
    }

    /** 대상 [rect]를 다 보이게 채우는 조각. 원본 사각형은 방향을 되돌린 크기라 어느 방향이든 타일 안이다. */
    private fun full(key: TileKey, rect: PixelRect, orientation: Orientation): MaskPiece {
        val source = orientation.inverse.targetSize(PixelSize(rect.width, rect.height))
        return MaskPiece(MaskTile.FULL, TilePart(key, PixelRect(0, 0, source.width, source.height), rect))
    }

    /** 새로 만들지 않고 그대로 쓸 수 있는 원본 마스크 타일. 없으면 null. */
    private fun reusable(target: TargetTile, pieces: List<MaskPiece>, outside: Boolean, plan: TransformPlan, sourceGrid: TileGrid): MaskTile? {
        // 다 보이는 타일들이 빈틈없이 덮거나 빈 자리도 보이게 채우면 다 보인다(옮기기는 빈 자리가 생길 수 있어 조각이 다 덮는지도 본다)
        if (pieces.size == target.parts.size && pieces.all { it.tile.isFull } && (outside || target.isCovered)) return MaskTile.FULL
        if (plan.orientation != Orientation.IDENTITY) return null
        val part = pieces.singleOrNull()?.part ?: return null
        val first = pieces.first().tile
        val whole = PixelRect(0, 0, target.width, target.height)
        val sourceBounds = sourceGrid.bounds(part.source)
        // 원본 타일의 캔버스 안쪽이 대상 타일의 캔버스 안쪽과 꼭 맞아야 한다(끝 타일의 마스크 그림은 캔버스 안쪽 크기다)
        val fits = part.sourceRect == whole && part.targetRect == whole &&
            sourceBounds.width == target.width && sourceBounds.height == target.height
        return if (fits) first else null
    }
}

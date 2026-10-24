package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileSquare
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection

/**
 * 레이어 마스크([com.kiln.stratum.engine.document.Layer.mask])를 레이어 픽셀과 같은 [TransformPlan]대로 옮긴다(결정 기록 '피부 보정'의 1c).
 * 픽셀과 같은 계획이라 마스크가 픽셀과 꼭 같이 따라가고, 캔버스 밖 마스크 타일도 같이 옮긴다(결정 기록 '캔버스 밖 픽셀').
 * 마스크 값은 타일이 담은 곳은 그 값이고, 타일이 없는 곳은 0(가림)이다(캔버스 밖도). 조정 레이어의 마스크([transform]의 infinite)만은
 * 바깥이 보이는 마스크면 캔버스 밖의 타일 없는 곳도 보인다(조정 레이어는 끝이 없어, 병합하면 캔버스 밖 픽셀에도 걸림, [PreviewRenderer]의 레이어 공간).
 *
 * 옮겨서 대상 캔버스 자리 타일에 생긴 빈 자리(원본 캔버스와 마스크 타일이 오지 않는 곳)는 마스크의 '바깥'으로 채운다([revealsOutside]):
 * 가장자리가 모두 보이는 마스크(다 보이는 마스크로 시작한 것)는 보이게, 아니면 가린다(포토샵 마스크의 기본색과 같다, 결정 기록 '피부 보정'의 1i).
 * 그래서 다 보이는 마스크의 조정 레이어를 옮겨도 조정이 빈 자리에서 빠지지 않고, 픽셀 레이어는 빈 자리에 나중에 칠한 것이 보인다.
 * 끝 자리 타일이 캔버스 밖까지 담은 곳의 빈 자리도 채워, 다 보이는 마스크는 옮긴 뒤에도 가장자리가 다 보이는 타일로 남는다([revealsOutside]가 그대로).
 * 캔버스 밖 자리 대상 타일은 꽉 찬 크기로 만든다: 일부만 덮인 다 보이는 조각이 다 보이는 타일([MaskTile.FULL])로 부풀어 덮지 않은 곳까지 보이지 않게.
 * 타일을 새로 만들지 않아도 되는 자리는 원본 마스크 타일 객체를 그대로 쓴다([RasterTransform]과 같은 규칙):
 * 다 보이는 조각과 빈 자리 채우기가 대상 자리를 다 덮을 때, 방향이 그대로이고 원본 타일 하나를 같은 자리에 같은 크기로 옮길 때다.
 */
object MaskTransform {

    /**
     * [mask]를 [plan]대로 옮긴 새 마스크. 원본 조각이 모두 0(타일 없음)인 자리는 대상도 비워 두고, 캔버스 안 빈 자리는 [revealsOutside]대로 채운다.
     * [infinite]면 조정 레이어의 마스크다: 바깥이 보이는 마스크는 캔버스 밖 빈 자리도 다 보이게 채우고, 다 보이는 캔버스 밖 타일은 바깥과 같아 두지 않는다.
     */
    fun transform(mask: Selection, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}, infinite: Boolean = false): Selection {
        if (mask.isEmpty) return mask
        val outside = revealsOutside(mask, plan.sourceSize)
        val sources = mask.tiles.mapValues { (_, tile) -> PixelSize(tile.width, tile.height) }
        val targetGrid = TileGrid(plan.targetSize.width, plan.targetSize.height)
        // 바깥이 보이는 마스크는 대상 캔버스의 모든 자리를 보고 빈 자리를 채운다
        val extra = if (outside) targetGrid.keys().associateWith { key -> targetGrid.bounds(key).let { PixelSize(it.width, it.height) } } else emptyMap()
        // 원본에서 값이 정해진 곳(캔버스 + 마스크 타일이 담은 곳)이 옮겨 간 사각형들(대상 좌표). 대상 캔버스 안에서 그 밖이 빈 자리다.
        val known = if (outside) knownRects(mask, plan) else emptyList()
        val tiles = HashMap<TileKey, MaskTile>()
        // 조정 레이어의 바깥이 보이는 마스크는 캔버스 밖도 빈 자리를 채운다
        val endless = infinite && outside
        for (target in plan.targets(sources, extra)) {
            checkCanceled()
            val inside = targetGrid.canvasRect(target.key)
            val square = tileSquare(target.key)
            val width = if (inside != null && !endless) target.width else TILE_SIZE
            val height = if (inside != null && !endless) target.height else TILE_SIZE
            val pieces = target.parts.map { part -> MaskPiece(mask.tiles.getValue(part.source), part) }
            // 캔버스 자리 타일은 담을 크기 전체(끝 자리는 캔버스 밖으로 담은 곳까지), 끝이 없는 마스크는 캔버스 밖 자리까지 채운다
            val area = if (endless) square else inside?.let { PixelRect(square.left, square.top, square.left + width, square.top + height) }
            val vacated = if (area != null && outside) vacated(target.key, area, known, plan.orientation) else emptyList()
            if (pieces.isEmpty() && vacated.isEmpty()) continue
            val tile = reusable(width, height, pieces, vacated, plan) ?: composer.composeMask(width, height, pieces + vacated, plan.orientation)
            // 캔버스 밖의 다 보이는 타일은 조정 레이어의 바깥과 같다
            if (tile == null || (endless && inside == null && tile.isFull)) continue
            tiles[target.key] = tile
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

    /** 원본에서 값이 정해진 곳(캔버스와 마스크 타일이 담은 사각형, 자르기면 남길 사각형 안)을 [plan]대로 옮긴 대상 사각형들 */
    private fun knownRects(mask: Selection, plan: TransformPlan): List<PixelRect> {
        val rects = ArrayList<PixelRect>(mask.tiles.size + 1)
        val stored = mask.tiles.map { (key, tile) -> tileSquare(key).let { PixelRect(it.left, it.top, it.left + tile.width, it.top + tile.height) } }
        for (rect in listOf(PixelRect.of(plan.sourceSize)) + stored) {
            val kept = if (plan.discardsOutside) rect.intersect(plan.crop) ?: continue else rect
            rects += plan.mapRect(kept)
        }
        return rects
    }

    /**
     * [key] 대상 타일의 [area](문서 좌표, 보통 캔버스 안쪽)에서 원본의 값이 오지 않는 곳(옮겨서 빈 자리)을 다 보이게 채울 조각들.
     * [known]은 값이 오는 대상 사각형들이고, [area]에서 그것들을 빼고 남은 사각형마다 조각 하나다.
     */
    private fun vacated(key: TileKey, area: PixelRect, known: List<PixelRect>, orientation: Orientation): List<MaskPiece> {
        val square = tileSquare(key)
        var left = listOf(area.offset(-square.left, -square.top))
        for (rect in known) {
            if (rect.intersect(area) == null) continue
            val local = rect.offset(-square.left, -square.top)
            left = left.flatMap { subtract(it, local) }
            if (left.isEmpty()) break
        }
        return left.map { full(key, it, orientation) }
    }

    /** [rect]에서 [cut]을 뺀 나머지(겹치지 않는 사각형 넷까지) */
    private fun subtract(rect: PixelRect, cut: PixelRect): List<PixelRect> {
        val overlap = rect.intersect(cut) ?: return listOf(rect)
        return listOf(
            PixelRect(rect.left, rect.top, rect.right, overlap.top),
            PixelRect(rect.left, overlap.bottom, rect.right, rect.bottom),
            PixelRect(rect.left, overlap.top, overlap.left, overlap.bottom),
            PixelRect(overlap.right, overlap.top, rect.right, overlap.bottom)
        ).filter { it.width > 0 && it.height > 0 }
    }

    /** 대상 [rect]를 다 보이게 채우는 조각. 원본 사각형은 방향을 되돌린 크기라 어느 방향이든 타일 안이다. */
    private fun full(key: TileKey, rect: PixelRect, orientation: Orientation): MaskPiece {
        val source = orientation.inverse.targetSize(PixelSize(rect.width, rect.height))
        return MaskPiece(MaskTile.FULL, TilePart(key, PixelRect(0, 0, source.width, source.height), rect))
    }

    /** 새로 만들지 않고 그대로 쓸 수 있는 원본 마스크 타일([width]×[height]는 대상 타일이 담을 크기). 없으면 null. */
    private fun reusable(width: Int, height: Int, pieces: List<MaskPiece>, vacated: List<MaskPiece>, plan: TransformPlan): MaskTile? {
        // 다 보이는 조각과 빈 자리 채우기가 담을 크기를 빈틈없이 덮으면 다 보인다
        val area = (pieces + vacated).sumOf { it.part.targetRect.width.toLong() * it.part.targetRect.height }
        if (pieces.all { it.tile.isFull } && area == width.toLong() * height) return MaskTile.FULL
        if (plan.orientation != Orientation.IDENTITY || vacated.isNotEmpty()) return null
        val piece = pieces.singleOrNull() ?: return null
        val tile = piece.tile
        val whole = PixelRect(0, 0, tile.width, tile.height)
        val fits = piece.part.sourceRect == whole && piece.part.targetRect == whole && width == tile.width && height == tile.height
        return if (fits) tile else null
    }
}

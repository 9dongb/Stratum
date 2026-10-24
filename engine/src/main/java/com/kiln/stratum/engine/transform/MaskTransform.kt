package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileKeysIn
import com.kiln.stratum.engine.raster.tileSquare
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import java.util.IdentityHashMap

/**
 * 레이어 마스크([com.kiln.stratum.engine.document.Layer.mask])를 레이어 픽셀과 같은 [TransformPlan]대로 옮긴다(결정 기록 '피부 보정'의 1c).
 * 픽셀과 같은 계획이라 마스크가 픽셀과 꼭 같이 따라가고, 캔버스 밖 마스크 타일도 같이 옮긴다(결정 기록 '캔버스 밖 픽셀').
 * 마스크 값은 타일이 담은 곳은 그 값이고, 타일이 없는 곳은 0(가림)이다(캔버스 밖도). 조정 레이어의 마스크([transform]의 infinite)만은
 * 바깥이 보이는 마스크면 캔버스 밖의 타일 없는 곳도 보인다(조정 레이어는 끝이 없어, 병합하면 캔버스 밖 픽셀에도 걸림, [PreviewRenderer]의 레이어 공간).
 *
 * 값이 정해진 곳은 캔버스와, 캔버스 밖에서는 마스크 타일이 담은 곳 중 뜻이 있는 곳이다: 픽셀 레이어만 쓰는 마스크는 그 자리 픽셀 타일이 담은 곳(픽셀이 없는
 * 곳의 마스크는 보이는 것을 바꾸지 않는다), 조정 레이어도 쓰는 마스크는 캔버스 밖 자리 타일 전체. 그래서 끝 자리 [MaskTile.FULL]이 캔버스 밖까지 담은 몫은 픽셀이 없으면
 * 옮기지 않고(돌리거나 뒤집어 반대편 캔버스 밖에 0이 섞인 쓰레기 타일이 생기지 않게), 픽셀 레이어만 쓰는 마스크의 캔버스 밖 대상 타일은 픽셀이 오는 자리에만 둔다.
 * 캔버스 밖의 픽셀이 담긴 곳은 마스크 타일이 없으면 0으로 정해진 곳이다(가린 곳을 캔버스 밖으로 밀었다 되들여도 가린 채, 검토에서 고침).
 *
 * 옮겨서 생긴 빈 자리(값이 정해진 곳이 오지 않는 곳)는 마스크의 '바깥'으로 채운다([revealsOutside]): 가장자리가 모두 보이는 마스크(다 보이는 마스크로
 * 시작한 것)는 보이게, 아니면 가린다(포토샵 마스크의 기본색과 같다, 결정 기록 '피부 보정'의 1i). 대상 타일이 담는 곳 전체(캔버스 밖 자리는 꽉 찬 타일)를
 * 채워, 꽉 찬 크기로 만든 캔버스 밖 타일의 앞쪽에 빈 자리 대신 0이 적히지 않는다(다른 만큼 다시 옮겨 캔버스에 들여도 바깥 그대로).
 * 그래서 다 보이는 마스크의 조정 레이어를 옮겨도 조정이 빈 자리에서 빠지지 않고, 픽셀 레이어는 빈 자리에 나중에 칠한 것이 보인다.
 * 조정 레이어의 바깥이 보이는 마스크는 캔버스 안의 가린 곳(타일 없음)도 옮기고, 캔버스 밖이나 끝 자리로 나간 다 가린 몫은 0인 타일([TileComposer.emptyMask])로
 * 적어 둔다(타일이 없으면 보임이라).
 * 타일을 새로 만들지 않아도 되는 자리는 원본 마스크 타일 객체를 그대로 쓴다([RasterTransform]과 같은 규칙):
 * 다 보이는 조각과 빈 자리 채우기가 대상 자리를 다 덮을 때, 방향이 그대로이고 원본 타일 하나를 같은 자리에 같은 크기로 옮길 때다.
 */
object MaskTransform {

    /**
     * 마스크를 쓰는 레이어들([users]): 픽셀 레이어들(글자 레이어 포함)의 타일이 담은 크기(자리마다 가로·세로 큰 쪽, 픽셀 레이어가 없으면 비었음)와,
     * 함께 옮기는 조정 레이어도 쓰는지([adjustment]). 조정 레이어도 쓰면 캔버스 밖 마스크를 픽셀이 있는 자리로 줄이지 않는다(조정은 끝이 없어 어디서나 쓰임).
     */
    class MaskUsers(val pixels: Map<TileKey, PixelSize>, val adjustment: Boolean)

    /**
     * [mask]를 [plan]대로 옮긴 새 마스크. 원본 조각이 모두 0(타일 없음)인 자리는 대상도 비워 두고, 빈 자리는 [revealsOutside]대로 채운다.
     * [users]는 이 마스크를 쓰는 레이어들([MaskTransform.users])이고, null이면 조정 레이어만 쓰는 마스크로 본다.
     * [infinite]면 조정 레이어의 마스크다: 바깥이 보이는 마스크는 캔버스 밖 빈 자리도 다 보이게 채우고, 다 보이는 캔버스 밖 타일은 바깥과 같아 두지 않는다.
     */
    fun transform(
        mask: Selection,
        plan: TransformPlan,
        composer: TileComposer,
        checkCanceled: () -> Unit = {},
        infinite: Boolean = false,
        users: MaskUsers? = null
    ): Selection {
        if (mask.isEmpty) return mask
        val outside = revealsOutside(mask, plan.sourceSize)
        // 조정 레이어의 바깥이 보이는 마스크는 끝이 없다: 캔버스 밖의 빈 곳이 보이고, 캔버스 밖으로 나간 몫은 다 담은 그대로 옮긴다
        val endless = infinite && outside
        val sourceGrid = TileGrid(plan.sourceSize.width, plan.sourceSize.height)
        val pixelSizes = if (endless) null else users?.pixels
        // 픽셀 레이어만 쓰는 마스크는 캔버스 밖을 픽셀이 있는 곳으로 줄인다
        val onlyPixels = !endless && users != null && !users.adjustment
        // 원본 자리마다 옮길 크기(왼쪽 위부터): 마스크 타일이 담은 곳 중 뜻이 있는 곳
        val sources = HashMap<TileKey, PixelSize>()
        for ((key, tile) in mask.tiles) reach(key, tile, sourceGrid, endless, pixelSizes, onlyPixels)?.let { sources[key] = it }
        // 끝이 없는 마스크는 캔버스 안의 가린 곳(타일 없음)도 옮긴다: 캔버스 밖에서는 타일이 없으면 보임이라 0을 적어 둔다
        if (endless) {
            for (key in sourceGrid.keys()) {
                if (key !in mask.tiles) sourceGrid.bounds(key).let { sources[key] = PixelSize(it.width, it.height) }
            }
        }
        val targetGrid = TileGrid(plan.targetSize.width, plan.targetSize.height)
        // 바깥이 보이는 마스크는 대상 캔버스의 모든 자리를 보고 빈 자리를 채운다
        val extra = if (outside) targetGrid.keys().associateWith { key -> targetGrid.bounds(key).let { PixelSize(it.width, it.height) } } else emptyMap()
        // 원본에서 값이 정해진 곳이 옮겨 간 사각형들(대상 좌표). 대상 타일이 담는 곳에서 그 밖이 빈 자리다.
        val known = if (outside) knownRects(sources, pixelSizes, plan) else emptyList()
        // 픽셀 레이어만 쓰는 마스크의 캔버스 밖 타일은 픽셀이 오는 자리에만 둔다(그 밖은 보이는 것을 바꾸지 않는다)
        val pixelTargets = if (onlyPixels) pixelTargets(pixelSizes.orEmpty(), plan) else null
        val tiles = HashMap<TileKey, MaskTile>()
        for (target in plan.targets(sources, extra)) {
            checkCanceled()
            val inside = targetGrid.canvasRect(target.key)
            if (inside == null && pixelTargets != null && target.key !in pixelTargets) continue
            val square = tileSquare(target.key)
            val width = if (inside != null && !endless) target.width else TILE_SIZE
            val height = if (inside != null && !endless) target.height else TILE_SIZE
            // 마스크 타일이 없는 원본 자리(끝이 없는 마스크의 가린 곳)는 0이라 그리지 않는다
            val pieces = target.parts.mapNotNull { part -> mask.tiles[part.source]?.let { MaskPiece(it, part) } }
            // 대상 타일이 담는 곳 전체(캔버스 자리 타일은 끝 자리면 캔버스 밖으로 담은 곳까지, 캔버스 밖 자리와 끝이 없는 마스크는 꽉 찬 타일)
            val area = if (inside != null && !endless) PixelRect(square.left, square.top, square.left + width, square.top + height) else square
            val vacated = if (outside) vacated(target.key, area, known, plan.orientation) else emptyList()
            if (pieces.isEmpty() && vacated.isEmpty() && !endless) continue
            val tile = reusable(width, height, pieces, vacated, plan) ?: composer.composeMask(width, height, pieces + vacated, plan.orientation)
            val kept = when {
                // 끝이 없는 마스크의 다 가린 몫: 캔버스 밖으로 나간 곳이 있으면(캔버스 밖·끝 자리) 0을 적어 둔다(타일이 없으면 보임이라)
                tile == null -> if (endless && (inside == null || inside.width < TILE_SIZE || inside.height < TILE_SIZE)) composer.emptyMask() else null
                // 캔버스 밖의 다 보이는 타일은 조정 레이어의 바깥과 같다
                endless && inside == null && tile.isFull -> null
                else -> tile
            }
            kept?.let { tiles[target.key] = it }
        }
        return Selection.of(tiles)
    }

    /**
     * 문서 [size]에서 함께 옮기는 [layers]의 마스크마다 그것을 쓰는 레이어들([MaskUsers]). 여러 레이어가 함께 쓰는 마스크(피부 보정의 피부 마스크)는
     * 한 번 옮겨 계속 함께 쓰므로 합친다. 조정 레이어의 바깥이 보이는 마스크는 끝이 없어 따로 옮기므로(그 조정 레이어 몫은 null) 조정 레이어로 세지 않는다.
     */
    fun users(layers: Iterable<Layer>, size: PixelSize): Map<Selection, MaskUsers> {
        val pixels = IdentityHashMap<Selection, HashMap<TileKey, PixelSize>>()
        val adjustment = IdentityHashMap<Selection, Boolean>()
        for (layer in layers) {
            val mask = layer.mask ?: continue
            when (val content = layer.content) {
                is RasterContent -> {
                    val sizes = pixels.getOrPut(mask) { HashMap() }
                    for ((key, tile) in content.raster.tiles) {
                        sizes.merge(key, PixelSize(tile.width, tile.height)) { a, b -> PixelSize(maxOf(a.width, b.width), maxOf(a.height, b.height)) }
                    }
                }
                is AdjustmentContent -> if (!revealsOutside(mask, size)) adjustment[mask] = true
                GroupContent -> Unit
            }
        }
        val users = IdentityHashMap<Selection, MaskUsers>()
        for (mask in pixels.keys + adjustment.keys) users[mask] = MaskUsers(pixels[mask].orEmpty(), adjustment[mask] == true)
        return users
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

    /**
     * [key] 자리 마스크 타일 [tile]에서 옮길 크기(왼쪽 위부터, 값이 뜻이 있는 곳). 없으면(뜻이 없는 타일) null.
     * 끝이 없는 마스크는 담은 그대로다(캔버스 밖의 0도 가린 곳이다). 아니면 캔버스 자리는 캔버스 안쪽과 그 자리 픽셀 타일([pixels])이 담은 곳까지,
     * 캔버스 밖 자리는 픽셀 레이어만 쓰는 마스크([onlyPixels])면 그 자리 픽셀 타일이 담은 곳까지(없으면 null), 아니면 담은 그대로다.
     */
    private fun reach(key: TileKey, tile: MaskTile, grid: TileGrid, endless: Boolean, pixels: Map<TileKey, PixelSize>?, onlyPixels: Boolean): PixelSize? {
        val stored = PixelSize(tile.width, tile.height)
        if (endless) return stored
        val pixel = pixels?.get(key)
        val inside = grid.canvasRect(key)
        val limit = when {
            inside != null -> PixelSize(maxOf(inside.width, pixel?.width ?: 0), maxOf(inside.height, pixel?.height ?: 0))
            !onlyPixels -> return stored
            else -> pixel ?: return null
        }
        return PixelSize(minOf(stored.width, limit.width), minOf(stored.height, limit.height))
    }

    /**
     * 원본에서 값이 정해진 곳(캔버스, 옮길 마스크 조각, 픽셀 레이어면 픽셀 타일이 담은 곳, 자르기면 남길 사각형 안)을 [plan]대로 옮긴 대상 사각형들.
     * 캔버스 안에 드는 사각형은 캔버스와 같아 넣지 않는다.
     */
    private fun knownRects(sources: Map<TileKey, PixelSize>, pixels: Map<TileKey, PixelSize>?, plan: TransformPlan): List<PixelRect> {
        val canvas = PixelRect.of(plan.sourceSize)
        val rects = ArrayList<PixelRect>()
        for (rect in listOf(canvas) + (sources.entries + pixels.orEmpty().entries).mapNotNull { (key, size) -> stored(key, size).takeIf { it !in canvas } }) {
            val kept = if (plan.discardsOutside) rect.intersect(plan.crop) ?: continue else rect
            rects += plan.mapRect(kept)
        }
        return rects
    }

    /** 원본 픽셀 타일이 담은 곳을 [plan]대로 옮긴 사각형이 걸친 대상 자리들(픽셀 레이어의 캔버스 밖 마스크를 둘 자리) */
    private fun pixelTargets(pixels: Map<TileKey, PixelSize>, plan: TransformPlan): Set<TileKey> {
        val keys = HashSet<TileKey>()
        for ((key, size) in pixels) {
            val rect = stored(key, size)
            val kept = if (plan.discardsOutside) rect.intersect(plan.crop) ?: continue else rect
            keys += tileKeysIn(plan.mapRect(kept))
        }
        return keys
    }

    /** [key] 자리 타일이 왼쪽 위부터 [size]만큼 담은 사각형(문서 좌표) */
    private fun stored(key: TileKey, size: PixelSize): PixelRect =
        tileSquare(key).let { PixelRect(it.left, it.top, it.left + size.width, it.top + size.height) }

    /**
     * [key] 대상 타일의 [area](문서 좌표, 대상 타일이 담는 곳)에서 원본의 값이 오지 않는 곳(옮겨서 빈 자리)을 다 보이게 채울 조각들.
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

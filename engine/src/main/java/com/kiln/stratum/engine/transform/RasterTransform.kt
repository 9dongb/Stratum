package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey

/**
 * [TransformPlan]대로 문서의 모든 레이어를 돌리거나 뒤집거나 자른다.
 *
 * 타일을 새로 만들지 않아도 되는 자리는 원본 타일 객체를 그대로 쓴다.
 * - 한 가지 색 타일(새 캔버스) 하나가 대상 자리를 다 덮을 때. 그래서 빈 캔버스는 돌려도 타일 한 장이다.
 * - 방향이 그대로이고, 원본 타일 하나가 대상 자리를 꼭 맞게 덮을 때(왼쪽 위에 맞춘 자르기 등).
 * 그 밖에는 [TileComposer]가 캔버스 안쪽만 담은 새 타일을 만든다. 잘라 낸 픽셀이 끝 타일의 캔버스 밖 부분에 남으면,
 * 축소본이나 크기 변경이 끝 픽셀 너머를 읽을 때 새어 나오기 때문이다.
 */
object RasterTransform {

    /**
     * [document]의 모든 레이어를 [plan]대로 옮긴 새 문서. 크기는 계획의 대상 크기이고, 레이어의 나머지는 그대로다.
     * [checkCanceled]는 타일마다 부르며, 취소됐으면 예외를 던지게 한다.
     */
    fun apply(
        document: Document,
        plan: TransformPlan,
        composer: TileComposer,
        checkCanceled: () -> Unit = {}
    ): Document {
        require(plan.sourceSize == PixelSize(document.width, document.height)) {
            "Plan for ${plan.sourceSize} applied to ${document.width}x${document.height}"
        }
        val layers = document.layers.map { layer ->
            when (val content = layer.content) {
                is RasterContent -> layer.copy(content = RasterContent(transform(content.raster, plan, composer, checkCanceled)))
            }
        }
        // 선택 영역은 옮기지 않고 푼다(결정 기록 '선택 도구')
        return document.copy(width = plan.targetSize.width, height = plan.targetSize.height, layers = layers, selection = null)
    }

    /** [raster]를 [plan]대로 옮긴 새 래스터. 원본 조각이 모두 투명(타일 없음)인 자리는 대상도 비워 둔다. */
    fun transform(raster: Raster, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}): Raster {
        if (raster.tiles.isEmpty()) return Raster.EMPTY
        val sourceGrid = TileGrid(plan.sourceSize.width, plan.sourceSize.height)
        val tiles = HashMap<TileKey, Tile?>()
        for (target in plan.targets) {
            checkCanceled()
            val pieces = target.parts.mapNotNull { part -> raster.tiles[part.source]?.let { TilePiece(it, part) } }
            if (pieces.isEmpty()) continue
            tiles[target.key] = reusable(target, pieces, plan, sourceGrid)
                ?: composer.compose(target.width, target.height, pieces, plan.orientation)
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 새로 만들지 않고 그대로 쓸 수 있는 원본 타일. 없으면 null. */
    private fun reusable(target: TargetTile, pieces: List<TilePiece>, plan: TransformPlan, sourceGrid: TileGrid): Tile? {
        val first = pieces.first().tile
        // 한 가지 색 타일 하나가 빈틈없이 덮으면, 어느 방향으로 옮겨도 같은 색이고 캔버스 밖 부분도 같은 색이다
        if (first.solidColor != null && pieces.size == target.parts.size && pieces.all { it.tile === first }) return first
        if (plan.orientation != Orientation.IDENTITY) return null
        val part = pieces.singleOrNull()?.part ?: return null
        val whole = PixelRect(0, 0, target.width, target.height)
        val sourceBounds = sourceGrid.bounds(part.source)
        // 원본 타일의 캔버스 안쪽이 대상 타일의 캔버스 안쪽과 꼭 맞아야, 잘라 낸 픽셀이 남지 않는다
        val fits = part.sourceRect == whole && part.targetRect == whole &&
            sourceBounds.width == target.width && sourceBounds.height == target.height
        return if (fits) first else null
    }
}

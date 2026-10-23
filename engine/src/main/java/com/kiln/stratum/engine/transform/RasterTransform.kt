package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.roundToInt

/**
 * [TransformPlan]대로 문서의 모든 레이어를 돌리거나 뒤집거나 자르고, 레이어 하나를 옮기거나 돌린다. 레이어 마스크도 같은 계획으로 옮긴다([MaskTransform]).
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
                is RasterContent -> {
                    val moved = layer.withPixels(
                        transform(content.raster, plan, composer, checkCanceled),
                        layer.gainmap?.let { transformGainmap(it, plan, composer, checkCanceled) }
                    )
                    // 글자 레이어(사용자 피드백 8차 1-6): 자르기만 하면 글자 자리를 같이 옮겨 다시 고칠 수 있게 남기고, 돌리거나 뒤집으면 픽셀 레이어가 된다
                    val text = layer.text?.takeIf { plan.orientation == Orientation.IDENTITY }?.moved(-plan.crop.left.toFloat(), -plan.crop.top.toFloat())
                    // 레이어 마스크도 픽셀과 같은 계획으로 옮긴다(결정 기록 '피부 보정'의 1c)
                    val masked = moved.copy(mask = layer.mask?.let { MaskTransform.transform(it, plan, composer, checkCanceled) })
                    if (text != null) masked.copy(text = text) else masked
                }
                // 조정 레이어는 마스크만 옮긴다(결정 기록 '피부 보정'의 1e)
                AdjustmentContent -> layer.copy(mask = layer.mask?.let { MaskTransform.transform(it, plan, composer, checkCanceled) })
                // 그룹은 그리는 것이 없다(안의 레이어가 따로 바뀐다, 1g)
                GroupContent -> layer
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

    /**
     * 게인맵을 문서와 같이 옮긴다(결정 기록 'HDR'). 게인맵은 레이어 전체를 늘여 덮으므로, 자르기는 같은 비율 자리를
     * 게인맵 칸 경계(가장 가까운 칸, 적어도 한 칸)로 잘라 내고, 돌리기·뒤집기는 그 뒤에 같은 방향으로 옮긴다(자르기·회전 화면은 둘을 한 번에 한다).
     * 게인맵이 보통 원본의 1/4이라 자른 가장자리가 게인맵 반 칸(원본 약 2픽셀)까지 어긋날 수 있지만, 밝기 지도는 부드러워 보이지 않는다.
     */
    fun transformGainmap(gainmap: HdrGainmap, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}): HdrGainmap {
        val size = PixelSize(gainmap.width, gainmap.height)
        val scaleX = gainmap.width.toDouble() / plan.sourceSize.width
        val scaleY = gainmap.height.toDouble() / plan.sourceSize.height
        val left = (plan.crop.left * scaleX).roundToInt().coerceIn(0, gainmap.width - 1)
        val top = (plan.crop.top * scaleY).roundToInt().coerceIn(0, gainmap.height - 1)
        val right = (plan.crop.right * scaleX).roundToInt().coerceIn(left + 1, gainmap.width)
        val bottom = (plan.crop.bottom * scaleY).roundToInt().coerceIn(top + 1, gainmap.height)
        val rect = PixelRect(left, top, right, bottom)
        if (rect == PixelRect.of(size) && plan.orientation == Orientation.IDENTITY) return gainmap
        val gainmapPlan = TransformPlan.cropThenOrient(size, rect, plan.orientation)
        return HdrGainmap(gainmapPlan.targetSize.width, gainmapPlan.targetSize.height, transform(gainmap.raster, gainmapPlan, composer, checkCanceled))
    }

    /**
     * 게인맵을 레이어 픽셀과 함께 ([dx], [dy]) 문서 픽셀만큼 옮긴다(이동 도구). 게인맵은 레이어 전체를 늘여 덮으므로 같은 비율만큼 옮기고,
     * 옮기는 양은 가장 가까운 게인맵 픽셀로 맞춘다(보통 원본의 1/4이라 원본 약 2픽셀까지 어긋날 수 있지만 밝기 지도는 부드럽다).
     * 비율로 줄여 0이 되면 그대로 둔다. 옮겨서 빈 자리의 레이어 픽셀은 투명이라 그 자리 게인맵은 쓰이지 않는다.
     */
    fun translateGainmap(
        gainmap: HdrGainmap,
        documentSize: PixelSize,
        dx: Int,
        dy: Int,
        composer: TileComposer,
        checkCanceled: () -> Unit = {}
    ): HdrGainmap {
        val gx = (dx.toDouble() * gainmap.width / documentSize.width).roundToInt()
        val gy = (dy.toDouble() * gainmap.height / documentSize.height).roundToInt()
        if (gx == 0 && gy == 0) return gainmap
        val plan = TransformPlan.translate(PixelSize(gainmap.width, gainmap.height), gx, gy)
        return HdrGainmap(gainmap.width, gainmap.height, transform(gainmap.raster, plan, composer, checkCanceled))
    }

    /**
     * 게인맵을 레이어 픽셀과 함께 돌리거나 뒤집는다(레이어 돌리기·뒤집기, 사용자 피드백 12차 2). 레이어 내용 사각형 [rect]를
     * [orientation]으로 바꿔 왼쪽 위 [origin](문서 좌표)에 놓은 것과 같은 비율 자리로 옮긴다. 게인맵은 레이어 전체를 늘여 덮으므로
     * 사각형과 자리를 가장 가까운 게인맵 픽셀(적어도 한 칸)로 맞춘다(원본 약 2픽셀까지 어긋날 수 있지만 밝기 지도는 부드럽다).
     */
    fun orientGainmap(
        gainmap: HdrGainmap,
        documentSize: PixelSize,
        rect: PixelRect,
        orientation: Orientation,
        origin: PixelPoint,
        composer: TileComposer,
        checkCanceled: () -> Unit = {}
    ): HdrGainmap {
        val scaleX = gainmap.width.toDouble() / documentSize.width
        val scaleY = gainmap.height.toDouble() / documentSize.height
        val left = (rect.left * scaleX).roundToInt().coerceIn(0, gainmap.width - 1)
        val top = (rect.top * scaleY).roundToInt().coerceIn(0, gainmap.height - 1)
        val right = (rect.right * scaleX).roundToInt().coerceIn(left + 1, gainmap.width)
        val bottom = (rect.bottom * scaleY).roundToInt().coerceIn(top + 1, gainmap.height)
        val size = PixelSize(gainmap.width, gainmap.height)
        val plan = TransformPlan.place(
            size,
            PixelRect(left, top, right, bottom),
            orientation,
            size,
            (origin.x * scaleX).roundToInt(),
            (origin.y * scaleY).roundToInt()
        )
        return HdrGainmap(gainmap.width, gainmap.height, transform(gainmap.raster, plan, composer, checkCanceled))
    }

    /** 새로 만들지 않고 그대로 쓸 수 있는 원본 타일. 없으면 null. */
    private fun reusable(target: TargetTile, pieces: List<TilePiece>, plan: TransformPlan, sourceGrid: TileGrid): Tile? {
        val first = pieces.first().tile
        // 한 가지 색 타일 하나가 빈틈없이 덮으면, 어느 방향으로 옮겨도 같은 색이고 캔버스 밖 부분도 같은 색이다
        // (옮기기는 빈 자리가 생길 수 있어 조각이 다 덮는지도 본다)
        if (first.solidColor != null && target.isCovered && pieces.size == target.parts.size && pieces.all { it.tile === first }) return first
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

package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Selection
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * [TransformPlan]대로 문서의 모든 레이어를 돌리거나 뒤집거나 자르고, 레이어 하나를 옮기거나 돌린다. 레이어 마스크도 같은 계획으로 옮긴다([MaskTransform]).
 *
 * 캔버스 밖 픽셀도 같이 옮기고, 캔버스 밖으로 나간 픽셀도 버리지 않는다(자르기만 버림, 결정 기록 '캔버스 밖 픽셀').
 * 타일을 새로 만들지 않아도 되는 자리는 원본 타일 객체를 그대로 쓴다.
 * - 같은 색의 한 가지 색 타일(새 캔버스)들이 대상 타일이 담을 크기를 다 덮을 때. 크기가 같은 원본 타일이 있으면 그것을, 없으면 그 크기의
 *   한 가지 색 타일을 한 번만 만들어 함께 쓴다(끝 자리는 캔버스 안쪽 크기). 그래서 빈 캔버스는 돌려도 타일 몇 장이다.
 * - 방향이 그대로이고, 원본 타일 하나가 담은 픽셀 전체를 대상 타일의 같은 자리에 같은 크기로 옮길 때(타일 크기의 배수만큼 옮기기 등).
 * 그 밖에는 [TileComposer]가 대상 타일이 담을 크기의 새 타일을 만든다(자르면 캔버스 안쪽만 담아, 잘라 낸 픽셀이 남지 않는다).
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
        // 함께 쓰던 마스크는 한 번 옮겨 계속 함께 쓴다(피부 보정의 색 보정·피부 정돈, 결정 기록 '피부 보정'의 2c).
        // 조정 레이어의 바깥이 보이는 마스크만 따로 옮긴다(끝이 없어 결과가 다름)
        val masks = IdentityHashMap<Selection, Selection>()
        val adjustmentMasks = IdentityHashMap<Selection, Selection>()
        fun movedMask(mask: Selection?, infinite: Boolean = false) = mask?.let {
            val endless = infinite && MaskTransform.revealsOutside(it, plan.sourceSize)
            (if (endless) adjustmentMasks else masks).getOrPut(it) { MaskTransform.transform(it, plan, composer, checkCanceled, endless) }
        }
        val layers = document.layers.map { layer ->
            when (val content = layer.content) {
                is RasterContent -> {
                    val pixels = transform(content.raster, plan, composer, checkCanceled)
                    val moved = layer.withPixels(pixels, layer.gainmap?.let { transformGainmap(it, plan, composer, checkCanceled) })
                    // 글자 레이어(사용자 피드백 8차 1-6): 자르기만 하면 글자 자리를 같이 옮겨 다시 고칠 수 있게 남기고, 돌리거나 뒤집으면 픽셀 레이어가 된다
                    val text = layer.text?.takeIf { plan.orientation == Orientation.IDENTITY }?.moved(-plan.crop.left.toFloat(), -plan.crop.top.toFloat())
                    // 레이어 마스크도 픽셀과 같은 계획으로 옮긴다(결정 기록 '피부 보정'의 1c)
                    val masked = moved.copy(mask = movedMask(layer.mask))
                    if (text != null) masked.copy(text = text) else masked
                }
                // 조정 레이어는 마스크만 옮긴다(결정 기록 '피부 보정'의 1e). 피부 색이면 얼굴 자리도 함께(3단계).
                is AdjustmentContent -> layer.copy(mask = movedMask(layer.mask, infinite = true)).withSkinMapped(1f, plan::mapPoint)
                // 그룹은 그리는 것이 없다(안의 레이어가 따로 바뀐다, 1g). 피부 보정 그룹이면 기록의 얼굴 자리를 옮긴다.
                GroupContent -> layer.withSkinMapped(1f, plan::mapPoint)
            }
        }
        // 선택 영역은 옮기지 않고 푼다(결정 기록 '선택 도구'). 피부 정돈도 함께 바뀌어 낡지 않는다(결정 기록 '피부 보정'의 4단계).
        val after = document.copy(width = plan.targetSize.width, height = plan.targetSize.height, layers = layers, selection = null)
        return SkinRecord.rebased(document, after)
    }

    /**
     * [raster]를 [plan]대로 옮긴 새 래스터. 원본 타일이 없는(투명한) 곳은 대상도 비워 둔다.
     * 캔버스 밖으로 담은 곳이 모두 투명한 대상 타일은 캔버스 안쪽까지만 남기거나(캔버스 밖 자리면) 버린다([trimmed]).
     */
    fun transform(raster: Raster, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}): Raster {
        if (raster.tiles.isEmpty()) return Raster.EMPTY
        val targetGrid = TileGrid(plan.targetSize.width, plan.targetSize.height)
        val tiles = HashMap<TileKey, Tile?>()
        // 이번에 만든 한 가지 색 타일(색과 크기마다 한 장)
        val solids = HashMap<Triple<Int, Int, Int>, Tile>()
        for (target in plan.targets(raster.tiles.mapValues { (_, tile) -> PixelSize(tile.width, tile.height) })) {
            checkCanceled()
            val pieces = target.parts.map { part -> TilePiece(raster.tiles.getValue(part.source), part) }
            if (pieces.isEmpty()) continue
            val tile = solidFor(target, pieces, composer, solids)
                ?: reusable(target, pieces, plan)
                ?: composer.compose(target.width, target.height, pieces, plan.orientation)
            trimmed(tile, target.key, targetGrid)?.let { tiles[target.key] = it }
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /**
     * 캔버스 밖으로 담은 곳(캔버스 밖 자리면 전체)이 모두 투명하면 잘라 낸 [tile]: 캔버스 안 자리는 캔버스 안쪽과 투명하지 않은 픽셀이 닿는 곳까지,
     * 캔버스 밖 자리는 투명하지 않은 픽셀이 닿는 곳까지 남기고, 다 투명하면 null이다. 옮겨서 빈(투명한) 곳이 캔버스 밖에 타일로 쌓이지 않게 한다
     * (그대로 두어도 보이는 것은 같다). 캔버스 밖으로 담은 곳이 없는 타일은 읽지 않는다.
     */
    private fun trimmed(tile: Tile, key: TileKey, grid: TileGrid): Tile? {
        val inside = grid.canvasRect(key)
        if (inside != null && tile.width <= inside.width && tile.height <= inside.height) return tile
        val content = tile.opaqueBounds()
        val width = maxOf(inside?.width ?: 0, content?.right ?: 0)
        val height = maxOf(inside?.height ?: 0, content?.bottom ?: 0)
        if (width == 0 || height == 0) return null
        return tile.cropped(minOf(width, tile.width), minOf(height, tile.height))
    }

    /**
     * 게인맵을 문서와 같이 옮긴다(결정 기록 'HDR'). 게인맵은 레이어 전체를 늘여 덮으므로, 자르기는 같은 비율 자리를
     * 게인맵 칸 경계(가장 가까운 칸, 적어도 한 칸)로 잘라 내고, 돌리기·뒤집기는 그 뒤에 같은 방향으로 옮긴다(자르기·회전 화면은 둘을 한 번에 한다).
     * 게인맵이 보통 원본의 1/4이라 자른 가장자리가 게인맵 반 칸(원본 약 2픽셀)까지 어긋날 수 있지만, 밝기 지도는 부드러워 보이지 않는다.
     * 자르지 않고 돌리거나 뒤집기만 하면 캔버스 밖 게인맵도 같이 돌린다(결정 기록 '캔버스 밖 픽셀').
     */
    fun transformGainmap(gainmap: HdrGainmap, plan: TransformPlan, composer: TileComposer, checkCanceled: () -> Unit = {}): HdrGainmap {
        val size = PixelSize(gainmap.width, gainmap.height)
        if (!plan.discardsOutside) {
            if (plan.orientation == Orientation.IDENTITY) return gainmap
            val turned = TransformPlan.orient(size, plan.orientation)
            return HdrGainmap(turned.targetSize.width, turned.targetSize.height, transform(gainmap.raster, turned, composer, checkCanceled))
        }
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
     * 비율로 줄여 0이 되면 그대로 둔다. 옮겨서 빈 자리의 레이어 픽셀은 투명이라 그 자리 게인맵은 쓰이지 않는다. 캔버스 밖으로 나간 게인맵도 남는다.
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
     * 게인맵을 레이어 픽셀과 함께 돌리거나 뒤집는다(레이어 돌리기·뒤집기, 사용자 피드백 12차 2). 레이어 내용 사각형 [rect](캔버스 밖 포함)를
     * [orientation]으로 바꿔 왼쪽 위 [origin](문서 좌표)에 놓은 것과 같은 비율 자리로 옮긴다. 게인맵은 레이어 전체를 늘여 덮으므로
     * 사각형과 자리를 가장 가까운 게인맵 픽셀(적어도 한 칸)로 맞춘다(원본 약 2픽셀까지 어긋날 수 있지만 밝기 지도는 부드럽다).
     * 캔버스 밖 게인맵도 같이 돌리고 버리지 않는다(결정 기록 '캔버스 밖 픽셀'). 내용 사각형 밖 게인맵은 픽셀이 투명해 쓰이지 않아 버린다.
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
        val left = (rect.left * scaleX).roundToInt()
        val top = (rect.top * scaleY).roundToInt()
        val right = maxOf((rect.right * scaleX).roundToInt(), left + 1)
        val bottom = maxOf((rect.bottom * scaleY).roundToInt(), top + 1)
        val size = PixelSize(gainmap.width, gainmap.height)
        val plan = TransformPlan.place(
            size,
            PixelRect(left, top, right, bottom),
            orientation,
            size,
            (origin.x * scaleX).roundToInt(),
            (origin.y * scaleY).roundToInt(),
            clip = true
        )
        return HdrGainmap(gainmap.width, gainmap.height, transform(gainmap.raster, plan, composer, checkCanceled))
    }

    /**
     * 같은 색의 한 가지 색 타일들이 담을 크기를 빈틈없이 덮는 대상 자리의 타일: 어느 방향으로 옮겨도 같은 색이다(옮기기는 빈 자리가 생길 수 있어 조각이 다 덮는지도 본다).
     * 대상 크기와 같은 원본 타일이 있으면 그것을, 없으면 그 크기의 한 가지 색 타일을 [solids]에 한 장만 만들어 함께 쓴다. 아니면 null.
     */
    private fun solidFor(target: TargetTile, pieces: List<TilePiece>, composer: TileComposer, solids: MutableMap<Triple<Int, Int, Int>, Tile>): Tile? {
        val color = pieces.first().tile.solidColor ?: return null
        if (!target.isCovered || pieces.any { it.tile.solidColor != color }) return null
        pieces.firstOrNull { it.tile.width == target.width && it.tile.height == target.height }?.let { return it.tile }
        return solids.getOrPut(Triple(color, target.width, target.height)) { composer.solid(color, target.width, target.height) }
    }

    /**
     * 새로 만들지 않고 그대로 쓸 수 있는 원본 타일: 방향이 그대로이고, 원본 타일 하나가 담은 픽셀 전체를 대상 타일의 같은 자리에 옮기며,
     * 대상 타일이 담을 크기가 원본 타일과 같을 때(그래야 잘라 낸 픽셀이 남지 않고 캔버스 안쪽도 다 담는다). 없으면 null.
     */
    private fun reusable(target: TargetTile, pieces: List<TilePiece>, plan: TransformPlan): Tile? {
        if (plan.orientation != Orientation.IDENTITY) return null
        val piece = pieces.singleOrNull() ?: return null
        val tile = piece.tile
        val whole = PixelRect(0, 0, tile.width, tile.height)
        val fits = piece.part.sourceRect == whole && piece.part.targetRect == whole && target.width == tile.width && target.height == tile.height
        return if (fits) tile else null
    }
}

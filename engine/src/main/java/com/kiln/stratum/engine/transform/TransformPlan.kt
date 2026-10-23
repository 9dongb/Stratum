package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey

/**
 * 대상 타일 하나가 원본 타일 하나에서 가져오는 조각.
 * [sourceRect]는 원본 타일 안 좌표, [targetRect]는 대상 타일 안 좌표이고, 둘 다 캔버스 안쪽만 담는다.
 * 조각 안에서는 계획의 방향([TransformPlan.orientation])대로 자리를 옮긴다.
 */
data class TilePart(val source: TileKey, val sourceRect: PixelRect, val targetRect: PixelRect)

/**
 * 대상 타일 한 장. [width]×[height]는 캔버스 안쪽 크기이고, [parts]가 그 안을 한 번씩 덮는다.
 * 돌리기·뒤집기·자르기는 빈틈없이 덮고, 옮기기([TransformPlan.translate])·놓기([TransformPlan.place])는 덮지 않는 자리가 있다(투명).
 */
data class TargetTile(val key: TileKey, val width: Int, val height: Int, val parts: List<TilePart>) {

    /** 조각들이 캔버스 안쪽을 빈틈없이 덮는지(조각은 겹치지 않는다) */
    val isCovered: Boolean
        get() = parts.sumOf { it.targetRect.width.toLong() * it.targetRect.height } == width.toLong() * height
}

/**
 * 문서를 돌리거나 뒤집거나 자르거나 레이어를 옮기거나 돌릴 때, 대상 타일마다 어느 원본 타일의 어느 부분을 가져올지 정한 계획.
 *
 * 픽셀 값은 그대로 두고 자리만 옮긴다. 실제 복사는 플랫폼 코드가 이 계획대로 한다.
 * 크기는 캔버스 안쪽 크기([TileGrid.bounds])로 계산하고, 끝 타일의 캔버스 밖 부분은 가져오지 않는다.
 */
class TransformPlan private constructor(
    val sourceSize: PixelSize,
    val targetSize: PixelSize,
    /** 원본에서 가져오는 사각형(돌리기·뒤집기·옮기기는 원본 전체, 레이어 돌리기는 레이어 내용을 담는 사각형) */
    val crop: PixelRect,
    val orientation: Orientation,
    /** 대상 타일들. 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다. */
    val targets: List<TargetTile>,
    /** [crop]을 바꿔 놓은 자리(대상 캔버스 안 왼쪽 위) */
    private val placeLeft: Int = 0,
    private val placeTop: Int = 0
) {

    /**
     * 원본 문서 좌표의 점 ([x], [y])가 옮겨 가는 대상 문서 좌표(픽셀 가장자리 기준 실수 좌표, [Orientation.mapPoint]).
     * 피부 보정의 얼굴 자리처럼 픽셀이 아닌 자리를 같이 옮기는 데 쓴다(결정 기록 '피부 보정'의 3단계).
     */
    fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
        val (mx, my) = orientation.mapPoint((x - crop.left).toDouble(), (y - crop.top).toDouble(), crop.width.toDouble(), crop.height.toDouble())
        return (mx + placeLeft).toFloat() to (my + placeTop).toFloat()
    }

    companion object {
        /** [source] 크기 이미지를 [orientation] 방향으로 바꾸는 계획. */
        fun orient(source: PixelSize, orientation: Orientation): TransformPlan =
            build(source, PixelRect.of(source), orientation)

        /** [source] 크기 이미지에서 [rect]만 남기는 계획. [rect]는 이미지 안에 있고 비어 있지 않아야 한다. */
        fun crop(source: PixelSize, rect: PixelRect): TransformPlan = cropThenOrient(source, rect, Orientation.IDENTITY)

        /**
         * [source] 크기 이미지에서 [rect]만 남긴 뒤 [orientation] 방향으로 바꾸는 계획(자르기·회전 화면의 ✓, 사용자 피드백 8차 1).
         * [rect]는 바꾸기 전(원래 방향) 좌표이고, 이미지 안에 있고 비어 있지 않아야 한다. 대상 크기는 [rect]를 그 방향으로 바꾼 크기다.
         */
        fun cropThenOrient(source: PixelSize, rect: PixelRect, orientation: Orientation): TransformPlan {
            require(rect.width > 0 && rect.height > 0) { "Crop rect must not be empty: $rect" }
            require(rect in PixelRect.of(source)) { "Crop rect outside image: $rect in $source" }
            return build(source, rect, orientation)
        }

        /**
         * [source] 크기 이미지를 ([dx], [dy]) 픽셀만큼 옮기는 계획(이동 도구, 결정 기록 '이동 도구'). 크기는 그대로이고,
         * 캔버스 밖으로 나간 부분은 버리며(놓을 때 잘림, 사용자 결정) 옮겨서 빈 자리는 어느 조각도 덮지 않는다(투명).
         * 타일 크기(256)의 배수만큼 옮기면 대상 타일마다 원본 타일 하나를 꼭 맞게 가져온다(그대로 쓸 수 있다).
         */
        fun translate(source: PixelSize, dx: Int, dy: Int): TransformPlan =
            place(source, PixelRect.of(source), Orientation.IDENTITY, source, dx, dy)

        /**
         * [source] 크기 이미지에서 [rect]를 [orientation] 방향으로 바꿔, [target] 크기 캔버스의 ([left], [top])에 왼쪽 위를 두는 계획.
         * 캔버스 밖으로 나간 부분은 버리고, 바꾼 [rect]가 덮지 않는 자리는 어느 조각도 덮지 않는다(투명).
         * 자르기·돌리기([cropThenOrient])와 레이어 옮기기([translate]), 레이어 돌리기·뒤집기(사용자 피드백 12차 2)가 모두 이것이다.
         * [rect]는 이미지 안에 있고 비어 있지 않아야 한다.
         */
        fun place(source: PixelSize, rect: PixelRect, orientation: Orientation, target: PixelSize, left: Int, top: Int): TransformPlan {
            require(rect.width > 0 && rect.height > 0) { "Source rect must not be empty: $rect" }
            require(rect in PixelRect.of(source)) { "Source rect outside image: $rect in $source" }
            val turned = orientation.targetSize(PixelSize(rect.width, rect.height))
            val placed = PixelRect(left, top, left + turned.width, top + turned.height)
            val sourceGrid = TileGrid(source.width, source.height)
            val targetGrid = TileGrid(target.width, target.height)
            val targets = targetGrid.keys().map { key ->
                val targetBounds = targetGrid.bounds(key)
                val targetTile = PixelRect(
                    targetBounds.left,
                    targetBounds.top,
                    targetBounds.left + targetBounds.width,
                    targetBounds.top + targetBounds.height
                )
                val parts = ArrayList<TilePart>(4)
                val covered = targetTile.intersect(placed)
                if (covered != null) {
                    // 이 대상 부분으로 오는 원본 사각형. 놓은 자리만큼 되돌리고, 방향을 되돌린 뒤 [rect]의 자리만큼 옮긴다.
                    val from = orientation.inverse
                        .mapRect(covered.offset(-left, -top), turned.width, turned.height)
                        .offset(rect.left, rect.top)
                    for (row in from.top / TILE_SIZE..(from.bottom - 1) / TILE_SIZE) {
                        for (column in from.left / TILE_SIZE..(from.right - 1) / TILE_SIZE) {
                            val sourceKey = TileKey(column, row)
                            val sourceBounds = sourceGrid.bounds(sourceKey)
                            val sourceTile = PixelRect(
                                sourceBounds.left,
                                sourceBounds.top,
                                sourceBounds.left + sourceBounds.width,
                                sourceBounds.top + sourceBounds.height
                            )
                            val piece = from.intersect(sourceTile) ?: continue
                            val moved = orientation.mapRect(piece.offset(-rect.left, -rect.top), rect.width, rect.height).offset(left, top)
                            parts += TilePart(
                                source = sourceKey,
                                sourceRect = piece.offset(-sourceTile.left, -sourceTile.top),
                                targetRect = moved.offset(-targetTile.left, -targetTile.top)
                            )
                        }
                    }
                }
                TargetTile(key, targetBounds.width, targetBounds.height, parts)
            }
            return TransformPlan(source, target, rect, orientation, targets, left, top)
        }

        /** [source]에서 [crop]을 잘라 [orientation] 방향으로 바꾸는 계획. */
        private fun build(source: PixelSize, crop: PixelRect, orientation: Orientation): TransformPlan =
            place(source, crop, orientation, orientation.targetSize(PixelSize(crop.width, crop.height)), 0, 0)
    }
}

/**
 * 조각 안의 대상 픽셀 ([x], [y])(대상 타일 안 좌표)에 오는 원본 픽셀의 원본 타일 안 좌표.
 * 조각 사각형 안에서 [orientation]을 되돌린다. 플랫폼 코드는 같은 결과가 나오는 [affine]으로 조각을 그린다.
 */
fun TilePart.sourcePixel(x: Int, y: Int, orientation: Orientation): PixelPoint {
    val local = orientation.inverse.mapPixel(x - targetRect.left, y - targetRect.top, targetRect.width, targetRect.height)
    return PixelPoint(sourceRect.left + local.x, sourceRect.top + local.y)
}

/**
 * 조각의 원본 타일 안 좌표를 대상 타일 안 좌표로 옮기는 정수 변환 `[a, b, c, d, e, f]`.
 * x' = a·x + b·y + c, y' = d·x + e·y + f이고, 픽셀 가장자리를 기준으로 한 좌표다.
 * 계수가 모두 정수라, 플랫폼 코드가 그대로 그리기 행렬로 쓰면 픽셀이 반올림 없이 옮겨진다.
 */
fun TilePart.affine(orientation: Orientation): IntArray {
    val w = sourceRect.width
    val h = sourceRect.height
    // 조각 안 좌표 X = x - 원본 왼쪽. 좌우로 뒤집으면 X → w - X
    val sign = if (orientation.mirrored) -1 else 1
    val flip = if (orientation.mirrored) w else 0
    val sl = sourceRect.left
    val st = sourceRect.top
    val tl = targetRect.left
    val tt = targetRect.top
    return when (orientation.quarterTurns) {
        // (X, Y)
        0 -> intArrayOf(sign, 0, -sign * sl + flip + tl, 0, 1, -st + tt)
        // (h - Y, X)
        1 -> intArrayOf(0, -1, h + st + tl, sign, 0, -sign * sl + flip + tt)
        // (w - X, h - Y)
        2 -> intArrayOf(-sign, 0, w + sign * sl - flip + tl, 0, -1, h + st + tt)
        // (Y, w - X)
        else -> intArrayOf(0, 1, -st + tl, -sign, 0, w + sign * sl - flip + tt)
    }
}

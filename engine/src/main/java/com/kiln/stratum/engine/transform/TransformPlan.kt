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

/** 대상 타일 한 장. [width]×[height]는 캔버스 안쪽 크기이고, [parts]가 그 안을 빈틈없이 한 번씩 덮는다. */
data class TargetTile(val key: TileKey, val width: Int, val height: Int, val parts: List<TilePart>)

/**
 * 문서를 돌리거나 뒤집거나 자를 때, 대상 타일마다 어느 원본 타일의 어느 부분을 가져올지 정한 계획.
 *
 * 픽셀 값은 그대로 두고 자리만 옮긴다. 실제 복사는 플랫폼 코드가 이 계획대로 한다.
 * 크기는 캔버스 안쪽 크기([TileGrid.bounds])로 계산하고, 끝 타일의 캔버스 밖 부분은 가져오지 않는다.
 */
class TransformPlan private constructor(
    val sourceSize: PixelSize,
    val targetSize: PixelSize,
    val orientation: Orientation,
    /** 대상 타일들. 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다. */
    val targets: List<TargetTile>
) {

    companion object {
        /** [source] 크기 이미지를 [orientation] 방향으로 바꾸는 계획. */
        fun orient(source: PixelSize, orientation: Orientation): TransformPlan =
            build(source, PixelRect.of(source), orientation)

        /** [source] 크기 이미지에서 [rect]만 남기는 계획. [rect]는 이미지 안에 있고 비어 있지 않아야 한다. */
        fun crop(source: PixelSize, rect: PixelRect): TransformPlan {
            require(rect.width > 0 && rect.height > 0) { "Crop rect must not be empty: $rect" }
            require(rect in PixelRect.of(source)) { "Crop rect outside image: $rect in $source" }
            return build(source, rect, Orientation.IDENTITY)
        }

        /** [source]에서 [crop]을 잘라 [orientation] 방향으로 바꾸는 계획. */
        private fun build(source: PixelSize, crop: PixelRect, orientation: Orientation): TransformPlan {
            val target = orientation.targetSize(PixelSize(crop.width, crop.height))
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
                // 이 대상 타일로 오는 원본 사각형. 방향을 되돌린 뒤 자른 위치만큼 옮긴다.
                val from = orientation.inverse
                    .mapRect(targetTile, target.width, target.height)
                    .offset(crop.left, crop.top)
                val parts = ArrayList<TilePart>(4)
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
                        val moved = orientation.mapRect(piece.offset(-crop.left, -crop.top), crop.width, crop.height)
                        parts += TilePart(
                            source = sourceKey,
                            sourceRect = piece.offset(-sourceTile.left, -sourceTile.top),
                            targetRect = moved.offset(-targetTile.left, -targetTile.top)
                        )
                    }
                }
                TargetTile(key, targetBounds.width, targetBounds.height, parts)
            }
            return TransformPlan(source, target, orientation, targets)
        }
    }
}

/**
 * 조각 안의 대상 픽셀 ([x], [y])(대상 타일 안 좌표)에 오는 원본 픽셀의 원본 타일 안 좌표.
 * 조각 사각형 안에서 [orientation]을 되돌린다. 플랫폼 코드가 같은 방법으로 조각을 그린다.
 */
fun TilePart.sourcePixel(x: Int, y: Int, orientation: Orientation): PixelPoint {
    val local = orientation.inverse.mapPixel(x - targetRect.left, y - targetRect.top, targetRect.width, targetRect.height)
    return PixelPoint(sourceRect.left + local.x, sourceRect.top + local.y)
}

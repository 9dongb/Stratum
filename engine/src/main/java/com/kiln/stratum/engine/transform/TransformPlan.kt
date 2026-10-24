package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileKeysIn
import com.kiln.stratum.engine.raster.tileSquare

/**
 * 대상 타일 하나가 원본 타일 하나에서 가져오는 조각.
 * [sourceRect]는 원본 타일 안 좌표(타일이 담은 픽셀 안), [targetRect]는 대상 타일 안 좌표(대상 타일이 담을 크기 안)다.
 * 조각 안에서는 계획의 방향([TransformPlan.orientation])대로 자리를 옮긴다.
 */
data class TilePart(val source: TileKey, val sourceRect: PixelRect, val targetRect: PixelRect)

/**
 * 대상 타일 한 장. [width]×[height]는 대상 타일이 담을 크기(왼쪽 위부터)이고, [parts]가 그 안에 겹치지 않게 놓인다.
 * 캔버스 안 자리는 캔버스 안쪽보다 작지 않고, 캔버스 밖으로 옮겨 간 픽셀이 있으면 그만큼 더 담는다(결정 기록 '캔버스 밖 픽셀').
 * 돌리기·뒤집기·자르기는 캔버스 안쪽을 빈틈없이 덮고, 옮기기([TransformPlan.translate])·놓기([TransformPlan.place])는 덮지 않는 자리가 있다(투명).
 */
data class TargetTile(val key: TileKey, val width: Int, val height: Int, val parts: List<TilePart>) {

    /** 조각들이 담을 크기를 빈틈없이 덮는지(조각은 겹치지 않는다) */
    val isCovered: Boolean
        get() = parts.sumOf { it.targetRect.width.toLong() * it.targetRect.height } == width.toLong() * height
}

/**
 * 문서를 돌리거나 뒤집거나 자르거나 레이어를 옮기거나 돌릴 때 원본 픽셀이 가는 자리를 정한 계획.
 *
 * 원본의 [crop]을 [orientation]으로 바꿔 대상의 ([placeLeft], [placeTop])에 왼쪽 위를 두는 옮김이고, 같은 식을 평면 전체에 건다.
 * 그래서 캔버스 밖 픽셀도 같이 옮겨 가고, 캔버스 밖으로 나간 픽셀도 버리지 않는다(사용자 피드백 13차 1 "포토샵처럼", 결정 기록 '캔버스 밖 픽셀').
 * 자르기([discardsOutside])만 [crop] 밖 원본을 버린다. 픽셀 값은 그대로 두고 자리만 옮긴다. 실제 복사는 플랫폼 코드가 [targets]대로 한다.
 */
class TransformPlan private constructor(
    val sourceSize: PixelSize,
    val targetSize: PixelSize,
    /** 옮기는 기준 사각형(원본 좌표). 자르기는 남길 사각형, 레이어 돌리기는 레이어 내용을 담는 사각형, 나머지는 원본 캔버스 */
    val crop: PixelRect,
    val orientation: Orientation,
    /** [crop]을 바꿔 놓은 자리(대상 좌표의 왼쪽 위) */
    private val placeLeft: Int,
    private val placeTop: Int,
    /** 자르기면 참: [crop] 밖 원본(캔버스 밖 픽셀도)을 버린다 */
    val discardsOutside: Boolean
) {

    /** [crop]을 [orientation]으로 바꾼 크기 */
    private val turned = orientation.targetSize(PixelSize(crop.width, crop.height))

    /**
     * 원본 문서 좌표의 점 ([x], [y])가 옮겨 가는 대상 문서 좌표(픽셀 가장자리 기준 실수 좌표, [Orientation.mapPoint]).
     * 피부 보정의 얼굴 자리처럼 픽셀이 아닌 자리를 같이 옮기는 데 쓴다(결정 기록 '피부 보정'의 3단계).
     */
    fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
        val (mx, my) = orientation.mapPoint((x - crop.left).toDouble(), (y - crop.top).toDouble(), crop.width.toDouble(), crop.height.toDouble())
        return (mx + placeLeft).toFloat() to (my + placeTop).toFloat()
    }

    /** 원본 픽셀 ([x], [y])(원본 문서 좌표, 캔버스 밖 포함)가 옮겨 가는 대상 픽셀 */
    fun mapPixel(x: Int, y: Int): PixelPoint {
        val moved = orientation.mapPixel(x - crop.left, y - crop.top, crop.width, crop.height)
        return PixelPoint(moved.x + placeLeft, moved.y + placeTop)
    }

    /** 원본 사각형 [rect](원본 문서 좌표, 캔버스 밖 포함)가 옮겨 가는 대상 사각형 */
    fun mapRect(rect: PixelRect): PixelRect =
        orientation.mapRect(rect.offset(-crop.left, -crop.top), crop.width, crop.height).offset(placeLeft, placeTop)

    /** 대상 사각형 [rect]로 오는 원본 사각형([mapRect]를 되돌림) */
    fun sourceRect(rect: PixelRect): PixelRect =
        orientation.inverse.mapRect(rect.offset(-placeLeft, -placeTop), turned.width, turned.height).offset(crop.left, crop.top)

    /**
     * 원본 타일들([sources]: 자리 → 담은 크기)을 옮긴 대상 타일들. 위 줄부터, 한 줄 안에서는 왼쪽부터 놓인다.
     * 원본 타일이 담은 사각형(자르기면 [crop]으로 자른 것)을 옮겨 대상 타일 자리(내림 나눗셈)마다 조각으로 나눈다.
     * 대상 타일이 담을 크기는 캔버스 안 자리는 캔버스 안쪽과 조각이 닿는 곳 중 큰 쪽, 캔버스 밖 자리는 조각이 닿는 곳까지다.
     * [extra]의 자리는 조각이 없어도 넣고 그 크기보다 작지 않게 한다(마스크의 빈 자리 채우기).
     */
    fun targets(sources: Map<TileKey, PixelSize>, extra: Map<TileKey, PixelSize> = emptyMap()): List<TargetTile> {
        val targetGrid = TileGrid(targetSize.width, targetSize.height)
        val parts = HashMap<TileKey, MutableList<TilePart>>()
        for ((key, size) in sources) {
            val square = tileSquare(key)
            val stored = PixelRect(square.left, square.top, square.left + size.width, square.top + size.height)
            val from = if (discardsOutside) stored.intersect(crop) ?: continue else stored
            val to = mapRect(from)
            for (targetKey in tileKeysIn(to)) {
                val targetSquare = tileSquare(targetKey)
                val covered = to.intersect(targetSquare) ?: continue
                parts.getOrPut(targetKey) { ArrayList(4) } += TilePart(
                    source = key,
                    sourceRect = sourceRect(covered).offset(-square.left, -square.top),
                    targetRect = covered.offset(-targetSquare.left, -targetSquare.top)
                )
            }
        }
        val keys = (parts.keys + extra.keys).sortedWith(compareBy({ it.row }, { it.column }))
        return keys.map { key ->
            val list = parts[key].orEmpty()
            var width = list.maxOfOrNull { it.targetRect.right } ?: 0
            var height = list.maxOfOrNull { it.targetRect.bottom } ?: 0
            targetGrid.canvasRect(key)?.let { inside ->
                width = maxOf(width, inside.width)
                height = maxOf(height, inside.height)
            }
            extra[key]?.let { size ->
                width = maxOf(width, size.width)
                height = maxOf(height, size.height)
            }
            TargetTile(key, width, height, list)
        }
    }

    companion object {
        /** [source] 크기 이미지를 [orientation] 방향으로 바꾸는 계획(캔버스 밖 픽셀도 같이 돌린다). */
        fun orient(source: PixelSize, orientation: Orientation): TransformPlan =
            TransformPlan(source, orientation.targetSize(source), PixelRect.of(source), orientation, 0, 0, discardsOutside = false)

        /** [source] 크기 이미지에서 [rect]만 남기는 계획. [rect]는 이미지 안에 있고 비어 있지 않아야 한다. */
        fun crop(source: PixelSize, rect: PixelRect): TransformPlan = cropThenOrient(source, rect, Orientation.IDENTITY)

        /**
         * [source] 크기 이미지에서 [rect]만 남긴 뒤 [orientation] 방향으로 바꾸는 계획(자르기·회전 화면의 ✓, 사용자 피드백 8차 1).
         * [rect] 밖 픽셀(캔버스 밖 픽셀도)은 버린다. [rect]는 바꾸기 전(원래 방향) 좌표이고, 이미지 안에 있고 비어 있지 않아야 한다.
         * 대상 크기는 [rect]를 그 방향으로 바꾼 크기다.
         */
        fun cropThenOrient(source: PixelSize, rect: PixelRect, orientation: Orientation): TransformPlan {
            require(rect.width > 0 && rect.height > 0) { "Crop rect must not be empty: $rect" }
            require(rect in PixelRect.of(source)) { "Crop rect outside image: $rect in $source" }
            return TransformPlan(source, orientation.targetSize(PixelSize(rect.width, rect.height)), rect, orientation, 0, 0, discardsOutside = true)
        }

        /**
         * [source] 크기 이미지를 ([dx], [dy]) 픽셀만큼 옮기는 계획(이동 도구, 결정 기록 '이동 도구'). 크기는 그대로이고,
         * 캔버스 밖으로 나간 부분도 버리지 않으며(포토샵처럼, 사용자 피드백 13차 1) 옮겨서 빈 자리는 어느 조각도 덮지 않는다(투명).
         * 타일 크기(256)의 배수만큼 옮기면 대상 타일마다 원본 타일 하나를 꼭 맞게 가져온다(그대로 쓸 수 있다).
         */
        fun translate(source: PixelSize, dx: Int, dy: Int): TransformPlan =
            TransformPlan(source, source, PixelRect.of(source), Orientation.IDENTITY, dx, dy, discardsOutside = false)

        /**
         * [source] 크기 이미지의 [rect]를 [orientation] 방향으로 바꿔 [target] 크기 캔버스의 ([left], [top])에 왼쪽 위를 두는 계획.
         * 같은 옮김을 평면 전체에 걸어 [rect] 밖·캔버스 밖 픽셀도 같이 옮기고 버리지 않는다([clip]이면 [rect] 밖은 버린다).
         * 레이어 돌리기·뒤집기(사용자 피드백 12차 2, [rect]는 레이어 내용을 담는 사각형)와 사진 넣기(12차)가 쓴다. [rect]는 비어 있지 않아야 한다.
         */
        fun place(source: PixelSize, rect: PixelRect, orientation: Orientation, target: PixelSize, left: Int, top: Int, clip: Boolean = false): TransformPlan {
            require(rect.width > 0 && rect.height > 0) { "Source rect must not be empty: $rect" }
            return TransformPlan(source, target, rect, orientation, left, top, discardsOutside = clip)
        }
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

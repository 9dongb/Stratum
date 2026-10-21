package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix as DrawMatrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.platform.DocumentColors
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 체크 무늬 한 칸의 크기. 확대해도 화면에서 같은 크기다. */
internal val CheckerSquare = 8.dp

/** 캔버스 둘레 테두리의 두께. */
internal val CanvasOutlineWidth = 1.dp

/**
 * 엔진이 그린 축소본을 보기 상태([view])대로 캔버스 영역에 보여 준다. 화면 코드는 이미지 픽셀을 계산하지 않는다(CLAUDE.md 3장).
 *
 * - 투명한 곳이 보이도록 문서 아래에 체크 무늬를 깔고, 검은 캔버스도 어두운 배경과 구분되도록 바깥에 테두리를 두른다.
 * - 전체 축소본 위에, 확대해서 볼 때 선명하게 다시 그린 부분 그림을 겹쳐 그린다. 둘 다 자기 픽셀 격자대로 그려 어긋나지 않는다.
 * - 원본 단계 그림을 키워 보일 때만 가장 가까운 픽셀로(또렷한 네모), 그 밖에는 부드럽게 그린다.
 * - 보이는 부분만 그려서, 아주 크게 확대해도 계산이 커지지 않는다.
 *
 * [view]는 그리는 순간에 읽어서, 두 손가락으로 움직이는 동안 다시 배치하지 않고 다시 그리기만 한다.
 * 모두 GPU가 그린다. 체크 무늬는 2×2 비트맵을 반복하는 셰이더라 CPU로 픽셀을 돌지 않는다.
 *
 * [orientation]은 자르기·회전 화면에서 아직 적용하지 않은 돌리기·뒤집기다(사용자 피드백 8차 1). 같은 그림을 그릴 때만 돌려,
 * 보기([view])는 돌린 모습의 문서(가로·세로가 바뀐 크기) 기준이다. 픽셀은 다시 그리지 않는다.
 */
@Composable
fun CanvasImage(state: EditorState.Ready, view: () -> CanvasView, modifier: Modifier = Modifier, orientation: Orientation = Orientation.IDENTITY) {
    // 그림이 바뀔 때만 Compose용 이미지로 감싼다. 넓은 색 문서는 그 색 공간의 꼬리표로 보이고,
    // HDR 문서는 그 자리만큼의 게인맵을 붙여 HDR 화면에서 밝게 보인다(엔진 그림은 그대로 둔다).
    val documentRect = PixelRect.of(state.documentSize)
    val base = remember(state.base.bitmap, state.colorSpace, state.gainmap) {
        DocumentColors.forScreen(state.base.bitmap, state.colorSpace, state.gainmap, documentRect, state.documentSize).asImageBitmap()
    }
    val detailImage = state.detail
    val detail = remember(detailImage, state.colorSpace, state.gainmap) {
        detailImage?.let { DocumentColors.forScreen(it.bitmap, state.colorSpace, state.gainmap, it.region.rect, state.documentSize).asImageBitmap() }
    }
    val checker = remember { checkerBitmap() }
    val outline = StratumTheme.colors.canvasOutline
    val description = stringResource(R.string.edit_canvas_description)
    Spacer(
        modifier
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .drawWithCache {
                // 크기·밀도에만 달린 것을 미리 만들고, 보기 상태는 그리는 순간에 읽는다
                val square = CheckerSquare.toPx()
                val checkerBrush = ShaderBrush(
                    BitmapShader(checker, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                        filterMode = BitmapShader.FILTER_MODE_NEAREST
                        // 한 픽셀을 한 칸 크기로 늘인다. 위치는 그릴 때 문서 왼쪽 위에 맞춘다.
                        setLocalMatrix(Matrix().apply { setScale(square, square) })
                    }
                )
                val stroke = CanvasOutlineWidth.toPx()
                onDrawBehind {
                    if (size.width < 1f || size.height < 1f) return@onDrawBehind
                    val area = PixelSize(size.width.toInt(), size.height.toInt())
                    // 돌려 보이는 중이면 돌린 모습의 문서 크기(가로·세로가 바뀔 수 있음)
                    val document = orientation.targetSize(state.documentSize)
                    val transform = view().transform(area, document)
                    // 문서의 화면 사각형. 정수 픽셀에 맞춰야 테두리와 체크 무늬가 흐려지지 않는다.
                    val docLeft = transform.toScreenX(0.0).roundToInt()
                    val docTop = transform.toScreenY(0.0).roundToInt()
                    val docRight = transform.toScreenX(document.width.toDouble()).roundToInt()
                    val docBottom = transform.toScreenY(document.height.toDouble()).roundToInt()
                    // 보이는 부분만 칠한다. 아주 크게 확대하면 문서 사각형이 화면보다 훨씬 커진다.
                    val clipLeft = docLeft.coerceIn(0, area.width).toFloat()
                    val clipTop = docTop.coerceIn(0, area.height).toFloat()
                    val clipRight = docRight.coerceIn(0, area.width).toFloat()
                    val clipBottom = docBottom.coerceIn(0, area.height).toFloat()
                    if (clipRight > clipLeft && clipBottom > clipTop) {
                        clipRect(clipLeft, clipTop, clipRight, clipBottom) {
                            // 무늬는 두 칸마다 되풀이되므로, 문서 왼쪽 위를 두 칸 단위로 나눈 나머지만큼만 옮겨 맞춘다
                            val period = 2 * square
                            val phaseX = docLeft.toFloat().mod(period)
                            val phaseY = docTop.toFloat().mod(period)
                            translate(phaseX, phaseY) {
                                drawRect(
                                    checkerBrush,
                                    topLeft = Offset(clipLeft - phaseX, clipTop - phaseY),
                                    size = Size(clipRight - clipLeft, clipBottom - clipTop)
                                )
                            }
                            drawPreview(base, state.base, transform, orientation, state.documentSize)
                            val detailImage = detail
                            val detailPreview = state.detail
                            if (detailImage != null && detailPreview != null) {
                                drawPreview(detailImage, detailPreview, transform, orientation, state.documentSize)
                            }
                        }
                    }
                    // 선은 가운데를 기준으로 그려지므로, 두께의 반만큼 바깥으로 옮겨 캔버스를 덮지 않게 한다.
                    // 화면 밖으로 멀리 나간 변은 화면 바로 밖으로 당겨, 좌표가 커지지 않게 한다.
                    val margin = 2 * stroke
                    val outlineLeft = docLeft.toFloat().coerceIn(-margin, area.width + margin)
                    val outlineTop = docTop.toFloat().coerceIn(-margin, area.height + margin)
                    val outlineRight = docRight.toFloat().coerceIn(-margin, area.width + margin)
                    val outlineBottom = docBottom.toFloat().coerceIn(-margin, area.height + margin)
                    drawRect(
                        color = outline,
                        topLeft = Offset(outlineLeft - stroke / 2, outlineTop - stroke / 2),
                        size = Size(outlineRight - outlineLeft + stroke, outlineBottom - outlineTop + stroke),
                        style = Stroke(stroke)
                    )
                }
            }
    )
}

/**
 * 축소본 한 장을 자기 픽셀 격자대로 그린다. 비트맵 픽셀 i는 문서 x = 영역 왼쪽 + i × 2^단계에서 시작한다.
 * 화면에 보이는 비트맵 픽셀만 골라, 그 경계를 정수 화면 픽셀에 맞춰 그린다. 문서 밖으로 넘친 끝은 바깥의 잘라 내기가 가린다.
 * 돌려 보이는 중이면([orientation], 원래 문서 크기 [original]) [screen]은 돌린 모습의 문서 기준이고, 같은 그림을 돌려 그린다.
 * 90° 단위라 돌린 경계도 정수 화면 픽셀에 맞는다.
 */
private fun DrawScope.drawPreview(image: ImageBitmap, preview: PreviewImage, screen: ViewTransform, orientation: Orientation, original: PixelSize) {
    val level = preview.region.level
    val step = (1 shl level).toDouble()
    val left = preview.region.rect.left
    val top = preview.region.rect.top
    val shown = orientation.targetSize(original)
    // 화면에 보이는 (돌린) 문서 영역을 원래 방향으로 되돌려, 보이는 비트맵 픽셀 범위를 고른다
    val back = orientation.inverse
    val (ax, ay) = back.mapPoint(screen.toDocumentX(0.0), screen.toDocumentY(0.0), shown.width.toDouble(), shown.height.toDouble())
    val (bx, by) = back.mapPoint(
        screen.toDocumentX(size.width.toDouble()),
        screen.toDocumentY(size.height.toDouble()),
        shown.width.toDouble(),
        shown.height.toDouble()
    )
    val firstColumn = floor((min(ax, bx) - left) / step).toInt().coerceIn(0, image.width)
    val lastColumn = ceil((max(ax, bx) - left) / step).toInt().coerceIn(0, image.width)
    val firstRow = floor((min(ay, by) - top) / step).toInt().coerceIn(0, image.height)
    val lastRow = ceil((max(ay, by) - top) / step).toInt().coerceIn(0, image.height)
    if (lastColumn <= firstColumn || lastRow <= firstRow) return
    // 고른 픽셀 사각형(원래 방향 문서 좌표)을 돌린 모습의 화면 사각형으로 옮긴다
    val (cx0, cy0) = orientation.mapPoint(left + firstColumn * step, top + firstRow * step, original.width.toDouble(), original.height.toDouble())
    val (cx1, cy1) = orientation.mapPoint(left + lastColumn * step, top + lastRow * step, original.width.toDouble(), original.height.toDouble())
    val x0 = screen.toScreenX(min(cx0, cx1)).roundToInt()
    val x1 = screen.toScreenX(max(cx0, cx1)).roundToInt()
    val y0 = screen.toScreenY(min(cy0, cy1)).roundToInt()
    val y1 = screen.toScreenY(max(cy0, cy1)).roundToInt()
    if (x1 <= x0 || y1 <= y0) return
    // 원본 단계를 키워 보일 때만 또렷한 네모로 그린다. 줄인 그림을 그렇게 그리면 없는 네모가 보인다.
    val magnifiedOriginal = level == 0 && screen.scaleX > 1.0
    val filter = if (magnifiedOriginal) FilterQuality.None else FilterQuality.Low
    val srcOffset = IntOffset(firstColumn, firstRow)
    val srcSize = IntSize(lastColumn - firstColumn, lastRow - firstRow)
    if (orientation == Orientation.IDENTITY) {
        drawImage(image, srcOffset, srcSize, IntOffset(x0, y0), IntSize(x1 - x0, y1 - y0), filterQuality = filter)
        return
    }
    // 돌리기 전 모양(가로·세로가 바뀌면 바꾼 크기)으로 (0, 0)에 그리고, 그 사각형을 화면 사각형으로 돌려 옮기는 변환을 건다
    val width = if (orientation.swapsAxes) y1 - y0 else x1 - x0
    val height = if (orientation.swapsAxes) x1 - x0 else y1 - y0
    withTransform({ transform(orientationMatrix(orientation, width, height, x0, y0)) }) {
        drawImage(image, srcOffset, srcSize, IntOffset.Zero, IntSize(width, height), filterQuality = filter)
    }
}

/**
 * 돌리기 전 모양의 [width]×[height] 사각형((0, 0)에서 시작)을 [orientation] 방향으로 돌려 화면 ([left], [top])에 놓는 변환.
 * 점 하나를 옮기는 식([Orientation.mapPoint])에서 계수를 읽는다(90° 단위라 계수가 0·±1이다).
 */
internal fun orientationMatrix(orientation: Orientation, width: Int, height: Int, left: Int, top: Int): DrawMatrix {
    val w = width.toDouble()
    val h = height.toDouble()
    val (ox, oy) = orientation.mapPoint(0.0, 0.0, w, h)
    val (ux, uy) = orientation.mapPoint(1.0, 0.0, w, h)
    val (vx, vy) = orientation.mapPoint(0.0, 1.0, w, h)
    return DrawMatrix().apply {
        values[DrawMatrix.ScaleX] = (ux - ox).toFloat()
        values[DrawMatrix.SkewX] = (vx - ox).toFloat()
        values[DrawMatrix.TranslateX] = (ox + left).toFloat()
        values[DrawMatrix.SkewY] = (uy - oy).toFloat()
        values[DrawMatrix.ScaleY] = (vy - oy).toFloat()
        values[DrawMatrix.TranslateY] = (oy + top).toFloat()
    }
}

/** 체크 무늬 한 벌(2×2칸) 비트맵. 셰이더가 칸 크기로 늘여 반복해 그린다. */
private fun checkerBitmap(): Bitmap {
    val light = CanvasOverlayColors.CheckerLight.toArgb()
    val dark = CanvasOverlayColors.CheckerDark.toArgb()
    return Bitmap.createBitmap(intArrayOf(light, dark, dark, light), 2, 2, Bitmap.Config.ARGB_8888)
}

package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 투명한 곳을 보여 주는 체크 무늬의 두 칸 색. 다른 편집 앱처럼 흰색과 밝은 회색이다. */
internal val CheckerLight = Color(0xFFFFFFFF)
internal val CheckerDark = Color(0xFFCCCCCC)

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
 */
@Composable
fun CanvasImage(state: EditorState.Ready, view: () -> CanvasView, modifier: Modifier = Modifier) {
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
    val outline = MaterialTheme.colorScheme.outlineVariant
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
                    val document = state.documentSize
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
                            drawPreview(base, state.base, transform)
                            val detailImage = detail
                            val detailPreview = state.detail
                            if (detailImage != null && detailPreview != null) drawPreview(detailImage, detailPreview, transform)
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
 */
private fun DrawScope.drawPreview(image: ImageBitmap, preview: PreviewImage, transform: ViewTransform) {
    val level = preview.region.level
    val step = (1 shl level).toDouble()
    val left = preview.region.rect.left
    val top = preview.region.rect.top
    // 화면에 보이는 비트맵 픽셀 범위
    val firstColumn = floor((transform.toDocumentX(0.0) - left) / step).toInt().coerceIn(0, image.width)
    val lastColumn = ceil((transform.toDocumentX(size.width.toDouble()) - left) / step).toInt().coerceIn(0, image.width)
    val firstRow = floor((transform.toDocumentY(0.0) - top) / step).toInt().coerceIn(0, image.height)
    val lastRow = ceil((transform.toDocumentY(size.height.toDouble()) - top) / step).toInt().coerceIn(0, image.height)
    if (lastColumn <= firstColumn || lastRow <= firstRow) return
    val x0 = transform.toScreenX(left + firstColumn * step).roundToInt()
    val x1 = transform.toScreenX(left + lastColumn * step).roundToInt()
    val y0 = transform.toScreenY(top + firstRow * step).roundToInt()
    val y1 = transform.toScreenY(top + lastRow * step).roundToInt()
    if (x1 <= x0 || y1 <= y0) return
    // 원본 단계를 키워 보일 때만 또렷한 네모로 그린다. 줄인 그림을 그렇게 그리면 없는 네모가 보인다.
    val magnifiedOriginal = level == 0 && transform.scaleX > 1.0
    drawImage(
        image = image,
        srcOffset = IntOffset(firstColumn, firstRow),
        srcSize = IntSize(lastColumn - firstColumn, lastRow - firstRow),
        dstOffset = IntOffset(x0, y0),
        dstSize = IntSize(x1 - x0, y1 - y0),
        filterQuality = if (magnifiedOriginal) FilterQuality.None else FilterQuality.Low
    )
}

/** 체크 무늬 한 벌(2×2칸) 비트맵. 셰이더가 칸 크기로 늘여 반복해 그린다. */
private fun checkerBitmap(): Bitmap {
    val light = CheckerLight.toArgb()
    val dark = CheckerDark.toArgb()
    return Bitmap.createBitmap(intArrayOf(light, dark, dark, light), 2, 2, Bitmap.Config.ARGB_8888)
}

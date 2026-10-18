package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.CanvasOverlayColors

/** 손잡이를 잡을 수 있는 거리. 손잡이 둘레 48dp가 잡힌다(CLAUDE.md 6장 최소 터치 영역). */
private val CropHandleReach = 24.dp

/** 화면에서 테두리 짧은 변의 가장 작은 크기. 손잡이가 겹치지 않고 안쪽을 잡아 옮길 수 있는 크기다. */
private val CropMinSide = 72.dp

/** 테두리 바깥을 어둡게 덮는 색 */

/**
 * 자르기 테두리. 캔버스 위에 겹쳐, 테두리 바깥을 어둡게 하고 3등분 선과 손잡이(모서리 넷, 변 넷)를 그린다.
 *
 * - 손잡이를 끌면 크기가, 안쪽을 끌면 위치가 바뀐다([CropFrame.dragged]). 테두리는 사진 밖으로 나가지 않는다.
 * - 한 손가락만 받는다. 두 손가락 확대·이동은 부모([CanvasViewport])가 먼저 받고, 시작되면 끌던 테두리를 처음 자리로 되돌린다.
 * - 손잡이는 사진과 함께 움직이므로 접히는 선을 피하지 않는다(결정 기록 '자르기·회전·뒤집기·크기 변경').
 *
 * [view]는 그리는 순간·끌기 시작하는 순간에 읽는다. 테두리는 문서 픽셀 좌표이고, [onFrameChange]로 새 테두리를 알린다.
 */
@Composable
fun CropOverlay(
    frame: CropFrame,
    document: PixelSize,
    view: () -> CanvasView,
    onFrameChange: (CropFrame) -> Unit,
    modifier: Modifier = Modifier
) {
    val current by rememberUpdatedState(frame)
    val change by rememberUpdatedState(onFrameChange)
    val description = stringResource(R.string.crop_frame_description)
    Spacer(
        modifier
            .testTag(EditScreenTags.CROP_OVERLAY)
            .semantics { contentDescription = description }
            .pointerInput(document) {
                // 끌기를 시작할 때의 손잡이·테두리·배율
                var handle: CropHandle? = null
                var start = current
                var total = Offset.Zero
                var scaleX = 1.0
                var scaleY = 1.0
                detectDragGestures(
                    orientationLock = null,
                    onDragStart = { down, _, _ ->
                        val transform = view().transform(PixelSize(size.width, size.height), document)
                        start = current
                        total = Offset.Zero
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        // 처음 닿은 자리로 손잡이를 고른다(터치 허용 거리만큼 움직인 뒤가 아니라)
                        handle = cropHandleAt(
                            down.position.x.toDouble(),
                            down.position.y.toDouble(),
                            transform.toScreenX(start.left),
                            transform.toScreenY(start.top),
                            transform.toScreenX(start.right),
                            transform.toScreenY(start.bottom),
                            CropHandleReach.toPx().toDouble()
                        )
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = {
                        // 두 손가락 조작이 시작되면 부모가 터치를 소비해 여기로 온다
                        if (handle != null) change(start)
                        handle = null
                    },
                    onDrag = { _, amount ->
                        val dragged = handle ?: return@detectDragGestures
                        total += amount
                        change(
                            start.dragged(
                                dragged,
                                total.x / scaleX,
                                total.y / scaleY,
                                document,
                                minSide = CropMinSide.toPx() / scaleX
                            )
                        )
                    }
                )
            }
            .drawBehind {
                if (size.width < 1f || size.height < 1f) return@drawBehind
                val transform = view().transform(PixelSize(size.width.toInt(), size.height.toInt()), document)
                val f = current
                drawCropFrame(
                    left = transform.toScreenX(f.left).toFloat(),
                    top = transform.toScreenY(f.top).toFloat(),
                    right = transform.toScreenX(f.right).toFloat(),
                    bottom = transform.toScreenY(f.bottom).toFloat()
                )
            }
    )
}

/** 테두리 바깥을 어둡게 하고, 3등분 선·테두리·손잡이를 그린다. 손잡이는 어두운 테를 둘러 밝은 사진 위에서도 보인다. */
private fun DrawScope.drawCropFrame(left: Float, top: Float, right: Float, bottom: Float) {
    // 바깥 네 조각
    drawRect(CanvasOverlayColors.CropScrim, Offset.Zero, Size(size.width, top.coerceIn(0f, size.height)))
    drawRect(CanvasOverlayColors.CropScrim, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
    drawRect(CanvasOverlayColors.CropScrim, Offset(0f, top), Size(left.coerceIn(0f, size.width), bottom - top))
    drawRect(CanvasOverlayColors.CropScrim, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), bottom - top))
    val thin = 1.dp.toPx()
    val w = right - left
    val h = bottom - top
    // 3등분 선
    val guide = CanvasOverlayColors.Guide
    for (i in 1..2) {
        drawLine(guide, Offset(left + w * i / 3, top), Offset(left + w * i / 3, bottom), thin)
        drawLine(guide, Offset(left, top + h * i / 3), Offset(right, top + h * i / 3), thin)
    }
    drawRect(CanvasOverlayColors.Light, Offset(left, top), Size(w, h), style = Stroke(thin))
    // 모서리는 ㄱ자, 변은 가운데 짧은 막대
    val arm = 20.dp.toPx().coerceAtMost(minOf(w, h) / 2)
    val bar = 24.dp.toPx().coerceAtMost(minOf(w, h) / 3)
    for ((color, width) in listOf(CanvasOverlayColors.DarkHalo to 5.dp.toPx(), CanvasOverlayColors.Light to 3.dp.toPx())) {
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), width)
        line(left, top, left + arm, top)
        line(left, top, left, top + arm)
        line(right, top, right - arm, top)
        line(right, top, right, top + arm)
        line(left, bottom, left + arm, bottom)
        line(left, bottom, left, bottom - arm)
        line(right, bottom, right - arm, bottom)
        line(right, bottom, right, bottom - arm)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        line(cx - bar / 2, top, cx + bar / 2, top)
        line(cx - bar / 2, bottom, cx + bar / 2, bottom)
        line(left, cy - bar / 2, left, cy + bar / 2)
        line(right, cy - bar / 2, right, cy + bar / 2)
    }
}

package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.CanvasOverlayColors

/**
 * 스포이드로 색을 고르는 중(손가락 둘레 고리, 결정 기록 '스포이드'). 좌표는 문서 픽셀이다.
 *
 * @property target 고른 색을 쓸 곳
 * @property color 그 자리에 보이는 색(아직 읽지 못했거나 투명한 곳이면 null)
 */
data class EyedropperState(val target: ColorTarget, val x: Float, val y: Float, val color: Int? = null)

/** 스포이드가 엔진 쪽(ViewModel)에 보내는 일. 좌표는 문서 픽셀이다. */
interface EyedropperActions {

    /** 손가락이 닿았다: [target]의 색을 그 자리에서 고르기 시작한다 */
    fun begin(target: ColorTarget, x: Float, y: Float)

    /** 손가락이 움직였다 */
    fun move(x: Float, y: Float)

    /** 손을 뗐다: 마지막 자리의 색을 쓰고 최근 색에 더한다(투명한 곳이면 그대로) */
    fun end()

    /** 두 손가락 조작이 시작되는 등으로 그만둔다(색은 그대로) */
    fun cancel()

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : EyedropperActions {
        override fun begin(target: ColorTarget, x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 스포이드 버튼을 켜면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 누르거나 끌면 그 자리 색을 고르고, 떼면 그 색을 쓴다.
 * 고르고 나면 [onPicked](버튼을 끈다). 두 손가락 조작(확대·이동)이 시작되거나 두 번째 손가락이 닿으면 고르지 않고 그만둔다(버튼은 켜 둔다).
 */
@Composable
fun EyedropperOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    target: ColorTarget,
    actions: EyedropperActions,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTarget by rememberUpdatedState(target)
    val currentActions by rememberUpdatedState(actions)
    val picked by rememberUpdatedState(onPicked)
    Box(
        modifier.testTag(EditScreenTags.EYEDROPPER_OVERLAY).pointerInput(document) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val area = PixelSize(size.width, size.height)
                fun toDocument(position: Offset, send: (Float, Float) -> Unit) {
                    val transform = view().transform(area, document)
                    send(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
                }
                val actions = currentActions
                toDocument(down.position) { x, y -> actions.begin(currentTarget, x, y) }
                down.consume()
                var done = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                        toDocument(change.position) { x, y -> actions.move(x, y) }
                        if (!change.pressed) {
                            done = true
                            break
                        }
                        change.consume()
                    }
                } finally {
                    if (done) {
                        actions.end()
                        picked()
                    } else {
                        actions.cancel()
                    }
                }
            }
        }
    )
}

/**
 * 스포이드 고리: 손가락 자리를 둘러싼 고리의 위쪽 반은 고를 색, 아래쪽 반은 지금 색이다(Procreate와 같다).
 * 아직 읽지 못했거나 투명한 곳이면 위쪽 반이 회색이다. 어떤 사진 위에서도 보이게 고리 둘레에 어두운 선을 두른다.
 */
@Composable
fun EyedropperLoupe(state: EyedropperState, currentColor: Int, view: () -> CanvasView, document: PixelSize, modifier: Modifier = Modifier) {
    // 화면 반전 중에도 고리는 손가락 자리에 있고, 위쪽 반이 고를 색이다(고리 가운데를 기준으로 반전을 되돌린다)
    val flip = LocalViewFlip.current
    Canvas(modifier.testTag(EditScreenTags.EYEDROPPER_LOUPE)) {
        val area = PixelSize(size.width.toInt(), size.height.toInt())
        if (area.width <= 0 || area.height <= 0) return@Canvas
        val transform = view().transform(area, document)
        val center = Offset(transform.toScreenX(state.x.toDouble()).toFloat(), transform.toScreenY(state.y.toDouble()).toFloat())
        val outer = LoupeRadius.toPx()
        val width = LoupeWidth.toPx()
        val radius = outer - width / 2
        val box = Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        withTransform({ scale(flip.scaleX, flip.scaleY, pivot = center) }) {
            val picked = state.color?.let { Color(it) } ?: CanvasOverlayColors.Unknown
            // 위쪽 반(9시 → 12시 → 3시): 고를 색, 아래쪽 반: 지금 색
            drawArc(picked, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(width))
            drawArc(Color(currentColor), startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(width))
            val edge = CanvasOverlayColors.DarkHalo
            drawCircle(edge, outer, center, style = Stroke(1.5.dp.toPx()))
            drawCircle(edge, outer - width, center, style = Stroke(1.5.dp.toPx()))
            // 가운데 작은 십자: 고르는 자리
            val cross = 6.dp.toPx()
            drawLine(CanvasOverlayColors.Light, Offset(center.x - cross, center.y), Offset(center.x + cross, center.y), 3.dp.toPx())
            drawLine(CanvasOverlayColors.Light, Offset(center.x, center.y - cross), Offset(center.x, center.y + cross), 3.dp.toPx())
            drawLine(CanvasOverlayColors.Dark, Offset(center.x - cross, center.y), Offset(center.x + cross, center.y), 1.dp.toPx())
            drawLine(CanvasOverlayColors.Dark, Offset(center.x, center.y - cross), Offset(center.x, center.y + cross), 1.dp.toPx())
        }
    }
}

/** 스포이드 고리의 바깥 반지름과 고리 두께. 손가락에 가려지지 않게 넉넉하다. */
private val LoupeRadius = 60.dp
private val LoupeWidth = 18.dp

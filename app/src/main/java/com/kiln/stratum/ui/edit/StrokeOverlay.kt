package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.components.editLongPressMillis
import com.kiln.stratum.ui.components.trackMouseHover
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import kotlin.math.roundToInt

/** 캔버스의 브러시·지우개 손가락이 엔진 쪽(ViewModel)에 보내는 일. 좌표는 문서 픽셀이다. */
interface StrokeActions {

    /** 한 손가락이 닿아 획을 시작한다([erase]면 지우개) */
    fun begin(x: Float, y: Float, erase: Boolean)

    /** 손가락이 움직였다 */
    fun extend(x: Float, y: Float)

    /** 손가락을 뗐다. 획을 기록한다. */
    fun end()

    /** 두 손가락 조작이 시작돼 획을 버린다 */
    fun cancel()

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) = Unit
        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/** 획 손가락을 움직이지 않고 꾹 누르면 넘겨받는 일(브러시의 스포이드, 결정 기록 '스포이드'). 좌표는 문서 픽셀이다. */
interface HoldActions {

    /** 꾹 눌렀다(그 자리) */
    fun begin(x: Float, y: Float)

    /** 누른 채 움직였다 */
    fun move(x: Float, y: Float)

    /** 손을 뗐다 */
    fun end()

    /** 두 손가락 조작 등으로 그만둔다 */
    fun cancel()
}

/**
 * 브러시·지우개를 고르면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 끌면 획을 그린다(눌렀다 떼기만 해도 점 하나).
 * [hold]가 있으면(브러시) 움직이지 않고 꾹 누를 때 그 손가락을 넘긴다(스포이드). [cursorSize]는 브러시 커서의 지름(문서 픽셀)이다.
 * 손가락 처리는 [PaintGestureOverlay]와 같다.
 */
@Composable
fun StrokeOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    erase: Boolean,
    actions: StrokeActions,
    modifier: Modifier = Modifier,
    hold: HoldActions? = null,
    cursorSize: Float? = null
) {
    PaintGestureOverlay(
        view = view,
        document = document,
        tag = EditScreenTags.STROKE_OVERLAY,
        onBegin = { x, y -> actions.begin(x, y, erase) },
        onExtend = actions::extend,
        onEnd = actions::end,
        onCancel = actions::cancel,
        modifier = modifier,
        hold = hold,
        cursorSize = cursorSize
    )
}

/**
 * 한 손가락으로 끌어 획을 그리는 터치 자리(브러시·지우개·명암). 좌표는 지금 보기([view])로 문서 좌표로 바꿔 보낸다.
 * 손가락이 빠르게 움직이면 이벤트 사이의 점들도 보낸다.
 * 두 손가락 조작(확대·이동)은 부모가 먼저 읽고, 시작되면 터치를 소비한다(CanvasGestures.kt). 그러면 끄던 획을 버린다([onCancel]).
 * 두 번째 손가락이 닿아도 버린다(두 손가락으로 확대·이동하려는 것으로 본다).
 * [hold]가 있으면, 닿은 뒤 길게 누르기 시간(약 0.4초) 동안 터치 허용 거리보다 움직이지 않으면 그리던 점을 버리고([onCancel])
 * 그 손가락을 [hold]로 넘긴다. 그 안에 움직이면 지금처럼 획이다.
 * [cursorSize](문서 픽셀 지름)가 있으면 칠하는 손가락 자리에 그 크기의 원(브러시 커서)을 보인다. 손을 떼거나 획을 버리거나
 * 손가락을 [hold]로 넘기면 지운다(결정 기록 '브러시 커서').
 */
@Composable
fun PaintGestureOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    tag: String,
    onBegin: (x: Float, y: Float) -> Unit,
    onExtend: (x: Float, y: Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    hold: HoldActions? = null,
    cursorSize: Float? = null
) {
    val begin by rememberUpdatedState(onBegin)
    val extend by rememberUpdatedState(onExtend)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    val currentHold by rememberUpdatedState(hold)
    // 칠하는 손가락의 화면 자리(브러시 커서). 칠하지 않으면 null.
    var finger by remember { mutableStateOf<Offset?>(null) }
    // 마우스를 올려 둔 자리(사용자 피드백 12차 4: 칠하기 전에도 붓 원을 보인다). 마우스가 없거나 나가면 null.
    var hover by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier
            .testTag(tag)
            // 붓을 쓰는 도구(붓 원이 있음)는 캔버스 위 마우스 포인터가 십자다. 이동 도구는 기본 포인터다.
            .then(if (cursorSize != null) Modifier.pointerHoverIcon(PointerIcon.Crosshair) else Modifier)
            .trackMouseHover { hover = it }
            .pointerInput(document) {
            awaitEachGesture {
                // 두 손가락 조작이 가져간 터치는 받지 않는다
                val down = awaitFirstDown()
                val area = PixelSize(size.width, size.height)
                fun toDocument(position: Offset, send: (Float, Float) -> Unit) {
                    val transform = view().transform(area, document)
                    send(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
                }
                toDocument(down.position) { x, y -> begin(x, y) }
                down.consume()
                finger = down.position
                // 두 손가락이면 버리고(false), 손을 떼면 기록한다(true). 그 전에 이 자리가 사라지면(도구를 바꿈, 접기·펼치기)
                // 터치를 기다리던 일이 취소되어 아래 finally에서 끄던 획까지 기록한다(보정 슬라이더와 같다).
                var keep: Boolean? = null
                // 꾹 눌러 손가락을 넘겼는지(그러면 획은 이미 버렸다)
                var held = false
                /** 손가락 하나의 움직임을 획으로 보낸다. 끝났으면(뗌·두 손가락) keep을 정하고 false다. */
                fun follow(event: PointerEvent): PointerInputChange? {
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                        keep = false
                        finger = null
                        return null
                    }
                    for (point in change.historical) toDocument(point.position) { x, y -> extend(x, y) }
                    toDocument(change.position) { x, y -> extend(x, y) }
                    if (!change.pressed) {
                        keep = true
                        finger = null
                        return null
                    }
                    change.consume()
                    finger = change.position
                    return change
                }
                try {
                    val holdActions = currentHold
                    if (holdActions != null) {
                        // 길게 누르기 시간 안에 움직이거나 손을 떼면 획이고, 시간이 다 가면 꾹 누른 것이다(null).
                        // 캔버스 안 편집이라 편집 묶음 시간(휴대폰 설정)이다(결정 기록 '꾹 누르기 시간')
                        var last = down.position
                        val decided = withTimeoutOrNull(viewConfiguration.editLongPressMillis()) {
                            while (true) {
                                val change = follow(awaitPointerEvent()) ?: return@withTimeoutOrNull true
                                last = change.position
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                            }
                            @Suppress("UNREACHABLE_CODE")
                            true
                        }
                        if (decided == null) {
                            held = true
                            finger = null
                            cancel()
                            followHold(down.id, last, holdActions, ::toDocument)
                            return@awaitEachGesture
                        }
                    }
                    while (keep == null) follow(awaitPointerEvent())
                } finally {
                    finger = null
                    if (!held) {
                        if (keep == false) cancel() else end()
                    }
                }
            }
        }
    ) {
        val at = finger ?: hover
        if (cursorSize != null && at != null) BrushCursor(at, cursorSize, view, document)
    }
}

/** 브러시 커서: 손가락 자리 [center](화면 좌표)에 지름 [size](문서 픽셀)의 원을 지금 보기 배율로 그린다 */
@Composable
internal fun BrushCursor(center: Offset, size: Float, view: () -> CanvasView, document: PixelSize) {
    Canvas(Modifier.fillMaxSize().testTag(EditScreenTags.BRUSH_CURSOR)) {
        val scale = view().transform(PixelSize(this.size.width.roundToInt(), this.size.height.roundToInt()), document).scaleX.toFloat()
        drawBrushCursor(center, size / 2f * scale)
    }
}

/** 브러시 커서 모양: 흰 선에 검은 테두리를 둘러 어떤 사진 위에서도 보인다(유동화와 같다) */
internal fun DrawScope.drawBrushCursor(center: Offset, radius: Float) {
    drawCircle(CanvasOverlayColors.Light, radius, center, style = Stroke(width = 2.dp.toPx()))
    drawCircle(CanvasOverlayColors.Dark, radius + 1.dp.toPx(), center, style = Stroke(width = 1.dp.toPx()))
}

/**
 * 꾹 누른 손가락([pointer])을 [hold]로 보낸다: 그 자리([from])에서 시작해 움직임을 보내고, 떼면 끝, 두 손가락이면 그만둔다.
 * 이 자리가 사라지면(도구를 바꿈 등) 그만둔다.
 */
private suspend fun AwaitPointerEventScope.followHold(
    pointer: PointerId,
    from: Offset,
    hold: HoldActions,
    toDocument: (Offset, (Float, Float) -> Unit) -> Unit
) {
    toDocument(from) { x, y -> hold.begin(x, y) }
    var picked = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointer }
            if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
            toDocument(change.position) { x, y -> hold.move(x, y) }
            if (!change.pressed) {
                picked = true
                break
            }
            change.consume()
        }
    } finally {
        if (picked) hold.end() else hold.cancel()
    }
}

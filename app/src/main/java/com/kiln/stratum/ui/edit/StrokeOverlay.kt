package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.kiln.stratum.engine.raster.PixelSize

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
 * [hold]가 있으면(브러시) 움직이지 않고 꾹 누를 때 그 손가락을 넘긴다(스포이드). 손가락 처리는 [PaintGestureOverlay]와 같다.
 */
@Composable
fun StrokeOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    erase: Boolean,
    actions: StrokeActions,
    modifier: Modifier = Modifier,
    hold: HoldActions? = null
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
        hold = hold
    )
}

/**
 * 한 손가락으로 끌어 획을 그리는 터치 자리(브러시·지우개·명암). 좌표는 지금 보기([view])로 문서 좌표로 바꿔 보낸다.
 * 손가락이 빠르게 움직이면 이벤트 사이의 점들도 보낸다.
 * 두 손가락 조작(확대·이동)은 부모가 먼저 읽고, 시작되면 터치를 소비한다(CanvasGestures.kt). 그러면 끄던 획을 버린다([onCancel]).
 * 두 번째 손가락이 닿아도 버린다(두 손가락으로 확대·이동하려는 것으로 본다).
 * [hold]가 있으면, 닿은 뒤 길게 누르기 시간(약 0.4초) 동안 터치 허용 거리보다 움직이지 않으면 그리던 점을 버리고([onCancel])
 * 그 손가락을 [hold]로 넘긴다. 그 안에 움직이면 지금처럼 획이다.
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
    hold: HoldActions? = null
) {
    val begin by rememberUpdatedState(onBegin)
    val extend by rememberUpdatedState(onExtend)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    val currentHold by rememberUpdatedState(hold)
    Box(
        modifier.testTag(tag).pointerInput(document) {
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
                        return null
                    }
                    for (point in change.historical) toDocument(point.position) { x, y -> extend(x, y) }
                    toDocument(change.position) { x, y -> extend(x, y) }
                    if (!change.pressed) {
                        keep = true
                        return null
                    }
                    change.consume()
                    return change
                }
                try {
                    val holdActions = currentHold
                    if (holdActions != null) {
                        // 길게 누르기 시간 안에 움직이거나 손을 떼면 획이고, 시간이 다 가면 꾹 누른 것이다(null)
                        var last = down.position
                        val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
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
                            cancel()
                            followHold(down.id, last, holdActions, ::toDocument)
                            return@awaitEachGesture
                        }
                    }
                    while (keep == null) follow(awaitPointerEvent())
                } finally {
                    if (!held) {
                        if (keep == false) cancel() else end()
                    }
                }
            }
        }
    )
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

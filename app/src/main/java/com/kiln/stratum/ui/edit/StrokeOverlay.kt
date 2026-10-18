package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/**
 * 브러시·지우개를 고르면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 끌면 획을 그린다(눌렀다 떼기만 해도 점 하나).
 * 손가락 처리는 [PaintGestureOverlay]와 같다.
 */
@Composable
fun StrokeOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    erase: Boolean,
    actions: StrokeActions,
    modifier: Modifier = Modifier
) {
    PaintGestureOverlay(
        view = view,
        document = document,
        tag = EditScreenTags.STROKE_OVERLAY,
        onBegin = { x, y -> actions.begin(x, y, erase) },
        onExtend = actions::extend,
        onEnd = actions::end,
        onCancel = actions::cancel,
        modifier = modifier
    )
}

/**
 * 한 손가락으로 끌어 획을 그리는 터치 자리(브러시·지우개·명암). 좌표는 지금 보기([view])로 문서 좌표로 바꿔 보낸다.
 * 손가락이 빠르게 움직이면 이벤트 사이의 점들도 보낸다.
 * 두 손가락 조작(확대·이동)은 부모가 먼저 읽고, 시작되면 터치를 소비한다(CanvasGestures.kt). 그러면 끄던 획을 버린다([onCancel]).
 * 두 번째 손가락이 닿아도 버린다(두 손가락으로 확대·이동하려는 것으로 본다).
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
    modifier: Modifier = Modifier
) {
    val begin by rememberUpdatedState(onBegin)
    val extend by rememberUpdatedState(onExtend)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
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
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                            keep = false
                            break
                        }
                        for (point in change.historical) toDocument(point.position) { x, y -> extend(x, y) }
                        toDocument(change.position) { x, y -> extend(x, y) }
                        if (!change.pressed) {
                            keep = true
                            break
                        }
                        change.consume()
                    }
                } finally {
                    if (keep == false) cancel() else end()
                }
            }
        }
    )
}

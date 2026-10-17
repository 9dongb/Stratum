package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import kotlin.math.abs

/**
 * 캔버스의 두 손가락 확대·이동을 알아챈다.
 *
 * - 두 손가락이 앞 이벤트부터 계속 닿아 있을 때만 움직인다. 둘째 손가락이 닿는 순간에 첫 손가락이 움직인 것은 쓰지 않는다.
 * - 한 손가락 터치는 건드리지 않아서, 나중에 브러시 같은 도구가 그대로 받는다.
 * - 손가락이 터치 허용 거리(touch slop)보다 많이 움직여 두 손가락 조작이 시작되면, 손가락을 모두 뗄 때까지
 *   모든 터치를 소비한다. 그래야 둘 중 하나를 먼저 떼도 남은 손가락이 도구 동작으로 이어지지 않는다.
 * - 도구(자식)보다 먼저 받도록 Initial 단계에서 읽는다. 도구는 소비된 터치를 보고 하던 동작을 멈추면 된다.
 * - 터치 허용 거리 안의 움직임은 버린다(조작 시작 전 떨림).
 *
 * [onTransform]은 손가락 가운데(지금), 손가락 가운데가 옮긴 거리, 손가락 사이가 몇 배 벌어졌는지를 받는다.
 * [onEnd]는 두 손가락 조작이 시작됐다가 손가락을 모두 뗐을 때 부른다.
 *
 * [onTwoFingerTap]이 있으면 두 손가락으로 톡 누른 것도 알아챈다: 두 손가락이 닿았다가, 어느 손가락도 터치 허용 거리만큼
 * 움직이지 않고, 처음 닿은 뒤 [TWO_FINGER_TAP_MILLIS] 안에 모두 뗐을 때다(설정의 '두 손가락으로 톡: 실행 취소').
 * 그런 터치가 손을 떼는 순간은 소비해서, 도구(글자 자리 누르기 등)가 한 손가락 톡으로 받지 않게 한다.
 */
suspend fun PointerInputScope.detectTwoFingerTransform(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit = {},
    onTwoFingerTap: (() -> Unit)? = null
) {
    awaitEachGesture {
        var transforming = false
        try {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomSinceStart = 1f
            var panSinceStart = Offset.Zero
            // 두 손가락 톡을 보기 위한 것: 손가락마다 닿은 자리, 함께 닿은 손가락 수의 최대, 움직였는지
            val downAt = HashMap<Long, Offset>().apply { put(first.id.value, first.position) }
            var mostFingers = 1
            var moved = false
            var lastTime = first.uptimeMillis
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                for (change in event.changes) {
                    if (change.pressed && !change.previousPressed) downAt[change.id.value] = change.position
                    val from = downAt[change.id.value]
                    if (from != null && (change.position - from).getDistance() > viewConfiguration.touchSlop) moved = true
                    lastTime = change.uptimeMillis
                }
                mostFingers = maxOf(mostFingers, event.changes.count { it.pressed })
                if (onTwoFingerTap != null && mostFingers >= 2 && !transforming && !moved) {
                    event.changes.forEach { if (!it.pressed && it.previousPressed) it.consume() }
                }
                val held = event.changes.count { it.type == PointerType.Touch && it.pressed && it.previousPressed }
                if (held >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (!transforming) {
                        // 터치 허용 거리를 넘을 때까지 모아 보고, 넘으면 그때부터 움직인다
                        zoomSinceStart *= zoom
                        panSinceStart += pan
                        val zoomMotion = abs(1 - zoomSinceStart) * event.calculateCentroidSize(useCurrent = false)
                        transforming = zoomMotion > viewConfiguration.touchSlop ||
                            panSinceStart.getDistance() > viewConfiguration.touchSlop
                    } else {
                        onTransform(event.calculateCentroid(useCurrent = true), pan, zoom)
                    }
                }
                if (transforming) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
            if (onTwoFingerTap != null && mostFingers == 2 && !transforming && !moved && lastTime - first.uptimeMillis <= TWO_FINGER_TAP_MILLIS) {
                onTwoFingerTap()
            }
        } finally {
            if (transforming) onEnd()
        }
    }
}

/** 두 손가락 톡으로 보는 가장 긴 시간(밀리초). 처음 손가락이 닿아서 모두 뗄 때까지다. */
const val TWO_FINGER_TAP_MILLIS = 350L

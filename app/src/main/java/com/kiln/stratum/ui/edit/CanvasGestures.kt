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
 */
suspend fun PointerInputScope.detectTwoFingerTransform(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit = {}
) {
    awaitEachGesture {
        var transforming = false
        try {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomSinceStart = 1f
            var panSinceStart = Offset.Zero
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
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
        } finally {
            if (transforming) onEnd()
        }
    }
}

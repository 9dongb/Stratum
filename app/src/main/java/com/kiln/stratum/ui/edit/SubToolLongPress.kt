package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 길게 누르기를 안쪽 버튼보다 먼저 알아챈다.
 *
 * Material 버튼·탭은 안쪽 클릭이 먼저 터치를 가져가서, 바깥에서 길게 누르기를 더해도 오지 않는다(소스로 확인).
 * 그래서 Initial 단계에서 먼저 보고, 길게 누른 것이 확인되면 손을 뗄 때까지 모든 터치를 소비한다.
 * 안쪽 버튼은 소비된 손 뗌을 보고 누르기를 취소하므로, 길게 누른 뒤에는 클릭이 일어나지 않는다.
 * 손을 일찍 떼거나 터치 허용 거리보다 많이 움직이면 아무것도 하지 않고 안쪽 버튼에 맡긴다.
 *
 * 길게 누른 뒤 손을 떼기 전까지는 그 손가락의 자리(창 좌표)를 [onDrag]로, 뗀 자리를 [onRelease]로 알린다
 * (하위 도구 메뉴에서 끌어 고르기, 사용자 피드백 7차). 꾹 누르기 시간은 이 자리의 터치 설정을 따른다:
 * 메뉴를 여는 자리는 [com.kiln.stratum.ui.components.MenuLongPress]로 감싸 메뉴 묶음 시간(0.3초)을 쓴다.
 */
@Composable
fun Modifier.longPressFirst(
    onLongPress: () -> Unit,
    onDrag: (Offset) -> Unit = {},
    onRelease: (Offset) -> Unit = {}
): Modifier {
    val current by rememberUpdatedState(onLongPress)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    // 터치 자리를 창 좌표로 바꾸는 데 쓴다(그리기를 다시 하지 않게 상태가 아닌 칸에 둔다)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coordinates[0] = it }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    val moved = change != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    if (change == null || !change.pressed || moved) break
                }
                true
            }
            if (endedEarly == null) {
                current()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    event.changes.forEach { it.consume() }
                    val window = coordinates[0]?.takeIf { it.isAttached }?.let { node -> change?.let { node.localToWindow(it.position) } }
                    if (change != null && window != null) {
                        if (change.pressed) drag(window) else release(window)
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

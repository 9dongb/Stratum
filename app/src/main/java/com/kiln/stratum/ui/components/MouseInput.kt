package com.kiln.stratum.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/*
 * 마우스(사용자 피드백 12차 4: "키보드 마우스 단축키 먼저"). 터치·펜에는 아무 일도 하지 않는다.
 */

/**
 * 마우스 오른쪽 버튼으로 눌렀다 떼면 [onClick]을 부른다(레이어 줄·홈 카드의 메뉴 열기, 꾹 누르기와 같은 메뉴).
 * 그 누름은 이 자리가 가져가 안쪽의 누르기·꾹 누르기가 받지 않는다. 왼쪽 버튼·터치는 그대로 지나간다.
 */
fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitEachGesture {
            val press = awaitPointerEvent(PointerEventPass.Initial)
            if (press.type != PointerEventType.Press || !press.buttons.isSecondaryPressed) return@awaitEachGesture
            press.changes.forEach { it.consume() }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
            currentOnClick()
        }
    }
}

/**
 * 마우스가 이 자리 위에 버튼을 누르지 않고 있으면 그 자리를, 나가거나 버튼을 누르면 null을 [onHover]로 알린다(붓 원을 미리 보이는 데 쓴다).
 * 터치에는 알리지 않는다. 다른 처리를 막지 않는다(소비하지 않음).
 */
fun Modifier.trackMouseHover(onHover: (Offset?) -> Unit): Modifier = composed {
    val currentOnHover by rememberUpdatedState(onHover)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> currentOnHover(if (change.pressed) null else change.position)
                    PointerEventType.Exit, PointerEventType.Press -> currentOnHover(null)
                    PointerEventType.Release -> currentOnHover(change.position)
                }
            }
        }
    }
}

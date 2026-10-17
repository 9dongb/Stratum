package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * 길게 누르기를 안쪽 버튼보다 먼저 알아챈다.
 *
 * Material 버튼·탭은 안쪽 클릭이 먼저 터치를 가져가서, 바깥에서 길게 누르기를 더해도 오지 않는다(소스로 확인).
 * 그래서 Initial 단계에서 먼저 보고, 길게 누른 것이 확인되면 손을 뗄 때까지 모든 터치를 소비한다.
 * 안쪽 버튼은 소비된 손 뗌을 보고 누르기를 취소하므로, 길게 누른 뒤에는 클릭이 일어나지 않는다.
 * 손을 일찍 떼거나 터치 허용 거리보다 많이 움직이면 아무것도 하지 않고 안쪽 버튼에 맡긴다.
 */
@Composable
fun Modifier.longPressFirst(onLongPress: () -> Unit): Modifier {
    val current by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
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
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

/**
 * [tool]의 하위 도구를 고르는 메뉴. 도구 버튼을 길게 누르면 그 버튼 옆에 뜬다.
 * 지금 쓰는 하위 도구([current])는 선택된 것으로 알린다. 크기 변경은 창이 생길 때까지 누를 수 없다.
 */
@Composable
fun SubToolMenu(
    tool: EditTool,
    expanded: Boolean,
    current: SubTool,
    onDismiss: () -> Unit,
    onChoose: (SubTool) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (subTool in tool.subTools) {
            DropdownMenuItem(
                text = { Text(stringResource(subTool.label)) },
                onClick = { onChoose(subTool) },
                leadingIcon = { Icon(painterResource(subTool.icon), contentDescription = null) },
                // TODO(크기 변경 창 단계): 창이 생기면 켠다
                enabled = !subTool.oneShot,
                modifier = Modifier
                    .testTag(EditScreenTags.subTool(subTool))
                    .semantics { selected = subTool == current }
            )
        }
    }
}

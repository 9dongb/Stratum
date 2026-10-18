package com.kiln.stratum.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.layout.HingeAvoidingTop
import kotlinx.coroutines.delay

/**
 * 제스처 알림(결정 기록 '제스처 알림')의 상태. [show]로 띄우면 마지막으로 띄운 지 [GESTURE_TOAST_MILLIS] 뒤 사라진다.
 * 같은 알림을 잇달아 띄우면 그때마다 사라지는 시간을 다시 잰다.
 */
@Stable
class GestureToastState {
    /** 보이는 글자. 사라지면 null이다. */
    var text: String? by mutableStateOf(null)
        private set

    /** 띄울 때마다 는다(사라지는 시간을 다시 잰다) */
    internal var serial by mutableIntStateOf(0)
        private set

    /** 마지막으로 띄운 글자(사라지는 동안에도 보인다) */
    internal var last: String = ""
        private set

    fun show(text: String) {
        last = text
        this.text = text
        serial++
    }

    internal fun hide() {
        text = null
    }
}

/** 제스처 알림 상태 */
@Composable
fun rememberGestureToastState(): GestureToastState = remember { GestureToastState() }

/**
 * 제스처 알림: 캔버스 위쪽 가운데에 짧게 뜨는 알약 모양 글자다. 반대 색이라 어떤 사진 위에서도 보이고, 화면 읽기 기능이 읽는다.
 * 왼쪽 위 배율 표시를 피해 [GESTURE_TOAST_TOP] 아래에 두고, 세로 힌지가 지나면 넓은 쪽 가운데로 옮긴다.
 * 안드로이드 기본 토스트와 달리 앱 안에 그려, 화면 아래의 옵션·탭 줄을 가리지 않고 잇달아 띄워도 쌓이지 않는다.
 * 캔버스 전체를 덮는 자리에 놓는다(터치는 받지 않는다).
 */
@Composable
fun GestureToast(state: GestureToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.serial) {
        if (state.text != null) {
            delay(GESTURE_TOAST_MILLIS)
            state.hide()
        }
    }
    HingeAvoidingTop(modifier, margin = GESTURE_TOAST_TOP) {
        AnimatedVisibility(visible = state.text != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 3.dp
            ) {
                Text(
                    text = state.last,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(EditScreenTags.GESTURE_TOAST),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/** 제스처 알림이 보이는 시간(밀리초). 마지막으로 띄운 때부터 잰다. */
const val GESTURE_TOAST_MILLIS = 1_000L

/** 제스처 알림의 캔버스 위쪽 여백. 왼쪽 위 배율 표시(누르는 자리 48dp)보다 아래다. */
private val GESTURE_TOAST_TOP = 64.dp

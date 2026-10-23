package com.kiln.stratum.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.delay

/**
 * 제스처 알림(결정 기록 '제스처 알림')의 상태. [show]로 띄우면 마지막으로 띄운 지 [GESTURE_TOAST_MILLIS](도구 안내는 더 길게) 뒤 사라진다.
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

    /** 마지막으로 띄운 알림이 보이는 시간(밀리초) */
    internal var durationMillis: Long = GESTURE_TOAST_MILLIS
        private set

    /** [last]가 끄던 값이다(손을 뗀 뒤 사라지는 동안 그 값을 보이고, 화면 읽기 기능은 읽지 않는다). 새로 띄우면 풀린다. */
    internal var lastWasScrub: Boolean = false
        private set

    /**
     * 수치 칸·칩을 누른 채 끄는 동안 손을 뗄 때까지 보일 글자('크기: 84 px', 2026-10-23 커버 막대). 값이 바뀌면 다시 읽는다.
     * 보이는 동안은 [text]보다 앞서고, 화면 읽기 기능은 읽지 않는다(끄는 값을 하나하나 읽지 않게). 없으면 null.
     */
    var scrubText: (() -> String)? by mutableStateOf(null)
        private set

    /** [text]를 [durationMillis] 동안 띄운다. */
    fun show(text: String, durationMillis: Long = GESTURE_TOAST_MILLIS) {
        last = text
        lastWasScrub = false
        this.durationMillis = durationMillis
        this.text = text
        serial++
    }

    /** 끄기를 시작했다: 손을 뗄 때([endScrub])까지 떠 있던 알림 대신 [text]를 보인다(뗀 뒤 지난 알림이 다시 나오지 않게 지운다) */
    fun startScrub(text: () -> String) {
        scrubText = text
        this.text = null
    }

    /** 손을 뗐다: 끄던 값이 마지막 값 그대로 사라진다. 그사이 새 알림이 떴으면 그 알림이 남는다. */
    fun endScrub() {
        val scrub = scrubText ?: return
        if (text == null) {
            last = scrub()
            lastWasScrub = true
        }
        scrubText = null
    }

    internal fun hide() {
        text = null
    }
}

/** 제스처 알림 상태 */
@Composable
fun rememberGestureToastState(): GestureToastState = remember { GestureToastState() }

/**
 * 수치 칸·칩의 손가락 처리([valueGestures])가 끄는 값과 0으로 되돌림을 알릴 곳: 편집 화면과 유동화 작업 모드가 각자의 제스처 알림을 넣는다
 * (유동화 작업 모드는 편집 화면의 알림 자리를 덮는다). 없으면 알리지 않는다.
 */
val LocalValueFeedback = staticCompositionLocalOf<GestureToastState?> { null }

/**
 * 제스처 알림: 캔버스 위쪽 가운데에 짧게 뜨는 알약 모양 글자다. 반대 색이라 어떤 사진 위에서도 보이고, 화면 읽기 기능이 읽는다.
 * 왼쪽 위 배율 표시를 피해 [GESTURE_TOAST_TOP] 아래, 가로 가운데에 둔다. 접히는 선은 보지 않는다(사용자 피드백 11차 1).
 * 안드로이드 기본 토스트와 달리 앱 안에 그려, 화면 아래의 옵션·탭 줄을 가리지 않고 잇달아 띄워도 쌓이지 않는다.
 * 캔버스 전체를 덮는 자리에 놓는다. 알림 자체도 터치를 받지 않아, 떠 있는 동안에도 그 밑을 누를 수 있다.
 */
@Composable
fun GestureToast(state: GestureToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.serial) {
        if (state.text != null) {
            delay(state.durationMillis)
            state.hide()
        }
    }
    val scrub = state.scrubText
    // 끄는 값은 읽지 않는다(손을 뗄 때까지 계속 바뀌고, 뗀 뒤에는 마지막 값 그대로 사라진다)
    val quiet = scrub != null || state.lastWasScrub
    Box(modifier.padding(top = GESTURE_TOAST_TOP), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = state.text != null || scrub != null, enter = fadeIn(), exit = fadeOut()) {
            // Surface는 아래로 가는 터치를 막으므로 쓰지 않는다. 알림이 떠 있어도 그 밑의 캔버스·옵션 창을 그대로 누를 수 있다.
            Text(
                text = scrub?.invoke() ?: state.last,
                modifier = Modifier
                    // 긴 도구 안내가 화면 폭으로 넓게 퍼지지 않게 한다(여러 줄로 가운데 맞춤)
                    .widthIn(max = GestureToastMaxWidth)
                    .shadow(3.dp, StratumShapes.Card)
                    .background(StratumTheme.colors.inverseBackground, StratumShapes.Card)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .semantics { if (!quiet) liveRegion = LiveRegionMode.Polite }
                    .testTag(EditScreenTags.GESTURE_TOAST),
                color = StratumTheme.colors.inverseText,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** 제스처 알림이 보이는 시간(밀리초). 마지막으로 띄운 때부터 잰다. */
const val GESTURE_TOAST_MILLIS = 1_000L

/**
 * 도구 안내 알림이 보이는 시간(밀리초, 사용자 피드백 10차 5). 글이 길수록 길다: 글자마다 [TOOL_HELP_MILLIS_PER_CHAR],
 * [TOOL_HELP_TOAST_MIN_MILLIS]~[TOOL_HELP_TOAST_MAX_MILLIS] 안(한국어 안내 30~87자는 3~8.7초). 폴드8에서 써 보고 고칠 수 있게 한곳에 둔다.
 */
fun toolHelpToastMillis(text: String): Long = (text.length * TOOL_HELP_MILLIS_PER_CHAR).coerceIn(TOOL_HELP_TOAST_MIN_MILLIS, TOOL_HELP_TOAST_MAX_MILLIS)

const val TOOL_HELP_TOAST_MIN_MILLIS = 3_000L
const val TOOL_HELP_TOAST_MAX_MILLIS = 10_000L
private const val TOOL_HELP_MILLIS_PER_CHAR = 100L

/** 도구 안내가 이만큼 넘게 보이면 본 것으로 적는다([ToolHelpSeen]) */
const val TOOL_HELP_SEEN_MILLIS = 2_000L

/** 알림의 가장 큰 폭(긴 도구 안내는 여러 줄) */
private val GestureToastMaxWidth = 360.dp

/** 제스처 알림의 캔버스 위쪽 여백. 왼쪽 위 배율 표시(누르는 자리 48dp)보다 아래다. */
private val GESTURE_TOAST_TOP = 64.dp

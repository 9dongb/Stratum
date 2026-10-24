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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 세 손가락으로 미는 방향. 밀기를 시작할 때 정해져 손가락을 모두 뗄 때까지 같다. */
enum class SwipeAxis {
    /** 위아래 */
    VERTICAL,

    /** 좌우 */
    HORIZONTAL
}

/** 세 손가락 제스처(설정의 '세 손가락 제스처')를 받는 곳 */
interface ThreeFingerActions {
    /** 세 손가락으로 톡 눌렀다 */
    fun tap()

    /**
     * 세 손가락으로 미는 중이다. [distance]는 방향([axis])을 정한 뒤 손가락 가운데가 그 방향으로 움직인 거리이고,
     * 위쪽·오른쪽이 +다. 방향을 정하는 순간 0으로 한 번 부른다.
     */
    fun swipe(axis: SwipeAxis, distance: Dp)

    /** 밀던 손가락을 모두 뗐다 */
    fun swipeEnd()
}

/**
 * 마우스로 붓 크기·경도를 끌어 바꾸기(사용자 피드백 13차 9: Ctrl(맥 키보드는 Command도)+Alt를 누른 채 왼쪽 버튼으로 끌기).
 * 터치 허용 거리를 넘으면 더 많이 움직인 쪽으로 축을 정하고 끝까지 그 축만 본다(세 손가락 밀기와 같다).
 */
interface BrushDragActions {
    /** 끌기를 시작했다. [at]은 누른 자리(캔버스 영역 안, 화면 반전을 되돌린 화면 자리)다. */
    fun start(at: Offset)

    /**
     * 끄는 중이다. [distance]는 축([axis])을 정한 뒤 그 축으로 움직인 거리이고 오른쪽·위쪽이 +다(화면에서 보이는 방향).
     * 축을 정하는 순간 0으로 한 번 부른다.
     */
    fun drag(axis: SwipeAxis, distance: Dp)

    /** 버튼을 뗐다 */
    fun end()
}

/**
 * 캔버스의 여러 손가락 조작을 알아챈다: 두 손가락 확대·이동, 두 손가락 톡, 세 손가락 톡·밀기.
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
 *
 * [threeFinger]가 있으면 세 손가락 제스처도 알아챈다(설정의 '세 손가락 제스처').
 * - 확대·이동이 시작되기 전에 셋째 손가락이 닿으면 세 손가락 제스처다. 그때부터 확대·이동은 하지 않고,
 *   손가락을 모두 뗄 때까지 모든 터치를 소비한다(도구가 받지 않는다). 확대·이동 중에 닿은 셋째 손가락은 확대·이동을 잇는다.
 * - 톡: 꼭 세 손가락이 닿았다가, 어느 손가락도 터치 허용 거리만큼 움직이지 않고, 처음 닿은 뒤 [THREE_FINGER_TAP_MILLIS] 안에
 *   모두 뗐을 때다. 네 손가락 이상은 톡이 아니다.
 * - 밀기: 손가락 가운데가 터치 허용 거리보다 많이 움직이면, 그때까지 더 많이 움직인 쪽(위아래·좌우)으로 방향을 정하고
 *   끝까지 그 방향만 알린다. 허용 거리까지의 움직임은 넣지 않아 값이 처음에 튀지 않는다. 손가락을 하나둘 떼도 남은 손가락으로 이어진다.
 */
suspend fun PointerInputScope.detectCanvasGestures(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit = {},
    onTwoFingerTap: (() -> Unit)? = null,
    threeFinger: ThreeFingerActions? = null
) {
    awaitEachGesture {
        var transforming = false
        // 세 손가락 제스처인지와 밀기 방향(정하기 전에는 null)
        var threeFingers = false
        var swipeAxis: SwipeAxis? = null
        try {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomSinceStart = 1f
            var panSinceStart = Offset.Zero
            // 세 손가락 제스처가 된 뒤 손가락 가운데가 움직인 거리와, 밀기 방향을 정한 때의 그 값
            var swipeMoved = Offset.Zero
            var swipeOrigin = Offset.Zero
            // 톡을 보기 위한 것: 손가락마다 닿은 자리, 함께 닿은 손가락 수의 최대, 움직였는지
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
                if (threeFinger != null && !transforming && mostFingers >= 3) threeFingers = true
                if (threeFingers) {
                    // 세 손가락: 확대·이동하지 않고 밀기만 본다. 닿거나 뗀 손가락은 그 이벤트의 움직임에 넣지 않는다(calculatePan).
                    swipeMoved += event.calculatePan()
                    val axis = swipeAxis
                    if (axis != null) {
                        val along = swipeMoved - swipeOrigin
                        threeFinger?.swipe(axis, (if (axis == SwipeAxis.VERTICAL) -along.y else along.x).toDp())
                    } else if (swipeMoved.getDistance() > viewConfiguration.touchSlop) {
                        val decided = if (abs(swipeMoved.x) > abs(swipeMoved.y)) SwipeAxis.HORIZONTAL else SwipeAxis.VERTICAL
                        swipeAxis = decided
                        swipeOrigin = swipeMoved
                        threeFinger?.swipe(decided, 0.dp)
                    }
                    event.changes.forEach { it.consume() }
                } else {
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
                }
            } while (event.changes.any { it.pressed })
            val quick = lastTime - first.uptimeMillis
            if (threeFingers) {
                if (mostFingers == 3 && swipeAxis == null && !moved && quick <= THREE_FINGER_TAP_MILLIS) threeFinger?.tap()
            } else if (onTwoFingerTap != null && mostFingers == 2 && !transforming && !moved && quick <= TWO_FINGER_TAP_MILLIS) {
                onTwoFingerTap()
            }
        } finally {
            if (transforming) onEnd()
            if (swipeAxis != null) threeFinger?.swipeEnd()
        }
    }
}

/** 두 손가락 톡으로 보는 가장 긴 시간(밀리초). 처음 손가락이 닿아서 모두 뗄 때까지다. */
const val TWO_FINGER_TAP_MILLIS = 350L

/** 세 손가락 톡으로 보는 가장 긴 시간(밀리초). 세 손가락은 함께 닿기 어려워 두 손가락보다 조금 길다. */
const val THREE_FINGER_TAP_MILLIS = 400L

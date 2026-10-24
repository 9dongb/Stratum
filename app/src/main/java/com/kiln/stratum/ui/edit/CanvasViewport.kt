package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import kotlin.math.abs
import kotlin.math.pow

/**
 * 캔버스 보기 상태를 들고 있는 곳. 두 손가락으로 움직이는 동안 자주 바뀌므로, 그리는 쪽은 그리는 순간에 읽는다.
 */
@Stable
class CanvasViewState(initial: CanvasView) {
    var view: CanvasView by mutableStateOf(initial)

    /** 캔버스 영역의 크기(픽셀). 그리는 쪽이 알린다([CanvasViewport]). 맞춤 배율을 구하는 데 쓰고, 저장하지 않는다. */
    var area: IntSize by mutableStateOf(IntSize.Zero)

    /**
     * 지금 보이는 배율(문서 1픽셀이 화면 몇 픽셀인지). 맞춤이면 캔버스 크기로 구한 맞춤 배율이고, 캔버스 크기를 아직 모르면 null이다.
     * 배율 표시([CanvasZoomChip])와 화면 읽기 기능의 캔버스 배율이 쓴다.
     */
    fun scaleFor(document: PixelSize): Double? = when (val shown = view) {
        is CanvasView.Free -> shown.scale
        CanvasView.Fit -> area.takeIf { it.width > 0 && it.height > 0 }?.let { fitScale(PixelSize(it.width, it.height), document) }
    }

    /**
     * 손으로 배율을 바꾼 횟수(두 손가락 확대·축소, 배율 표시 누르기). 늘 때마다 배율 표시가 캔버스 왼쪽 위에 잠깐 보인다
     * (사용자 피드백 10차 8: "비율 조정 시에만 좌측 상단에 보여지고 사라지게"). 옮기기만 한 것과 화면이 보기를 바꾼 것(자르기 시작 등)은
     * 세지 않는다. 저장하지 않는다(접기·펼치기 뒤에는 보이지 않는다).
     */
    var zoomChanges: Int by mutableIntStateOf(0)
        private set

    /**
     * 배율 표시가 마지막으로 보인 [zoomChanges]. 배율 표시가 화면에서 빠졌다 다시 들어올 때(닫힌 화면의 자르기 창을 열고 닫음) 지난 바뀜을
     * 다시 보이지 않게 한다([CanvasZoomChip]).
     */
    internal var zoomChipShown = 0

    /**
     * 이번 두 손가락 조작에서 마지막으로 센 뒤 손으로 곱한 배율. [ZOOM_CHANGE_STEP]보다 많이 바뀌면 한 번 센다
     * (두 손가락으로 옮길 때 손가락 사이가 조금 달라지는 떨림은 세지 않게). 조작이 끝나면 처음으로 돌린다([gestureEnded]).
     */
    private var zoomDrift = 1.0

    /** 두 손가락 조작이 배율을 [factor]배 했다 */
    fun noteZoom(factor: Double) {
        zoomDrift *= factor
        if (abs(zoomDrift - 1.0) > ZOOM_CHANGE_STEP) {
            zoomDrift = 1.0
            zoomChanges++
        }
    }

    /** 두 손가락 조작이 끝났다. 떨림이 조작마다 쌓여 옮기기만 한 조작에서 배율 표시가 뜨지 않게 한다. */
    fun gestureEnded() {
        zoomDrift = 1.0
    }

    /** 화면에 맞추고, 맞춘 배율을 배율 표시로 잠깐 보인다(배율 표시 누르기, 화면 읽기 기능의 '화면에 맞추기') */
    fun fit() {
        view = CanvasView.Fit
        zoomDrift = 1.0
        zoomChanges++
    }

    /**
     * 캔버스 영역의 ([centerX], [centerY])(기본은 가운데) 아래 문서 위치를 그대로 두고 [factor]배 확대·축소한다(키보드 단축키·마우스 휠,
     * 사용자 피드백 12차 4). 배율 범위는 두 손가락 확대와 같고, 배율 표시가 잠깐 보인다. 캔버스 크기를 아직 모르면 아무것도 하지 않는다.
     */
    fun zoomBy(factor: Double, document: PixelSize, centerX: Double = area.width / 2.0, centerY: Double = area.height / 2.0) {
        if (area.width <= 0 || area.height <= 0 || factor == 1.0) return
        view = view.transformedBy(0.0, 0.0, factor, centerX, centerY, PixelSize(area.width, area.height), document)
        zoomDrift = 1.0
        zoomChanges++
    }

    /** 100%(문서 픽셀 하나가 화면 픽셀 하나)로 본다. 캔버스 가운데의 문서 위치는 그대로다(Ctrl+1). */
    fun actualPixels(document: PixelSize) {
        val scale = scaleFor(document) ?: return
        zoomBy(1.0 / scale, document)
    }

    /** 보기를 화면에서 ([dx], [dy])픽셀 옮긴다(스페이스바를 누른 채 끌기·마우스 가운데 버튼·휠). 배율은 그대로다. */
    fun panBy(dx: Double, dy: Double, document: PixelSize) {
        if (area.width <= 0 || area.height <= 0) return
        view = view.transformedBy(dx, dy, 1.0, area.width / 2.0, area.height / 2.0, PixelSize(area.width, area.height), document)
    }

    companion object {
        /** 접기·펼치기나 백그라운드 종료 뒤에도 남긴다. 맞춤은 빈 배열이다. */
        val Saver: Saver<CanvasViewState, DoubleArray> = Saver(
            save = { it.view.toSaved().toDoubleArray() },
            restore = { CanvasViewState(canvasViewFromSaved(it.toList())) }
        )
    }
}

/** 마우스 휠 한 칸에 확대·축소하는 배율(Ctrl+휠) */
private const val WHEEL_ZOOM_STEP = 1.15

/** 마우스 휠 한 칸에 옮기는 거리 */
private val WheelPanStep = 48.dp

/**
 * 문서 크기가 [documentSize]인 문서의 보기 상태. 처음에는 맞춤이고, 다른 문서가 열리면 맞춤으로 돌아간다.
 * 화면 폭에 따라 배치가 바뀌어도 이어지도록, 배치를 고르기 전(편집 화면 맨 위)에서 부른다.
 */
@Composable
fun rememberCanvasViewState(documentSize: PixelSize?): CanvasViewState =
    rememberSaveable(documentSize, saver = CanvasViewState.Saver) { CanvasViewState(CanvasView.Fit) }

/**
 * 캔버스 영역: 보기 상태대로 문서를 그리고, 두 손가락으로 확대·이동한다.
 * 보기·영역 크기·문서가 바뀔 때마다 [onViewport]로 지금 보이는 문서 영역을 알린다.
 * 엔진 쪽(ViewModel)은 그것으로 보이는 곳을 선명하게 다시 그린다.
 *
 * [overlay]는 문서 위에 겹치는 도구 화면(자르기 테두리 등)이다. 두 손가락 감지를 그 부모에 두어,
 * 도구 화면이 터치를 받아도 두 손가락 확대·이동이 된다. 형제로 겹치면 위의 것만 터치를 받는다(소스로 확인).
 * 문서와 도구 화면은 이 영역 안에서만 보인다(밖은 잘라 그린다). 그래서 확대해도 옆의 도구 막대·아래 빈 줄에 선이 새어 나오지 않는다.
 *
 * [onTwoFingerTap]이 있으면 캔버스를 두 손가락으로 톡 눌렀을 때 부른다(설정의 '두 손가락으로 톡: 실행 취소').
 * 그 터치는 도구 화면이 한 손가락 톡으로 받지 않는다.
 *
 * [threeFinger]가 있으면 세 손가락 톡·밀기를 알린다(설정의 '세 손가락 제스처', [detectCanvasGestures]). 그동안 도구 화면은 터치를 받지 않는다.
 *
 * [brushDrag]가 있으면(크기·경도가 있는 도구) Ctrl(맥 키보드는 Command)+Alt를 누른 채 마우스 왼쪽 버튼으로 끌 때 붓 크기·경도를 바꾸게 알린다
 * (사용자 피드백 13차 9). 그 누름은 도구 화면에 가지 않는다.
 *
 * [flip]은 화면 반전(결정 기록 '화면 반전')이다. 이 영역 전체(문서·도구 화면)를 그림 층으로 뒤집는 변환 하나만 더하고, 픽셀은 그대로다.
 * Compose는 그림 층의 변환을 터치 자리에도 거꾸로 걸어 주므로, 도구 화면과 두 손가락 확대·이동은 뒤집기 전의 자리를 받는다
 * (반전된 화면의 오른쪽을 누르면 문서의 왼쪽). 세 손가락 밀기만 화면의 손가락 방향 그대로 알린다(위로 밀면 크기를 키움).
 * 안쪽에는 [LocalViewFlip]으로 알린다(방향이 있는 표시가 반전을 되돌리는 데 쓴다).
 *
 * [orientation]은 자르기·회전 화면에서 아직 적용하지 않은 돌리기·뒤집기다(사용자 피드백 8차 1). 문서를 그 방향으로 돌린 모습으로 그리고,
 * 보기·두 손가락 조작·도구 화면(자르기 테두리)은 돌린 모습의 문서 크기([Orientation.targetSize]) 기준이다.
 * 엔진 쪽에는 원래 방향으로 되돌린 보이는 영역을 알린다([Viewport.turnedBack]).
 */
@Composable
fun CanvasViewport(
    state: EditorState.Ready,
    viewState: CanvasViewState,
    modifier: Modifier = Modifier,
    onViewport: (Viewport) -> Unit = {},
    onTwoFingerTap: (() -> Unit)? = null,
    threeFinger: ThreeFingerActions? = null,
    brushDrag: BrushDragActions? = null,
    flip: ViewFlip = ViewFlip.None,
    orientation: Orientation = Orientation.IDENTITY,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    // 조작 도중에 문서·콜백이 바뀌어도 터치 처리와 알림을 다시 시작하지 않는다.
    // 보기는 돌려 보이는 모습의 문서 크기 기준이다(돌리지 않았으면 문서 크기 그대로).
    val documentSize by rememberUpdatedState(orientation.targetSize(state.documentSize))
    val shownOrientation by rememberUpdatedState(orientation)
    val originalSize by rememberUpdatedState(state.documentSize)
    val report by rememberUpdatedState(onViewport)
    val twoFingerTap by rememberUpdatedState(onTwoFingerTap)
    val threeFingerActions by rememberUpdatedState(threeFinger)
    val brushDragActions by rememberUpdatedState(brushDrag)
    val currentFlip by rememberUpdatedState(flip)
    // 두 손가락 톡·세 손가락 제스처를 켜고 끌 때만 터치 처리를 다시 시작한다(켜져 있을 때만 그 터치를 소비한다)
    val tapEnabled = onTwoFingerTap != null
    val threeEnabled = threeFinger != null
    // 캔버스 영역의 크기(픽셀). 그리는 쪽이 쓰는 크기와 같다.
    var area by remember { mutableStateOf(IntSize.Zero) }
    // 스페이스바를 누르고 있는지(키보드 단축키, 사용자 피드백 12차 4). 누른 채 끌면 도구 대신 캔버스를 옮긴다.
    val shortcutHost = LocalKeyShortcutHost.current
    val zoomFormat = stringResource(R.string.canvas_zoom_description)
    val fitLabel = stringResource(R.string.canvas_fit)
    LaunchedEffect(viewState) {
        // 두 손가락으로 움직이는 동안에는 매 프레임 알린다. 엔진 쪽이 움직임이 멈춘 뒤에만 그린다.
        snapshotFlow { ViewportInputs(area, viewState.view, documentSize, shownOrientation, originalSize) }.collect { inputs ->
            // 새 보기 상태(다른 크기의 문서)에도 캔버스 크기를 알린다(크기가 그대로면 크기 바뀜 알림이 오지 않는다)
            val size = inputs.area
            viewState.area = size
            if (size.width > 0 && size.height > 0) {
                report(inputs.view.viewport(PixelSize(size.width, size.height), inputs.shown).turnedBack(inputs.orientation, inputs.original))
            }
        }
    }
    Box(
        modifier
            // 화면 반전: 그릴 때 거는 변환 하나(가운데를 기준으로 뒤집음). 터치는 Compose가 거꾸로 바꿔 넘긴다.
            // 이 영역 안에 그리는 것(문서 테두리 선·선택 윤곽선·도구 표시)은 영역 밖으로 나가지 않게 잘라 그린다.
            // 확대해서 문서 끝이 영역 바로 밖에 오면 테두리 선이 옆의 도구 막대 위에 겹쳐 보였다(사용자 피드백).
            .graphicsLayer {
                scaleX = flip.scaleX
                scaleY = flip.scaleY
                clip = true
            }
            .onSizeChanged { area = it }
            // 스페이스바를 누른 채 끌거나 마우스 가운데 버튼으로 끌면 캔버스를 옮긴다(포토샵과 같다, 사용자 피드백 12차 4).
            // 마우스의 다른 버튼(오른쪽 등)은 칠하지 않는다. 도구·두 손가락 조작보다 먼저 받아 그 누름은 도구에 가지 않는다.
            // Ctrl(Command)+Alt를 누른 채 왼쪽 버튼으로 끌면 붓 크기(좌우)·경도(위아래)를 바꾼다(사용자 피드백 13차 9).
            .pointerInput(viewState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val buttons = currentEvent.buttons
                    val keys = currentEvent.keyboardModifiers
                    val mouse = down.type == PointerType.Mouse
                    val pan = shortcutHost.spaceHeld || (mouse && buttons.isTertiaryPressed)
                    val otherButton = mouse && !buttons.isPrimaryPressed
                    val adjust = mouse && !pan && buttons.isPrimaryPressed && (keys.isCtrlPressed || keys.isMetaPressed) && keys.isAltPressed &&
                        brushDragActions != null
                    if (adjust) {
                        down.consume()
                        dragBrush(down, viewConfiguration.touchSlop, { brushDragActions }, { currentFlip })
                        return@awaitEachGesture
                    }
                    if (!pan && !otherButton) return@awaitEachGesture
                    down.consume()
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) break
                        val delta = change.position - last
                        last = change.position
                        if (pan && delta != Offset.Zero) viewState.panBy(delta.x.toDouble(), delta.y.toDouble(), documentSize)
                    }
                }
            }
            // 마우스 휠: 굴리면 위아래로, Shift를 누르면 좌우로 옮기고, Ctrl(맥 키보드는 Command)을 누르면 마우스 자리를 기준으로 확대·축소한다.
            // 화면을 뒤집었으면(화면 반전) 화면에서 보이는 방향 그대로 옮긴다.
            .pointerInput(viewState) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta
                        val modifiers = event.keyboardModifiers
                        if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                            viewState.zoomBy(WHEEL_ZOOM_STEP.pow(-delta.y.toDouble()), documentSize, change.position.x.toDouble(), change.position.y.toDouble())
                        } else {
                            val step = WheelPanStep.toPx().toDouble()
                            var dx = if (modifiers.isShiftPressed) -delta.y * step else -delta.x * step
                            var dy = if (modifiers.isShiftPressed) 0.0 else -delta.y * step
                            if (currentFlip.horizontal) dx = -dx
                            if (currentFlip.vertical) dy = -dy
                            viewState.panBy(dx, dy, documentSize)
                        }
                        change.consume()
                    }
                }
            }
            .pointerInput(viewState, tapEnabled, threeEnabled) {
                detectCanvasGestures(
                    onTransform = { centroid, pan, zoom ->
                        if (size.width > 0 && size.height > 0) {
                            viewState.view = viewState.view.transformedBy(
                                panX = pan.x.toDouble(),
                                panY = pan.y.toDouble(),
                                zoom = zoom.toDouble(),
                                centroidX = centroid.x.toDouble(),
                                centroidY = centroid.y.toDouble(),
                                area = PixelSize(size.width, size.height),
                                document = documentSize
                            )
                            // 배율을 바꾸면 배율 표시가 잠깐 보인다(옮기기만 하면 보이지 않는다)
                            if (zoom != 1f) viewState.noteZoom(zoom.toDouble())
                        }
                    },
                    onEnd = viewState::gestureEnded,
                    onTwoFingerTap = if (tapEnabled) ({ twoFingerTap?.invoke() }) else null,
                    // 화면이 다시 그려지며 넘긴 것이 바뀌어도 지금 것에 보낸다
                    threeFinger = if (threeEnabled) {
                        object : ThreeFingerActions {
                            override fun tap() {
                                threeFingerActions?.tap()
                            }

                            override fun swipe(axis: SwipeAxis, distance: Dp) {
                                // 뒤집힌 축은 거리 부호를 되돌려 화면의 손가락 방향 그대로 알린다
                                val mirrored = when (axis) {
                                    SwipeAxis.HORIZONTAL -> currentFlip.horizontal
                                    SwipeAxis.VERTICAL -> currentFlip.vertical
                                }
                                threeFingerActions?.swipe(axis, if (mirrored) -distance else distance)
                            }

                            override fun swipeEnd() {
                                threeFingerActions?.swipeEnd()
                            }
                        }
                    } else {
                        null
                    }
                )
            }
    ) {
        CompositionLocalProvider(LocalViewFlip provides flip) {
            // 화면 읽기 기능은 캔버스 그림에 머문다. 그래서 지금 배율(예: '배율 250%')과, 확대·이동한 상태면 화면에 맞추는 동작을 여기에 둔다
            // (배율 표시는 배율을 바꿀 때만 잠깐 보인다, 사용자 피드백 10차 8)
            CanvasImage(
                state = state,
                view = { viewState.view },
                modifier = Modifier.fillMaxSize().semantics {
                    viewState.scaleFor(documentSize)?.let { stateDescription = zoomFormat.format(zoomPercentText(it)) }
                    if (viewState.view is CanvasView.Free) {
                        customActions = listOf(
                            CustomAccessibilityAction(fitLabel) {
                                viewState.fit()
                                true
                            }
                        )
                    }
                },
                orientation = orientation
            )
            // 선택 영역 윤곽선은 어떤 도구를 골라도 보인다(도구 화면 아래)
            SelectionAnts(
                state.selection,
                state.selectionDetail,
                view = { viewState.view },
                document = state.documentSize,
                modifier = Modifier.fillMaxSize(),
                orientation = orientation
            )
            overlay()
        }
    }
}

/**
 * 한 번의 두 손가락 조작에서 손으로 바꾼 배율이 이만큼(3%) 넘게 쌓이면 배율 표시를 보인다([CanvasViewState.noteZoom]).
 * 두 손가락으로 옮길 때 손가락 사이가 조금 달라지는 떨림은 넘지 않게 정했다(Claude 판단, 폴드8에서 써 보고 고칠 수 있게 한곳에 둔다).
 */
private const val ZOOM_CHANGE_STEP = 0.03

/** 엔진 쪽에 알릴 보기를 정하는 것들: 캔버스 크기, 보기, 보이는(돌린) 문서 크기, 돌린 방향, 원래 문서 크기 */
private data class ViewportInputs(
    val area: IntSize,
    val view: CanvasView,
    val shown: PixelSize,
    val orientation: Orientation,
    val original: PixelSize
)

/**
 * Ctrl(Command)+Alt + 왼쪽 버튼 끌기(사용자 피드백 13차 9)를 [actions]에 알린다. 버튼을 뗄 때까지 모든 움직임을 받는다.
 * 그림 층 안의 자리라 화면 반전([flip])이 걸린 축은 부호를 되돌려 화면에서 보이는 방향으로 알린다. 터치 허용 거리([slop])를 넘으면
 * 더 많이 움직인 쪽으로 축을 정하고(세 손가락 밀기와 같다), 정한 순간의 자리부터 잰다(값이 처음에 튀지 않게).
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragBrush(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    slop: Float,
    actions: () -> BrushDragActions?,
    flip: () -> ViewFlip
) {
    fun screen(local: Offset): Offset {
        val f = flip()
        return Offset(if (f.horizontal) size.width - local.x else local.x, if (f.vertical) size.height - local.y else local.y)
    }
    val start = screen(down.position)
    actions()?.start(start)
    var axis: SwipeAxis? = null
    var origin = start
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        event.changes.forEach { it.consume() }
        if (!change.pressed) break
        val now = screen(change.position)
        val moved = now - start
        val chosen = axis ?: if (moved.getDistance() > slop) {
            (if (abs(moved.x) >= abs(moved.y)) SwipeAxis.HORIZONTAL else SwipeAxis.VERTICAL).also {
                axis = it
                origin = now
            }
        } else {
            continue
        }
        val pixels = if (chosen == SwipeAxis.HORIZONTAL) now.x - origin.x else origin.y - now.y
        actions()?.drag(chosen, (pixels / density).dp)
    }
    actions()?.end()
}

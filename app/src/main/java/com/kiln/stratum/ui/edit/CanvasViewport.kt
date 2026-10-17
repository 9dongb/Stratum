package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.kiln.stratum.engine.raster.PixelSize

/**
 * 캔버스 보기 상태를 들고 있는 곳. 두 손가락으로 움직이는 동안 자주 바뀌므로, 그리는 쪽은 그리는 순간에 읽는다.
 */
@Stable
class CanvasViewState(initial: CanvasView) {
    var view: CanvasView by mutableStateOf(initial)

    companion object {
        /** 접기·펼치기나 백그라운드 종료 뒤에도 남긴다. 맞춤은 빈 배열이다. */
        val Saver: Saver<CanvasViewState, DoubleArray> = Saver(
            save = { it.view.toSaved().toDoubleArray() },
            restore = { CanvasViewState(canvasViewFromSaved(it.toList())) }
        )
    }
}

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
 *
 * [onTwoFingerTap]이 있으면 캔버스를 두 손가락으로 톡 눌렀을 때 부른다(설정의 '두 손가락으로 톡: 실행 취소').
 * 그 터치는 도구 화면이 한 손가락 톡으로 받지 않는다.
 */
@Composable
fun CanvasViewport(
    state: EditorState.Ready,
    viewState: CanvasViewState,
    modifier: Modifier = Modifier,
    onViewport: (Viewport) -> Unit = {},
    onTwoFingerTap: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    // 조작 도중에 문서·콜백이 바뀌어도 터치 처리와 알림을 다시 시작하지 않는다
    val documentSize by rememberUpdatedState(state.documentSize)
    val report by rememberUpdatedState(onViewport)
    val twoFingerTap by rememberUpdatedState(onTwoFingerTap)
    // 두 손가락 톡을 켜고 끌 때만 터치 처리를 다시 시작한다(켜져 있을 때만 그 터치를 소비한다)
    val tapEnabled = onTwoFingerTap != null
    // 캔버스 영역의 크기(픽셀). 그리는 쪽이 쓰는 크기와 같다.
    var area by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewState) {
        // 두 손가락으로 움직이는 동안에는 매 프레임 알린다. 엔진 쪽이 움직임이 멈춘 뒤에만 그린다.
        snapshotFlow { Triple(area, viewState.view, documentSize) }.collect { (size, view, document) ->
            if (size.width > 0 && size.height > 0) report(view.viewport(PixelSize(size.width, size.height), document))
        }
    }
    Box(
        modifier.onSizeChanged { area = it }.pointerInput(viewState, tapEnabled) {
            detectTwoFingerTransform(
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
                    }
                },
                onTwoFingerTap = if (tapEnabled) ({ twoFingerTap?.invoke() }) else null
            )
        }
    ) {
        CanvasImage(state = state, view = { viewState.view }, modifier = Modifier.fillMaxSize())
        // 선택 영역 윤곽선은 어떤 도구를 골라도 보인다(도구 화면 아래)
        SelectionAnts(state.selection, state.selectionDetail, view = { viewState.view }, document = state.documentSize, modifier = Modifier.fillMaxSize())
        overlay()
    }
}

package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kiln.stratum.engine.raster.PixelSize

/**
 * 이동 도구가 보내는 일(문서 좌표, 사용자 피드백 7차, 결정 기록 '이동 도구').
 * 한 손가락으로 끌면 고른 레이어가 손가락을 따라 옮겨 보이고, 손을 떼면 옮긴 만큼 실행 취소 한 단계로 기록한다.
 */
interface MoveActions {

    /** 한 손가락이 문서 좌표 ([x], [y])에 닿았다(옮기기 시작) */
    fun begin(x: Float, y: Float)

    /** 끄는 손가락이 ([x], [y])까지 왔다 */
    fun move(x: Float, y: Float)

    /** 손가락을 뗐다. 옮긴 만큼 기록한다. */
    fun end()

    /** 두 손가락 조작이 시작돼 끄던 옮기기를 버린다(기록하지 않음) */
    fun cancel()

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : MoveActions {
        override fun begin(x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 이동 도구의 캔버스 손가락. 한 손가락 끌기를 문서 좌표로 [actions]에 보낸다(브러시와 같은 터치 처리: 두 손가락이면 버림).
 * 화면 반전 중에도 Compose가 터치 자리를 거꾸로 바꿔 주어, 레이어는 화면에서 손가락을 따라간다.
 */
@Composable
fun MoveOverlay(view: () -> CanvasView, document: PixelSize, actions: MoveActions, modifier: Modifier = Modifier) {
    PaintGestureOverlay(
        view = view,
        document = document,
        tag = EditScreenTags.MOVE_OVERLAY,
        onBegin = actions::begin,
        onExtend = actions::move,
        onEnd = actions::end,
        onCancel = actions::cancel,
        modifier = modifier
    )
}


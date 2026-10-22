package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation

/**
 * 이동 도구가 보내는 일(문서 좌표, 사용자 피드백 7차, 결정 기록 '이동 도구').
 * 한 손가락으로 끌면 고른 레이어가 손가락을 따라 옮겨 보이고, 손을 떼면 옮긴 만큼 실행 취소 한 단계로 기록한다.
 * 띠·막대의 버튼으로 고른 레이어만 돌리거나 뒤집는다([orient]).
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

    /**
     * 고른 레이어만 [orientation] 방향으로 돌리거나 뒤집는다(띠·막대의 버튼, 사용자 피드백 12차 2, 결정 기록 '레이어 돌리기·뒤집기').
     * 축은 레이어 내용의 가운데이고 실행 취소 한 단계다.
     */
    fun orient(orientation: Orientation)

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : MoveActions {
        override fun begin(x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
        override fun orient(orientation: Orientation) = Unit
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

/**
 * 이동 도구의 돌리기·뒤집기 버튼 하나(사용자 피드백 12차 2, 결정 기록 '레이어 돌리기·뒤집기'): 칸·칩 이름표 열쇠([key]), 아이콘과 돌려 그릴 각도,
 * 이름(자르기·회전 창과 같음), 화면 읽기 이름('레이어를 …'), 방향. 펼친 화면 띠와 커버 막대가 같이 쓴다.
 */
internal class LayerTurn(
    val key: String,
    @DrawableRes val icon: Int,
    val iconRotation: Float,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val orientation: Orientation
)

/** 왼쪽 90°·오른쪽 90°·좌우 뒤집기·상하 뒤집기(자르기·회전 창과 같은 순서·아이콘) */
internal val LayerTurns = listOf(
    LayerTurn("layerRotateLeft", R.drawable.ic_rotate_90_degrees_ccw, 0f, R.string.rotate_left, R.string.layer_rotate_left, Orientation.ROTATE_COUNTERCLOCKWISE),
    LayerTurn("layerRotateRight", R.drawable.ic_rotate_90_degrees_cw, 0f, R.string.rotate_right, R.string.layer_rotate_right, Orientation.ROTATE_CLOCKWISE),
    LayerTurn("layerFlipHorizontal", R.drawable.ic_flip, 0f, R.string.flip_horizontal, R.string.layer_flip_horizontal, Orientation.FLIP_HORIZONTAL),
    LayerTurn("layerFlipVertical", R.drawable.ic_flip, 90f, R.string.flip_vertical, R.string.layer_flip_vertical, Orientation.FLIP_VERTICAL)
)

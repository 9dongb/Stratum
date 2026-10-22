package com.kiln.stratum.ui.edit

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceSlider
import com.kiln.stratum.engine.face.FaceValues
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.settings.StripPosition
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.components.TopBarAccentButton
import com.kiln.stratum.ui.components.TopBarIconButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.components.stratumTopAppBarColors
import com.kiln.stratum.ui.components.trackMouseHover
import com.kiln.stratum.ui.layout.BarButtonContentInset
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.isCoverLandscape
import com.kiln.stratum.ui.layout.layoutClassOf
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.topBarItem
import com.kiln.stratum.ui.layout.windowInsets
import com.kiln.stratum.ui.layout.workTopBarHeight
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 픽셀 유동화 작업 모드의 화면 상태(열려 있을 때만). [preview]는 고른 레이어만 비튼 축소본이다.
 *
 * @property size 브러시 지름(문서 픽셀)
 * @property strength 압력(0..1)
 * @property canUndo 작업 모드 안에서 한 획 되돌릴 수 있는지
 * @property canRedo 되돌린 획을 다시 걸 수 있는지
 * @property changed 움직인 곳이 있는지(적용·모두 되돌리기를 켠다)
 * @property busy 적용하는 중이다
 * @property colorSpace 문서 색 공간(미리 보기를 그 꼬리표로 보인다)
 * @property faceMode 얼굴 모드(얼굴 인식 보정)를 골랐는지. 고르면 칠하지 않고 얼굴을 톡해서 고른다.
 * @property faces 찾은 얼굴(화면에 보이는 자리의 문서 좌표, 왼쪽부터). 얼굴 모드가 아니거나 아직 찾지 않았으면 null
 * @property selectedFace 고른 얼굴의 번호([faces] 안). 없으면 -1
 * @property findingFaces 얼굴을 찾는 중이다
 * @property faceNotice 화면에 한 번 알릴 것(얼굴을 못 찾음 등)
 * @property faceValues 고른 얼굴의 보정 값(얼굴 슬라이더). 고른 얼굴이 없으면 null
 */
data class LiquifyState(
    val preview: PreviewImage,
    val documentSize: PixelSize,
    val tool: LiquifyTool,
    val size: Float,
    val strength: Float,
    val canUndo: Boolean,
    val changed: Boolean,
    val busy: Boolean = false,
    val canRedo: Boolean = false,
    val colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB,
    val faceMode: Boolean = false,
    val faces: List<FaceLandmarks>? = null,
    val selectedFace: Int = -1,
    val findingFaces: Boolean = false,
    val faceNotice: FaceNotice? = null,
    val faceValues: FaceValues? = null
)

/** 얼굴 모드가 한 번 알릴 것([kind], 여럿 찾았으면 그 수 [count]). 같은 알림도 다시 알리게 [serial]이 늘 바뀐다. */
data class FaceNotice(val kind: Kind, val count: Int = 0, val serial: Int = 0) {
    enum class Kind {
        /** 얼굴을 찾지 못했다 */
        NOT_FOUND,

        /** 얼굴을 여럿 찾았다(톡해서 고른다) */
        SEVERAL,

        /** 이 기기에서 얼굴 모델을 돌릴 수 없다 */
        UNAVAILABLE,

        /** 메모리가 모자라 찾지 못했다 */
        NOT_ENOUGH_MEMORY
    }
}

/** 픽셀 유동화 작업 모드가 보내는 일 */
interface LiquifyActions {
    /** 한 손가락이 문서 좌표 ([x], [y])에 닿았다(한 획 시작) */
    fun begin(x: Float, y: Float)

    /** 끄는 손가락이 ([x], [y])까지 왔다 */
    fun move(x: Float, y: Float)

    /** 손가락을 뗐다 */
    fun end()

    /** 두 번째 손가락이 닿아 끄던 획을 버린다(브러시와 같다) */
    fun cancelStroke()

    fun setTool(tool: LiquifyTool)

    /** 브러시 지름(문서 픽셀) */
    fun setSize(size: Float)

    /** 압력(0..1) */
    fun setStrength(strength: Float)

    /** 마지막 한 획을 되돌린다(작업 모드 안에서) */
    fun undo()

    /** 되돌린 한 획을 다시 건다 */
    fun redo()

    /** 모두 원래대로 */
    fun reset()

    /** 적용(실행 취소 한 단계로 기록)하고 닫는다 */
    fun apply()

    /** 바꾼 것을 버리고 닫는다 */
    fun cancel()

    /** 얼굴 모드(얼굴 인식 보정)를 켠다. 처음 켜면 얼굴을 찾는다. 브러시 도구를 고르면([setTool]) 꺼진다. */
    fun setFaceMode() {}

    /** 얼굴 모드에서 문서 좌표 ([x], [y])를 톡했다: 그 자리 얼굴을 고른다 */
    fun pickFace(x: Float, y: Float) {}

    /** 고른 얼굴의 슬라이더 [slider]를 [value](−1..1)로 바꾼다(끄는 대로 미리 보기). 손을 떼면 [finishFaceValue]. */
    fun setFaceValue(slider: FaceSlider, value: Float) {}

    /** 얼굴 슬라이더에서 손을 뗐다(그 끌기가 되돌리기 한 단계) */
    fun finishFaceValue() {}

    /** 고른 얼굴의 슬라이더 [slider]를 0으로(원 두 번 톡, 되돌리기 한 단계) */
    fun resetFaceValue(slider: FaceSlider) {}

    /** 고른 얼굴의 값을 모두 0으로('얼굴 초기화', 되돌리기 한 단계) */
    fun resetFace() {}

    object None : LiquifyActions {
        override fun begin(x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun setTool(tool: LiquifyTool) = Unit
        override fun setSize(size: Float) = Unit
        override fun setStrength(strength: Float) = Unit
        override fun undo() = Unit
        override fun redo() = Unit
        override fun cancelStroke() = Unit
        override fun reset() = Unit
        override fun apply() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 픽셀 유동화 작업 모드. 편집 화면 위를 덮는 전체 화면이다(CLAUDE.md 6장). 화면 이동이 아니라 덮개라, 닫으면 편집 화면이 그대로 있다.
 *
 * - 위: 취소(버림), 이름, 한 단계 되돌리기(획·얼굴 슬라이더), 한 단계 다시 실행, 모두 되돌리기, 적용(실행 취소 한 단계로 기록).
 * - 가운데: 고른 레이어를 비튼 미리 보기. 한 손가락으로 끌어 비틀고, 두 손가락으로 확대·이동한다. 브러시 크기만큼 동그라미를 보인다.
 *   [twoFingerTapUndo](설정)면 두 손가락으로 톡 눌러 한 획 되돌린다(편집 화면과 같다, 결정 기록 '제스처 알림').
 *   [threeFingerGestures](설정)면 세 손가락으로 톡 눌러 한 획 다시 실행하고, 위아래로 밀어 크기, 좌우로 밀어 압력을 바꾼다
 *   (유동화에는 단단함이 없어 압력으로 대신한다, 결정 기록 '세 손가락 제스처').
 * - 옵션: 크기, 압력, 도구(뒤틀기·오목·볼록·재구성)(사용자 피드백 8차 1-5 정정: "도구 표시를 왼쪽 상단에 기존 도구들처럼").
 *   펼친 화면은 편집 화면과 같은 왼쪽 위 띠(손잡이로 옮기고 같은 자리 [stripPosition]을 기억)이고 캔버스가 창 전체를 쓴다.
 *   닫힌 화면은 크기·압력을 편집 화면과 같은 한 줄 막대에, 도구 탭을 맨 아래 X와 ✓ 사이에 둔다(사용자 피드백 8차 1-1, 커버 화면 개편).
 * - 시스템 뒤로는 띠의 옵션 창이 떠 있으면 그것부터 닫고, 아니면 취소와 같다.
 * - [flip]: 편집 화면의 화면 반전(보기만 뒤집음)을 캔버스에 그대로 건다.
 */
@Composable
fun LiquifyMode(
    state: LiquifyState,
    actions: LiquifyActions,
    modifier: Modifier = Modifier,
    twoFingerTapUndo: Boolean = false,
    threeFingerGestures: Boolean = false,
    flip: ViewFlip = ViewFlip.None,
    stripPosition: StripPosition? = null,
    onStripPosition: (StripPosition) -> Unit = {}
) {
    BackHandler(enabled = !state.busy, onBack = actions::cancel)
    // 띠의 옵션 창(세로 슬라이더)은 작업 모드 안에서만 쓴다. 떠 있으면 뒤로 가기는 그것부터 닫는다(취소보다 나중에 등록해 먼저 받는다).
    val floating = rememberFloatingState()
    BackHandler(enabled = floating.flyout != null, onBack = floating::closeFlyout)
    // 닫힌 화면의 한 줄 막대(크기·압력)에서 펼친 칩. 펼쳐져 있으면 뒤로 가기는 칩 줄로 돌아간다(닫힌 화면에서만, 취소보다 먼저).
    val coverBar = rememberCoverBarState()
    val viewState = rememberCanvasViewState(state.documentSize)
    // 작업 모드와 캔버스의 위쪽 끝(창 루트 기준 픽셀). 제스처 알림을 화면 정중앙, 캔버스 위쪽 아래에 띄운다(편집 화면과 같다).
    var screenTop by remember { mutableFloatStateOf(0f) }
    var canvasTop by remember { mutableFloatStateOf(0f) }
    // 크기 슬라이더를 끄거나 세 손가락으로 미는 동안 캔버스 가운데에 브러시 크기를 보인다
    val sizePreview = rememberSizePreviewState()
    // 두 손가락 톡: 처리 중이면 아무것도 하지 않고, 한 획 되돌리거나 되돌릴 것이 없다고 알린다
    val gestureToast = rememberGestureToastState()
    val undoneText = stringResource(R.string.action_undo)
    val nothingToUndoText = stringResource(R.string.gesture_nothing_to_undo)
    val twoFingerUndo: (() -> Unit)? = if (twoFingerTapUndo) {
        {
            when {
                state.busy -> Unit
                state.canUndo -> {
                    actions.undo()
                    gestureToast.show(undoneText)
                }
                else -> gestureToast.show(nothingToUndoText)
            }
        }
    } else {
        null
    }
    // 세 손가락: 톡은 한 획 다시 실행, 위아래로 밀면 크기, 좌우로 밀면 압력. 미는 동안은 알림 없이 크기 원만 보인다(편집 화면과 같다).
    val redoneText = stringResource(R.string.action_redo)
    val nothingToRedoText = stringResource(R.string.gesture_nothing_to_redo)
    val swipeAnchor = remember { SwipeAnchor<LiquifyState>() }
    val threeFinger: ThreeFingerActions? = if (threeFingerGestures) {
        object : ThreeFingerActions {
            override fun tap() {
                when {
                    state.busy -> Unit
                    state.canRedo -> {
                        actions.redo()
                        gestureToast.show(redoneText)
                    }
                    else -> gestureToast.show(nothingToRedoText)
                }
            }

            override fun swipe(axis: SwipeAxis, distance: Dp) {
                val start = swipeAnchor.from(state)
                when (axis) {
                    SwipeAxis.VERTICAL -> actions.setSize(swipedSize(start.size, distance.value))
                    SwipeAxis.HORIZONTAL -> actions.setStrength(swipedStrength(start.strength, distance.value))
                }
                sizePreview.onChange()
            }

            override fun swipeEnd() {
                swipeAnchor.end()
                sizePreview.onFinished()
            }
        }
    } else {
        null
    }
    // 얼굴 모드가 알릴 것(못 찾음·여럿 찾음 등)은 제스처 알림 자리에 한 번 띄운다. 접기·펼치기로 다시 만들어져도 다시 띄우지 않는다.
    val notice = state.faceNotice
    val noticeText = notice?.let { faceNoticeText(it) }
    var shownNotice by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(notice?.serial) {
        if (notice != null && noticeText != null && notice.serial > shownNotice) {
            shownNotice = notice.serial
            gestureToast.show(noticeText, toolHelpToastMillis(noticeText))
        }
    }
    // 바탕은 편집 화면처럼 작업 공간 색이다(캔버스 주변). 위 막대와 옵션은 제 색을 칠한다.
    Surface(modifier.fillMaxSize().testTag(EditScreenTags.LIQUIFY_MODE), color = StratumTheme.colors.workspace) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { screenTop = it.positionInRoot().y }) {
            // 편집 화면과 같이 짧은 쪽으로 커버 배치인지 고른다(사용자 피드백 12차 3)
            val widthClass = layoutClassOf(maxWidth.value, maxHeight.value)
            // 닫힌 화면(커버)은 새 창 공통 규칙대로 취소·적용을 맨 아래 X·✓ 줄에 둔다(결정 기록 '닫힌 화면 새 창')
            val coverWindow = widthClass == WidthClass.Compact
            // 커버를 가로로 돌렸으면 그 줄들이 캔버스 오른쪽에 세로로 선다(사용자 피드백 12차 3: "커버화면 배치가 돌아간형태")
            val coverLandscape = isCoverLandscape(maxWidth.value, maxHeight.value)
            // 배치가 바뀌거나(접기·펼치기) 적용하기 시작하면 띠의 옵션 창을 닫는다
            LaunchedEffect(widthClass, state.busy) { floating.closeFlyout() }
            Column(Modifier.fillMaxSize()) {
                LiquifyTopBar(state, actions, cancelAndApplyOnTop = !coverWindow)
                if (coverWindow && coverLandscape) {
                    // 돌아간 배치: [캔버스 | 크기·압력 기둥 | ✓ · 도구 · X 기둥]. 캔버스는 왼쪽 카메라 구멍과 아래 시스템 바를 피한다(편집 화면과 같다).
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LiquifyCanvas(
                            state, viewState, actions, sizePreview.visible, { canvasTop = it }, twoFingerUndo, threeFinger, flip,
                            Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))
                        )
                        BackHandler(enabled = coverBar.expanded != null, onBack = coverBar::collapse)
                        CoverToolColumn(items = liquifyBar(state, actions, sizePreview), state = coverBar, empty = faceHint(state).takeIf { state.faceMode })
                        CoverWorkBar(
                            onCancel = actions::cancel,
                            onDone = actions::apply,
                            doneEnabled = state.changed && !state.busy,
                            cancelEnabled = !state.busy,
                            cancelTag = EditScreenTags.LIQUIFY_CANCEL,
                            doneTag = EditScreenTags.LIQUIFY_APPLY,
                            vertical = true,
                            middle = { LiquifyToolColumn(state, enabled = !state.busy, actions = actions) }
                        )
                    }
                } else if (coverWindow) {
                    LiquifyCanvas(state, viewState, actions, sizePreview.visible, { canvasTop = it }, twoFingerUndo, threeFinger, flip, Modifier.weight(1f).fillMaxWidth())
                    // 크기·압력은 편집 화면과 같은 한 줄 막대다(칩을 누르면 같은 줄이 슬라이더, 결정 기록 '커버 화면 개편').
                    // 아래 시스템 바는 맨 아래 X·✓ 줄이 피한다.
                    BackHandler(enabled = coverBar.expanded != null, onBack = coverBar::collapse)
                    // 얼굴 모드는 막대에 칩이 없고 안내 글(찾는 중·고르기·고른 얼굴)을 보인다
                    CoverToolBar(items = liquifyBar(state, actions, sizePreview), state = coverBar, empty = faceHint(state).takeIf { state.faceMode })
                    // 맨 아래 줄: X(취소) · 도구 탭 · ✓(적용)(사용자 피드백 8차 1-1: 고르는 탭은 X와 ✓ 사이)
                    CoverWorkBar(
                        onCancel = actions::cancel,
                        onDone = actions::apply,
                        doneEnabled = state.changed && !state.busy,
                        cancelEnabled = !state.busy,
                        cancelTag = EditScreenTags.LIQUIFY_CANCEL,
                        doneTag = EditScreenTags.LIQUIFY_APPLY,
                        middle = { LiquifyToolTabs(state, enabled = !state.busy, actions = actions) }
                    )
                } else {
                    // 펼친 화면: 캔버스가 창 전체를 쓰고, 옵션은 편집 화면과 같은 왼쪽 위 띠(손잡이로 옮김)다.
                    // 아래 시스템 바는 편집 화면의 캔버스처럼 피한다(맨 아래에서 시작한 획이 홈 제스처와 섞이지 않게)
                    Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                        LiquifyCanvas(
                            state, viewState, actions, sizePreview.visible, { canvasTop = it }, twoFingerUndo, threeFinger, flip, Modifier.fillMaxSize(),
                            floating = floating
                        )
                        // 띠와 옵션 창은 캔버스와 같은 자리(옆쪽 카메라 구멍도 피함)에 뜬다
                        val items = liquifyStrip(state, actions, sizePreview)
                        val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        OptionStripHost(items, floating, stripPosition, onStripPosition, Modifier.fillMaxSize().windowInsetsPadding(insets))
                        FlyoutHost(floating, items, Modifier.fillMaxSize().windowInsetsPadding(insets))
                    }
                }
            }
            // 제스처 알림은 옵션 칸과 상관없이 화면 가로 정중앙, 캔버스 위쪽 아래에 띄운다(편집 화면과 같다, 2026-10-20 사용자 요청)
            GestureToast(gestureToast, Modifier.fillMaxSize().padding(top = with(LocalDensity.current) { (canvasTop - screenTop).coerceAtLeast(0f).toDp() }))
        }
    }
}

/**
 * 위쪽 막대: 취소, 이름, 한 단계 되돌리기, 한 단계 다시 실행, 모두 되돌리기, 적용. 편집 화면 상단 바와 같은 높이(상태 표시줄 높이)다.
 * 모두 되돌리기는 자리를 아끼려고 아이콘 버튼이다(커버 폭에서도 이름이 잘리지 않게).
 * [cancelAndApplyOnTop]이 거짓이면(닫힌 화면) 취소·적용은 맨 아래 X·✓ 줄([CoverWorkBar])에 있어 여기에는 두지 않는다.
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiquifyTopBar(state: LiquifyState, actions: LiquifyActions, cancelAndApplyOnTop: Boolean = true) {
    val barHeight = workTopBarHeight()
    val barInsets = rememberTopBarInsets(barHeight)
    TopAppBar(
        title = { Text(stringResource(R.string.tool_liquify), Modifier.topBarItem(barInsets, "title")) },
        modifier = Modifier.edgeDivider(DividerEdge.Bottom, StratumTheme.colors.divider).testTag(EditScreenTags.LIQUIFY_TOP_BAR),
        navigationIcon = {
            if (cancelAndApplyOnTop) {
                TopBarIconButton(
                    onClick = actions::cancel,
                    height = barHeight,
                    enabled = !state.busy,
                    modifier = Modifier.topBarItem(barInsets, "cancel", BarButtonContentInset).testTag(EditScreenTags.LIQUIFY_CANCEL)
                ) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                }
            }
        },
        actions = {
            // 버튼 줄은 보이는 부분만 알린다(편집 화면 상단 바와 같다)
            Row(Modifier.topBarItem(barInsets, "actions", BarButtonContentInset), verticalAlignment = Alignment.CenterVertically) {
                TopBarIconButton(onClick = actions::undo, height = barHeight, enabled = state.canUndo && !state.busy, modifier = Modifier.testTag(EditScreenTags.LIQUIFY_UNDO)) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.liquify_undo))
                }
                TopBarIconButton(onClick = actions::redo, height = barHeight, enabled = state.canRedo && !state.busy, modifier = Modifier.testTag(EditScreenTags.LIQUIFY_REDO)) {
                    Icon(painterResource(R.drawable.ic_redo), contentDescription = stringResource(R.string.liquify_redo))
                }
                TopBarIconButton(onClick = actions::reset, height = barHeight, enabled = state.changed && !state.busy, modifier = Modifier.testTag(EditScreenTags.LIQUIFY_RESET)) {
                    Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = stringResource(R.string.liquify_reset))
                }
                // 적용은 편집 화면의 내보내기처럼 파란 글자 버튼이다(사용자 결정). 닫힌 화면은 맨 아래 ✓다.
                if (cancelAndApplyOnTop) {
                    TopBarAccentButton(
                        onClick = actions::apply,
                        height = barHeight,
                        enabled = state.changed && !state.busy,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp).testTag(EditScreenTags.LIQUIFY_APPLY)
                    ) {
                        Text(stringResource(R.string.action_apply))
                    }
                }
            }
        },
        expandedHeight = barHeight,
        windowInsets = barInsets.windowInsets(),
        colors = stratumTopAppBarColors()
    )
}

/**
 * 비튼 미리 보기와 브러시 손가락 자리. 적용하는 동안 위쪽에 처리 중 막대를 보인다.
 * [showSize]면(크기 슬라이더를 끄거나 세 손가락으로 미는 중) 가운데에 브러시 크기 원을 보인다. [onCanvasTop]으로 캔버스의 위쪽 끝을 알린다
 * (제스처 알림은 작업 모드 전체 자리에서 그 아래 화면 정중앙에 뜬다).
 */
@Composable
private fun LiquifyCanvas(
    state: LiquifyState,
    viewState: CanvasViewState,
    actions: LiquifyActions,
    showSize: Boolean,
    onCanvasTop: (Float) -> Unit,
    onTwoFingerTap: (() -> Unit)?,
    threeFinger: ThreeFingerActions?,
    flip: ViewFlip,
    modifier: Modifier,
    floating: FloatingState? = null
) {
    val ready = remember(state.preview, state.documentSize, state.colorSpace) {
        EditorState.Ready(state.preview, state.documentSize, colorSpace = state.colorSpace)
    }
    Box(
        modifier
            .onGloballyPositioned { onCanvasTop(it.positionInRoot().y) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .testTag(EditScreenTags.LIQUIFY_CANVAS)
    ) {
        // 편집 화면의 화면 반전을 그대로 따른다(결정 기록 '화면 반전')
        CanvasViewport(ready, viewState, Modifier.fillMaxSize(), onTwoFingerTap = onTwoFingerTap, threeFinger = threeFinger, flip = flip) {
            if (state.faceMode) {
                FaceOverlay(
                    view = { viewState.view },
                    document = state.documentSize,
                    faces = state.faces.orEmpty(),
                    selected = state.selectedFace,
                    enabled = !state.busy,
                    onPick = actions::pickFace,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LiquifyOverlay(view = { viewState.view }, document = state.documentSize, brushSize = state.size, enabled = !state.busy, actions = actions, modifier = Modifier.fillMaxSize())
            }
            if (showSize) {
                SizePreviewOverlay(view = { viewState.view }, document = state.documentSize, size = state.size, hardness = null, modifier = Modifier.fillMaxSize())
            }
            // 띠의 옵션 창이 떠 있는 동안 캔버스를 누르면 닫기만 한다(편집 화면과 같다)
            floating?.let { FloatingDismissLayer(it, Modifier.fillMaxSize()) }
        }
        if (state.busy || state.findingFaces) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/**
 * 유동화 브러시의 터치 자리. 한 손가락으로 끌면 획이 이어지고, 손가락 둘레에 브러시 크기 동그라미를 보인다.
 * 두 손가락 조작(확대·이동·톡)은 부모가 먼저 읽고 소비하고, 두 번째 손가락이 닿아도 그 획을 버린다([LiquifyActions.cancelStroke], 브러시와 같다).
 * 그래서 두 손가락 톡으로 되돌릴 때 첫 손가락이 찍은 작은 획이 남지 않는다.
 */
@Composable
fun LiquifyOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    brushSize: Float,
    enabled: Boolean,
    actions: LiquifyActions,
    modifier: Modifier = Modifier
) {
    val currentActions by rememberUpdatedState(actions)
    val currentEnabled by rememberUpdatedState(enabled)
    // 손가락 자리(화면 좌표). 떼면 null.
    var finger by remember { mutableStateOf<Offset?>(null) }
    // 마우스를 올려 둔 자리(사용자 피드백 12차 4: 끌기 전에도 붓 원을 보인다). 마우스가 없거나 나가면 null.
    var hover by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier
            .testTag(EditScreenTags.LIQUIFY_OVERLAY)
            // 캔버스 위 마우스 포인터는 십자다
            .pointerHoverIcon(PointerIcon.Crosshair)
            .trackMouseHover { hover = it }
            .pointerInput(document) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    val area = PixelSize(size.width, size.height)
                    fun toDocument(position: Offset, send: (Float, Float) -> Unit) {
                        val transform = view().transform(area, document)
                        send(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
                    }
                    down.consume()
                    finger = down.position
                    toDocument(down.position) { x, y -> currentActions.begin(x, y) }
                    // 두 손가락이면 버리고(false), 손을 떼면 남긴다. 그 전에 이 자리가 사라지면(닫힘 등) 끄던 획까지 남긴다.
                    var keep: Boolean? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                                keep = false
                                break
                            }
                            for (point in change.historical) toDocument(point.position) { x, y -> currentActions.move(x, y) }
                            toDocument(change.position) { x, y -> currentActions.move(x, y) }
                            finger = change.position
                            if (!change.pressed) {
                                keep = true
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        finger = null
                        if (keep == false) currentActions.cancelStroke() else currentActions.end()
                    }
                }
            }
    ) {
        val at = finger ?: hover
        if (at != null) BrushCursor(at, brushSize, view, document)
    }
}

/**
 * 닫힌 화면 맨 아래 줄 가운데의 도구 탭: 뒤틀기·오목·볼록·재구성·얼굴(아이콘, 화면 읽기는 이름). 커버 폭(411dp)에 다섯 칸이 들어가게 아이콘이다(띠의 칸과 같은 아이콘).
 */
@Composable
private fun LiquifyToolTabs(state: LiquifyState, enabled: Boolean, actions: LiquifyActions) {
    val tools = LiquifyTool.entries
    val count = tools.size + 1
    // 칸 폭을 정해 다섯 칸이 X·✓ 사이에 들어가게 한다(M3 분할 버튼의 최소 폭대로면 커버 411dp에서 ✓가 밀려난다)
    StratumSegmentedRow(Modifier.width(ToolTabWidth * count)) {
        tools.forEachIndexed { index, entry ->
            StratumSegment(
                selected = !state.faceMode && entry == state.tool,
                onClick = { actions.setTool(entry) },
                index = index,
                count = count,
                enabled = enabled,
                modifier = Modifier.testTag(EditScreenTags.liquifyTool(entry))
            ) {
                Icon(painterResource(liquifyToolIcon(entry)), contentDescription = stringResource(liquifyToolLabel(entry)))
            }
        }
        StratumSegment(
            selected = state.faceMode,
            onClick = actions::setFaceMode,
            index = tools.size,
            count = count,
            enabled = enabled,
            modifier = Modifier.testTag(EditScreenTags.LIQUIFY_FACE)
        ) {
            Icon(painterResource(R.drawable.ic_face_retouching_natural), contentDescription = stringResource(R.string.liquify_face))
        }
    }
}

/**
 * 커버를 가로로 돌렸을 때 오른쪽 기둥 가운데(✓와 X 사이)의 도구 칸 다섯(위아래로, 아이콘과 이름: 브러시 도구 넷과 얼굴). 고른 도구는 선택 배경이다.
 * 이름표·할 일은 가로 탭([LiquifyToolTabs])과 같다. 세로 칸은 폭이 늘 같아 이름도 함께 보인다.
 */
@Composable
private fun LiquifyToolColumn(state: LiquifyState, enabled: Boolean, actions: LiquifyActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (entry in LiquifyTool.entries) {
            ToolColumnCell(
                tag = EditScreenTags.liquifyTool(entry),
                icon = liquifyToolIcon(entry),
                label = stringResource(liquifyToolLabel(entry)),
                selected = !state.faceMode && entry == state.tool,
                enabled = enabled,
                onClick = { actions.setTool(entry) }
            )
        }
        ToolColumnCell(
            tag = EditScreenTags.LIQUIFY_FACE,
            icon = R.drawable.ic_face_retouching_natural,
            label = stringResource(R.string.liquify_face),
            selected = state.faceMode,
            enabled = enabled,
            onClick = actions::setFaceMode
        )
    }
}

/** 세로 도구 칸 하나(아이콘과 이름) */
@Composable
private fun ToolColumnCell(tag: String, @DrawableRes icon: Int, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    val color = when {
        !enabled -> colors.textDisabled
        selected -> colors.selectedContent
        else -> colors.textSecondary
    }
    CellBox(tag, label, null, open = selected, enabled = enabled, onClick = onClick) {
        Icon(painterResource(icon), contentDescription = null, tint = color)
        CompactLabel(label, if (selected && enabled) colors.selectedContent else if (enabled) colors.textDescription else colors.textDisabled)
    }
}

/** 닫힌 화면 도구 탭 한 칸의 폭: 아이콘(24dp)과 좌우 여백(12dp씩), 누르는 영역 48dp 이상 */
private val ToolTabWidth = 52.dp

/** 유동화 도구의 아이콘(Material Icons: 뒤틀기 swipe, 오목 zoom_in_map, 볼록 zoom_out_map, 재구성 settings_backup_restore) */
@DrawableRes
internal fun liquifyToolIcon(tool: LiquifyTool): Int = when (tool) {
    LiquifyTool.PUSH -> R.drawable.ic_swipe
    LiquifyTool.PUCKER -> R.drawable.ic_zoom_in_map
    LiquifyTool.BLOAT -> R.drawable.ic_zoom_out_map
    LiquifyTool.RECONSTRUCT -> R.drawable.ic_settings_backup_restore
}

/** 유동화 도구의 이름 */
@StringRes
internal fun liquifyToolLabel(tool: LiquifyTool): Int = when (tool) {
    LiquifyTool.PUSH -> R.string.liquify_push
    LiquifyTool.PUCKER -> R.string.liquify_pucker
    LiquifyTool.BLOAT -> R.string.liquify_bloat
    LiquifyTool.RECONSTRUCT -> R.string.liquify_reconstruct
}

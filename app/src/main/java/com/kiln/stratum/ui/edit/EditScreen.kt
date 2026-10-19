package com.kiln.stratum.ui.edit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.export.ExportStorage
import com.kiln.stratum.settings.ColorSlot
import com.kiln.stratum.settings.PaintColors
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumIconToggleButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.components.stratumTopAppBarColors
import com.kiln.stratum.ui.layout.HingeAvoidingBottom
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.WorkTopBarHeight
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.topBarItem
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.layout.windowInsets
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 편집 화면. 상단 바 아래를 화면 폭에 따라 세 가지로 배치한다(CLAUDE.md 6장).
 *
 * - Compact(커버·폰 세로): 위 캔버스, 아래 도구 탭 줄
 * - Medium(메인 세로)·Expanded(메인 가로): 왼쪽 도구 막대와 캔버스. 고정 패널은 없다(2026-10-19 사용자 요청).
 *
 * 도구를 고르면 그 옵션이 커버 화면은 캔버스와 탭 줄 사이, 메인 화면은 도구 막대 오른쪽의 좁은 띠(칸을 누르면 옵션 창)에 보인다.
 * 조정·레이어는 커버 화면에서는 탭(도구)이고, 메인 화면에서는 상단 바 버튼으로 여는 떠 있는 패널(캔버스 오른쪽 위)이다.
 *
 * @param onCanvasSize 캔버스 영역의 크기(픽셀)가 정해지거나 바뀌면 알린다. 엔진이 알맞은 축소본을 그리는 데 쓴다.
 * @param onViewport 확대·이동으로 보기가 바뀌면 보이는 문서 영역을 알린다. 엔진이 보이는 곳을 선명하게 다시 그리는 데 쓴다.
 * @param onChooseSize 큰 사진 선택 창에서 고른 크기. true면 원본 크기, false면 줄여서 연다.
 * @param messages 잠깐 알릴 일. 캔버스 아래쪽에 알림 막대로 하나씩 보인다.
 * @param onEdit 엔진에 보낼 편집 명령(회전·뒤집기 등)
 * @param exportChoice 마지막으로 내보낸 고르기. 내보내기 창이 이것으로 시작한다.
 * @param onExport 내보내기 창에서 고른 것과 내보낼 곳(갤러리·공유)
 * @param shareRequests 공유 창으로 보낼 사진. 앱이 화면에 있을 때 공유 창을 띄운다.
 * @param liveAdjustments 보정 슬라이더를 끄는 동안의 값(없으면 null). 보정 패널만 읽도록 함수로 받는다.
 * @param adjust 보정 패널이 보내는 일(미리 보기·기록)
 * @param liveOpacity 불투명도 슬라이더를 끄는 동안의 값(없으면 null). 레이어 패널만 읽도록 함수로 받는다.
 * @param layerActions 레이어 패널이 보내는 일(고르기·더하기·지우기·옮기기·불투명도·혼합 모드)
 * @param brush 브러시 설정, [onBrush]로 바꾼다
 * @param eraser 지우개 설정, [onEraser]로 바꾼다
 * @param strokeActions 브러시·지우개를 고른 채 캔버스를 끌 때 보내는 일
 * @param textStyle 글자 모양, [onTextStyle]로 바꾼다
 * @param onAddText 글자 창에서 '넣기'를 눌렀다: 글자와, 캔버스에서 누른 자리(문서 좌표, 글자 상자의 왼쪽 위)
 * @param twoFingerTapUndo 캔버스를 두 손가락으로 톡 누르면 실행 취소한다(설정). 상단 바의 실행 취소 버튼이 켜져 있을 때만 된다.
 * @param threeFingerGestures 세 손가락 제스처(설정): 캔버스를 톡 누르면 다시 실행, 위아래로 밀면 브러시 크기, 좌우로 밀면 단단함을 바꾼다.
 * @param selectionOptions 선택 도구 설정, [onSelectionOptions]로 바꾼다
 * @param selectionActions 선택 도구가 캔버스와 옵션 버튼에서 보내는 일
 * @param liquify 열려 있는 픽셀 유동화 작업 모드. 있으면 편집 화면 전체를 덮는다.
 * @param liquifyActions 유동화 작업 모드가 보내는 일
 * @param onStartLiquify 유동화 도구를 골랐다(작업 모드를 연다)
 * @param retouch 리터칭 설정(크기·단단함·불투명도), [onRetouch]로 바꾼다
 * @param cloneSource 복제 도장·복구 브러시가 가져올 곳(정하지 않았으면 null)
 * @param retouchActions 리터칭 도구를 고른 채 캔버스를 누르거나 끌 때 보내는 일
 * @param toneOptions 명암 도구 설정(범위·노출·크기·단단함), [onToneOptions]로 바꾼다
 * @param toneActions 명암 도구를 고른 채 캔버스를 끌 때 보내는 일
 * @param blurOptions 흐림 도구(흐림·선명) 설정(강도·크기·경도), [onBlurOptions]로 바꾼다
 * @param mosaicOptions 모자이크 도구 설정(셀 크기·크기·경도), [onMosaicOptions]로 바꾼다
 * @param effectActions 흐림·모자이크 도구를 고른 채 캔버스를 끌 때 보내는 일
 * @param paintColors 전경색·배경색(브러시·글자가 함께 쓰고, 칠할 때는 전경색, 결정 기록 '전경색·배경색')
 * @param onChooseColor 전경색이나 배경색을 골랐다(팔레트 확인)
 * @param onSwapColors 전경색과 배경색을 맞바꾼다(색 줄의 바꾸기 버튼)
 * @param eyedropper 스포이드로 고르는 중인 자리와 색(없으면 null). 손가락 둘레 고리만 읽도록 함수로 받는다.
 * @param eyedropperActions 스포이드(버튼을 켜고 누르기, 브러시로 꾹 누르기)가 보내는 일
 */
@Composable
fun EditScreen(
    state: EditorState,
    onBack: () -> Unit,
    onCanvasSize: (width: Int, height: Int) -> Unit,
    onViewport: (Viewport) -> Unit,
    onChooseSize: (original: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    messages: Flow<EditorMessage> = emptyFlow(),
    onEdit: (EditCommand) -> Unit = {},
    exportChoice: ExportChoice = ExportChoice(),
    onExport: (ExportChoice, ExportTarget) -> Unit = { _, _ -> },
    shareRequests: Flow<ShareRequest> = emptyFlow(),
    liveAdjustments: () -> Adjustments? = { null },
    adjust: AdjustActions = AdjustActions.None,
    liveOpacity: () -> Float? = { null },
    layerActions: LayerActions = LayerActions.None,
    brush: BrushSettings = BrushSettings(),
    onBrush: (BrushSettings) -> Unit = {},
    eraser: BrushSettings = BrushSettings(),
    onEraser: (BrushSettings) -> Unit = {},
    strokeActions: StrokeActions = StrokeActions.None,
    textStyle: TextStyle = TextStyle(),
    onTextStyle: (TextStyle) -> Unit = {},
    onAddText: (text: String, x: Float, y: Float) -> Unit = { _, _, _ -> },
    twoFingerTapUndo: Boolean = false,
    threeFingerGestures: Boolean = false,
    selectionOptions: SelectionOptions = SelectionOptions(),
    onSelectionOptions: (SelectionOptions) -> Unit = {},
    selectionActions: SelectionActions = SelectionActions.None,
    liquify: LiquifyState? = null,
    liquifyActions: LiquifyActions = LiquifyActions.None,
    onStartLiquify: () -> Unit = {},
    retouch: BrushSettings = BrushSettings(),
    onRetouch: (BrushSettings) -> Unit = {},
    cloneSource: CloneSource? = null,
    retouchActions: RetouchActions = RetouchActions.None,
    toneOptions: ToneOptions = ToneOptions(),
    onToneOptions: (ToneOptions) -> Unit = {},
    toneActions: ToneActions = ToneActions.None,
    blurOptions: BlurOptions = BlurOptions(),
    onBlurOptions: (BlurOptions) -> Unit = {},
    mosaicOptions: MosaicOptions = MosaicOptions(),
    onMosaicOptions: (MosaicOptions) -> Unit = {},
    effectActions: EffectActions = EffectActions.None,
    paintColors: PaintColors = PaintColors.DEFAULT,
    onChooseColor: (ColorSlot, Int) -> Unit = { _, _ -> },
    onSwapColors: () -> Unit = {},
    eyedropper: () -> EyedropperState? = { null },
    eyedropperActions: EyedropperActions = EyedropperActions.None,
    moveActions: MoveActions = MoveActions.None
) {
    // 캔버스 보기(확대·이동). 화면 폭에 따라 배치가 바뀌어도 이어지도록 배치를 고르기 전에 둔다.
    val viewState = rememberCanvasViewState((state as? EditorState.Ready)?.documentSize)
    // 고른 도구. 커버에서 고른 도구가 펼친 뒤에도 그대로 이어지도록 배치를 고르기 전에 둔다.
    val selection = rememberToolSelection()
    // 메인 화면 캔버스 위에 떠 있는 옵션 창·패널(한 번에 하나)
    val floating = rememberFloatingState()
    // 도구 막대·커버 탭 줄의 하위 도구 메뉴(편집 화면 맨 위에 하나, 결정 기록 '하위 도구 메뉴')
    val subToolMenu = remember { SubToolMenuState() }
    // 캔버스 보기 반전(좌우·상하, 결정 기록 '화면 반전'). 문서가 아니라 보기라 자르기·크기 변경 뒤에도, 접기·펼치기에도 남고,
    // 편집 화면을 나가면 풀린다. 레이어 탭의 버튼으로 켜고 끈다.
    var viewFlipBits by rememberSaveable { mutableIntStateOf(0) }
    val viewFlip = ViewFlip.fromBits(viewFlipBits)
    // 커버 화면 조정 옵션에서 고른 항목
    var adjustItem by rememberSaveable { mutableStateOf(AdjustItem.EXPOSURE) }
    // 리터칭의 '가져올 곳 정하기'를 켰는지(가져올 곳이 아직 없으면 이것과 상관없이 정한다)
    var retouchPicking by rememberSaveable { mutableStateOf(false) }
    // 크기·단단함 슬라이더를 끄거나 세 손가락으로 미는 동안 캔버스 가운데에 브러시 크기를 보인다(브러시·지우개·리터칭·명암)
    val sizePreview = rememberSizePreviewState()
    // 두 손가락·세 손가락 톡의 짧은 알림(결정 기록 '제스처 알림')
    val gestureToast = rememberGestureToastState()
    val undoneText = stringResource(R.string.action_undo)
    val sourceLockedText = stringResource(R.string.retouch_source_locked)
    val sourceUnlockedText = stringResource(R.string.retouch_source_unlocked)
    val nothingToUndoText = stringResource(R.string.gesture_nothing_to_undo)
    val redoneText = stringResource(R.string.action_redo)
    val nothingToRedoText = stringResource(R.string.gesture_nothing_to_redo)
    // 세 손가락으로 미는 동안 시작할 때의 브러시 설정(그 값에서 움직인 거리만큼 바꾼다)
    val swipeAnchor = remember { SwipeAnchor<BrushSettings>() }
    val ready = state as? EditorState.Ready
    // 유동화는 도구로 남지 않고 작업 모드를 연다
    LaunchedEffect(selection.active) {
        if (selection.active == EditTool.Liquify) {
            selection.close()
            onStartLiquify()
        }
    }
    // 자르기 테두리(문서 픽셀 좌표). 접기·펼치기에도 남고, 문서가 바뀌면(편집·실행 취소) 새로 시작한다.
    var cropFrame by rememberSaveable(ready?.revision, stateSaver = CropFrameSaver) { mutableStateOf<CropFrame?>(null) }
    val cropMode = ready != null && selection.active == EditTool.Transform && selection.transformMode == SubTool.CROP
    // 크기 변경 창의 입력. 창이 닫혀 있으면 null이고, 열려 있으면 접기·펼치기에도 남는다.
    var resizeInput by rememberSaveable(stateSaver = ResizeInput.Saver) { mutableStateOf<ResizeInput?>(null) }
    // 다른 크기의 문서를 보게 되면(다른 문서를 열었거나 편집됨) 창을 닫는다
    LaunchedEffect(ready?.documentSize) {
        if (resizeInput?.document != ready?.documentSize) resizeInput = null
    }
    // 하위 도구 고르기. 크기 변경은 한 번 하고 끝나는 일이라 도구를 바꾸지 않고 창만 띄운다.
    val chooseSubTool: (SubTool) -> Unit = { subTool ->
        if (subTool == SubTool.RESIZE) {
            ready?.let { resizeInput = ResizeInput.start(it.documentSize) }
        } else {
            selection.choose(subTool)
        }
    }
    // 글자를 넣을 자리(문서 좌표 x, y)와 그때의 문서 크기. 글자 창이 닫혀 있으면 null이고, 열려 있으면 접기·펼치기에도 남는다.
    var textAt by rememberSaveable { mutableStateOf<List<Float>?>(null) }
    // 다른 크기의 문서를 보게 되면(다른 문서를 열었거나 편집됨) 창을 닫는다(누른 자리가 맞지 않는다)
    LaunchedEffect(ready?.documentSize) {
        val size = ready?.documentSize
        if (textAt?.let { it[2].toInt() != size?.width || it[3].toInt() != size.height } == true) textAt = null
    }
    // 내보내기 창에서 고르던 것. 창이 닫혀 있으면 null이고, 열려 있으면 접기·펼치기에도 남는다.
    var exportDraft by rememberSaveable(stateSaver = ExportDraftSaver) { mutableStateOf<ExportChoice?>(null) }
    // 색 팔레트에서 고르던 색. 닫혀 있으면 null이고, 열려 있으면 접기·펼치기에도 남는다.
    var palette by rememberSaveable(stateSaver = PaletteDraft.Saver) { mutableStateOf<PaletteDraft?>(null) }
    // 스포이드 버튼을 켠 곳(브러시·글자). 켜면 다음에 캔버스를 누른 자리의 색을 고르고 꺼진다. 도구를 바꾸면 꺼진다.
    var eyedropperTarget by rememberSaveable { mutableStateOf<ColorTarget?>(null) }
    LaunchedEffect(selection.active) { eyedropperTarget = null }
    // 브러시·글자의 색 줄: 전경색·배경색을 누르면 그 색의 팔레트, 바꾸기 버튼은 두 색을 맞바꾸고, 스포이드 버튼은 켜고 끈다
    // (결정 기록 '전경색·배경색'). 두 색은 브러시·글자가 함께 쓴다. 메인 화면의 띠에서 열린 옵션 창은 닫는다.
    fun colorControls(target: ColorTarget) = ColorControls(
        colors = paintColors,
        onOpenPalette = { slot ->
            floating.closeFlyout()
            palette = PaletteDraft.start(slot, paintColors[slot])
        },
        onSwap = {
            floating.closeFlyout()
            onSwapColors()
        },
        eyedropperOn = eyedropperTarget == target,
        onEyedropper = { eyedropperTarget = if (eyedropperTarget == target) null else target }
    )
    // 브러시로 움직이지 않고 꾹 누르면 그 손가락이 스포이드가 된다(결정 기록 '스포이드')
    val brushHold = remember(eyedropperActions) {
        object : HoldActions {
            override fun begin(x: Float, y: Float) = eyedropperActions.begin(ColorTarget.BRUSH, x, y)
            override fun move(x: Float, y: Float) = eyedropperActions.move(x, y)
            override fun end() = eyedropperActions.end()
            override fun cancel() = eyedropperActions.cancel()
        }
    }
    // 공유 창은 앱이 화면에 있을 때만 띄운다. 뒤로 가 있는 동안 끝난 것은 돌아왔을 때 띄운다.
    val context = LocalContext.current
    // 넓은 색 문서를 여는 동안 창을 넓은 색 모드로, HDR 문서는 HDR 모드로 둔다. 닫으면 되돌린다.
    WindowColorMode(wideColor = ready?.colorSpace == DocumentColorSpace.DISPLAY_P3, hdr = ready?.gainmap != null)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(shareRequests, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            shareRequests.collect { request ->
                context.startActivity(ExportStorage.shareChooser(request.uri, request.mimeType))
            }
        }
    }
    // 캔버스 영역의 크기(픽셀). 메인 화면에서 자르기를 시작할 때 띠를 뺀 자리에 맞추는 데 쓴다.
    var canvasArea by remember { mutableStateOf(IntSize.Zero) }
    val reportCanvasSize: (Int, Int) -> Unit = { width, height ->
        canvasArea = IntSize(width, height)
        onCanvasSize(width, height)
    }
    // 자르기를 시작할 때 바꾼 보기. 그대로인 채 자르기가 끝나면 맞춤으로 돌린다(배율 표시가 남지 않게).
    var cropStartView by remember { mutableStateOf<CanvasView?>(null) }
    // 닫힌 화면에서 캔버스 위에 겹쳐 뜬 옵션 칸의 높이(픽셀). 알림 막대·크기 원을 그 위 보이는 자리에 놓는다.
    var coverSheetPx by remember { mutableStateOf(0) }
    // 편집 화면과 캔버스 영역의 위쪽 끝(창 루트 기준 픽셀). 제스처 알림을 화면 전체 자리에서 캔버스 위쪽 아래에 띄운다.
    var screenTop by remember { mutableFloatStateOf(0f) }
    var canvasTop by remember { mutableFloatStateOf(0f) }
    val reportCanvasTop: (Float) -> Unit = remember { { top -> canvasTop = top } }

    // 창 전체 폭으로 배치를 고르므로 Scaffold 바깥에서 잰다
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { screenTop = it.positionInRoot().y }) {
        val widthClass = widthClassOf(maxWidth.value)
        // 커버 화면 옵션 자리의 최대 높이를 정하는 창 높이
        val windowHeight = maxHeight
        // 문서가 열려 있어야 도구를 쓴다. 고른 도구가 있으면 그 옵션을 보인다.
        val toolsEnabled = ready != null
        val activeTool = selection.active.takeIf { toolsEnabled }
        // 뒤로 가기: 도구를 골랐으면 그 도구를 닫는다(결정 기록 '뒤로 가기로 도구 닫기'). 스포이드·자르기는 도구를 닫으면 함께 끝난다.
        // 메인 화면에서는 도구 막대의 도구만 본다. 커버에서 고른 조정·레이어는 메인에서 보이지 않아 뒤로를 먹지 않는다.
        // 늘 두고 켜고 끄기만 해서, 홈으로 가는 처리(StratumApp)보다 나중에·창과 유동화보다 먼저 등록된 순서를 지킨다.
        val density = LocalDensity.current
        LaunchedEffect(cropMode, ready?.revision) {
            if (cropMode && ready != null) {
                // 자르기를 시작하면 사진 전체를 테두리로 두고, 사진 전체가 보이게 맞춘다.
                // 메인 화면은 테두리 손잡이가 왼쪽 띠에 가리지 않게 띠 자리를 양쪽에 비워 캔버스 정중앙에 맞춘다(결정 기록 '도구 옵션 띠').
                if (cropFrame == null) {
                    cropFrame = CropFrame.whole(ready.documentSize)
                    val start = if (widthClass != WidthClass.Compact && canvasArea.width > 0 && canvasArea.height > 0) {
                        cropStartView(
                            area = PixelSize(canvasArea.width, canvasArea.height),
                            document = ready.documentSize,
                            strip = with(density) { (OptionStripWidth + OptionStripGap).roundToPx() },
                            margin = with(density) { CropStartMargin.roundToPx() }
                        )
                    } else {
                        CanvasView.Fit
                    }
                    viewState.view = start
                    cropStartView = start
                }
            } else {
                cropFrame = null
                if (cropStartView != null && viewState.view == cropStartView) viewState.view = CanvasView.Fit
                cropStartView = null
            }
        }
        val closableTool = if (widthClass == WidthClass.Compact) activeTool else activeTool?.takeIf { it in EditTool.railTools }
        BackHandler(enabled = closableTool != null, onBack = selection::close)
        // 옵션 창이 떠 있으면 뒤로 가기는 그것부터 닫는다. 도구의 것 바로 뒤에 늘 두어, 창·유동화의 것보다는 먼저 등록된다.
        // (Scaffold 안에 두면 다시 만들어질 때 창보다 나중에 등록돼 창보다 먼저 받는다)
        // 레이어·조정 패널은 뒤로 가기로 닫지 않는다(사용자 결정: 상단 바 버튼으로만 닫음)
        BackHandler(enabled = floating.flyout != null, onBack = floating::closeFlyout)
        // 도구·하위 도구·배치가 바뀌거나, 자르기·유동화를 시작하거나, 다른 크기의 문서를 보면 옵션 창을 닫는다(칸이 바뀌거나 사라진다)
        val activeMode = activeTool?.let { selection.modeOf(it) }
        LaunchedEffect(activeTool, activeMode, widthClass, cropMode, ready?.documentSize, liquify != null) { floating.closeFlyout() }
        // 창(크기 변경·글자·내보내기·팔레트·큰 사진)이나 유동화 작업 모드가 뜨면 옵션 창을 닫는다.
        // 패널은 그대로 둔다(창·작업 모드 아래에 남았다가, 닫히면 다시 보인다)
        val dialogOpen = resizeInput != null || textAt != null || exportDraft != null || palette != null || state is EditorState.AskSize || liquify != null
        LaunchedEffect(dialogOpen) { if (dialogOpen) floating.closeFlyout() }
        val crop = cropFrame?.takeIf { cropMode }?.let { frame ->
            val document = checkNotNull(ready).documentSize
            CropControls(
                frame = frame,
                canApply = frame.toPixelRect(document) != PixelRect.of(document),
                onRatio = { ratio -> cropFrame = cropFrame?.withRatio(ratio, document) },
                onSwap = { cropFrame = cropFrame?.swapped(document) },
                onCancel = selection::close,
                onApply = {
                    cropFrame?.let { onEdit(CropImage(it.toPixelRect(document), document)) }
                    selection.close()
                }
            )
        }
        // 고른 레이어의 보정. 자르기 중에는 막는다(기록하면 문서가 바뀌어 자르기 테두리가 처음으로 돌아가므로).
        val adjustControls = ready?.active?.let { layer ->
            AdjustControls(committed = layer.adjustments, live = liveAdjustments, enabled = !cropMode, actions = adjust)
        }
        // 레이어 목록과 고른 레이어. 자르기 중에는 막는다(보정과 같은 까닭).
        val layerControls = ready?.let {
            LayerControls(
                it.layers, it.activeLayer, liveOpacity, enabled = !cropMode, busy = it.busy, actions = layerActions, colorSpace = it.colorSpace,
                viewFlip = viewFlip, onViewFlip = { flip -> viewFlipBits = flip.bits }
            )
        }
        // 고른 브러시·지우개의 설정
        val brushControls = when (activeTool) {
            EditTool.Brush -> BrushControls(brush, erase = false, onChange = onBrush, sizePreview = sizePreview, colors = colorControls(ColorTarget.BRUSH))
            EditTool.Eraser -> BrushControls(eraser, erase = true, onChange = onEraser, sizePreview = sizePreview)
            else -> null
        }
        val textControls = TextControls(textStyle, onTextStyle, colorControls(ColorTarget.TEXT)).takeIf { activeTool == EditTool.Text }
        val selectionControls = ready?.takeIf { activeTool == EditTool.Select }?.let {
            SelectionControls(selectionOptions, onSelectionOptions, hasSelection = it.selection != null, busy = it.busy, actions = selectionActions)
        }
        // 리터칭: 고른 하위 도구의 획 종류와, 캔버스를 누르면 가져올 곳을 정하는지(복제·복구는 가져올 곳이 없으면 늘 정한다)
        val retouchMode = selection.modeOf(EditTool.Retouch)?.retouchMode ?: RetouchMode.SPOT_HEAL
        val retouchPicks = retouchMode != RetouchMode.SPOT_HEAL && (retouchPicking || cloneSource == null)
        val retouchControls = RetouchControls(
            settings = retouch,
            onChange = onRetouch,
            source = cloneSource,
            picking = retouchPicks,
            onPick = { if (cloneSource != null) retouchPicking = !retouchPicking },
            sizePreview = sizePreview
        ).takeIf { activeTool == EditTool.Retouch }
        val toneControls = ToneControls(toneOptions, sizePreview = sizePreview, onChange = onToneOptions).takeIf { activeTool == EditTool.Tone }
        val blurControls = BlurControls(blurOptions, sizePreview = sizePreview, onChange = onBlurOptions).takeIf { activeTool == EditTool.Blur }
        val mosaicControls = MosaicControls(mosaicOptions, sizePreview = sizePreview, onChange = onMosaicOptions).takeIf { activeTool == EditTool.Mosaic }
        // 크기를 미리 볼 도구의 크기·단단함
        val sizedBrush = when (activeTool) {
            EditTool.Brush -> brush
            EditTool.Eraser -> eraser
            EditTool.Retouch -> retouch
            EditTool.Tone -> toneOptions.brush
            EditTool.Blur -> blurOptions.brush
            EditTool.Mosaic -> mosaicOptions.brush
            else -> null
        }
        val toolOptions: (@Composable () -> Unit)? = activeTool?.let {
            {
                ToolOptions(
                    selection,
                    busy = ready?.busy == true,
                    onEdit = onEdit,
                    onChoose = chooseSubTool,
                    crop = crop,
                    adjust = adjustControls,
                    adjustItem = adjustItem,
                    onAdjustItem = { adjustItem = it },
                    layers = layerControls,
                    brush = brushControls,
                    text = textControls,
                    select = selectionControls,
                    retouch = retouchControls,
                    tone = toneControls,
                    blur = blurControls,
                    mosaic = mosaicControls
                )
            }
        }
        // 닫힌 화면 공통 규칙(결정 기록 '닫힌 화면 도구 옵션'): 옵션 칸은 캔버스를 줄이지 않고 아래쪽 위에 겹쳐 뜬다.
        // 그래서 탭을 고르거나 바꾸거나 닫아도 보던 이미지의 크기·자리가 그대로다. 자르기(새 창)는 따로다.
        val coverSheetOverlays = widthClass == WidthClass.Compact && toolOptions != null && !cropMode
        val coverSheetInset = if (coverSheetOverlays) with(density) { coverSheetPx.toDp() } else 0.dp
        // 메인 화면: 도구 막대에서 고른 도구의 옵션은 도구 막대 오른쪽 띠에 보인다(결정 기록 '편집 화면 도구 배치').
        // 띠가 없는 도구는 패널 위쪽에 옵션을 보인다. 조정·레이어는 패널에 늘 있다.
        val railTool = activeTool?.takeIf { it in EditTool.railTools }
        val stripItems: List<StripItem>? = if (widthClass == WidthClass.Compact) {
            null
        } else {
            when (railTool) {
                EditTool.Brush, EditTool.Eraser -> brushControls?.let { brushStrip(it) }
                EditTool.Text -> textControls?.let { textStrip(it) }
                EditTool.Transform -> transformStrip(selection.transformMode, ready?.busy == true, onEdit, crop, floating::closeFlyout)
                EditTool.Select -> selectionControls?.let {
                    selectionStrip(checkNotNull(selection.modeOf(EditTool.Select)), it, floating::closeFlyout)
                }
                EditTool.Retouch -> retouchControls?.let {
                    retouchStrip(checkNotNull(selection.modeOf(EditTool.Retouch)), it)
                }
                EditTool.Tone -> toneControls?.let {
                    toneStrip(it, floating::closeFlyout)
                }
                EditTool.Blur -> blurControls?.let {
                    blurStrip(it)
                }
                EditTool.Mosaic -> mosaicControls?.let { mosaicStrip(it) }
                else -> null
            }
        }
        // 접기·펼치기 잇기(결정 기록 '떠 있는 레이어·조정 패널'): 커버에서 고른 조정·레이어 탭은 펼치면 그 패널로 열고,
        // 고른 도구 없이 패널을 연 채 접으면 커버에서 그 탭을 연다.
        // 도구를 고른 채 접으면 커버에는 그 도구가 보이고, 패널은 끄지 않고 남겨 두어 다시 펼치면 그대로 보인다(직접 끄기 전까지 유지).
        // 배치가 바뀌면(접기·펼치기) 메뉴를 연 버튼 자리가 사라지므로 닫는다
        LaunchedEffect(widthClass) { subToolMenu.close() }
        // 메뉴가 뜨면 띠의 옵션 창은 닫는다(다른 곳을 눌렀으므로)
        LaunchedEffect(subToolMenu.isOpen) { if (subToolMenu.isOpen) floating.closeFlyout() }
        LaunchedEffect(widthClass) {
            if (widthClass == WidthClass.Compact) {
                floating.panel?.let { panel ->
                    if (selection.active == null) {
                        selection.tap(if (panel == MainPanel.ADJUST) EditTool.Adjust else EditTool.Layers)
                        floating.closePanel()
                    }
                }
            } else {
                when (selection.active) {
                    EditTool.Adjust -> {
                        selection.close()
                        floating.openPanel(MainPanel.ADJUST)
                    }
                    EditTool.Layers -> {
                        selection.close()
                        floating.openPanel(MainPanel.LAYERS)
                    }
                    else -> Unit
                }
            }
        }
        // 메인 화면은 띠에 안내 글이 들어갈 자리가 없어, 도구를 고를 때 캔버스 위쪽 가운데에 알림으로 한 번 보인다
        // 이동 도구는 고를 옵션이 없어 띠 없이 안내만 띄운다
        val toolHint = if (stripItems != null || (widthClass != WidthClass.Compact && railTool == EditTool.Move)) toolHintText(railTool, activeMode, retouchPicks) else null
        var shownHints by rememberSaveable { mutableStateOf(listOf<String>()) }
        LaunchedEffect(toolHint) {
            if (toolHint != null && toolHint !in shownHints) {
                shownHints = shownHints + toolHint
                gestureToast.show(toolHint, TOOL_HINT_MILLIS)
            }
        }
        // 띠와 그 칸의 옵션 창. 캔버스를 줄이지 않고 위에 겹친다(열고 닫아도 사진이 튀지 않게).
        val stripInset = if (stripItems != null) OptionStripWidth + OptionStripGap else 0.dp
        val floatingUi: @Composable BoxScope.(WindowInsets) -> Unit = { insets ->
            // 상단 바 버튼으로 여는 레이어·조정 패널(캔버스 오른쪽 위). 자르기 중에는 숨겼다가(조정·레이어를 막으므로) 끝나면 다시 보인다.
            // 좁은 화면에서 옵션 창과 겹치면 옵션 창이 위에 보이게 먼저 그린다.
            if (!cropMode) FloatingPanelHost(floating, adjustControls, layerControls, Modifier.fillMaxSize().windowInsetsPadding(insets))
            if (stripItems != null && railTool != null) {
                // 띠는 고른 도구 옆에 칸만큼의 높이로 뜬다(사용자 요청)
                OptionStripHost(stripItems, floating, railTool, Modifier.fillMaxSize().windowInsetsPadding(insets))
                FlyoutHost(floating, stripItems, Modifier.fillMaxSize().windowInsetsPadding(insets))
            }
        }
        // 자르기 중에는 캔버스 위에 테두리를, 브러시·지우개·리터칭·명암을 고르면 그리는 터치 자리를 겹친다.
        // 크기 슬라이더를 끄는 동안은 그 위에 브러시 크기 원을 보인다.
        // 스포이드 버튼을 켰으면 브러시·글자의 손가락 대신 스포이드 손가락을 겹친다
        val picking = eyedropperTarget?.takeIf {
            (it == ColorTarget.BRUSH && activeTool == EditTool.Brush) || (it == ColorTarget.TEXT && activeTool == EditTool.Text)
        }
        val overlay: @Composable BoxScope.() -> Unit = {
            val frame = cropFrame
            if (ready != null && picking != null) {
                EyedropperOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    target = picking,
                    actions = eyedropperActions,
                    onPicked = { eyedropperTarget = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ready != null && activeTool == EditTool.Move) {
                // 이동 도구: 한 손가락으로 끌어 고른 레이어를 옮긴다(결정 기록 '이동 도구')
                MoveOverlay(view = { viewState.view }, document = ready.documentSize, actions = moveActions, modifier = Modifier.fillMaxSize())
            }
            if (ready != null && picking == null && (activeTool == EditTool.Brush || activeTool == EditTool.Eraser)) {
                StrokeOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    erase = activeTool == EditTool.Eraser,
                    actions = strokeActions,
                    modifier = Modifier.fillMaxSize(),
                    hold = brushHold.takeIf { activeTool == EditTool.Brush },
                    // 칠하는 손가락 자리에 브러시 크기 원(결정 기록 '브러시 커서')
                    cursorSize = if (activeTool == EditTool.Eraser) eraser.size else brush.size
                )
            }
            if (ready != null && activeTool == EditTool.Retouch) {
                RetouchOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    mode = retouchMode,
                    picking = retouchPicks,
                    source = cloneSource,
                    radius = retouch.radius,
                    actions = retouchActions,
                    onPicked = { retouchPicking = false },
                    modifier = Modifier.fillMaxSize(),
                    // 표시를 꾹 눌러 고정을 켜고 끄면 짧게 알린다
                    onLockChanged = { locked -> gestureToast.show(if (locked) sourceLockedText else sourceUnlockedText) }
                )
            }
            if (ready != null && activeTool == EditTool.Tone) {
                ToneOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    dodge = selection.modeOf(EditTool.Tone) != SubTool.TONE_BURN,
                    actions = toneActions,
                    modifier = Modifier.fillMaxSize(),
                    cursorSize = toneOptions.brush.size
                )
            }
            if (ready != null && activeTool == EditTool.Blur) {
                // 흐림·선명·스머지
                EffectOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    // 스머지는 효과가 아니라(null) 따로 끄는 획이다
                    kind = selection.modeOf(EditTool.Blur)?.effectKind,
                    actions = effectActions,
                    tag = EditScreenTags.BLUR_OVERLAY,
                    modifier = Modifier.fillMaxSize(),
                    cursorSize = blurOptions.brush.size
                )
            }
            if (ready != null && activeTool == EditTool.Mosaic) {
                EffectOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    kind = EffectKind.MOSAIC,
                    actions = effectActions,
                    tag = EditScreenTags.MOSAIC_OVERLAY,
                    modifier = Modifier.fillMaxSize(),
                    cursorSize = mosaicOptions.brush.size
                )
            }
            if (ready != null && activeTool == EditTool.Select) {
                SelectionOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    mode = checkNotNull(selection.modeOf(EditTool.Select)),
                    options = selectionOptions,
                    actions = selectionActions,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ready != null && picking == null && activeTool == EditTool.Text) {
                TextOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    onTap = { x, y -> textAt = listOf(x, y, ready.documentSize.width.toFloat(), ready.documentSize.height.toFloat()) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (cropMode && frame != null && ready != null) {
                CropOverlay(
                    frame = frame,
                    document = ready.documentSize,
                    view = { viewState.view },
                    onFrameChange = { cropFrame = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ready != null && sizedBrush != null && sizePreview.visible) {
                SizePreviewOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    size = sizedBrush.size,
                    hardness = sizedBrush.hardness,
                    modifier = Modifier.fillMaxSize(),
                    bottomInset = coverSheetInset
                )
            }
            // 스포이드로 고르는 동안 손가락 둘레에 고를 색(위)과 지금 색(아래) 고리를 보인다
            val picked = eyedropper()
            if (ready != null && picked != null) {
                EyedropperLoupe(
                    state = picked,
                    // 아래쪽 반은 지금 칠하는 색(전경색)이다
                    currentColor = paintColors.foreground,
                    view = { viewState.view },
                    document = ready.documentSize,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 옵션 창이 떠 있는 동안 캔버스를 누르면 닫기만 한다(맨 위라 도구가 받지 않는다). 패널만 열려 있으면 캔버스를 바로 쓴다.
            FloatingDismissLayer(floating, Modifier.fillMaxSize())
        }
        // 닫힌 화면 자르기는 새 창이다(결정 기록 '닫힌 화면 새 창'): 위는 이름 줄, 아래는 X·✓ 줄이고 탭 줄을 숨긴다
        val coverCropWindow = widthClass == WidthClass.Compact && cropMode
        Scaffold(
            topBar = {
                if (coverCropWindow) {
                    CoverWindowTopBar(stringResource(R.string.subtool_crop))
                } else {
                    EditTopBar(
                        onBack = onBack,
                        // 편집을 처리하는 동안에는 막는다
                        canUndo = ready?.canUndo == true && !ready.busy,
                        canRedo = ready?.canRedo == true && !ready.busy,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        // 자르기 중에는 막는다(적용이나 취소한 뒤 내보낸다)
                        canExport = ready != null && !ready.busy && !cropMode,
                        onExport = { exportDraft = exportChoice },
                        // 메인 화면만: 조정·레이어 패널 버튼. 자르기 중에는 막는다(조정·레이어를 막으므로)
                        panels = if (widthClass == WidthClass.Compact) {
                            null
                        } else {
                            PanelToggles(open = floating.panel, enabled = ready != null && !cropMode, onToggle = floating::togglePanel)
                        }
                    )
                }
            },
            // 시스템 바·카메라 구멍은 영역마다 바깥쪽만 비켜 두므로, Scaffold는 상단 바 높이만 비운다
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            val body = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
            // 두 손가락 톡: 실행 취소 버튼과 같은 조건(되돌릴 것이 있고 처리 중이 아님)일 때만 되돌린다
            // 처리 중이면 아무것도 하지 않고, 되돌릴 것이 없으면 그렇다고 알린다
            val twoFingerUndo: (() -> Unit)? = if (twoFingerTapUndo) {
                {
                    when {
                        ready == null || ready.busy -> Unit
                        ready.canUndo -> {
                            onUndo()
                            gestureToast.show(undoneText)
                        }
                        else -> gestureToast.show(nothingToUndoText)
                    }
                }
            } else {
                null
            }
            // 세 손가락(결정 기록 '세 손가락 제스처'): 톡은 다시 실행(두 손가락 톡의 반대), 위아래로 밀면 크기, 좌우로 밀면 단단함.
            // 브러시·지우개·리터칭·명암의 설정을 바꾸고, 미는 동안은 알림 없이 캔버스 가운데에 크기 원만 보인다(사용자 결정).
            // 다른 도구를 골랐거나 고른 도구가 없으면 밀기는 아무것도 하지 않는다.
            val threeFinger: ThreeFingerActions? = if (threeFingerGestures) {
                object : ThreeFingerActions {
                    override fun tap() {
                        when {
                            ready == null || ready.busy -> Unit
                            ready.canRedo -> {
                                onRedo()
                                gestureToast.show(redoneText)
                            }
                            else -> gestureToast.show(nothingToRedoText)
                        }
                    }

                    override fun swipe(axis: SwipeAxis, distance: Dp) {
                        val changed = swipeAnchor.from(sizedBrush ?: return).swiped(axis, distance.value)
                        when (activeTool) {
                            EditTool.Brush -> onBrush(changed)
                            EditTool.Eraser -> onEraser(changed)
                            EditTool.Retouch -> onRetouch(changed)
                            EditTool.Tone -> onToneOptions(toneOptions.copy(brush = changed))
                            EditTool.Blur -> onBlurOptions(blurOptions.copy(brush = changed))
                            EditTool.Mosaic -> onMosaicOptions(mosaicOptions.copy(brush = changed))
                            else -> return
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
            when (widthClass) {
                WidthClass.Compact -> Column(body) {
                    val sheetMaxHeight = windowHeight * COMPACT_OPTIONS_MAX_FRACTION
                    if (coverCropWindow) {
                        // 자르기 새 창: 크기 유지 규칙에서 빠진다. 캔버스(사진 전체를 맞춤), 하위 도구·비율, 맨 아래 X(취소)·✓(적용).
                        // 뒤로 가기는 X와 같다(도구를 닫는 뒤로 가기). 탭 줄은 숨긴다.
                        EditCanvas(
                            state, viewState, messages, reportCanvasSize, onViewport, overlay, false,
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), reportCanvasTop, Modifier.weight(1f), twoFingerUndo, threeFinger,
                            flip = viewFlip
                        )
                        if (crop != null) {
                            Surface(Modifier.fillMaxWidth().edgeDivider(DividerEdge.Top, StratumTheme.colors.divider), color = StratumTheme.colors.panel) {
                                CoverCropOptions(
                                    selection.transformMode,
                                    chooseSubTool,
                                    crop,
                                    Modifier
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            CoverWorkBar(
                                onCancel = crop.onCancel,
                                onDone = crop.onApply,
                                doneEnabled = crop.canApply && ready?.busy != true,
                                cancelTag = EditScreenTags.CROP_CANCEL,
                                doneTag = EditScreenTags.CROP_APPLY
                            )
                        }
                    } else {
                        // 캔버스는 상단 바와 탭 줄 사이 전체를 늘 차지하고, 옵션 칸은 그 아래쪽 위에 겹쳐 뜬다(이미지 크기·자리 그대로)
                        EditCanvas(
                            state, viewState, messages, reportCanvasSize, onViewport, overlay, true,
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), reportCanvasTop, Modifier.weight(1f), twoFingerUndo, threeFinger,
                            overlayBottom = coverSheetInset,
                            flip = viewFlip,
                            floating = {
                                if (toolOptions != null) {
                                    ToolOptionsSheet(
                                        toolOptions,
                                        maxHeight = sheetMaxHeight,
                                        modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { coverSheetPx = it.height }
                                    )
                                }
                            }
                        )
                        ToolTabBar(selection, enabled = toolsEnabled, onChoose = chooseSubTool, menu = subToolMenu)
                    }
                }

                // 메인 세로·가로: 도구 막대와 캔버스. 도구 옵션 띠·떠 있는 패널은 캔버스 위에 겹친다(floatingUi).
                // 캔버스는 키보드 높이를 빼지 않는다(시스템 바·카메라 구멍만 피함). 글자·크기 변경 창에서 키보드가 열려도 다시 그리지 않는다.
                else -> Row(body) {
                    ToolRail(
                        selection,
                        enabled = toolsEnabled,
                        onChoose = chooseSubTool,
                        menu = subToolMenu,
                        onToolBounds = { tool, bounds -> if (floating.toolAnchors[tool] != bounds) floating.toolAnchors[tool] = bounds }
                    )
                    EditCanvas(
                        state, viewState, messages, reportCanvasSize, onViewport, overlay, !cropMode,
                        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.End + WindowInsetsSides.Bottom),
                        reportCanvasTop, Modifier.weight(1f), twoFingerUndo, threeFinger, overlayStart = stripInset, flip = viewFlip, floating = floatingUi
                    )
                }
            }
        }

        // 제스처 알림·도구 안내는 도구 막대·띠와 상관없이 화면 가로 정중앙에 띄운다(2026-10-20 사용자 요청: "도구바나 띠 생각하지 말고 화면 정중앙에").
        // 세로는 캔버스 위쪽 끝 아래(배율 표시 아래)다. 떠 있는 띠·패널보다 위에 그리고, 터치는 받지 않아 그 밑을 그대로 누를 수 있다.
        GestureToast(gestureToast, Modifier.fillMaxSize().padding(top = with(LocalDensity.current) { (canvasTop - screenTop).coerceAtLeast(0f).toDp() }))
        // 하위 도구 메뉴는 캔버스·띠 위에 뜬다(창보다는 아래). 고르면 닫고 하위 도구를 고른다(크기 변경은 창을 띄움).
        SubToolMenuHost(
            state = subToolMenu,
            current = { selection.modeOf(it) },
            onChoose = {
                subToolMenu.close()
                chooseSubTool(it)
            }
        )
        // 화면 전체를 덮어야 해서 Scaffold 밖, 같은 Box의 마지막에 둔다
        if (state is EditorState.AskSize) {
            LargePhotoDialog(state = state, onChooseSize = onChooseSize, onCancel = onBack)
        }
        val input = resizeInput
        if (input != null && ready != null) {
            ResizePrompt(
                input = input,
                widthClass = widthClass,
                busy = ready.busy,
                onDismiss = { resizeInput = null },
                onApply = { width, height ->
                    onEdit(ResizeImage(width, height))
                    resizeInput = null
                }
            )
        }
        val place = textAt
        if (place != null && ready != null) {
            TextPrompt(
                widthClass = widthClass,
                busy = ready.busy,
                onDismiss = { textAt = null },
                onAdd = { text ->
                    onAddText(text, place[0], place[1])
                    textAt = null
                }
            )
        }
        val draft = exportDraft
        if (draft != null && ready != null) {
            ExportPrompt(
                choice = draft,
                documentSize = ready.documentSize,
                widthClass = widthClass,
                busy = ready.busy,
                onChoiceChange = { exportDraft = it },
                onDismiss = { exportDraft = null },
                onExport = { target ->
                    onExport(draft, target)
                    exportDraft = null
                },
                hdr = ready.hdr
            )
        }
        palette?.let { draft ->
            ColorPalettePrompt(
                draft = draft,
                widthClass = widthClass,
                windowHeight = windowHeight,
                onChange = { palette = it },
                onDismiss = { palette = null },
                onConfirm = {
                    onChooseColor(draft.slot, draft.color)
                    palette = null
                }
            )
        }
        // 픽셀 유동화 작업 모드는 편집 화면(창 포함) 전체를 덮는다(CLAUDE.md 6장)
        if (liquify != null) {
            LiquifyMode(liquify, liquifyActions, twoFingerTapUndo = twoFingerTapUndo, threeFingerGestures = threeFingerGestures, flip = viewFlip)
        }
    }
}

/**
 * 커버 화면(Compact)의 도구 옵션 자리. 탭 줄 바로 위에서 캔버스 아래쪽 위에 겹쳐 뜬다(캔버스를 줄이지 않아 이미지 크기·자리가 그대로,
 * 결정 기록 '닫힌 화면 도구 옵션'). 자르기는 새 창이라 캔버스 아래에 따로 놓는다.
 * 커버 화면에는 접히는 부분이 없고, 옆쪽 시스템 바·카메라 구멍만 피한다.
 * 옵션이 길면(선택 도구 등) [maxHeight]까지만 차지하고 그 안에서 위아래로 밀어 본다. 작은 폰에서도 캔버스가 보인다.
 */
@Composable
private fun ToolOptionsSheet(options: @Composable () -> Unit, maxHeight: Dp, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().edgeDivider(DividerEdge.Top, StratumTheme.colors.divider).testTag(EditScreenTags.COVER_OPTIONS), color = StratumTheme.colors.panel) {
        Box(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            options()
        }
    }
}

/** 메인 화면 상단 바의 조정·레이어 패널 버튼: 열린 패널, 누를 수 있는지, 누르면 할 일(열고 닫음) */
class PanelToggles(val open: MainPanel?, val enabled: Boolean, val onToggle: (MainPanel) -> Unit)

/**
 * 편집 화면 상단 바: 뒤로, 실행 취소·다시 실행, (메인 화면만) 조정·레이어 패널 버튼, 내보내기.
 * 작업 공간을 넓게 쓰려고 버튼이 꼭 들어가는 높이(48dp)로 낮게 두고, 카메라 구멍이 버튼에 걸릴 때만 구멍 아래로 내린다(결정 기록 '화면 공간').
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(
    onBack: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canExport: Boolean,
    onExport: () -> Unit,
    panels: PanelToggles? = null
) {
    val barInsets = rememberTopBarInsets(WorkTopBarHeight)
    TopAppBar(
        title = {},
        // 캔버스(작업 공간)와 기본 배경 바를 1dp 구분선으로 나눈다(시안)
        modifier = Modifier.edgeDivider(DividerEdge.Bottom, StratumTheme.colors.divider).testTag(EditScreenTags.TOP_BAR),
        navigationIcon = {
            StratumIconButton(onClick = onBack, modifier = Modifier.topBarItem(barInsets, "back")) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        actions = {
            Row(Modifier.topBarItem(barInsets, "actions"), verticalAlignment = Alignment.CenterVertically) {
                StratumIconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag(EditScreenTags.UNDO)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_undo),
                        contentDescription = stringResource(R.string.action_undo)
                    )
                }
                StratumIconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag(EditScreenTags.REDO)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_redo),
                        contentDescription = stringResource(R.string.action_redo)
                    )
                }
                // 조정·레이어 패널 버튼(프로크리에이트처럼 켜고 끈다). 열린 패널의 버튼이 켜진 모양이다.
                if (panels != null) {
                    for (panel in MainPanel.entries) {
                        StratumIconToggleButton(
                            checked = panels.open == panel,
                            onCheckedChange = { panels.onToggle(panel) },
                            enabled = panels.enabled,
                            modifier = Modifier.testTag(EditScreenTags.panelToggle(panel))
                        ) {
                            Icon(
                                painter = painterResource(if (panel == MainPanel.ADJUST) R.drawable.ic_tune else R.drawable.ic_layers),
                                contentDescription = stringResource(if (panel == MainPanel.ADJUST) R.string.tool_adjust else R.string.tool_layers)
                            )
                        }
                    }
                }
                // 내보내기는 시안처럼 파란 글자 버튼이다(사용자 결정, 자리는 그대로)
                AccentButton(
                    onClick = onExport,
                    enabled = canExport,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp).testTag(EditScreenTags.EXPORT)
                ) {
                    Text(stringResource(R.string.action_export))
                }
            }
        },
        expandedHeight = WorkTopBarHeight,
        windowInsets = barInsets.windowInsets(),
        colors = stratumTopAppBarColors()
    )
}

/**
 * 캔버스 영역. 엔진이 그린 축소본을 보기 상태([viewState])대로 보여 주고, 두 손가락으로 확대·이동한다.
 * 화면 코드는 픽셀을 계산하지 않는다(CLAUDE.md 3장).
 * 배경은 영역 끝까지 칠하고, 안의 내용만 [insets](시스템 바·카메라 구멍)를 피한다.
 * 편집을 처리하는 동안 위쪽에 처리 중 막대를 보이고, [messages]는 아래쪽 알림 막대로 보인다.
 * [onCanvasTop]으로 캔버스 영역의 위쪽 끝(창 루트 기준)을 알린다. 제스처 알림은 편집 화면 전체 자리에서 그 아래 화면 정중앙에 뜬다.
 * [onTwoFingerTap]·[threeFinger]는 캔버스의 두 손가락 톡·세 손가락 제스처를 받는다(설정이 꺼져 있으면 null).
 * [floating]은 캔버스 위에 겹치는 메인 화면의 띠·옵션 창·패널이다. 캔버스 영역 전체(시스템 바 쪽 포함)를 받고, 내용이 피할 여백을 함께 받는다.
 * 배율 표시는 [overlayStart](왼쪽 띠의 폭과 간격)만큼 오른쪽으로 비킨다. 알림 막대는 양쪽을 [overlayStart]만큼 비워
 * 띠를 덮지 않으면서 캔버스 정중앙에 둔다(2026-10-20 사용자 요청). 알림 막대('보기' 버튼)는 떠 있는 것보다 위에 그린다.
 * 닫힌 화면은 옵션 칸을 [floating]으로 캔버스 아래쪽 위에 겹쳐 띄우고, 알림 막대는 그 높이([overlayBottom])만큼 위로 비킨다.
 * [flip](화면 반전)은 캔버스 영역([CanvasViewport])에만 건다. 배율 표시·알림·띠·패널은 뒤집지 않는다.
 */
@Composable
private fun EditCanvas(
    state: EditorState,
    viewState: CanvasViewState,
    messages: Flow<EditorMessage>,
    onCanvasSize: (width: Int, height: Int) -> Unit,
    onViewport: (Viewport) -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    showZoomChip: Boolean,
    insets: WindowInsets,
    onCanvasTop: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onTwoFingerTap: (() -> Unit)? = null,
    threeFinger: ThreeFingerActions? = null,
    overlayStart: Dp = 0.dp,
    overlayBottom: Dp = 0.dp,
    flip: ViewFlip = ViewFlip.None,
    floating: @Composable BoxScope.(WindowInsets) -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notEnoughMemory = stringResource(R.string.edit_not_enough_memory)
    val exportSaved = stringResource(R.string.edit_export_saved)
    val exportNotEnoughMemory = stringResource(R.string.edit_export_not_enough_memory)
    val exportFailed = stringResource(R.string.edit_export_failed)
    val view = stringResource(R.string.action_view)
    val autosaveFailed = stringResource(R.string.edit_autosave_failed)
    val hiddenLayer = stringResource(R.string.edit_hidden_layer)
    // 처리 중 막대는 잠깐(0.2초) 넘게 걸릴 때만 보인다. 보정 슬라이더를 놓을 때처럼 짧은 편집마다 깜빡이지 않게 한다.
    // 편집 버튼은 바쁨(busy)이 켜지는 순간 막힌다.
    val busy = (state as? EditorState.Ready)?.busy == true
    var showBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        showBusy = false
        if (busy) {
            delay(BUSY_INDICATOR_DELAY_MILLIS)
            showBusy = true
        }
    }
    LaunchedEffect(messages) {
        // 하나씩 보이고, 사라진 뒤 다음 것을 보인다
        messages.collect { message ->
            when (message) {
                is EditorMessage.ExportSaved -> {
                    // 저장한 사본을 사진 앱으로 바로 열어 볼 수 있게 한다
                    val result = snackbarHostState.showSnackbar(exportSaved, actionLabel = view, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        try {
                            context.startActivity(ExportStorage.viewIntent(message.uri, message.mimeType))
                        } catch (e: ActivityNotFoundException) {
                            // 사진 앱이 없으면 아무것도 하지 않는다
                        }
                    }
                }
                EditorMessage.EditNotEnoughMemory -> snackbarHostState.showSnackbar(notEnoughMemory)
                EditorMessage.ExportNotEnoughMemory -> snackbarHostState.showSnackbar(exportNotEnoughMemory)
                EditorMessage.ExportFailed -> snackbarHostState.showSnackbar(exportFailed)
                EditorMessage.AutosaveFailed -> snackbarHostState.showSnackbar(autosaveFailed)
                EditorMessage.HiddenLayer -> snackbarHostState.showSnackbar(hiddenLayer)
            }
        }
    }
    Box(modifier.fillMaxSize().background(StratumTheme.colors.workspace).onGloballyPositioned { onCanvasTop(it.positionInRoot().y) }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(insets)
                .onSizeChanged { onCanvasSize(it.width, it.height) }
                .testTag(EditScreenTags.CANVAS),
            contentAlignment = Alignment.Center
        ) {
            CanvasContent(state, viewState, onViewport, overlay, showZoomChip, showBusy, onTwoFingerTap, threeFinger, overlayStart, flip)
        }
        floating(insets)
        // 알림 막대는 캔버스 아래쪽 정중앙에 띄우되, 펼친 화면의 접히는 선과 왼쪽 띠(양쪽을 띠 폭만큼 비움), 닫힌 화면에서 겹쳐 뜬 옵션 칸([overlayBottom])을 피한다
        HingeAvoidingBottom(Modifier.fillMaxSize().windowInsetsPadding(insets).padding(start = overlayStart, end = overlayStart, bottom = overlayBottom), margin = 8.dp) {
            SnackbarHost(snackbarHostState, Modifier.testTag(EditScreenTags.MESSAGE_BAR))
        }
    }
}

/** 캔버스 영역 안: 문서 상태에 따른 내용(여는 중 표시, 캔버스와 배율 표시·처리 중 막대, 실패 글) */
@Composable
private fun CanvasContent(
    state: EditorState,
    viewState: CanvasViewState,
    onViewport: (Viewport) -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    showZoomChip: Boolean,
    showBusy: Boolean,
    onTwoFingerTap: (() -> Unit)?,
    threeFinger: ThreeFingerActions?,
    overlayStart: Dp,
    flip: ViewFlip
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            EditorState.Empty, is EditorState.AskSize -> Unit
            EditorState.Opening -> ProgressIndicator(stringResource(R.string.edit_opening_photo))
            EditorState.Creating -> ProgressIndicator(stringResource(R.string.edit_creating_canvas))
            EditorState.LoadingProject -> ProgressIndicator(stringResource(R.string.edit_opening_project))
            is EditorState.Ready -> {
                CanvasViewport(state, viewState, Modifier.fillMaxSize(), onViewport, onTwoFingerTap, threeFinger, flip = flip, overlay = overlay)
                // 확대·이동했을 때만 보이는 배율 표시. 누르면 화면에 맞춘다. 자르기 중에는 테두리를 가리지 않게 숨긴다. 왼쪽 띠가 있으면 그 오른쪽에 둔다.
                if (showZoomChip) CanvasZoomChip(viewState, Modifier.fillMaxSize().padding(start = overlayStart))
                if (showBusy) {
                    val working = stringResource(R.string.edit_working)
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .testTag(EditScreenTags.BUSY)
                            .semantics { contentDescription = working }
                    )
                }
            }
            is EditorState.Failed -> Text(
                text = stringResource(
                    when (state.reason) {
                        EditorFailure.PHOTO_UNREADABLE -> R.string.edit_open_failed
                        EditorFailure.PHOTO_NOT_ENOUGH_MEMORY -> R.string.edit_open_failed_memory
                        EditorFailure.CANVAS_NOT_ENOUGH_MEMORY -> R.string.edit_create_failed_memory
                        EditorFailure.PROJECT_UNREADABLE -> R.string.edit_project_unreadable
                        EditorFailure.PROJECT_NOT_ENOUGH_MEMORY -> R.string.edit_project_failed_memory
                    }
                ),
                modifier = Modifier.padding(24.dp),
                color = StratumTheme.colors.textDescription,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * 창 색 모드를 문서에 맞춘다: [hdr]이면 HDR 모드(`COLOR_MODE_HDR`, 넓은 색도 담는다), 아니고 [wideColor]이면 넓은 색 모드,
 * 아니면 기본. 화면이 지원하지 않으면 안드로이드가 무시한다. 화면을 떠나거나 바뀌면 기본 모드로 되돌린다.
 * HDR 모드는 게인맵이 붙은 그림을 HDR 화면에서 밝게 그린다(안드로이드 14 이상, [hdr]은 그때만 참이다).
 */
@Composable
private fun WindowColorMode(wideColor: Boolean, hdr: Boolean) {
    val window = LocalContext.current.findActivity()?.window
    val mode = when {
        hdr -> ActivityInfo.COLOR_MODE_HDR
        wideColor -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        else -> ActivityInfo.COLOR_MODE_DEFAULT
    }
    DisposableEffect(window, mode) {
        if (window != null && mode != ActivityInfo.COLOR_MODE_DEFAULT) window.colorMode = mode
        onDispose {
            if (window != null && mode != ActivityInfo.COLOR_MODE_DEFAULT) window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
}

/** 이 화면을 띄운 Activity. Compose의 Context는 감싼 것일 수 있어 안쪽으로 찾아 들어간다. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** 바쁨이 이만큼(밀리초) 이어지면 처리 중 막대를 보인다 */
private const val BUSY_INDICATOR_DELAY_MILLIS = 200L

/** 커버 화면에서 도구 옵션 자리가 차지할 수 있는 화면 높이의 비율 */
private const val COMPACT_OPTIONS_MAX_FRACTION = 0.45f

/** 메인 화면에서 자르기를 시작할 때 사진 둘레에 남기는 여백(테두리 손잡이를 잡을 수 있게) */
private val CropStartMargin = 24.dp

/** 문서를 열거나 만드는 동안 보이는 표시와 [message]. */
@Composable
private fun ProgressIndicator(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            color = StratumTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 큰 사진을 원본 크기로 열지 줄여서 열지 고르는 창. 취소하면 홈으로 돌아간다.
 * 기기 메모리로 원본 크기가 무리면 '원본 크기'를 누를 수 없게 하고 까닭을 알려 준다.
 * 펼친 화면 가운데의 힌지를 피해 뜬다.
 */
@Composable
private fun LargePhotoDialog(
    state: EditorState.AskSize,
    onChooseSize: (original: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    HingeAwareDialog(
        onDismissRequest = onCancel,
        title = stringResource(R.string.large_photo_title),
        // 버튼 세 개가 한 줄에 다 들어가지 않으면 순서가 뒤섞여 줄바꿈되므로, 처음부터 세로로 쌓는다.
        // 위에서부터 메모리를 덜 쓰는 '줄여서', '원본 크기', '취소'
        buttons = {
            Column(horizontalAlignment = Alignment.End) {
                QuietButton(onClick = { onChooseSize(false) }) {
                    Text(stringResource(R.string.large_photo_open_reduced, state.reducedMegapixels))
                }
                QuietButton(onClick = { onChooseSize(true) }, enabled = state.originalAllowed) {
                    Text(stringResource(R.string.large_photo_open_original, state.originalMegapixels))
                }
                QuietButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    ) {
        Text(
            stringResource(
                if (state.originalAllowed) R.string.large_photo_message else R.string.large_photo_message_no_memory,
                state.originalMegapixels
            )
        )
    }
}

// 화면 폭별 배치 미리보기(폴드8 커버·메인 세로·메인 가로와 비슷한 크기)
@Preview(name = "Compact", widthDp = 411, heightDp = 800)
@Preview(name = "Medium", widthDp = 700, heightDp = 900)
@Preview(name = "Expanded", widthDp = 930, heightDp = 700)
@Composable
private fun EditScreenPreview() {
    StratumTheme {
        EditScreen(state = EditorState.Empty, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
    }
}

@Preview
@Composable
private fun EditScreenOpeningPreview() {
    StratumTheme {
        EditScreen(state = EditorState.Opening, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
    }
}

/** 자르기 테두리를 숫자 목록으로 저장한다. 테두리가 없으면 저장하지 않는다. */
private val CropFrameSaver = Saver<CropFrame?, DoubleArray>(
    save = { it?.toSaved()?.toDoubleArray() },
    restore = { CropFrame.fromSaved(it.toList()) }
)

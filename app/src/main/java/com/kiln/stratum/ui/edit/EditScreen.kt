package com.kiln.stratum.ui.edit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.export.ExportStorage
import com.kiln.stratum.ui.layout.HingeAvoidingBottom
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 편집 화면. 상단 바 아래를 화면 폭에 따라 세 가지로 배치한다(CLAUDE.md 6장).
 *
 * - Compact(커버·폰 세로): 위 캔버스, 아래 도구 탭 줄
 * - Medium(메인 세로): 왼쪽 도구 막대, 캔버스, 아래 접이식 패널
 * - Expanded(메인 가로): 왼쪽 도구 막대, 캔버스, 오른쪽 고정 패널
 *
 * 도구는 자르기·변형, 조정, 레이어가 켜져 있다. 도구를 고르면 그 옵션이 커버 화면은 캔버스와 탭 줄 사이,
 * 메인 세로는 아래 패널, 메인 가로는 오른쪽 패널 맨 위에 보인다.
 * 조정·레이어는 커버 화면에서는 탭(도구)이고, 메인 화면에서는 패널(아래 패널의 탭, 오른쪽 패널의 칸)이다.
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
    toneActions: ToneActions = ToneActions.None
) {
    // 아래 패널을 펼쳤는지. 접기·펼치기나 회전으로 배치가 바뀌어도 이어진다.
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    // 캔버스 보기(확대·이동). 화면 폭에 따라 배치가 바뀌어도 이어지도록 배치를 고르기 전에 둔다.
    val viewState = rememberCanvasViewState((state as? EditorState.Ready)?.documentSize)
    // 고른 도구. 커버에서 고른 도구가 펼친 뒤에도 그대로 이어지도록 배치를 고르기 전에 둔다.
    val selection = rememberToolSelection()
    // 메인 세로 아래 패널의 탭. 커버에서 조정·레이어를 고른 채 펼치면 그 탭을 보인다.
    var panelTab by rememberSaveable { mutableStateOf(PanelTab.ADJUST) }
    // 커버 화면 조정 옵션에서 고른 항목
    var adjustItem by rememberSaveable { mutableStateOf(AdjustItem.EXPOSURE) }
    // 리터칭의 '가져올 곳 정하기'를 켰는지(가져올 곳이 아직 없으면 이것과 상관없이 정한다)
    var retouchPicking by rememberSaveable { mutableStateOf(false) }
    // 크기 슬라이더를 끄는 동안 캔버스 가운데에 브러시 크기를 보인다(브러시·지우개·리터칭·명암)
    val sizePreview = rememberSizePreviewState()
    // 메인 세로에서 도구를 고르면 옵션이 보이게 아래 패널을 펼친다
    LaunchedEffect(selection.active) {
        if (selection.active != null) panelExpanded = true
        if (selection.active == EditTool.Adjust) panelTab = PanelTab.ADJUST
        if (selection.active == EditTool.Layers) panelTab = PanelTab.LAYERS
    }
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
    LaunchedEffect(cropMode, ready?.revision) {
        if (cropMode && ready != null) {
            // 자르기를 시작하면 사진 전체를 테두리로 두고, 사진 전체가 보이게 화면에 맞춘다
            if (cropFrame == null) {
                cropFrame = CropFrame.whole(ready.documentSize)
                viewState.view = CanvasView.Fit
            }
        } else {
            cropFrame = null
        }
    }

    // 창 전체 폭으로 배치를 고르므로 Scaffold 바깥에서 잰다
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = widthClassOf(maxWidth.value)
        // 커버 화면 옵션 자리의 최대 높이를 정하는 창 높이
        val windowHeight = maxHeight
        // 문서가 열려 있어야 도구를 쓴다. 고른 도구가 있으면 그 옵션을 보인다.
        val toolsEnabled = ready != null
        val activeTool = selection.active.takeIf { toolsEnabled }
        val toolTitle = activeTool?.let { stringResource(it.label) }
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
            LayerControls(it.layers, it.activeLayer, liveOpacity, enabled = !cropMode, busy = it.busy, actions = layerActions, colorSpace = it.colorSpace)
        }
        // 고른 브러시·지우개의 설정
        val brushControls = when (activeTool) {
            EditTool.Brush -> BrushControls(brush, erase = false, onChange = onBrush, sizePreview = sizePreview)
            EditTool.Eraser -> BrushControls(eraser, erase = true, onChange = onEraser, sizePreview = sizePreview)
            else -> null
        }
        val textControls = TextControls(textStyle, onTextStyle).takeIf { activeTool == EditTool.Text }
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
        // 크기를 미리 볼 도구의 크기·단단함
        val sizedBrush = when (activeTool) {
            EditTool.Brush -> brush
            EditTool.Eraser -> eraser
            EditTool.Retouch -> retouch
            EditTool.Tone -> toneOptions.brush
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
                    tone = toneControls
                )
            }
        }
        // 메인 화면의 패널 위쪽에는 도구 막대의 도구 옵션만 보인다. 조정·레이어는 패널에 늘 있다.
        val railTool = activeTool?.takeIf { it in EditTool.railTools }
        val railToolTitle = toolTitle?.takeIf { railTool != null }
        val railToolOptions = toolOptions?.takeIf { railTool != null }
        // 자르기 중에는 캔버스 위에 테두리를, 브러시·지우개·리터칭·명암을 고르면 그리는 터치 자리를 겹친다.
        // 크기 슬라이더를 끄는 동안은 그 위에 브러시 크기 원을 보인다.
        val overlay: @Composable BoxScope.() -> Unit = {
            val frame = cropFrame
            if (ready != null && (activeTool == EditTool.Brush || activeTool == EditTool.Eraser)) {
                StrokeOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    erase = activeTool == EditTool.Eraser,
                    actions = strokeActions,
                    modifier = Modifier.fillMaxSize()
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
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ready != null && activeTool == EditTool.Tone) {
                ToneOverlay(
                    view = { viewState.view },
                    document = ready.documentSize,
                    dodge = selection.modeOf(EditTool.Tone) != SubTool.TONE_BURN,
                    actions = toneActions,
                    modifier = Modifier.fillMaxSize()
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
            if (ready != null && activeTool == EditTool.Text) {
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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Scaffold(
            topBar = {
                EditTopBar(
                    onBack = onBack,
                    // 편집을 처리하는 동안에는 막는다
                    canUndo = ready?.canUndo == true && !ready.busy,
                    canRedo = ready?.canRedo == true && !ready.busy,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    // 자르기 중에는 막는다(적용이나 취소한 뒤 내보낸다)
                    canExport = ready != null && !ready.busy && !cropMode,
                    onExport = { exportDraft = exportChoice }
                )
            },
            // 시스템 바·카메라 구멍은 영역마다 바깥쪽만 비켜 두므로, Scaffold는 상단 바 높이만 비운다
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            val body = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
            // 두 손가락 톡: 실행 취소 버튼과 같은 조건(되돌릴 것이 있고 처리 중이 아님)일 때만 되돌린다
            val twoFingerUndo: (() -> Unit)? = if (twoFingerTapUndo) {
                { if (ready?.canUndo == true && !ready.busy) onUndo() }
            } else {
                null
            }
            when (widthClass) {
                WidthClass.Compact -> Column(body) {
                    EditCanvas(state, viewState, messages, onCanvasSize, onViewport, overlay, !cropMode, WindowInsetsSides.Horizontal, Modifier.weight(1f), twoFingerUndo)
                    if (toolOptions != null) ToolOptionsSheet(toolOptions, maxHeight = windowHeight * COMPACT_OPTIONS_MAX_FRACTION)
                    ToolTabBar(selection, enabled = toolsEnabled, onChoose = chooseSubTool)
                }

                WidthClass.Medium -> Row(body) {
                    ToolRail(selection, enabled = toolsEnabled, onChoose = chooseSubTool)
                    Column(Modifier.weight(1f)) {
                        EditCanvas(state, viewState, messages, onCanvasSize, onViewport, overlay, !cropMode, WindowInsetsSides.End, Modifier.weight(1f), twoFingerUndo)
                        val adjustTab = adjustControls?.takeIf { panelTab == PanelTab.ADJUST }
                        val layersTab = layerControls?.takeIf { panelTab == PanelTab.LAYERS }
                        BottomPanel(
                            expanded = panelExpanded,
                            onToggle = { panelExpanded = !panelExpanded },
                            toolTitle = railToolTitle,
                            toolOptions = railToolOptions,
                            tab = panelTab,
                            onTab = { panelTab = it },
                            tabsEnabled = toolsEnabled,
                            tabAction = when {
                                adjustTab != null -> {
                                    { ResetAllButton(adjustTab) }
                                }
                                layersTab != null -> {
                                    { LayerToolbar(layersTab) }
                                }
                                else -> null
                            },
                            tabContent = when {
                                adjustTab != null -> {
                                    { AdjustList(adjustTab, twoColumns = true) }
                                }
                                layersTab != null -> {
                                    { LayerTwoColumns(layersTab) }
                                }
                                else -> null
                            }
                        )
                    }
                }

                WidthClass.Expanded -> Row(body) {
                    ToolRail(selection, enabled = toolsEnabled, onChoose = chooseSubTool)
                    EditCanvas(state, viewState, messages, onCanvasSize, onViewport, overlay, !cropMode, WindowInsetsSides.Bottom, Modifier.weight(1f), twoFingerUndo)
                    SidePanel(
                        toolTitle = railToolTitle,
                        toolOptions = railToolOptions,
                        adjustAction = adjustControls?.let { controls -> { ResetAllButton(controls) } },
                        adjustContent = adjustControls?.let { controls -> { AdjustList(controls, twoColumns = false) } },
                        layersAction = layerControls?.let { controls -> { LayerToolbar(controls) } },
                        layersContent = layerControls?.let { controls -> { LayerColumn(controls) } }
                    )
                }
            }
        }

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
        // 픽셀 유동화 작업 모드는 편집 화면(창 포함) 전체를 덮는다(CLAUDE.md 6장)
        if (liquify != null) {
            LiquifyMode(liquify, liquifyActions)
        }
    }
}

/**
 * 커버 화면(Compact)에서 캔버스와 탭 줄 사이에 놓는 도구 옵션 자리. 캔버스를 가리지 않고 그 아래를 차지한다.
 * 커버 화면에는 접히는 부분이 없고, 옆쪽 시스템 바·카메라 구멍만 피한다.
 * 옵션이 길면(선택 도구 등) [maxHeight]까지만 차지하고 그 안에서 위아래로 밀어 본다. 작은 폰에서도 캔버스가 남는다.
 */
@Composable
private fun ToolOptionsSheet(options: @Composable () -> Unit, maxHeight: Dp) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
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

/** 편집 화면 상단 바: 뒤로, 실행 취소·다시 실행, 내보내기 */
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
    onExport: () -> Unit
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag(EditScreenTags.UNDO)) {
                Icon(
                    painter = painterResource(R.drawable.ic_undo),
                    contentDescription = stringResource(R.string.action_undo)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag(EditScreenTags.REDO)) {
                Icon(
                    painter = painterResource(R.drawable.ic_redo),
                    contentDescription = stringResource(R.string.action_redo)
                )
            }
            IconButton(onClick = onExport, enabled = canExport, modifier = Modifier.testTag(EditScreenTags.EXPORT)) {
                Icon(
                    painter = painterResource(R.drawable.ic_ios_share),
                    contentDescription = stringResource(R.string.action_export)
                )
            }
        },
        // 캔버스와 구분되게 상단 바를 한 단계 밝게 칠한다
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

/**
 * 캔버스 영역. 엔진이 그린 축소본을 보기 상태([viewState])대로 보여 주고, 두 손가락으로 확대·이동한다.
 * 화면 코드는 픽셀을 계산하지 않는다(CLAUDE.md 3장).
 * 배경은 영역 끝까지 칠하고, 안의 내용만 [insetSides] 쪽 시스템 바·카메라 구멍을 피한다.
 * 편집을 처리하는 동안 위쪽에 처리 중 막대를 보이고, [messages]는 아래쪽 알림 막대로 보인다.
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
    insetSides: WindowInsetsSides,
    modifier: Modifier = Modifier,
    onTwoFingerTap: (() -> Unit)? = null
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notEnoughMemory = stringResource(R.string.edit_not_enough_memory)
    val exportSaved = stringResource(R.string.edit_export_saved)
    val exportNotEnoughMemory = stringResource(R.string.edit_export_not_enough_memory)
    val exportFailed = stringResource(R.string.edit_export_failed)
    val view = stringResource(R.string.action_view)
    val autosaveFailed = stringResource(R.string.edit_autosave_failed)
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
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(insetSides))
            .onSizeChanged { onCanvasSize(it.width, it.height) }
            .testTag(EditScreenTags.CANVAS),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            EditorState.Empty, is EditorState.AskSize -> Unit
            EditorState.Opening -> ProgressIndicator(stringResource(R.string.edit_opening_photo))
            EditorState.Creating -> ProgressIndicator(stringResource(R.string.edit_creating_canvas))
            EditorState.LoadingProject -> ProgressIndicator(stringResource(R.string.edit_opening_project))
            is EditorState.Ready -> {
                CanvasViewport(state, viewState, Modifier.fillMaxSize(), onViewport, onTwoFingerTap, overlay)
                // 확대·이동했을 때만 보이는 배율 표시. 누르면 화면에 맞춘다. 자르기 중에는 테두리를 가리지 않게 숨긴다.
                if (showZoomChip) CanvasZoomChip(viewState, Modifier.fillMaxSize())
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        // 알림 막대는 캔버스 아래쪽에 띄우되, 펼친 화면의 접히는 선을 피한다
        HingeAvoidingBottom(Modifier.fillMaxSize(), margin = 8.dp) {
            SnackbarHost(snackbarHostState)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                TextButton(onClick = { onChooseSize(false) }) {
                    Text(stringResource(R.string.large_photo_open_reduced, state.reducedMegapixels))
                }
                TextButton(onClick = { onChooseSize(true) }, enabled = state.originalAllowed) {
                    Text(stringResource(R.string.large_photo_open_original, state.originalMegapixels))
                }
                TextButton(onClick = onCancel) {
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

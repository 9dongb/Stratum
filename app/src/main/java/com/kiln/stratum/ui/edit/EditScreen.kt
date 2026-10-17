package com.kiln.stratum.ui.edit

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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.ui.layout.HingeAvoidingBottom
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 편집 화면. 상단 바 아래를 화면 폭에 따라 세 가지로 배치한다(CLAUDE.md 6장).
 *
 * - Compact(커버·폰 세로): 위 캔버스, 아래 도구 탭 줄
 * - Medium(메인 세로): 왼쪽 도구 막대, 캔버스, 아래 접이식 패널
 * - Expanded(메인 가로): 왼쪽 도구 막대, 캔버스, 오른쪽 고정 패널
 *
 * 도구는 자르기·변형만 켜져 있다. 도구를 고르면 그 옵션이 커버 화면은 캔버스와 탭 줄 사이,
 * 메인 세로는 아래 패널, 메인 가로는 오른쪽 패널 맨 위에 보인다. 패널 탭(레이어·조정)은 아직 비활성이다.
 *
 * @param onCanvasSize 캔버스 영역의 크기(픽셀)가 정해지거나 바뀌면 알린다. 엔진이 알맞은 축소본을 그리는 데 쓴다.
 * @param onViewport 확대·이동으로 보기가 바뀌면 보이는 문서 영역을 알린다. 엔진이 보이는 곳을 선명하게 다시 그리는 데 쓴다.
 * @param onChooseSize 큰 사진 선택 창에서 고른 크기. true면 원본 크기, false면 줄여서 연다.
 * @param messages 잠깐 알릴 일. 캔버스 아래쪽에 알림 막대로 하나씩 보인다.
 * @param onEdit 엔진에 보낼 편집 명령(회전·뒤집기 등)
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
    onEdit: (EditCommand) -> Unit = {}
) {
    // 아래 패널을 펼쳤는지. 접기·펼치기나 회전으로 배치가 바뀌어도 이어진다.
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    // 캔버스 보기(확대·이동). 화면 폭에 따라 배치가 바뀌어도 이어지도록 배치를 고르기 전에 둔다.
    val viewState = rememberCanvasViewState((state as? EditorState.Ready)?.documentSize)
    // 고른 도구. 커버에서 고른 도구가 펼친 뒤에도 그대로 이어지도록 배치를 고르기 전에 둔다.
    val selection = rememberToolSelection()
    // 메인 세로에서 도구를 고르면 옵션이 보이게 아래 패널을 펼친다
    LaunchedEffect(selection.active) {
        if (selection.active != null) panelExpanded = true
    }
    val ready = state as? EditorState.Ready
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
        val toolOptions: (@Composable () -> Unit)? = activeTool?.let {
            { ToolOptions(selection, busy = ready?.busy == true, onEdit = onEdit, onChoose = chooseSubTool, crop = crop) }
        }
        // 자르기 중에는 캔버스 위에 테두리를 겹친다
        val overlay: @Composable BoxScope.() -> Unit = {
            val frame = cropFrame
            if (cropMode && frame != null && ready != null) {
                CropOverlay(
                    frame = frame,
                    document = ready.documentSize,
                    view = { viewState.view },
                    onFrameChange = { cropFrame = it },
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
                    onRedo = onRedo
                )
            },
            // 시스템 바·카메라 구멍은 영역마다 바깥쪽만 비켜 두므로, Scaffold는 상단 바 높이만 비운다
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            val body = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
            when (widthClass) {
                WidthClass.Compact -> Column(body) {
                    EditCanvas(state, viewState, messages, onCanvasSize, onViewport, overlay, !cropMode, WindowInsetsSides.Horizontal, Modifier.weight(1f))
                    if (toolOptions != null) ToolOptionsSheet(toolOptions)
                    ToolTabBar(selection, enabled = toolsEnabled, onChoose = chooseSubTool)
                }

                WidthClass.Medium -> Row(body) {
                    ToolRail(selection, enabled = toolsEnabled, onChoose = chooseSubTool)
                    Column(Modifier.weight(1f)) {
                        EditCanvas(state, viewState, messages, onCanvasSize, onViewport, overlay, !cropMode, WindowInsetsSides.End, Modifier.weight(1f))
                        BottomPanel(
                            expanded = panelExpanded,
                            onToggle = { panelExpanded = !panelExpanded },
                            toolTitle = toolTitle,
                            toolOptions = toolOptions
                        )
                    }
                }

                WidthClass.Expanded -> Row(body) {
                    ToolRail(selection, enabled = toolsEnabled, onChoose = chooseSubTool)
                    EditCanvas(state, viewState, messages, onCanvasSize, onViewport, overlay, !cropMode, WindowInsetsSides.Bottom, Modifier.weight(1f))
                    SidePanel(toolTitle = toolTitle, toolOptions = toolOptions)
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
    }
}

/**
 * 커버 화면(Compact)에서 캔버스와 탭 줄 사이에 놓는 도구 옵션 자리. 캔버스를 가리지 않고 그 아래를 차지한다.
 * 커버 화면에는 접히는 부분이 없고, 옆쪽 시스템 바·카메라 구멍만 피한다.
 */
@Composable
private fun ToolOptionsSheet(options: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            options()
        }
    }
}

/** 편집 화면 상단 바: 뒤로, 실행 취소·다시 실행, 내보내기(아직 비활성) */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(
    onBack: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit
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
            // 내보내기는 아직 기능이 없어서, 자리만 두고 누를 수 없게 한다
            IconButton(onClick = {}, enabled = false) {
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
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val notEnoughMemory = stringResource(R.string.edit_not_enough_memory)
    LaunchedEffect(messages) {
        // 하나씩 보이고, 사라진 뒤 다음 것을 보인다
        messages.collect { message ->
            val text = when (message) {
                EditorMessage.EDIT_NOT_ENOUGH_MEMORY -> notEnoughMemory
            }
            snackbarHostState.showSnackbar(text)
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
            is EditorState.Ready -> {
                CanvasViewport(state, viewState, Modifier.fillMaxSize(), onViewport, overlay)
                // 확대·이동했을 때만 보이는 배율 표시. 누르면 화면에 맞춘다. 자르기 중에는 테두리를 가리지 않게 숨긴다.
                if (showZoomChip) CanvasZoomChip(viewState, Modifier.fillMaxSize())
                if (state.busy) {
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

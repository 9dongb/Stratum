package com.kiln.stratum.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.WorkTopBarHeight
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.topBarItem
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.layout.windowInsets
import kotlin.math.roundToInt

/**
 * 픽셀 유동화 작업 모드의 화면 상태(열려 있을 때만). [preview]는 고른 레이어만 비튼 축소본이다.
 *
 * @property size 브러시 지름(문서 픽셀)
 * @property strength 압력(0..1)
 * @property canUndo 작업 모드 안에서 한 획 되돌릴 수 있는지
 * @property changed 움직인 곳이 있는지(적용·모두 되돌리기를 켠다)
 * @property busy 적용하는 중이다
 * @property colorSpace 문서 색 공간(미리 보기를 그 꼬리표로 보인다)
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
    val colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB
)

/** 픽셀 유동화 작업 모드가 보내는 일 */
interface LiquifyActions {
    /** 한 손가락이 문서 좌표 ([x], [y])에 닿았다(한 획 시작) */
    fun begin(x: Float, y: Float)

    /** 끄는 손가락이 ([x], [y])까지 왔다 */
    fun move(x: Float, y: Float)

    /** 손가락을 뗐다 */
    fun end()

    fun setTool(tool: LiquifyTool)

    /** 브러시 지름(문서 픽셀) */
    fun setSize(size: Float)

    /** 압력(0..1) */
    fun setStrength(strength: Float)

    /** 마지막 한 획을 되돌린다(작업 모드 안에서) */
    fun undo()

    /** 모두 원래대로 */
    fun reset()

    /** 적용(실행 취소 한 단계로 기록)하고 닫는다 */
    fun apply()

    /** 바꾼 것을 버리고 닫는다 */
    fun cancel()

    object None : LiquifyActions {
        override fun begin(x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun setTool(tool: LiquifyTool) = Unit
        override fun setSize(size: Float) = Unit
        override fun setStrength(strength: Float) = Unit
        override fun undo() = Unit
        override fun reset() = Unit
        override fun apply() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 픽셀 유동화 작업 모드. 편집 화면 위를 덮는 전체 화면이다(CLAUDE.md 6장). 화면 이동이 아니라 덮개라, 닫으면 편집 화면이 그대로 있다.
 *
 * - 위: 취소(버림), 이름, 한 획 되돌리기, 모두 되돌리기, 적용(실행 취소 한 단계로 기록).
 * - 가운데: 고른 레이어를 비튼 미리 보기. 한 손가락으로 끌어 비틀고, 두 손가락으로 확대·이동한다. 브러시 크기만큼 동그라미를 보인다.
 * - 옵션: 도구(뒤틀기·오목·볼록·재구성), 크기, 압력. 커버·메인 세로는 아래, 메인 가로는 오른쪽에 둔다(접히는 선을 피함).
 * - 시스템 뒤로는 취소와 같다.
 */
@Composable
fun LiquifyMode(state: LiquifyState, actions: LiquifyActions, modifier: Modifier = Modifier) {
    BackHandler(enabled = !state.busy, onBack = actions::cancel)
    val viewState = rememberCanvasViewState(state.documentSize)
    // 크기 슬라이더를 끄는 동안 캔버스 가운데에 브러시 크기를 보인다
    val sizePreview = rememberSizePreviewState()
    Surface(modifier.fillMaxSize().testTag(EditScreenTags.LIQUIFY_MODE), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthClass = widthClassOf(maxWidth.value)
            val maxOptionsHeight = maxHeight * 0.4f
            Column(Modifier.fillMaxSize()) {
                LiquifyTopBar(state, actions)
                val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
                    LiquifyCanvas(state, viewState, actions, sizePreview.visible, canvasModifier)
                }
                if (widthClass == WidthClass.Expanded) {
                    Row(Modifier.fillMaxSize()) {
                        canvas(Modifier.weight(1f).fillMaxHeight())
                        Surface(Modifier.width(320.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            LiquifyOptions(
                                state, actions, sizePreview,
                                Modifier
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            )
                        }
                    }
                } else {
                    canvas(Modifier.weight(1f).fillMaxWidth())
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        LiquifyOptions(
                            state, actions, sizePreview,
                            Modifier
                                .heightIn(max = maxOptionsHeight)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 위쪽 막대: 취소, 이름, 한 획 되돌리기, 모두 되돌리기, 적용. 편집 화면 상단 바와 같은 높이(48dp)다. */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiquifyTopBar(state: LiquifyState, actions: LiquifyActions) {
    val barInsets = rememberTopBarInsets(WorkTopBarHeight)
    TopAppBar(
        title = { Text(stringResource(R.string.tool_liquify), Modifier.topBarItem(barInsets, "title")) },
        modifier = Modifier.testTag(EditScreenTags.LIQUIFY_TOP_BAR),
        navigationIcon = {
            IconButton(
                onClick = actions::cancel,
                enabled = !state.busy,
                modifier = Modifier.topBarItem(barInsets, "cancel").testTag(EditScreenTags.LIQUIFY_CANCEL)
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
            }
        },
        actions = {
            Row(Modifier.topBarItem(barInsets, "actions"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions::undo, enabled = state.canUndo && !state.busy, modifier = Modifier.testTag(EditScreenTags.LIQUIFY_UNDO)) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.liquify_undo))
                }
                TextButton(onClick = actions::reset, enabled = state.changed && !state.busy, modifier = Modifier.testTag(EditScreenTags.LIQUIFY_RESET)) {
                    Text(stringResource(R.string.liquify_reset))
                }
                TextButton(onClick = actions::apply, enabled = state.changed && !state.busy, modifier = Modifier.testTag(EditScreenTags.LIQUIFY_APPLY)) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        },
        expandedHeight = WorkTopBarHeight,
        windowInsets = barInsets.windowInsets(),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    )
}

/**
 * 비튼 미리 보기와 브러시 손가락 자리. 적용하는 동안 위쪽에 처리 중 막대를 보인다.
 * [showSize]면(크기 슬라이더를 끄는 중) 가운데에 브러시 크기 원을 보인다.
 */
@Composable
private fun LiquifyCanvas(state: LiquifyState, viewState: CanvasViewState, actions: LiquifyActions, showSize: Boolean, modifier: Modifier) {
    val ready = remember(state.preview, state.documentSize, state.colorSpace) {
        EditorState.Ready(state.preview, state.documentSize, colorSpace = state.colorSpace)
    }
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .testTag(EditScreenTags.LIQUIFY_CANVAS)
    ) {
        CanvasViewport(ready, viewState, Modifier.fillMaxSize()) {
            LiquifyOverlay(view = { viewState.view }, document = state.documentSize, brushSize = state.size, enabled = !state.busy, actions = actions, modifier = Modifier.fillMaxSize())
            if (showSize) {
                SizePreviewOverlay(view = { viewState.view }, document = state.documentSize, size = state.size, hardness = null, modifier = Modifier.fillMaxSize())
            }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/**
 * 유동화 브러시의 터치 자리. 한 손가락으로 끌면 획이 이어지고, 손가락 둘레에 브러시 크기 동그라미를 보인다.
 * 두 손가락 조작(확대·이동)은 부모가 먼저 읽고 소비하므로, 그러면 그 획을 거기서 끝낸다(되돌리기는 위쪽 버튼).
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
    Box(
        modifier
            .testTag(EditScreenTags.LIQUIFY_OVERLAY)
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
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                            for (point in change.historical) toDocument(point.position) { x, y -> currentActions.move(x, y) }
                            toDocument(change.position) { x, y -> currentActions.move(x, y) }
                            finger = change.position
                            if (!change.pressed) break
                            change.consume()
                        }
                    } finally {
                        finger = null
                        currentActions.end()
                    }
                }
            }
    ) {
        val at = finger
        if (at != null) {
            Canvas(Modifier.matchParentSize()) {
                val scale = view().transform(PixelSize(size.width.roundToInt(), size.height.roundToInt()), document).scaleX.toFloat()
                val radius = brushSize / 2f * scale
                drawCircle(Color.White, radius, at, style = Stroke(width = 2.dp.toPx()))
                drawCircle(Color.Black, radius + 1.dp.toPx(), at, style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
}

/** 옵션: 도구, 크기(끄는 동안 [sizePreview]가 캔버스에 크기를 보임), 압력, 안내 */
@Composable
private fun LiquifyOptions(state: LiquifyState, actions: LiquifyActions, sizePreview: SizePreviewState, modifier: Modifier) {
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.LIQUIFY_OPTIONS), gap = 8.dp, fillHeight = false) {
        LiquifyToolRow(state.tool, enabled = !state.busy, onTool = actions::setTool)
        SizeSlider(state.size, actions::setSize, EditScreenTags.LIQUIFY_SIZE, sizePreview)
        OptionSlider(
            label = stringResource(R.string.liquify_strength),
            shown = stringResource(R.string.layer_percent, (state.strength * 100).roundToInt()),
            fraction = state.strength,
            onFraction = { actions.setStrength(((it * 100).roundToInt() / 100f).coerceAtLeast(0.01f)) },
            tag = EditScreenTags.LIQUIFY_STRENGTH
        )
        Text(
            stringResource(R.string.liquify_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** 도구 줄: 뒤틀기·오목·볼록·재구성 */
@Composable
private fun LiquifyToolRow(tool: LiquifyTool, enabled: Boolean, onTool: (LiquifyTool) -> Unit) {
    val tools = LiquifyTool.entries
    SingleChoiceSegmentedButtonRow {
        tools.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = entry == tool,
                onClick = { onTool(entry) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, tools.size),
                icon = {},
                modifier = Modifier.testTag(EditScreenTags.liquifyTool(entry))
            ) {
                Text(
                    stringResource(
                        when (entry) {
                            LiquifyTool.PUSH -> R.string.liquify_push
                            LiquifyTool.PUCKER -> R.string.liquify_pucker
                            LiquifyTool.BLOAT -> R.string.liquify_bloat
                            LiquifyTool.RECONSTRUCT -> R.string.liquify_reconstruct
                        }
                    ),
                    modifier = Modifier.width(IntrinsicSize.Max),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

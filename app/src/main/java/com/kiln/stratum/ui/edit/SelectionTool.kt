package com.kiln.stratum.ui.edit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.platform.MAX_FEATHER
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.ColorRegion
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionPoint
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.components.StratumSwitch
import com.kiln.stratum.ui.components.ToolActionButton
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 선택 도구 옵션이 쓰는 값과 할 일.
 *
 * @property hasSelection 지금 고른 곳이 있는지. 해제·반전·지우기·복사·잘라내기·AI 채우기는 고른 곳이 있을 때만 켠다.
 * @property busy 편집을 처리하는 중이면 동작 버튼을 막는다.
 */
@Stable
class SelectionControls(
    val options: SelectionOptions,
    val onOptions: (SelectionOptions) -> Unit,
    val hasSelection: Boolean,
    val busy: Boolean,
    val actions: SelectionActions
)

/**
 * 선택 도구 옵션: 하위 도구(사각형·원형·올가미·자동 선택·AI 선택), 합치는 방법(새로·더하기·빼기·교차),
 * 페더(모양) 또는 허용치·이어진 곳만(자동 선택), 동작 버튼(모두·해제·반전·지우기·복사·잘라내기·AI 채우기), 안내 한 줄.
 * AI 선택은 경계가 이미 부드러워 페더가 없다.
 * 설정은 바꾸면 다음 선택부터 쓴다(기록하지 않는다). 동작은 누르면 바로 하고 실행 취소 한 단계로 남는다.
 */
@Composable
fun SelectionToolOptions(mode: SubTool, controls: SelectionControls, onChoose: (SubTool) -> Unit, modifier: Modifier = Modifier) {
    val options = controls.options
    // 패널 안에서 내용만큼만 차지하고, 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(다른 도구 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.SELECTION_OPTIONS), gap = 8.dp, fillHeight = false) {
        SubToolSegments(EditTool.Select.subTools, mode, onChoose)
        SelectionModeRow(options.mode) { controls.onOptions(options.copy(mode = it)) }
        if (mode == SubTool.SELECT_WAND) {
            OptionSlider(
                label = stringResource(R.string.selection_tolerance),
                shown = options.tolerance.toString(),
                fraction = options.tolerance / ColorRegion.MAX_TOLERANCE.toFloat(),
                onFraction = { controls.onOptions(options.copy(tolerance = (it * ColorRegion.MAX_TOLERANCE).roundToInt())) },
                tag = EditScreenTags.SELECTION_TOLERANCE
            )
            ContiguousRow(options.contiguous) { controls.onOptions(options.copy(contiguous = it)) }
        } else if (mode != SubTool.SELECT_AI) {
            OptionSlider(
                label = stringResource(R.string.selection_feather),
                shown = stringResource(R.string.selection_feather_value, options.feather.roundToInt()),
                fraction = featherToFraction(options.feather),
                onFraction = { controls.onOptions(options.copy(feather = fractionToFeather(it))) },
                tag = EditScreenTags.SELECTION_FEATHER
            )
        }
        SelectionActionRow(controls)
        Text(
            text = stringResource(
                when (mode) {
                    SubTool.SELECT_WAND -> R.string.selection_hint_wand
                    SubTool.SELECT_AI -> R.string.selection_hint_ai
                    SubTool.SELECT_LASSO -> R.string.selection_hint_lasso
                    else -> R.string.selection_hint_drag
                }
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            color = StratumTheme.colors.textDescription,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 하위 도구 줄(모두 옵션이 이어지는 하위 도구일 때). 길게 누르는 메뉴(CLAUDE.md 6장)를 모르는 사람도 찾을 수 있게 옵션 위쪽에 둔다.
 */
@Composable
fun SubToolSegments(subTools: List<SubTool>, mode: SubTool, onChoose: (SubTool) -> Unit) {
    StratumSegmentedRow {
        subTools.forEachIndexed { index, subTool ->
            StratumSegment(
                selected = subTool == mode,
                onClick = { onChoose(subTool) },
                index = index,
                count = subTools.size,
                modifier = Modifier.testTag(EditScreenTags.subToolOption(subTool))
            ) {
                // 한국어는 글자마다 줄을 바꿀 수 있어, 이름 전체 폭(최대 고유 폭)을 쓰게 한다(자르기·변형 하위 도구 줄과 같다)
                Text(stringResource(subTool.shortLabel), modifier = Modifier.width(IntrinsicSize.Max), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** 새로 그린 모양을 앞 선택 영역과 합치는 방법 */
@Composable
private fun SelectionModeRow(mode: SelectionMode, onMode: (SelectionMode) -> Unit) {
    val modes = SelectionMode.entries
    StratumSegmentedRow {
        modes.forEachIndexed { index, entry ->
            StratumSegment(
                selected = entry == mode,
                onClick = { onMode(entry) },
                index = index,
                count = modes.size,
                modifier = Modifier.testTag(EditScreenTags.selectionMode(entry))
            ) {
                Text(
                    stringResource(selectionModeLabel(entry)),
                    modifier = Modifier.width(IntrinsicSize.Max),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** 합치는 방법의 이름 */
internal fun selectionModeLabel(mode: SelectionMode): Int =
    when (mode) {
        SelectionMode.REPLACE -> R.string.selection_mode_replace
        SelectionMode.ADD -> R.string.selection_mode_add
        SelectionMode.SUBTRACT -> R.string.selection_mode_subtract
        SelectionMode.INTERSECT -> R.string.selection_mode_intersect
    }

/** 자동 선택의 '이어진 곳만' 스위치 줄. 줄 전체를 누를 수 있다(48dp). */
@Composable
internal fun ContiguousRow(contiguous: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = contiguous, role = Role.Switch, onValueChange = onChange)
            .testTag(EditScreenTags.SELECTION_CONTIGUOUS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.selection_contiguous),
            modifier = Modifier.weight(1f),
            color = StratumTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(16.dp))
        StratumSwitch(checked = contiguous, onCheckedChange = null)
    }
}

/** 동작 버튼: 모두 선택은 늘, 나머지는 고른 곳이 있을 때만 켠다. 폭이 모자라면 다음 줄로 내려간다. */
@Composable
private fun SelectionActionRow(controls: SelectionControls) {
    val ready = !controls.busy
    val selected = ready && controls.hasSelection
    val actions = controls.actions
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton(R.drawable.ic_select_all, R.string.selection_all, R.string.selection_all_description, ready, EditScreenTags.SELECTION_ALL, actions::all)
        ActionButton(R.drawable.ic_deselect, R.string.selection_none, null, selected, EditScreenTags.SELECTION_NONE, actions::none)
        ActionButton(R.drawable.ic_flip_to_front, R.string.selection_invert, null, selected, EditScreenTags.SELECTION_INVERT, actions::invert)
        ActionButton(R.drawable.ic_delete, R.string.selection_delete, R.string.selection_delete_description, selected, EditScreenTags.SELECTION_DELETE, actions::delete)
        ActionButton(R.drawable.ic_content_copy, R.string.selection_copy, R.string.selection_copy_description, selected, EditScreenTags.SELECTION_COPY) {
            actions.copyToLayer(cut = false)
        }
        ActionButton(R.drawable.ic_content_cut, R.string.selection_cut, R.string.selection_cut_description, selected, EditScreenTags.SELECTION_CUT) {
            actions.copyToLayer(cut = true)
        }
        ActionButton(R.drawable.ic_auto_awesome, R.string.selection_ai_fill, R.string.selection_ai_fill_description, selected, EditScreenTags.SELECTION_AI_FILL, actions::aiFill)
    }
}

/** 아이콘 아래에 짧은 이름이 있는 버튼. [description]이 있으면 화면 읽기 기능은 그 긴 이름으로 읽는다. 누르는 영역은 48dp 이상이다. */
@Composable
private fun ActionButton(icon: Int, label: Int, description: Int?, enabled: Boolean, tag: String, onClick: () -> Unit) {
    ToolActionButton(
        icon = icon,
        label = stringResource(label),
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        enabled = enabled,
        description = description?.let { stringResource(it) }
    )
}

/** 페더 슬라이더 자리(0..1)와 픽셀. 작은 값을 세밀하게 고르게 제곱 눈금이다. */
internal fun featherToFraction(feather: Float): Float = sqrt((feather / MAX_FEATHER).coerceIn(0f, 1f))

internal fun fractionToFeather(fraction: Float): Float = (fraction.coerceIn(0f, 1f).let { it * it } * MAX_FEATHER).roundToInt().toFloat()

/**
 * 선택 도구를 고르면 캔버스 위에 겹치는 터치 자리.
 *
 * - 사각형·원형: 한 손가락으로 끌면 누른 곳과 지금 곳을 꼭짓점으로 하는 사각형(에 맞는 타원)을 고른다. 꼭짓점은 픽셀 경계에 맞춘다.
 * - 올가미: 끄는 손가락 길을 이어 닫은 모양을 고른다.
 * - 자동 선택: 누른 곳의 색과 비슷한 곳을 고른다(끌면 무시한다).
 * - AI 선택: 누른 물체를 AI가 모양대로 고른다(끌면 무시한다).
 * - 끌지 않고 누르기만 하면(사각형·원형·올가미), 새로 고르기일 때 선택을 푼다(포토샵과 같다).
 *
 * 끄는 동안 모양을 점선으로 보인다. 두 손가락 조작(확대·이동)은 부모가 먼저 읽고 소비하므로, 그러면 끄던 모양을 버린다.
 * 두 번째 손가락이 닿아도 버린다(브러시와 같다).
 */
@Composable
fun SelectionOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    mode: SubTool,
    options: SelectionOptions,
    actions: SelectionActions,
    modifier: Modifier = Modifier
) {
    val currentActions by rememberUpdatedState(actions)
    val currentOptions by rememberUpdatedState(options)
    // 끄는 동안의 손가락 길(화면 좌표). 비어 있으면 아무것도 그리지 않는다.
    var drag by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        modifier
            .testTag(EditScreenTags.SELECTION_OVERLAY)
            .pointerInput(document, mode) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val area = PixelSize(size.width, size.height)
                    fun toDocument(position: Offset): SelectionPoint {
                        val transform = view().transform(area, document)
                        return SelectionPoint(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
                    }
                    down.consume()
                    val path = arrayListOf(down.position)
                    var dragged = false
                    var finished = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        // 두 손가락 조작이 가져갔거나 두 번째 손가락이 닿으면 버린다
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                        if (!dragged && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (dragged && mode != SubTool.SELECT_WAND && mode != SubTool.SELECT_AI) {
                            for (point in change.historical) addPoint(path, point.position)
                            addPoint(path, change.position)
                            drag = path.toList()
                        }
                        if (!change.pressed) {
                            finished = true
                            break
                        }
                        change.consume()
                    }
                    drag = emptyList()
                    if (!finished) return@awaitEachGesture
                    val start = toDocument(path.first())
                    val end = toDocument(path.last())
                    when {
                        mode == SubTool.SELECT_WAND -> if (!dragged) currentActions.similar(start.x, start.y)
                        mode == SubTool.SELECT_AI -> if (!dragged) currentActions.objectAt(start.x, start.y)
                        !dragged -> if (currentOptions.mode == SelectionMode.REPLACE) currentActions.none()
                        mode == SubTool.SELECT_LASSO -> currentActions.shape(SelectionShape.Lasso(path.map(::toDocument)))
                        else -> {
                            // 픽셀 경계에 맞춰 경계가 또렷하게 고른다
                            val left = minOf(start.x, end.x).roundToInt().toFloat()
                            val top = minOf(start.y, end.y).roundToInt().toFloat()
                            val right = maxOf(start.x, end.x).roundToInt().toFloat()
                            val bottom = maxOf(start.y, end.y).roundToInt().toFloat()
                            currentActions.shape(
                                if (mode == SubTool.SELECT_ELLIPSE) SelectionShape.Ellipse(left, top, right, bottom)
                                else SelectionShape.Rectangle(left, top, right, bottom)
                            )
                        }
                    }
                }
            }
    ) {
        val points = drag
        if (points.size >= 2) {
            Canvas(Modifier.matchParentSize()) {
                val outline = Path().apply {
                    when (mode) {
                        SubTool.SELECT_LASSO -> {
                            moveTo(points.first().x, points.first().y)
                            for (point in points.drop(1)) lineTo(point.x, point.y)
                        }
                        else -> {
                            val first = points.first()
                            val last = points.last()
                            val rect = Rect(minOf(first.x, last.x), minOf(first.y, last.y), maxOf(first.x, last.x), maxOf(first.y, last.y))
                            if (mode == SubTool.SELECT_ELLIPSE) addOval(rect) else addRect(rect)
                        }
                    }
                }
                drawAnts(outline, phase = 0f)
            }
        }
    }
}

/** 손가락 길에 점을 더한다. 바로 앞 점과 2픽셀보다 가까우면 건너뛴다(점이 너무 많아지지 않게). */
private fun addPoint(path: MutableList<Offset>, point: Offset) {
    if ((point - path.last()).getDistance() >= 2f) path += point
}

/**
 * 선택 영역 윤곽선(개미 행렬)을 보기 상태([view])대로 그린다. 흰 선 위에 검은 점선이 흘러가 어떤 사진 위에서도 보인다.
 * 확대해 볼 때 더 정확한 부분 윤곽선([detail])이 있으면 그 자리 안은 그것으로, 밖은 전체 윤곽선([outline])으로 그린다.
 * 터치는 받지 않는다. 보이지 않을 때(고른 곳이 없을 때)는 그리지 않는다.
 */
@Composable
fun SelectionAnts(
    outline: SelectionOutline?,
    detail: SelectionOutline?,
    view: () -> CanvasView,
    document: PixelSize,
    modifier: Modifier = Modifier
) {
    if (outline == null) return
    val whole = remember(outline) { pathOf(outline) }
    val part = remember(detail) { detail?.let { pathOf(it) } }
    val transition = rememberInfiniteTransition(label = "ants")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing), RepeatMode.Restart),
        label = "antsPhase"
    )
    Canvas(modifier.testTag(EditScreenTags.SELECTION_OUTLINE)) {
        val transform = view().transform(PixelSize(size.width.roundToInt(), size.height.roundToInt()), document)
        val matrix = android.graphics.Matrix().apply {
            setScale(transform.scaleX.toFloat(), transform.scaleY.toFloat())
            postTranslate(transform.originX.toFloat(), transform.originY.toFloat())
        }
        val region = detail?.region
        if (part != null && region != null) {
            val left = transform.toScreenX(region.left.toDouble()).toFloat()
            val top = transform.toScreenY(region.top.toDouble()).toFloat()
            val right = transform.toScreenX(region.right.toDouble()).toFloat()
            val bottom = transform.toScreenY(region.bottom.toDouble()).toFloat()
            clipRect(left, top, right, bottom) { drawAnts(transformed(part, matrix), phase) }
            clipRect(left, top, right, bottom, clipOp = ClipOp.Difference) { drawAnts(transformed(whole, matrix), phase) }
        } else {
            drawAnts(transformed(whole, matrix), phase)
        }
    }
}

/** 윤곽선(문서 좌표)의 안드로이드 경로 */
private fun pathOf(outline: SelectionOutline): android.graphics.Path =
    android.graphics.Path().apply {
        for (line in outline.lines) {
            if (line.size < 4) continue
            moveTo(line[0], line[1])
            for (i in 2 until line.size step 2) lineTo(line[i], line[i + 1])
        }
    }

/** 문서 좌표 경로를 화면 좌표로 옮긴 경로(선 굵기·점선 길이는 화면에서 같게 하려고 경로를 옮긴다) */
private fun transformed(path: android.graphics.Path, matrix: android.graphics.Matrix): Path =
    android.graphics.Path().also { path.transform(matrix, it) }.asComposePath()

/** 흰 선 위에 검은 점선(개미 행렬). [phase]는 0..1이고 점선 한 칸만큼 흘러간다. */
private fun DrawScope.drawAnts(path: Path, phase: Float) {
    val width = 1.dp.toPx()
    val dash = 4.dp.toPx()
    drawPath(path, CanvasOverlayColors.Light, style = Stroke(width = width))
    drawPath(
        path,
        CanvasOverlayColors.Dark,
        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), phase * dash * 2))
    )
}

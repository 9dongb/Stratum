package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.ui.components.StratumSlider
import com.kiln.stratum.ui.layout.HingeAvoidingColumn

/** 보정 패널이 엔진 쪽(ViewModel)에 보내는 일 */
interface AdjustActions {

    /** 끄는 동안 미리 본다. [change]는 지금 값(미리 보는 값, 없으면 기록된 값)을 받아 새 값을 돌려준다. */
    fun preview(change: (Adjustments) -> Adjustments)

    /** 미리 보던 값을 기록한다(실행 취소 한 단계, 놓을 때). */
    fun commit()

    /** 지금 값에 [change]를 걸어 바로 기록한다(초기화·점 지우기 버튼). */
    fun record(change: (Adjustments) -> Adjustments)

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : AdjustActions {
        override fun preview(change: (Adjustments) -> Adjustments) = Unit
        override fun commit() = Unit
        override fun record(change: (Adjustments) -> Adjustments) = Unit
    }
}

/**
 * 보정 패널이 쓰는 값과 할 일. 편집 화면이 고른 레이어의 값으로 만든다.
 *
 * @param committed 고른 레이어에 기록된 값
 * @param live 끄는 동안의 값(없으면 null). 값을 읽는 패널만 다시 그려지도록 함수로 받는다.
 * @property enabled 바꿀 수 있는지. 자르기 중에는 막는다(기록하면 자르기 테두리가 처음으로 돌아가므로).
 */
@Stable
class AdjustControls(
    private val committed: Adjustments,
    private val live: () -> Adjustments?,
    val enabled: Boolean,
    val actions: AdjustActions
) {
    /** 보일 값: 끄는 동안의 값, 없으면 기록된 값 */
    val adjustments: Adjustments
        get() = live() ?: committed
}

/** 슬라이더 줄의 이름 칸 폭. 영어 'Temperature'가 한 줄에 들어간다. */
private val NameWidth = 88.dp

/** 슬라이더 줄의 숫자 칸 폭. '-100', '+1.25'가 들어간다. */
private val ValueWidth = 48.dp

/** 커버 화면 곡선 그래프의 높이. 캔버스를 너무 가리지 않게 낮게 둔다. */
private val CompactCurveHeight = 168.dp

/** 메인 가로 오른쪽 패널 곡선 그래프(제목 포함)의 높이 */
private val ListCurveHeight = 232.dp

/** 곡선 점의 반지름과, 점을 잡을 수 있는 거리(터치 영역 48dp의 절반) */
private val PointRadius = 6.dp
private val PointTouchRadius = 24.dp

/**
 * 커버 화면(Compact)의 보정 옵션. 위에서 항목(칩)을 고르고, 그 아래 슬라이더 하나 또는 곡선 편집기로 바꾼다.
 * 한 손으로 쓰는 빠른 보정이라 한 번에 하나만 보인다(기획서 '화면별 UI 전략'). '모두 초기화'는 칩 줄 끝에 고정한다.
 *
 * @param item 고른 항목. 접기·펼치기에도 남도록 편집 화면이 들고 있다.
 */
@Composable
fun AdjustCompact(controls: AdjustControls, item: AdjustItem, onItem: (AdjustItem) -> Unit, modifier: Modifier = Modifier) {
    CommitOnDispose(controls.actions)
    val adjustments = controls.adjustments
    Column(modifier.testTag(EditScreenTags.ADJUST_PANEL), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (entry in AdjustItem.entries) {
                    AdjustChip(entry, selected = entry == item, changed = entry.isChanged(adjustments), enabled = controls.enabled) {
                        onItem(entry)
                    }
                }
            }
            ResetAllButton(controls)
        }
        if (item.isSlider) {
            AdjustSliderRow(item, controls)
        } else {
            CurveBlock(controls, Modifier.fillMaxWidth().height(CompactCurveHeight))
        }
    }
}

/**
 * 메인 화면의 보정 옵션: 슬라이더를 모두 늘어놓고 곡선 편집기를 함께 보인다.
 * 메인 세로 아래 패널은 폭이 넉넉해 두 칸(왼쪽 슬라이더, 오른쪽 곡선)으로, 메인 가로 오른쪽 패널은 한 칸으로 쌓는다.
 * 슬라이더 칸은 위아래로 밀어 본다. 곡선 그래프 위에서 끌면 곡선이 바뀌므로, 밀 때는 슬라이더 쪽을 끈다.
 *
 * 두 칸일 때는 가로로 지나가는 힌지의 띠에 걸리면 띠 아래로 비켜 놓는다(아래 패널이 힌지보다 아래라 보통은 걸리지 않는다).
 */
@Composable
fun AdjustList(controls: AdjustControls, twoColumns: Boolean, modifier: Modifier = Modifier) {
    CommitOnDispose(controls.actions)
    if (twoColumns) {
        HingeAvoidingColumn(modifier.testTag(EditScreenTags.ADJUST_PANEL), fillHeight = false) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 곡선 칸은 그래프가 정사각형에 가깝게, 슬라이더 칸이 너무 좁아지지 않게 정한다
                val curveWidth = min(maxWidth * 0.42f, maxHeight + 48.dp)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        for (item in AdjustItem.sliders) AdjustSliderRow(item, controls)
                    }
                    CurveSection(controls, Modifier.width(curveWidth).fillMaxHeight().padding(bottom = 8.dp))
                }
            }
        }
    } else {
        Column(modifier.testTag(EditScreenTags.ADJUST_PANEL).verticalScroll(rememberScrollState())) {
            for (item in AdjustItem.sliders) AdjustSliderRow(item, controls)
            CurveSection(controls, Modifier.fillMaxWidth().height(ListCurveHeight).padding(bottom = 8.dp))
        }
    }
}

/** 고른 레이어의 보정을 모두 처음으로 돌린다(실행 취소 한 단계). 바꾼 것이 없으면 누를 수 없다. */
@Composable
fun ResetAllButton(controls: AdjustControls, modifier: Modifier = Modifier) {
    TextButton(
        onClick = { controls.actions.record { Adjustments.NONE } },
        enabled = controls.enabled && !controls.adjustments.isIdentity,
        modifier = modifier.testTag(EditScreenTags.ADJUST_RESET_ALL)
    ) {
        Text(stringResource(R.string.adjust_reset_all), maxLines = 1, softWrap = false)
    }
}

/**
 * 끄는 도중에 패널이 사라지면(접기·펼치기로 배치가 바뀜) 슬라이더가 '놓음'을 알리지 못한다.
 * 그때는 놓은 것으로 보고 끌던 값을 기록한다. 끄는 중이 아니면 아무 일도 없다.
 */
@Composable
private fun CommitOnDispose(actions: AdjustActions) {
    DisposableEffect(actions) {
        onDispose { actions.commit() }
    }
}

/** 커버 화면의 항목 칩. 바꾼 항목은 이름 옆에 작은 점을 찍는다. */
@Composable
private fun AdjustChip(item: AdjustItem, selected: Boolean, changed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(item.label), maxLines = 1, softWrap = false) },
        trailingIcon = if (changed) {
            { Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
        } else {
            null
        },
        modifier = Modifier.testTag(EditScreenTags.adjustChip(item))
    )
}

/**
 * 슬라이더 한 줄: 이름, 슬라이더, 숫자. 끄는 동안 미리 보고, 놓으면 기록한다.
 * 이름을 두 번 누르면 그 항목만 0으로 돌린다(라이트룸과 같은 방식). 화면 읽기 기능에서는 '초기화' 동작으로 한다.
 */
@Composable
private fun AdjustSliderRow(item: AdjustItem, controls: AdjustControls) {
    val value = item.valueIn(controls.adjustments)
    val name = stringResource(item.label)
    val resetLabel = stringResource(R.string.adjust_reset_item, name)
    val shown = item.format(value, LocalConfiguration.current.locales[0])
    val actions = controls.actions
    val reset = { actions.record { item.resetIn(it) } }
    val textColor = if (controls.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(EditScreenTags.adjustSlider(item)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            modifier = Modifier
                .width(NameWidth)
                // 커버 화면은 같은 줄에서 항목만 바뀌므로, 항목이 바뀌면 다시 시작해 그 항목을 초기화한다
                .pointerInput(controls.enabled, item) {
                    if (controls.enabled) detectTapGestures(onDoubleTap = { reset() })
                },
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        StratumSlider(
            value = value,
            onValueChange = { newValue -> actions.preview { item.applyTo(it, newValue) } },
            onValueChangeFinished = actions::commit,
            valueRange = item.min..item.max,
            enabled = controls.enabled,
            centered = true,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = name
                    stateDescription = shown
                    if (controls.enabled) {
                        customActions = listOf(
                            CustomAccessibilityAction(resetLabel) {
                                reset()
                                true
                            }
                        )
                    }
                }
        )
        Text(
            text = shown,
            modifier = Modifier.width(ValueWidth),
            color = textColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** 제목('곡선')과 곡선 편집기. 메인 화면에서 슬라이더와 함께 놓는다. */
@Composable
private fun CurveSection(controls: AdjustControls, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.adjust_curve),
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )
        CurveBlock(controls, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * 곡선 편집기와 그 옆의 버튼(곡선 초기화, 고른 점 지우기).
 * 고른 점은 화면만의 상태다. 곡선이 바뀌어(실행 취소 등) 그 점이 없어지면 고르지 않은 것으로 본다.
 */
@Composable
private fun CurveBlock(controls: AdjustControls, modifier: Modifier = Modifier) {
    val curve = controls.adjustments.curve
    var picked by remember { mutableIntStateOf(-1) }
    val selected = picked.takeIf { it in curve.points.indices } ?: -1
    val actions = controls.actions
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ToneCurveEditor(
            curve = curve,
            selected = selected,
            enabled = controls.enabled,
            onSelect = { picked = it },
            onChange = { changed -> actions.preview { it.copy(curve = changed) } },
            onFinish = actions::commit,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        Column {
            IconButton(
                onClick = {
                    actions.record { it.copy(curve = ToneCurve.IDENTITY) }
                    picked = -1
                },
                enabled = controls.enabled && !curve.isIdentity,
                modifier = Modifier.testTag(EditScreenTags.CURVE_RESET)
            ) {
                Icon(painterResource(R.drawable.ic_restart_alt), contentDescription = stringResource(R.string.adjust_curve_reset))
            }
            IconButton(
                onClick = {
                    actions.record { current -> CurveEditing.removePoint(current.curve, selected)?.let { current.copy(curve = it) } ?: current }
                    picked = -1
                },
                enabled = controls.enabled && CurveEditing.canRemove(curve, selected),
                modifier = Modifier.testTag(EditScreenTags.CURVE_DELETE_POINT)
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.adjust_curve_delete_point))
            }
        }
    }
}

/**
 * 곡선 그래프. 가로는 입력(왼쪽 어두움 → 오른쪽 밝음), 세로는 출력이다.
 *
 * - 점을 눌러 끌면 그 점이 손가락을 따라 움직인다(처음 누른 곳과의 차이만큼). 점은 이웃 점을 넘지 않는다.
 * - 점이 없는 곳을 끌면 그 자리 곡선 위에 점을 더해 끈다. 톡 누르면 곡선 위에 점만 더한다.
 * - 끄는 동안 미리 보고([onChange]), 놓으면 기록한다([onFinish]). 점을 고르기만 하면 기록하지 않는다.
 *
 * 점이 그래프 밖으로 잘리지 않게 테두리 안쪽에 여백을 두고 그린다.
 */
@Composable
private fun ToneCurveEditor(
    curve: ToneCurve,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onChange: (ToneCurve) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.adjust_curve_editor, curve.points.size)
    // 제스처는 한 번 시작해 계속 돌므로, 그 안에서는 늘 지금 값을 읽는다
    val currentCurve by rememberUpdatedState(curve)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinish by rememberUpdatedState(onFinish)
    val alpha = if (enabled) 1f else 0.38f
    Canvas(
        modifier
            .testTag(EditScreenTags.CURVE_EDITOR)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val inset = (PointRadius + 2.dp).toPx()
                val touchRadius = PointTouchRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val graphWidth = size.width - inset * 2
                    val graphHeight = size.height - inset * 2
                    if (graphWidth <= 0f || graphHeight <= 0f) return@awaitEachGesture
                    // 그래프 안쪽 좌표(픽셀)와 곡선 값(0..1) 사이
                    fun inputAt(x: Float) = (x - inset) / graphWidth
                    fun outputAt(y: Float) = 1f - (y - inset) / graphHeight
                    var working = currentCurve
                    var index = CurveEditing.hitTest(working, down.position.x - inset, down.position.y - inset, graphWidth, graphHeight, touchRadius)
                    if (index >= 0) currentOnSelect(index)
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop == null) {
                        // 움직이지 않고 뗐으면(톡) 점이 없는 곳에 점을 더한다. 더할 수 없으면 입력이 가장 가까운 점을 고른다.
                        // 다른 제스처가 가져갔으면 아무것도 하지 않는다.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (index < 0 && up != null && up.changedToUpIgnoreConsumed() && !up.isConsumed) {
                            val added = CurveEditing.addPoint(working, inputAt(down.position.x))
                            if (added != null) {
                                currentOnSelect(added.second)
                                currentOnChange(added.first)
                                currentOnFinish()
                            } else {
                                currentOnSelect(CurveEditing.nearestByInput(working, inputAt(down.position.x)))
                            }
                        }
                        return@awaitEachGesture
                    }
                    if (index < 0) {
                        val added = CurveEditing.addPoint(working, inputAt(down.position.x))
                        if (added != null) {
                            working = added.first
                            index = added.second
                        } else {
                            index = CurveEditing.nearestByInput(working, inputAt(down.position.x))
                        }
                        currentOnSelect(index)
                    }
                    // 누른 곳과의 차이만큼 옮긴다. 점을 조금 비껴 눌러도 점이 손가락 밑으로 튀지 않는다.
                    val start = working.points[index]
                    val startX = inputAt(down.position.x)
                    val startY = outputAt(down.position.y)
                    fun moveTo(x: Float, y: Float) {
                        working = CurveEditing.movePoint(working, index, start.x + inputAt(x) - startX, start.y + outputAt(y) - startY)
                        currentOnChange(working)
                    }
                    moveTo(slop.position.x, slop.position.y)
                    drag(slop.id) { change ->
                        change.consume()
                        moveTo(change.position.x, change.position.y)
                    }
                    currentOnFinish()
                }
            }
    ) {
        val inset = (PointRadius + 2.dp).toPx()
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        if (width <= 0f || height <= 0f) return@Canvas
        fun pointAt(x: Float, y: Float) = Offset(inset + x * width, inset + (1f - y) * height)
        val grid = colors.outlineVariant.copy(alpha = alpha)
        val line = colors.primary.copy(alpha = alpha)
        // 바탕과 4칸 격자, 그대로인 대각선
        drawRect(colors.surfaceContainerHighest, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(width, height))
        for (step in 1..3) {
            val x = inset + width * step / 4
            val y = inset + height * step / 4
            drawLine(grid, Offset(x, inset), Offset(x, inset + height))
            drawLine(grid, Offset(inset, y), Offset(inset + width, y))
        }
        drawLine(grid, pointAt(0f, 0f), pointAt(1f, 1f), strokeWidth = 1.dp.toPx())
        // 곡선(그래프 폭 4픽셀마다 한 번 계산)
        val steps = (width / 4f).toInt().coerceAtLeast(16)
        val path = Path()
        for (step in 0..steps) {
            val x = step / steps.toFloat()
            val point = pointAt(x, curve.valueAt(x))
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        // 조정점. 고른 점은 채워 그린다.
        val radius = PointRadius.toPx()
        curve.points.forEachIndexed { index, point ->
            val center = pointAt(point.x, point.y)
            if (index == selected) {
                drawCircle(line, radius + 1.dp.toPx(), center)
            } else {
                drawCircle(colors.surfaceContainerHighest, radius, center)
                drawCircle(line, radius, center, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

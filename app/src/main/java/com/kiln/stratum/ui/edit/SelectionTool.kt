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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.engine.platform.MAX_FEATHER
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionPoint
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.ui.components.StratumSwitch
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 선택 도구 옵션이 쓰는 값과 할 일.
 *
 * @property hasSelection 지금 고른 곳이 있는지. 해제·반전·지우기·복사·잘라내기·AI 채우기는 고른 곳이 있을 때만 켠다.
 * @property busy 편집을 처리하는 중이면 동작 버튼을 막는다.
 * @property textureReady 보이는 문서가 가장 마지막 AI 채우기의 결과 그대로인지. 질감 향상은 그때만 켠다.
 */
@Stable
class SelectionControls(
    val options: SelectionOptions,
    val onOptions: (SelectionOptions) -> Unit,
    val hasSelection: Boolean,
    val busy: Boolean,
    val actions: SelectionActions,
    val textureReady: Boolean = false
)

/** 합치는 방법의 이름 */
internal fun selectionModeLabel(mode: SelectionMode): Int =
    when (mode) {
        SelectionMode.REPLACE -> R.string.selection_mode_replace
        SelectionMode.ADD -> R.string.selection_mode_add
        SelectionMode.SUBTRACT -> R.string.selection_mode_subtract
        SelectionMode.INTERSECT -> R.string.selection_mode_intersect
    }

/** 합치는 방법의 아이콘(메인 화면 띠, 사용자 피드백 10차 1). 포토샵처럼 사각형 모양이고, Material에 없는 모양이라 직접 그렸다(11차 2). */
internal fun selectionModeIcon(mode: SelectionMode): Int =
    when (mode) {
        SelectionMode.REPLACE -> R.drawable.ic_selection_new
        SelectionMode.ADD -> R.drawable.ic_selection_add
        SelectionMode.SUBTRACT -> R.drawable.ic_selection_subtract
        SelectionMode.INTERSECT -> R.drawable.ic_selection_intersect
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
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.IDENTITY
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
        // 자르기·회전 화면에서 돌려 보이는 중이면 보기는 돌린 모습의 문서 기준이고, 윤곽선(원래 방향 좌표)을 먼저 그 방향으로 돌린다
        val transform = view().transform(PixelSize(size.width.roundToInt(), size.height.roundToInt()), orientation.targetSize(document))
        val width = document.width.toDouble()
        val height = document.height.toDouble()
        val (ox, oy) = orientation.mapPoint(0.0, 0.0, width, height)
        val (ux, uy) = orientation.mapPoint(1.0, 0.0, width, height)
        val (vx, vy) = orientation.mapPoint(0.0, 1.0, width, height)
        val matrix = android.graphics.Matrix().apply {
            setValues(floatArrayOf((ux - ox).toFloat(), (vx - ox).toFloat(), ox.toFloat(), (uy - oy).toFloat(), (vy - oy).toFloat(), oy.toFloat(), 0f, 0f, 1f))
            postScale(transform.scaleX.toFloat(), transform.scaleY.toFloat())
            postTranslate(transform.originX.toFloat(), transform.originY.toFloat())
        }
        val region = detail?.region
        if (part != null && region != null) {
            val (ax, ay) = orientation.mapPoint(region.left.toDouble(), region.top.toDouble(), width, height)
            val (bx, by) = orientation.mapPoint(region.right.toDouble(), region.bottom.toDouble(), width, height)
            val left = transform.toScreenX(minOf(ax, bx)).toFloat()
            val top = transform.toScreenY(minOf(ay, by)).toFloat()
            val right = transform.toScreenX(maxOf(ax, bx)).toFloat()
            val bottom = transform.toScreenY(maxOf(ay, by)).toFloat()
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

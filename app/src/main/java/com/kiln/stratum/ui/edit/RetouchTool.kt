package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.editLongPressMillis
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import kotlin.math.roundToInt

/**
 * 복제 도장·복구 브러시가 가져올 곳. [x]·[y]는 표시가 놓인 자리(문서 좌표)로, 손을 뗀 뒤에도 늘 보인다(사용자 요청).
 * - 풀린 상태(기본, 포토샵의 '정렬'): 정한 뒤 처음 칠할 때 [offset](칠한 곳 - 가져올 곳)이 정해지고, 그 뒤의 획은 같은 간격으로 가져온다.
 *   획이 끝나면 표시는 그 획이 마지막으로 가져온 자리로 옮겨 간다.
 * - [locked](표시를 꾹 누름): 새 획마다 표시된 자리에서 다시 가져온다(포토샵 '정렬' 끔, 획 안에서는 붓을 따라감). 표시는 그 자리에 남는다.
 * 가져올 곳을 다시 정하거나 표시를 끌어 옮기면 간격도 새로 정한다.
 */
data class CloneSource(val x: Float, val y: Float, val offset: SourceOffset? = null, val locked: Boolean = false)

/**
 * AI 제거에서 톡 눌러 고른 물체(결정 기록 'AI 제거·AI 선택'). 화면은 [shape](알파 그림, 문서를 [level] 단계로 줄인 크기)을
 * 반투명 검정으로, [outline](문서 좌표)을 점선으로 보인다. [area]는 지울 때 채울 곳, [revision]은 고를 때 보이던 문서의 번호다.
 */
class RemovePick(val shape: Bitmap, val level: Int, val outline: SelectionOutline, val area: InpaintArea.Masked, val revision: Long)

/** 캔버스의 리터칭 손가락이 엔진 쪽(ViewModel)에 보내는 일. 좌표는 문서 픽셀이다. */
interface RetouchActions {

    /** 가져올 곳을 정한다(복제 도장·복구 브러시) */
    fun setSource(x: Float, y: Float)

    /** 한 손가락이 닿아 [mode] 획을 시작한다 */
    fun begin(x: Float, y: Float, mode: RetouchMode)

    /** 손가락이 움직였다 */
    fun extend(x: Float, y: Float)

    /** 손가락을 뗐다. 획을 기록한다. */
    fun end()

    /** 두 손가락 조작이 시작돼 획을 버린다 */
    fun cancel()

    /** 가져올 곳 표시를 끌어 [x],[y]로 옮겼다 */
    fun moveSource(x: Float, y: Float) {}

    /** 가져올 곳 표시를 꾹 눌러 고정을 켜고 끈다 */
    fun toggleSourceLock() {}

    /** 한 손가락이 닿아 AI 제거 획을 시작한다. 손을 떼면([end]) 칠한 곳을 AI가 채운다. */
    fun beginRemove(x: Float, y: Float) {}

    /** AI 제거를 골랐다. 처음 지울 때 오래 기다리지 않게 채우기 모델을 미리 불러 둔다. */
    fun prepareRemove() {}

    /** AI 제거에서 [x],[y]를 톡 눌렀다. 그 자리 물체를 AI가 찾아 지울 곳으로 고른다(반투명으로 보임). */
    fun pickObject(x: Float, y: Float) {}

    /** 톡 눌러 고른 물체를 지운다(AI 제거) */
    fun removePicked() {}

    /** 톡 눌러 고른 물체를 버린다(뒤로 가기·하위 도구를 바꿈) */
    fun clearPicked() {}

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : RetouchActions {
        override fun setSource(x: Float, y: Float) = Unit
        override fun begin(x: Float, y: Float, mode: RetouchMode) = Unit
        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 복구 브러시·복제 도장을 고를 때 가져올 곳 표시를 둘 자리(문서 좌표, 사용자 피드백 10차 4). 지금 가져올 곳([current])이 보이는 곳
 * (캔버스 [area]에서 아래 [hiddenBottom] px를 뺀 곳, 닫힌 화면은 옵션 칸이 덮는다) 안에 있으면 null(그대로 둠)이고,
 * 없거나 밖이면 보이는 곳 가운데의 문서 자리다(문서 안으로 맞춤). 화면 반전([flip])은 캔버스 가운데를 기준으로 그림만 뒤집으므로,
 * 화면 자리를 뒤집어 문서 자리와 오간다.
 */
internal fun sourceSpot(
    view: CanvasView,
    area: IntSize,
    document: PixelSize,
    hiddenBottom: Int,
    current: CloneSource?,
    flip: ViewFlip = ViewFlip.None
): Pair<Float, Float>? {
    val transform = view.transform(PixelSize(area.width, area.height), document)
    val visibleBottom = (area.height - hiddenBottom).coerceAtLeast(1)
    // 뒤집기 전 자리 ↔ 화면 자리(뒤집기는 제 거꾸로다)
    fun flipX(x: Double) = if (flip.horizontal) area.width - x else x
    fun flipY(y: Double) = if (flip.vertical) area.height - y else y
    if (current != null) {
        val x = flipX(transform.toScreenX(current.x.toDouble()))
        val y = flipY(transform.toScreenY(current.y.toDouble()))
        if (x >= 0 && x <= area.width && y >= 0 && y <= visibleBottom) return null
    }
    val x = transform.toDocumentX(flipX(area.width / 2.0)).toFloat().coerceIn(0f, document.width - 1f)
    val y = transform.toDocumentY(flipY(visibleBottom / 2.0)).toFloat().coerceIn(0f, document.height - 1f)
    return x to y
}

/** 리터칭 하위 도구의 획 종류. 리터칭이 아닌 하위 도구면 null. */
val SubTool.retouchMode: RetouchMode?
    get() = when (this) {
        SubTool.RETOUCH_SPOT -> RetouchMode.SPOT_HEAL
        SubTool.RETOUCH_HEAL -> RetouchMode.HEAL
        SubTool.RETOUCH_CLONE -> RetouchMode.CLONE
        else -> null
    }

/**
 * 리터칭 옵션이 쓰는 값과 할 일. 설정은 ViewModel이 들고(앱을 끌 때까지 남음), 바꾸면 [onChange]로 알린다.
 *
 * @property source 가져올 곳(복구 브러시·복제 도장을 고르면 편집 화면이 보이는 곳에 둔다, 없으면 null)
 * @property sizePreview 크기·단단함 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태(없으면 보이지 않음)
 * @property objectPicked AI 제거에서 톡 눌러 고른 물체가 있는지. [지우기]는 그때만 켠다.
 * @property busy 편집을 처리하는 중이면 [지우기]를 막는다.
 * @property onRemovePicked [지우기]를 눌렀다(고른 물체를 지움)
 */
@Stable
class RetouchControls(
    val settings: BrushSettings,
    val onChange: (BrushSettings) -> Unit,
    val source: CloneSource?,
    val sizePreview: SizePreviewState? = null,
    val objectPicked: Boolean = false,
    val busy: Boolean = false,
    val onRemovePicked: () -> Unit = {}
)

/**
 * 리터칭 옵션: (AI 제거) 톡 눌러 고른 물체를 지우는 [지우기] 버튼, 크기,
 * (AI 제거가 아니면) 경도, (복제 도장만) 불투명도, 맨 아래 하위 도구(스팟 복구·복구 브러시·복제 도장·AI 제거).
 * 바꾸면 다음 획부터 쓴다(기록하지 않는다). AI 제거는 칠한 곳을 단단한 원으로 보고 AI가 채우므로 크기만 고른다(결정 기록 'AI 제거·AI 선택').
 * 복구 브러시·복제 도장의 가져올 곳은 버튼 없이 캔버스의 표시를 끌어 옮긴다(사용자 피드백 10차 4).
 * 안내 글은 하위 도구를 처음 고를 때 편집 화면이 알림으로 보인다(사용자 피드백 10차 5, [toolHelpText]).
 */
@Composable
fun RetouchOptions(mode: SubTool, controls: RetouchControls, onChoose: (SubTool) -> Unit, modifier: Modifier = Modifier) {
    val settings = controls.settings
    // 패널 안에서 내용만큼만 차지하고 가운데에 맞춰 차례로 쌓는다(다른 도구 옵션과 같다)
    Column(modifier.testTag(EditScreenTags.RETOUCH_OPTIONS), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (mode == SubTool.RETOUCH_REMOVE) {
            // 톡 눌러 고른 물체를 지운다(고른 것이 있을 때만)
            AccentButton(
                onClick = controls.onRemovePicked,
                enabled = controls.objectPicked && !controls.busy,
                modifier = Modifier.testTag(EditScreenTags.RETOUCH_REMOVE_PICKED)
            ) {
                Icon(painterResource(R.drawable.ic_auto_awesome), contentDescription = null)
                Text(stringResource(R.string.retouch_remove_picked), modifier = Modifier.padding(start = 8.dp))
            }
        }
        SizeSlider(settings.size, { controls.onChange(settings.copy(size = it)) }, EditScreenTags.RETOUCH_SIZE, controls.sizePreview)
        if (mode != SubTool.RETOUCH_REMOVE) {
            HardnessSlider(settings.hardness, { controls.onChange(settings.copy(hardness = it)) }, EditScreenTags.RETOUCH_HARDNESS, controls.sizePreview)
        }
        // 복구는 둘레에 맞춰 섞으므로 불투명도가 없다(포토샵과 같다)
        if (mode == SubTool.RETOUCH_CLONE) {
            OptionSlider(
                label = stringResource(R.string.layer_opacity),
                shown = stringResource(R.string.layer_percent, (settings.opacity * 100).roundToInt()),
                fraction = settings.opacity,
                onFraction = { controls.onChange(settings.copy(opacity = ((it * 100).roundToInt() / 100f).coerceAtLeast(0.01f))) },
                tag = EditScreenTags.RETOUCH_OPACITY
            )
        }
        // 하위 도구 줄은 맨 아래(커버 공통 규칙)
        SubToolSegments(EditTool.Retouch.subTools, mode, onChoose)
    }
}

/**
 * 리터칭 도구를 고르면 캔버스 위에 겹치는 터치 자리.
 *
 * - 가져올 곳 표시를 누르면(복구 브러시·복제 도장, 결정 기록 '리터칭'): 끌면 표시가 따라오고 손을 떼면 그 자리가 새 가져올 곳이다.
 *   움직이지 않고 길게 누르면 고정을 켜고 끈다([onLockChanged]에 새 상태를 알린다). 표시를 누른 터치는 칠하지 않는다.
 * - 아니면 한 손가락으로 끌어 [mode] 획을 그린다(브러시와 같다). 두 손가락 조작이 시작되거나 두 번째 손가락이 닿으면 끄던 획을 버린다.
 * - [removing](AI 제거)이면 [mode] 대신 AI 제거 획을 그린다([RetouchActions.beginRemove]). 가져올 곳은 쓰지 않는다.
 *   끌기 시작할 때(터치 허용 거리) 칠하기 시작하고, 움직이지 않고 떼면(톡) 그 자리 물체를 고른다([RetouchActions.pickObject]).
 *   고른 물체([pick])는 반투명 검정과 점선으로 보인다.
 *
 * 가져올 곳은 표시(브러시 크기의 원과 십자, 고정이면 굵은 원과 가운데 점)로 늘 보인다(정하는 버튼은 없다, 사용자 피드백 10차 4).
 * 칠하는 동안에는 가져오는 자리(손가락 - 간격)에, 쉬는 동안에는 [source] 자리에 있다. 스팟 복구·AI 제거는 가져올 곳을 보이지 않는다.
 * 칠하는 동안에는 손가락 자리에 브러시 커서(크기 원)도 보인다(브러시와 같다).
 */
@Composable
fun RetouchOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    mode: RetouchMode,
    source: CloneSource?,
    radius: Float,
    actions: RetouchActions,
    modifier: Modifier = Modifier,
    removing: Boolean = false,
    pick: RemovePick? = null,
    onLockChanged: (locked: Boolean) -> Unit = {}
) {
    // 가져올 곳을 쓰는 획인지(복구 브러시·복제 도장)
    val usesSource = !removing && mode != RetouchMode.SPOT_HEAL
    val currentActions by rememberUpdatedState(actions)
    val currentMode by rememberUpdatedState(mode)
    val currentRemoving by rememberUpdatedState(removing)
    val currentUsesSource by rememberUpdatedState(usesSource)
    val currentSource by rememberUpdatedState(source)
    val currentRadius by rememberUpdatedState(radius)
    val currentOnLockChanged by rememberUpdatedState(onLockChanged)
    val haptic = LocalHapticFeedback.current
    // 누르고 있는 손가락의 화면 자리. 놓으면 null.
    var finger by remember { mutableStateOf<Offset?>(null) }
    // 칠하는 획이 가져오는 간격(문서 픽셀, 칠한 곳 - 가져올 곳). 표시가 붓과 같은 간격으로 따라가게 그린다.
    var strokeOffset by remember { mutableStateOf<SourceOffset?>(null) }
    // 끌어 옮기는 표시의 자리(문서 좌표). 손을 뗀 뒤에는 새 가져올 곳이 들어올 때까지 이 자리에 그린다(옛 자리가 번쩍이지 않게).
    var movedMarker by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(source) { movedMarker = null }

    /** 고정을 켜고 끈다(꾹 누름·화면 읽기 기능의 동작) */
    fun toggleLock() {
        val locked = currentSource?.locked != true
        currentActions.toggleSourceLock()
        currentOnLockChanged(locked)
    }

    Box(
        modifier
            .testTag(EditScreenTags.RETOUCH_OVERLAY)
            .pointerInput(document) {
                awaitEachGesture {
                    // 두 손가락 조작이 가져간 터치는 받지 않는다
                    val down = awaitFirstDown()
                    val area = PixelSize(size.width, size.height)
                    fun toDocument(position: Offset, send: (Float, Float) -> Unit) {
                        val transform = view().transform(area, document)
                        send(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
                    }
                    down.consume()
                    movedMarker = null
                    // 가져올 곳 표시를 눌렀으면 칠하지 않고 표시를 옮기거나 고정한다
                    val marker = currentSource?.takeIf { currentUsesSource }
                    if (marker != null) {
                        val transform = view().transform(area, document)
                        val center = Offset(transform.toScreenX(marker.x.toDouble()).toFloat(), transform.toScreenY(marker.y.toDouble()).toFloat())
                        val grab = (currentRadius * transform.scaleX).toFloat().coerceIn(MarkerGrabMin.toPx(), MarkerGrabMax.toPx())
                        if ((down.position - center).getDistance() <= grab) {
                            /** 끈 만큼 옮긴 표시 자리(문서 좌표, 문서 안으로 맞춤) */
                            fun movedBy(delta: Offset): Offset {
                                val now = view().transform(area, document)
                                val target = center + delta
                                return Offset(
                                    now.toDocumentX(target.x.toDouble()).toFloat().coerceIn(0f, document.width - 1f),
                                    now.toDocumentY(target.y.toDouble()).toFloat().coerceIn(0f, document.height - 1f)
                                )
                            }
                            followMarker(
                                down,
                                onHold = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    toggleLock()
                                },
                                onDrag = { delta -> movedMarker = movedBy(delta) },
                                onDrop = { delta ->
                                    val target = movedBy(delta)
                                    movedMarker = target
                                    currentActions.moveSource(target.x, target.y)
                                },
                                onCancel = { movedMarker = null }
                            )
                            return@awaitEachGesture
                        }
                    }
                    finger = down.position
                    // AI 제거는 끌기 시작할 때 칠하기 시작하고, 움직이지 않고 떼면(톡) 그 자리 물체를 고른다
                    var painting = !currentRemoving
                    if (painting) {
                        toDocument(down.position) { x, y ->
                            // ViewModel과 같은 간격: 고정이면 획마다 표시 자리에서, 아니면 정한 간격(처음이면 이 획이 정함)
                            strokeOffset = currentSource?.takeIf { currentUsesSource }?.let { s ->
                                s.offset?.takeUnless { s.locked } ?: SourceOffset((x - s.x).roundToInt(), (y - s.y).roundToInt())
                            }
                            currentActions.begin(x, y, currentMode)
                        }
                    }
                    // 두 손가락이면 버리고(false), 손을 떼면 끝낸다(true). 그 전에 이 자리가 사라지면(도구를 바꿈, 접기·펼치기)
                    // 칠하던 획은 아래 finally에서 기록한다(브러시와 같다).
                    var keep: Boolean? = null
                    var last = down.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                                keep = false
                                break
                            }
                            if (!painting && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                toDocument(down.position) { x, y -> currentActions.beginRemove(x, y) }
                                painting = true
                            }
                            if (painting) {
                                for (point in change.historical) toDocument(point.position) { x, y -> currentActions.extend(x, y) }
                                toDocument(change.position) { x, y -> currentActions.extend(x, y) }
                            }
                            last = change.position
                            finger = change.position
                            if (!change.pressed) {
                                keep = true
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        finger = null
                        strokeOffset = null
                        if (!painting) {
                            // AI 제거를 톡 눌렀다
                            if (keep == true) toDocument(down.position) { x, y -> currentActions.pickObject(x, y) }
                        } else if (keep == false) {
                            currentActions.cancel()
                        } else {
                            currentActions.end()
                        }
                    }
                }
            }
    ) {
        // AI 제거에서 톡 눌러 고른 물체: 반투명 검정과 점선(선택 영역 윤곽선과 같은 모양)
        if (removing && pick != null) {
            val image = remember(pick) { pick.shape.asImageBitmap() }
            Canvas(Modifier.matchParentSize()) {
                val area = PixelSize(size.width.roundToInt(), size.height.roundToInt())
                if (area.width <= 0 || area.height <= 0) return@Canvas
                val transform = view().transform(area, document)
                val cell = (1 shl pick.level).toDouble()
                val left = transform.toScreenX(0.0).roundToInt()
                val top = transform.toScreenY(0.0).roundToInt()
                val right = transform.toScreenX(image.width * cell).roundToInt()
                val bottom = transform.toScreenY(image.height * cell).roundToInt()
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(right - left, bottom - top),
                    colorFilter = ColorFilter.tint(CanvasOverlayColors.RemovePick),
                    filterQuality = FilterQuality.Low
                )
            }
            SelectionAnts(pick.outline, null, view, document, Modifier.matchParentSize())
        }
        // 칠하는 손가락 자리의 브러시 커서
        val touching = finger
        if (touching != null) BrushCursor(touching, radius * 2f, view, document)
        if (usesSource) {
            val offset = strokeOffset
            val moved = movedMarker
            Canvas(Modifier.matchParentSize()) {
                val area = PixelSize(size.width.roundToInt(), size.height.roundToInt())
                if (area.width <= 0 || area.height <= 0) return@Canvas
                val transform = view().transform(area, document)
                val marker: Offset? = when {
                    touching != null && offset != null -> Offset(
                        touching.x - (offset.x * transform.scaleX).toFloat(),
                        touching.y - (offset.y * transform.scaleY).toFloat()
                    )
                    moved != null -> Offset(transform.toScreenX(moved.x.toDouble()).toFloat(), transform.toScreenY(moved.y.toDouble()).toFloat())
                    source != null -> Offset(transform.toScreenX(source.x.toDouble()).toFloat(), transform.toScreenY(source.y.toDouble()).toFloat())
                    else -> null
                }
                if (marker != null) drawSourceMarker(marker, (radius * transform.scaleX).toFloat(), locked = source?.locked == true)
            }
            if (source != null) SourceMarkerNode(source, view, document, onToggleLock = ::toggleLock)
        }
    }
}

/** 가져올 곳 표시를 잡는 자리의 반지름: 표시 원 안이되 작아도 지름 48dp, 커도 지름 80dp까지(큰 브러시로 표시 가까이에서 칠할 수 있게) */
private val MarkerGrabMin = 24.dp
private val MarkerGrabMax = 40.dp

/**
 * 가져올 곳 표시를 잡은 손가락([down])을 따른다(결정 기록 '리터칭').
 * - 터치 허용 거리보다 움직이면 표시를 끈다: 끄는 동안 [onDrag], 손을 떼면 [onDrop](둘 다 누른 자리에서 움직인 만큼, 화면 픽셀).
 * - 움직이지 않고 길게 누르기 시간(약 0.4초)이 지나면 [onHold]를 부르고 손을 뗄 때까지 터치를 가진다(고정 켜고 끄기).
 * - 그 전에 손을 떼면(톡) 아무것도 하지 않는다. 두 손가락 조작이 시작되거나 이 자리가 사라지면 그만둔다([onCancel]).
 */
private suspend fun AwaitPointerEventScope.followMarker(
    down: PointerInputChange,
    onHold: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: (Offset) -> Unit,
    onCancel: () -> Unit
) {
    var last = down.position
    // 길게 누르기 시간 안에 움직이면 true(끌기), 손을 떼거나 두 손가락이면 false, 시간이 다 가면 null(꾹 누름).
    // 캔버스 안 편집이라 편집 묶음 시간(휴대폰 설정)이다(결정 기록 '꾹 누르기 시간')
    val dragging = withTimeoutOrNull(viewConfiguration.editLongPressMillis()) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
            change.consume()
            if (!change.pressed) return@withTimeoutOrNull false
            last = change.position
            if ((last - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
    when (dragging) {
        null -> {
            onHold()
            // 손을 뗄 때까지 이 터치를 가진다(움직여도 옮기지 않는다)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                change.consume()
                if (!change.pressed) break
            }
        }
        true -> {
            var dropped = false
            try {
                onDrag(last - down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                    change.consume()
                    last = change.position
                    if (!change.pressed) {
                        dropped = true
                        onDrop(last - down.position)
                        break
                    }
                    onDrag(last - down.position)
                }
            } finally {
                if (!dropped) onCancel()
            }
        }
        false -> Unit
    }
}

/**
 * 가져올 곳 표시 자리의 보이지 않는 칸(지름 48dp). 화면 읽기 기능에 표시와 고정 상태를 알리고 '고정'·'고정 풀기' 동작을 준다.
 * 화면 테스트도 이것으로 표시 자리를 찾는다. 누르는 것은 받지 않는다(표시를 잡는 터치는 [RetouchOverlay]가 받는다).
 */
@Composable
private fun SourceMarkerNode(source: CloneSource, view: () -> CanvasView, document: PixelSize, onToggleLock: () -> Unit) {
    val label = stringResource(R.string.retouch_marker)
    val lockedState = stringResource(R.string.retouch_marker_locked)
    val actionLabel = stringResource(if (source.locked) R.string.retouch_unlock else R.string.retouch_lock)
    Box(
        Modifier
            .layout { measurable, constraints ->
                val side = (MarkerGrabMin * 2).roundToPx()
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // 보기(확대·이동)를 배치할 때 읽어, 보기가 바뀌면 다시 놓는다
                    val transform = view().transform(PixelSize(constraints.maxWidth, constraints.maxHeight), document)
                    placeable.place(
                        (transform.toScreenX(source.x.toDouble()) - side / 2.0).roundToInt(),
                        (transform.toScreenY(source.y.toDouble()) - side / 2.0).roundToInt()
                    )
                }
            }
            .testTag(EditScreenTags.RETOUCH_SOURCE_MARKER)
            .semantics {
                contentDescription = label
                if (source.locked) stateDescription = lockedState
                customActions = listOf(
                    CustomAccessibilityAction(actionLabel) {
                        onToggleLock()
                        true
                    }
                )
            }
    )
}

/**
 * 가져올 곳 표시: 브러시 크기의 원과 가운데 십자. 어떤 사진 위에서도 보이게 검은 테두리 위에 흰 선이다.
 * 고정되면([locked]) 원을 굵게 그리고 가운데에 채운 점을 더한다.
 */
private fun DrawScope.drawSourceMarker(center: Offset, radius: Float, locked: Boolean) {
    val arm = 8.dp.toPx()
    val ring = radius.coerceAtLeast(arm)
    val ringWidths = if (locked) 5.dp.toPx() to 2.5.dp.toPx() else 3.dp.toPx() to 1.5.dp.toPx()
    for ((color, ringWidth, width) in listOf(
        Triple(CanvasOverlayColors.Dark, ringWidths.first, 3.dp.toPx()),
        Triple(CanvasOverlayColors.Light, ringWidths.second, 1.5.dp.toPx())
    )) {
        drawCircle(color, ring, center, style = Stroke(ringWidth))
        drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), width)
        drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), width)
    }
    if (locked) {
        drawCircle(CanvasOverlayColors.Dark, 4.5.dp.toPx(), center)
        drawCircle(CanvasOverlayColors.Light, 3.dp.toPx(), center)
    }
}

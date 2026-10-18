package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import kotlin.math.roundToInt

/**
 * 복제 도장·복구 브러시가 가져올 곳(문서 좌표). 정한 뒤 처음 칠할 때 [offset](칠한 곳 - 가져올 곳)이 정해지고,
 * 그 뒤의 획은 모두 같은 간격으로 가져온다(포토샵의 '정렬'). 가져올 곳을 다시 정하면 간격도 새로 정한다.
 */
data class CloneSource(val x: Float, val y: Float, val offset: SourceOffset? = null)

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

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : RetouchActions {
        override fun setSource(x: Float, y: Float) = Unit
        override fun begin(x: Float, y: Float, mode: RetouchMode) = Unit
        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
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
 * @property source 정한 가져올 곳(없으면 null)
 * @property picking 캔버스를 누르면 가져올 곳을 정하는 중인지(가져올 곳이 없으면 늘 그렇다)
 * @property onPick 가져올 곳 정하기 버튼을 눌렀다(켜고 끈다)
 * @property sizePreview 크기·단단함 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태(없으면 보이지 않음)
 */
@Stable
class RetouchControls(
    val settings: BrushSettings,
    val onChange: (BrushSettings) -> Unit,
    val source: CloneSource?,
    val picking: Boolean,
    val onPick: () -> Unit,
    val sizePreview: SizePreviewState? = null
)

/**
 * 리터칭 옵션: 하위 도구(스팟 복구·복구 브러시·복제 도장), (복구 브러시·복제 도장) 가져올 곳 정하기 버튼, 안내 한 줄,
 * 크기, 단단함, (복제 도장만) 불투명도. 바꾸면 다음 획부터 쓴다(기록하지 않는다).
 */
@Composable
fun RetouchOptions(mode: SubTool, controls: RetouchControls, onChoose: (SubTool) -> Unit, modifier: Modifier = Modifier) {
    val settings = controls.settings
    // 패널 안에서 내용만큼만 차지하고, 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(다른 도구 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.RETOUCH_OPTIONS), gap = 8.dp, fillHeight = false) {
        SubToolSegments(EditTool.Retouch.subTools, mode, onChoose)
        // 할 일(가져올 곳 정하기·안내)을 위쪽에 둔다. 옵션 자리가 좁으면 아래 슬라이더는 밀어서 본다.
        if (mode != SubTool.RETOUCH_SPOT) {
            FilterChip(
                selected = controls.picking,
                onClick = controls.onPick,
                label = { Text(stringResource(R.string.retouch_source)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_gps_fixed), contentDescription = null) },
                modifier = Modifier.testTag(EditScreenTags.RETOUCH_SOURCE)
            )
        }
        Text(
            text = stringResource(
                when {
                    mode == SubTool.RETOUCH_SPOT -> R.string.retouch_hint_spot
                    controls.picking -> R.string.retouch_hint_pick
                    mode == SubTool.RETOUCH_HEAL -> R.string.retouch_hint_heal
                    else -> R.string.retouch_hint_clone
                }
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(EditScreenTags.RETOUCH_HINT),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        SizeSlider(settings.size, { controls.onChange(settings.copy(size = it)) }, EditScreenTags.RETOUCH_SIZE, controls.sizePreview)
        HardnessSlider(settings.hardness, { controls.onChange(settings.copy(hardness = it)) }, EditScreenTags.RETOUCH_HARDNESS, controls.sizePreview)
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
    }
}

/**
 * 리터칭 도구를 고르면 캔버스 위에 겹치는 터치 자리.
 *
 * - 가져올 곳을 정하는 중([picking])이면, 누른 채 움직여 자리를 맞추고 손을 떼면 그곳을 가져올 곳으로 정한다([onPicked]도 부른다).
 * - 아니면 한 손가락으로 끌어 [mode] 획을 그린다(브러시와 같다). 두 손가락 조작이 시작되거나 두 번째 손가락이 닿으면 끄던 획을 버린다.
 *
 * 가져올 곳은 표시(브러시 크기의 원과 십자)로 보인다. 정하는 중에는 손가락 자리에, 칠하는 동안에는 가져오는 자리(손가락 - 간격)에,
 * 정했지만 아직 칠하지 않았으면 정한 자리에 있다. 스팟 복구는 가져올 곳을 보이지 않는다.
 * 칠하는 동안에는 손가락 자리에 브러시 커서(크기 원)도 보인다(브러시와 같다).
 */
@Composable
fun RetouchOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    mode: RetouchMode,
    picking: Boolean,
    source: CloneSource?,
    radius: Float,
    actions: RetouchActions,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentActions by rememberUpdatedState(actions)
    val currentMode by rememberUpdatedState(mode)
    val currentPicking by rememberUpdatedState(picking)
    val currentOnPicked by rememberUpdatedState(onPicked)
    // 누르고 있는 손가락의 화면 자리와, 그것이 가져올 곳을 정하는 손가락인지. 놓으면 null.
    var finger by remember { mutableStateOf<Offset?>(null) }
    var fingerPicks by remember { mutableStateOf(false) }
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
                    val picks = currentPicking && currentMode != RetouchMode.SPOT_HEAL
                    fingerPicks = picks
                    finger = down.position
                    if (!picks) toDocument(down.position) { x, y -> currentActions.begin(x, y, currentMode) }
                    // 두 손가락이면 버리고(false), 손을 떼면 끝낸다(true). 그 전에 이 자리가 사라지면(도구를 바꿈, 접기·펼치기)
                    // 칠하던 획은 아래 finally에서 기록한다(브러시와 같다). 가져올 곳은 손을 뗄 때만 정한다.
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
                            if (!picks) {
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
                        if (picks) {
                            if (keep == true) {
                                toDocument(last) { x, y -> currentActions.setSource(x, y) }
                                currentOnPicked()
                            }
                        } else if (keep == false) {
                            currentActions.cancel()
                        } else {
                            currentActions.end()
                        }
                    }
                }
            }
    ) {
        // 칠하는 손가락 자리의 브러시 커서(가져올 곳을 정하는 손가락은 아래 표시만 보인다)
        val touching = finger
        if (touching != null && !fingerPicks) BrushCursor(touching, radius * 2f, view, document)
        if (mode != RetouchMode.SPOT_HEAL) {
            Canvas(Modifier.matchParentSize()) {
                val area = PixelSize(size.width.roundToInt(), size.height.roundToInt())
                if (area.width <= 0 || area.height <= 0) return@Canvas
                val transform = view().transform(area, document)
                val marker: Offset? = when {
                    touching != null && fingerPicks -> touching
                    touching != null -> source?.offset?.let { offset ->
                        Offset(
                            touching.x - (offset.x * transform.scaleX).toFloat(),
                            touching.y - (offset.y * transform.scaleY).toFloat()
                        )
                    }
                    source != null && source.offset == null ->
                        Offset(transform.toScreenX(source.x.toDouble()).toFloat(), transform.toScreenY(source.y.toDouble()).toFloat())
                    else -> null
                }
                if (marker != null) drawSourceMarker(marker, (radius * transform.scaleX).toFloat())
            }
        }
    }
}

/** 가져올 곳 표시: 브러시 크기의 원과 가운데 십자. 어떤 사진 위에서도 보이게 검은 테두리 위에 흰 선이다. */
private fun DrawScope.drawSourceMarker(center: Offset, radius: Float) {
    val arm = 8.dp.toPx()
    val ring = radius.coerceAtLeast(arm)
    for ((color, width) in listOf(Color.Black to 3.dp.toPx(), Color.White to 1.5.dp.toPx())) {
        drawCircle(color, ring, center, style = Stroke(width))
        drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), width)
        drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), width)
    }
}

package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.ColorPickerColors
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/** 색을 고르는 곳(브러시·글자) */
enum class ColorTarget { BRUSH, TEXT }

/**
 * 색 줄이 쓰는 값과 할 일(브러시·글자 옵션, 결정 기록 '색 팔레트·최근 색'·'스포이드').
 *
 * @property color 지금 색(불투명한 0xAARRGGBB)
 * @property recent 최근 색(맨 앞이 가장 최근)
 * @property onChoose 최근 색을 눌렀다. 그 색을 쓰고 최근 색 맨 앞으로 옮긴다.
 * @property onOpenPalette 지금 색을 눌렀다(팔레트를 연다)
 * @property eyedropperOn 스포이드 버튼이 켜져 있는지(다음에 캔버스를 누르면 그 자리 색을 고른다)
 * @property onEyedropper 스포이드 버튼을 눌렀다(켜고 끈다). 없으면 버튼을 보이지 않는다.
 */
@Stable
class ColorControls(
    val color: Int,
    val recent: List<Int>,
    val onChoose: (Int) -> Unit,
    val onOpenPalette: () -> Unit,
    val eyedropperOn: Boolean = false,
    val onEyedropper: (() -> Unit)? = null
)

private const val OPAQUE = 0xFF000000.toInt()

/** 색상 띠의 색(빨강에서 시작해 한 바퀴) */

/**
 * 색 줄: 지금 색(무지개 테두리, 누르면 팔레트), 스포이드 버튼, 최근 색(6개까지, 누르면 그 색). 폭이 모자라면 옆으로 밀어 본다.
 * 지금 색과 같은 최근 색은 테두리로 보인다. 누르는 영역은 모두 48dp다.
 */
@Composable
fun ColorRow(controls: ColorControls, modifier: Modifier = Modifier) {
    val current = controls.color or OPAQUE
    val currentLabel = stringResource(R.string.color_current, colorHex(current))
    val chooseLabel = stringResource(R.string.color_choose)
    val selectedLabel = stringResource(R.string.layer_selected)
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(EditScreenTags.BRUSH_COLORS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 지금 색: 무지개 테두리가 '색 고르기'를 알린다
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClickLabel = chooseLabel, role = Role.Button, onClick = controls.onOpenPalette)
                .semantics { contentDescription = currentLabel }
                .testTag(EditScreenTags.COLOR_CURRENT),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .border(BorderStroke(3.dp, Brush.sweepGradient(ColorPickerColors.Hues)), CircleShape)
                    .padding(5.dp)
                    .background(Color(current), CircleShape)
            )
        }
        controls.onEyedropper?.let { toggle ->
            // 스포이드: 켜면 다음에 캔버스를 누르거나 끈 자리의 색을 고른다(고른 도구가 칠하던 손가락 대신)
            IconToggleButton(
                checked = controls.eyedropperOn,
                onCheckedChange = { toggle() },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.testTag(EditScreenTags.COLOR_EYEDROPPER)
            ) {
                Icon(painterResource(R.drawable.ic_colorize), contentDescription = stringResource(R.string.color_eyedropper))
            }
        }
        VerticalDivider(Modifier.padding(horizontal = 8.dp).height(28.dp))
        controls.recent.forEachIndexed { index, color ->
            val isSelected = color or OPAQUE == current
            val name = stringResource(R.string.color_recent, index + 1, colorHex(color))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { controls.onChoose(color) })
                    .semantics {
                        contentDescription = name
                        if (isSelected) stateDescription = selectedLabel
                    }
                    .testTag(EditScreenTags.recentColor(index)),
                contentAlignment = Alignment.Center
            ) {
                ColorDot(color, isSelected)
            }
        }
    }
}

/** 색 동그라미(보이는 32dp). 고른 색은 굵은 테두리다. */
@Composable
private fun ColorDot(color: Int, selected: Boolean) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(32.dp)
            .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), CircleShape)
            .padding(if (selected) 4.dp else 1.dp)
            .background(Color(color or OPAQUE), CircleShape)
    )
}

/**
 * 팔레트에서 고르는 중인 색. 색상(0~360)·채도(0~1)·밝기(0~1)로 든다(회색에서 채도를 올려도 색상이 남게).
 *
 * @property target 고른 색을 쓸 곳
 * @property original 팔레트를 열 때의 색(비교해 보이고, 누르면 되돌린다)
 */
data class PaletteDraft(val target: ColorTarget, val original: Int, val hue: Float, val saturation: Float, val value: Float) {

    /** 고르는 색(불투명) */
    val color: Int
        get() = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))

    companion object {
        /** [color]에서 시작하는 팔레트 */
        fun start(target: ColorTarget, color: Int): PaletteDraft {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(color or OPAQUE, hsv)
            return PaletteDraft(target, color or OPAQUE, hsv[0], hsv[1], hsv[2])
        }

        /** 접기·펼치기에도 팔레트가 남게 저장한다(닫혀 있으면 저장하지 않는다) */
        val Saver: Saver<PaletteDraft?, List<Any>> = Saver(
            save = { draft -> draft?.let { listOf(it.target.name, it.original, it.hue, it.saturation, it.value) } },
            restore = { PaletteDraft(ColorTarget.valueOf(it[0] as String), it[1] as Int, it[2] as Float, it[3] as Float, it[4] as Float) }
        )
    }
}

/** 팔레트 네모 옆 색상 띠의 누르는 폭(보이는 띠는 20dp) */
private val HueStripWidth = 48.dp

/** 팔레트 오른쪽의 새 색·원래 색·16진 코드 칸 폭 */
private val CompareWidth = 88.dp

/**
 * 창이 들어갈 높이에서 팔레트 네모 말고 쓰는 높이의 어림값: 창 바깥 여백, 제목·버튼·안쪽 여백, 힌지 띠와 시스템 바.
 * Pixel_Fold 에뮬레이터 메인 세로(841dp, 위쪽 절반)에서 스크롤 없이 들어가게 맞췄다.
 */
private val PaletteChrome = 280.dp

/**
 * 색 팔레트(결정 기록 '색 팔레트·최근 색'). 커버 화면(힌지 없는 좁은 화면)은 아래에서 올라오는 시트, 그 밖에는 접히는 선을 피해 뜨는 창이다.
 * 그래서 화면 전체를 덮는 자리에 놓아야 한다(내보내기 창과 같다). 고르는 동안은 [onChange]로 [draft]를 바꾸고,
 * '확인'을 누르면 [onConfirm](그 색을 쓰고 최근 색에 더함), '취소'·바깥·뒤로는 [onDismiss]다.
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPalettePrompt(
    draft: PaletteDraft,
    widthClass: WidthClass,
    windowHeight: Dp,
    onChange: (PaletteDraft) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.color_choose)
    // 가로 힌지가 있으면(메인 세로) 창이 그 위쪽 절반에 들어가므로, 네모를 남는 높이에 맞춰 낮춘다
    val region = if (LocalHinges.current.any { !it.isVertical }) windowHeight / 2 else windowHeight
    val maxHeight = (region - PaletteChrome).coerceIn(MinSquare, MaxSquare)
    val buttons: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(EditScreenTags.PALETTE_CANCEL)) { Text(stringResource(R.string.action_cancel)) }
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(EditScreenTags.PALETTE_OK)) { Text(stringResource(R.string.action_ok)) }
        }
    }
    if (widthClass == WidthClass.Compact && LocalHinges.current.isEmpty()) {
        // 처음부터 끝까지 펼쳐야 버튼이 가려지지 않는다
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    PaletteForm(draft, onChange, maxHeight)
                }
                Spacer(Modifier.height(16.dp))
                buttons(Modifier.align(Alignment.End))
            }
        }
    } else {
        HingeAwareDialog(onDismissRequest = onDismiss, title = title, buttons = { buttons(Modifier) }) {
            PaletteForm(draft, onChange, maxHeight)
        }
    }
}

/** 채도·밝기 네모의 높이 범위와 가장 넓은 폭 */
private val MinSquare = 120.dp
private val MaxSquare = 200.dp
private val MaxSquareWidth = 260.dp

/**
 * 채도·밝기 네모, 그 옆 세로 색상 띠, 오른쪽에 새 색·원래 색과 16진 코드.
 * 높이가 네모 하나뿐이라 메인 세로의 창(접히는 선 위쪽 절반)에도 스크롤 없이 들어간다.
 * 네모는 남는 폭에 맞추고(260dp까지), 높이는 [maxHeight]까지라 높이가 모자라면 가로로 긴 네모가 된다.
 */
@Composable
private fun PaletteForm(draft: PaletteDraft, onChange: (PaletteDraft) -> Unit, maxHeight: Dp) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(EditScreenTags.PALETTE)) {
        val width = (maxWidth - HueStripWidth - CompareWidth - 20.dp).coerceIn(MinSquare, MaxSquareWidth)
        val height = minOf(width, maxHeight)
        Row {
            SaturationValueSquare(draft, onChange, Modifier.width(width).height(height))
            Spacer(Modifier.width(8.dp))
            HueStrip(draft, onChange, Modifier.width(HueStripWidth).height(height))
            Spacer(Modifier.width(12.dp))
            ColorCompare(draft, onChange, Modifier.width(CompareWidth))
        }
    }
}

/**
 * 채도(왼쪽 → 오른쪽)·밝기(아래 → 위) 네모. 누르거나 끌면 그 자리 색을 고른다.
 * 화면 읽기 기능은 채도·밝기를 읽고, '더 밝게'·'더 어둡게'·'채도 높이기'·'채도 낮추기' 동작으로 10%씩 바꾼다.
 */
@Composable
private fun SaturationValueSquare(draft: PaletteDraft, onChange: (PaletteDraft) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    val name = stringResource(R.string.color_saturation_value)
    val state = stringResource(R.string.color_saturation_value_state, (draft.saturation * 100).roundToInt(), (draft.value * 100).roundToInt())
    val brighter = stringResource(R.string.color_brighter)
    val darker = stringResource(R.string.color_darker)
    val moreSaturated = stringResource(R.string.color_more_saturated)
    val lessSaturated = stringResource(R.string.color_less_saturated)
    val pure = Color(AndroidColor.HSVToColor(floatArrayOf(draft.hue, 1f, 1f)))
    Canvas(
        modifier
            .pointerInput(Unit) {
                fun pick(position: Offset) {
                    val saturation = (position.x / size.width).coerceIn(0f, 1f)
                    val value = 1f - (position.y / size.height).coerceIn(0f, 1f)
                    change(current.copy(saturation = saturation, value = value))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position)
                    drag(down.id) { moved ->
                        moved.consume()
                        pick(moved.position)
                    }
                }
            }
            .semantics {
                contentDescription = name
                stateDescription = state
                customActions = listOf(
                    CustomAccessibilityAction(brighter) { change(current.copy(value = (current.value + 0.1f).coerceAtMost(1f))); true },
                    CustomAccessibilityAction(darker) { change(current.copy(value = (current.value - 0.1f).coerceAtLeast(0f))); true },
                    CustomAccessibilityAction(moreSaturated) { change(current.copy(saturation = (current.saturation + 0.1f).coerceAtMost(1f))); true },
                    CustomAccessibilityAction(lessSaturated) { change(current.copy(saturation = (current.saturation - 0.1f).coerceAtLeast(0f))); true }
                )
            }
            .testTag(EditScreenTags.PALETTE_SQUARE)
    ) {
        // 네모만 둥글게 그리고, 고른 자리 고리는 모서리에서도 잘리지 않게 그 위에 그린다
        val corner = CornerRadius(8.dp.toPx())
        drawRoundRect(Brush.horizontalGradient(listOf(ColorPickerColors.NoSaturation, pure)), cornerRadius = corner)
        drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, ColorPickerColors.NoBrightness)), cornerRadius = corner)
        // 고른 자리: 어떤 색 위에서도 보이게 검은 테두리 위의 흰 고리
        val center = Offset(draft.saturation * size.width, (1f - draft.value) * size.height)
        val radius = 9.dp.toPx()
        drawCircle(CanvasOverlayColors.Dark, radius, center, style = Stroke(3.dp.toPx()))
        drawCircle(CanvasOverlayColors.Light, radius, center, style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * 세로 색상 띠(위가 빨강, 아래로 한 바퀴). 누르거나 끌면 색상을 바꾼다. 화면 읽기 기능은 슬라이더처럼 0~360°로 읽고 바꾼다.
 */
@Composable
private fun HueStrip(draft: PaletteDraft, onChange: (PaletteDraft) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    val name = stringResource(R.string.color_hue)
    val state = stringResource(R.string.color_hue_state, draft.hue.roundToInt())
    Canvas(
        modifier
            .pointerInput(Unit) {
                fun pick(position: Offset) = change(current.copy(hue = (position.y / size.height).coerceIn(0f, 1f) * 360f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position)
                    drag(down.id) { moved ->
                        moved.consume()
                        pick(moved.position)
                    }
                }
            }
            .semantics {
                contentDescription = name
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(draft.hue, 0f..360f)
                setProgress { hue ->
                    change(current.copy(hue = hue.coerceIn(0f, 360f)))
                    true
                }
            }
            .testTag(EditScreenTags.PALETTE_HUE)
    ) {
        // 보이는 띠는 가운데 20dp, 누르는 자리는 48dp
        val stripWidth = 20.dp.toPx()
        val left = (size.width - stripWidth) / 2
        drawRoundRect(
            brush = Brush.verticalGradient(ColorPickerColors.Hues),
            topLeft = Offset(left, 0f),
            size = Size(stripWidth, size.height),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
        // 고른 색상: 띠를 가로지르는 검은 테두리의 흰 막대
        val y = draft.hue / 360f * size.height
        val markerHeight = 6.dp.toPx()
        val top = (y - markerHeight / 2).coerceIn(0f, size.height - markerHeight)
        drawRect(CanvasOverlayColors.Dark, Offset(left - 4.dp.toPx(), top), Size(stripWidth + 8.dp.toPx(), markerHeight), style = Stroke(3.dp.toPx()))
        drawRect(CanvasOverlayColors.Light, Offset(left - 4.dp.toPx(), top), Size(stripWidth + 8.dp.toPx(), markerHeight), style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * 새 색(위)과 원래 색(아래, 누르면 되돌림)을 붙인 한 덩어리와 16진 코드(#RRGGBB). Procreate처럼 이름 없이 위아래로 비교한다.
 * 칸마다 48dp라 원래 색도 누르기 쉽고, 메인 세로의 낮은 창에도 들어간다(높이 약 120dp).
 */
@Composable
private fun ColorCompare(draft: PaletteDraft, onChange: (PaletteDraft) -> Unit, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val newLabel = stringResource(R.string.color_new_state, colorHex(draft.color))
    val revertLabel = stringResource(R.string.color_revert, colorHex(draft.original))
    Column(modifier) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, outline, RoundedCornerShape(8.dp))) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(draft.color))
                    .semantics { contentDescription = newLabel }
                    .testTag(EditScreenTags.PALETTE_NEW)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(draft.original))
                    .clickable(onClickLabel = revertLabel, role = Role.Button) { onChange(PaletteDraft.start(draft.target, draft.original)) }
                    .semantics { contentDescription = revertLabel }
                    .testTag(EditScreenTags.PALETTE_ORIGINAL)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(colorHex(draft.color), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(EditScreenTags.PALETTE_HEX))
    }
}

/** 색을 '#RRGGBB'로(화면에 보이고 화면 읽기 기능이 읽는 이름) */
internal fun colorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

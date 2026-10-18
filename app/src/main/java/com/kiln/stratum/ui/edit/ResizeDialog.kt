package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import com.kiln.stratum.engine.canvas.MAX_CANVAS_PIXELS
import com.kiln.stratum.engine.canvas.MAX_CANVAS_SIDE
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.WidthClass

/**
 * 이미지 크기 변경 창. 새 캔버스 창과 같은 방식으로, 커버 화면(힌지 없는 좁은 화면)은 아래에서 올라오는 시트,
 * 그 밖에는 접히는 선을 피해 뜨는 창이다. 그래서 화면 전체를 덮는 자리에 놓아야 한다.
 * 가로·세로를 픽셀이나 퍼센트로 넣고, 비율 유지는 기본으로 켜 둔다. 보간 방식은 앱이 고른다(결정 기록).
 * '적용'을 누르면 [onApply]에 새 가로·세로 픽셀을 넘긴다.
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResizePrompt(
    input: ResizeInput,
    widthClass: WidthClass,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApply: (width: Int, height: Int) -> Unit
) {
    val title = stringResource(R.string.subtool_resize)
    val apply = {
        val width = input.targetWidth()
        val height = input.targetHeight()
        if (width != null && height != null && input.canApply) onApply(width, height)
    }
    if (widthClass == WidthClass.Compact && LocalHinges.current.isEmpty()) {
        // 처음부터 끝까지 펼쳐야 '적용'이 가려지지 않는다
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                // 내용만 스크롤되고 버튼은 아래에 고정된다
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ResizeForm(input)
                }
                Spacer(Modifier.height(16.dp))
                ResizeButtons(input, busy, onCancel = onDismiss, onApply = apply, Modifier.align(Alignment.End))
            }
        }
    } else {
        HingeAwareDialog(
            onDismissRequest = onDismiss,
            title = title,
            buttons = { ResizeButtons(input, busy, onCancel = onDismiss, onApply = apply) }
        ) {
            ResizeForm(input)
        }
    }
}

/** 단위와 비율 유지, 안내 한 줄, 가로·세로 칸 */
@Composable
private fun ResizeForm(input: ResizeInput) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 단위와 비율 유지를 한 줄에 둔다. 펼친 화면을 세로로 들면 창이 접히는 선 위쪽 절반에 들어가 높이가 모자라서다.
        // 폭이 좁으면(커버 화면, 메인 가로의 오른쪽 절반) 비율 유지가 다음 줄로 내려간다.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow {
                ResizeUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = input.unit == unit,
                        onClick = { input.changeUnit(unit) },
                        shape = SegmentedButtonDefaults.itemShape(index, ResizeUnit.entries.size),
                        icon = {},
                        modifier = Modifier.testTag(EditScreenTags.resizeUnit(unit))
                    ) {
                        Text(
                            stringResource(
                                when (unit) {
                                    ResizeUnit.PIXELS -> R.string.resize_unit_pixels
                                    ResizeUnit.PERCENT -> R.string.resize_unit_percent
                                }
                            ),
                            modifier = Modifier.width(IntrinsicSize.Max),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            // 줄 어디를 눌러도 켜고 끈다. 스위치만으로는 32dp라 누르는 높이를 48dp로 맞춘다.
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .toggleable(value = input.locked, role = Role.Switch, onValueChange = input::changeLocked)
                    .testTag(EditScreenTags.RESIZE_LOCK),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.resize_keep_ratio), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Switch(checked = input.locked, onCheckedChange = null)
            }
        }
        // 안내는 칸 바로 위 한 줄이다. 창이 낮아 내용이 스크롤되어도(키보드가 열리면 고치는 칸이 보이게 스크롤된다)
        // 칸과 함께 보이고, 안내가 바뀌어도 칸이 움직이지 않는다.
        ResizeStatus(input)
        val check = input.check
        val suffix = stringResource(if (input.unit == ResizeUnit.PIXELS) R.string.new_canvas_px else R.string.resize_percent_suffix)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ResizeField(
                value = input.width,
                onValueChange = input::changeWidth,
                label = stringResource(R.string.new_canvas_width),
                suffix = suffix,
                unit = input.unit,
                isError = check != CanvasSizeCheck.OK,
                imeAction = ImeAction.Next,
                modifier = Modifier.weight(1f).testTag(EditScreenTags.RESIZE_WIDTH)
            )
            ResizeField(
                value = input.height,
                onValueChange = input::changeHeight,
                label = stringResource(R.string.new_canvas_height),
                suffix = suffix,
                unit = input.unit,
                isError = check != CanvasSizeCheck.OK,
                imeAction = ImeAction.Done,
                modifier = Modifier.weight(1f).testTag(EditScreenTags.RESIZE_HEIGHT)
            )
        }
    }
}

/** 안내 한 줄. 한도를 벗어나면 그 까닭(오류 색), 퍼센트면 바뀔 픽셀 크기, 아니면 지금 크기다. */
@Composable
private fun ResizeStatus(input: ResizeInput) {
    val error = when (input.check) {
        CanvasSizeCheck.OK -> null
        CanvasSizeCheck.SIDE_OUT_OF_RANGE -> stringResource(R.string.new_canvas_error_range, MAX_CANVAS_SIDE)
        CanvasSizeCheck.TOO_MANY_PIXELS -> stringResource(
            R.string.resize_error_too_large,
            (maxOf(MAX_CANVAS_PIXELS, input.document.pixelCount) / 1_000_000).toInt()
        )
    }
    val width = input.targetWidth()
    val height = input.targetHeight()
    val text = when {
        error != null -> error
        input.unit == ResizeUnit.PERCENT && width != null && height != null ->
            stringResource(R.string.resize_result, width, height)
        else -> stringResource(R.string.resize_current, input.document.width, input.document.height)
    }
    Text(
        text = text,
        // 고쳐 쓰는 동안 생긴 오류의 까닭을 화면 읽기 기능이 알려 준다
        modifier = if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ResizeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    unit: ResizeUnit,
    isError: Boolean,
    imeAction: ImeAction,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        suffix = { Text(suffix) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (unit == ResizeUnit.PIXELS) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = imeAction
        )
    )
}

/** 취소와 적용. 적용은 한도 안이고 지금 크기와 다르며 처리 중이 아닐 때만 누를 수 있다. */
@Composable
private fun ResizeButtons(
    input: ResizeInput,
    busy: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
        TextButton(
            onClick = onApply,
            enabled = input.canApply && !busy,
            modifier = Modifier.testTag(EditScreenTags.RESIZE_APPLY)
        ) {
            Text(stringResource(R.string.action_apply))
        }
    }
}

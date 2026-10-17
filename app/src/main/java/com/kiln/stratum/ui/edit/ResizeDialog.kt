package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** 지금 크기, 단위, 가로·세로 칸, 비율 유지, (퍼센트면) 새 크기와 한도 안내 */
@Composable
private fun ResizeForm(input: ResizeInput) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.resize_current, input.document.width, input.document.height),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ResizeUnit.entries.forEachIndexed { index, unit ->
                SegmentedButton(
                    selected = input.unit == unit,
                    onClick = { input.changeUnit(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index, ResizeUnit.entries.size),
                    modifier = Modifier.testTag(EditScreenTags.resizeUnit(unit))
                ) {
                    Text(
                        stringResource(
                            when (unit) {
                                ResizeUnit.PIXELS -> R.string.resize_unit_pixels
                                ResizeUnit.PERCENT -> R.string.resize_unit_percent
                            }
                        )
                    )
                }
            }
        }
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
        // 줄 어디를 눌러도 켜고 끈다
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = input.locked, role = Role.Switch, onValueChange = input::changeLocked)
                .testTag(EditScreenTags.RESIZE_LOCK),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.resize_keep_ratio), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(12.dp))
            Switch(checked = input.locked, onCheckedChange = null)
        }
        val width = input.targetWidth()
        val height = input.targetHeight()
        if (input.unit == ResizeUnit.PERCENT && width != null && height != null) {
            Text(
                text = stringResource(R.string.resize_result, width, height),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val error = when (check) {
            CanvasSizeCheck.OK -> null
            CanvasSizeCheck.SIDE_OUT_OF_RANGE -> stringResource(R.string.new_canvas_error_range, MAX_CANVAS_SIDE)
            CanvasSizeCheck.TOO_MANY_PIXELS -> stringResource(
                R.string.resize_error_too_large,
                (maxOf(MAX_CANVAS_PIXELS, input.document.pixelCount) / 1_000_000).toInt()
            )
        }
        if (error != null) {
            Text(
                text = error,
                // 고쳐 쓰는 동안 바뀌는 까닭을 화면 읽기 기능이 알려 준다
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
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

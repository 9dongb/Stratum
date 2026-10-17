package com.kiln.stratum.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import com.kiln.stratum.engine.canvas.MAX_CANVAS_PIXELS
import com.kiln.stratum.engine.canvas.MAX_CANVAS_SIDE
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.WidthClass

/** 새 캔버스 창의 화면 테스트용 이름표. */
object NewCanvasTags {
    const val WIDTH = "newCanvasWidth"
    const val HEIGHT = "newCanvasHeight"
    const val SWAP = "newCanvasSwap"
    const val CREATE = "newCanvasCreate"
    fun preset(preset: CanvasPreset) = "newCanvasPreset_${preset.name}"
    fun background(background: CanvasBackground) = "newCanvasBackground_${background.name}"
}

/**
 * 새 캔버스 창의 입력값. 가로·세로는 칸에 보이는 글자 그대로 둔다.
 * 창 모양(시트·창)이 바뀌어도 이어지도록 창 밖에서 [rememberNewCanvasInput]으로 기억한다.
 */
@Stable
class NewCanvasInput(width: String, height: String, background: CanvasBackground) {
    var width by mutableStateOf(width)
    var height by mutableStateOf(height)
    var background by mutableStateOf(background)

    /** 지금 크기로 캔버스를 만들 수 있는지. */
    val check: CanvasSizeCheck
        get() = checkSizeInput(width, height)

    /** 지금 크기와 같은 프리셋. 직접 입력한 크기면 null. */
    val preset: CanvasPreset?
        get() {
            val w = width.toIntOrNull() ?: return null
            val h = height.toIntOrNull() ?: return null
            return CanvasPreset.matching(w, h)
        }

    fun choose(preset: CanvasPreset) {
        width = preset.width.toString()
        height = preset.height.toString()
    }

    fun swap() {
        val oldWidth = width
        width = height
        height = oldWidth
    }

    companion object {
        /** 접기·펼치기나 백그라운드 종료 뒤에도 입력이 남게 문자열로 저장한다. */
        val Saver = listSaver<NewCanvasInput, String>(
            save = { listOf(it.width, it.height, it.background.name) },
            restore = { NewCanvasInput(it[0], it[1], CanvasBackground.valueOf(it[2])) }
        )
    }
}

/** 새 캔버스 입력값. 처음에는 정사각형 프리셋, 흰 배경이다. 창을 닫았다 다시 열면 마지막 값이 남아 있다. */
@Composable
fun rememberNewCanvasInput(): NewCanvasInput =
    rememberSaveable(saver = NewCanvasInput.Saver) {
        NewCanvasInput(CanvasPreset.SQUARE.width.toString(), CanvasPreset.SQUARE.height.toString(), CanvasBackground.WHITE)
    }

/**
 * 새 캔버스 창. 기획서 화면 목록대로 커버 화면은 아래에서 올라오는 시트, 메인 화면은 창으로 띄운다.
 *
 * - 시트는 폭이 좁고(Compact) 힌지가 없을 때만 쓴다. 분할 화면·화면 확대 설정으로 좁은데 힌지가 있으면 창을 쓴다.
 * - 창은 힌지를 피해 뜬다(HingeAwareDialog). 그래서 화면 전체를 덮는 자리에 놓아야 한다.
 * - '만들기'를 누르면 [onCreate]에 가로·세로와 배경을 넘긴다. 크기는 한도 안일 때만 누를 수 있다.
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCanvasPrompt(
    input: NewCanvasInput,
    widthClass: WidthClass,
    onDismiss: () -> Unit,
    onCreate: (width: Int, height: Int, fill: CanvasFill) -> Unit
) {
    val title = stringResource(R.string.new_canvas_title)
    val create = { onCreate(input.width.toInt(), input.height.toInt(), input.background.fill) }
    if (widthClass == WidthClass.Compact && LocalHinges.current.isEmpty()) {
        // 처음부터 끝까지 펼쳐야 '만들기'가 가려지지 않는다
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                // 내용만 스크롤되고 버튼은 아래에 고정된다
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    NewCanvasForm(input)
                }
                Spacer(Modifier.height(16.dp))
                NewCanvasButtons(input, onCancel = onDismiss, onCreate = create, Modifier.align(Alignment.End))
            }
        }
    } else {
        HingeAwareDialog(
            onDismissRequest = onDismiss,
            title = title,
            buttons = { NewCanvasButtons(input, onCancel = onDismiss, onCreate = create) }
        ) {
            NewCanvasForm(input)
        }
    }
}

/** 크기(프리셋 목록, 가로·세로 칸)와 배경 고르기. */
@Composable
private fun NewCanvasForm(input: NewCanvasInput) {
    Column {
        SectionTitle(stringResource(R.string.new_canvas_size))
        Column(Modifier.selectableGroup()) {
            for (preset in CanvasPreset.entries) {
                PresetRow(preset, selected = input.preset == preset, onClick = { input.choose(preset) })
            }
        }
        Spacer(Modifier.height(8.dp))
        SizeFields(input)
        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.new_canvas_background))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CanvasBackground.entries.forEachIndexed { index, background ->
                SegmentedButton(
                    selected = input.background == background,
                    onClick = { input.background = background },
                    shape = SegmentedButtonDefaults.itemShape(index, CanvasBackground.entries.size),
                    modifier = Modifier.testTag(NewCanvasTags.background(background))
                ) {
                    Text(stringResource(background.label))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleSmall
    )
}

/** 프리셋 한 줄: 이름과 비율·픽셀 크기. 줄 어디를 눌러도 고른다. */
@Composable
private fun PresetRow(preset: CanvasPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(NewCanvasTags.preset(preset)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 줄 전체가 고르기 버튼이라 동그라미는 표시만 한다
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(preset.label), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (preset.ratioWidth > 0) {
                    stringResource(
                        R.string.new_canvas_preset_detail_ratio,
                        preset.ratioWidth, preset.ratioHeight, preset.width, preset.height
                    )
                } else {
                    stringResource(R.string.new_canvas_preset_detail, preset.width, preset.height)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 가로·세로 입력 칸과 바꾸기 버튼. 크기가 한도 밖이면 아래에 까닭을 보인다. */
@Composable
private fun SizeFields(input: NewCanvasInput) {
    val check = input.check
    Row(verticalAlignment = Alignment.CenterVertically) {
        SizeField(
            value = input.width,
            onValueChange = { input.width = sizeDigits(it) },
            label = stringResource(R.string.new_canvas_width),
            isError = check != CanvasSizeCheck.OK,
            imeAction = ImeAction.Next,
            modifier = Modifier.weight(1f).testTag(NewCanvasTags.WIDTH)
        )
        IconButton(onClick = input::swap, modifier = Modifier.testTag(NewCanvasTags.SWAP)) {
            Icon(painterResource(R.drawable.ic_swap_horiz), contentDescription = stringResource(R.string.new_canvas_swap))
        }
        SizeField(
            value = input.height,
            onValueChange = { input.height = sizeDigits(it) },
            label = stringResource(R.string.new_canvas_height),
            isError = check != CanvasSizeCheck.OK,
            imeAction = ImeAction.Done,
            modifier = Modifier.weight(1f).testTag(NewCanvasTags.HEIGHT)
        )
    }
    val error = when (check) {
        CanvasSizeCheck.OK -> null
        CanvasSizeCheck.SIDE_OUT_OF_RANGE -> stringResource(R.string.new_canvas_error_range, MAX_CANVAS_SIDE)
        CanvasSizeCheck.TOO_MANY_PIXELS ->
            stringResource(R.string.new_canvas_error_too_large, (MAX_CANVAS_PIXELS / 1_000_000).toInt())
    }
    if (error != null) {
        Text(
            text = error,
            // 고쳐 쓰는 동안 바뀌는 까닭을 화면 읽기 기능이 알려 준다
            modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SizeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    imeAction: ImeAction,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        suffix = { Text(stringResource(R.string.new_canvas_px)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction)
    )
}

@Composable
private fun NewCanvasButtons(
    input: NewCanvasInput,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
        TextButton(
            onClick = onCreate,
            enabled = input.check == CanvasSizeCheck.OK,
            modifier = Modifier.testTag(NewCanvasTags.CREATE)
        ) {
            Text(stringResource(R.string.new_canvas_create))
        }
    }
}

package com.kiln.stratum.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import com.kiln.stratum.engine.canvas.MAX_CANVAS_PIXELS
import com.kiln.stratum.engine.canvas.MAX_CANVAS_SIDE
import com.kiln.stratum.project.ProjectNames
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.HingeSplitRow
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.theme.StratumRadius
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/** 새 캔버스 창의 화면 테스트용 이름표. */
object NewCanvasTags {
    const val NAME = "newCanvasName"
    const val WIDTH = "newCanvasWidth"
    const val HEIGHT = "newCanvasHeight"
    const val CREATE = "newCanvasCreate"
    const val PRESETS = "newCanvasPresets"
    fun preset(preset: CanvasPreset) = "newCanvasPreset_${preset.name}"
    fun background(background: CanvasBackground) = "newCanvasBackground_${background.name}"
    fun orientation(portrait: Boolean) = if (portrait) "newCanvasPortrait" else "newCanvasLandscape"
}

/**
 * 새 캔버스 창의 입력값. 가로·세로는 칸에 보이는 글자 그대로 두고, [name]은 적은 이름 그대로다(만들 때 다듬는다, 비면 '제목 없음').
 * 창 모양(시트·창)이 바뀌어도 이어지도록 창 밖에서 [rememberNewCanvasInput]으로 기억한다.
 */
@Stable
class NewCanvasInput(width: String, height: String, background: CanvasBackground, name: String = "") {
    var width by mutableStateOf(width)
    var height by mutableStateOf(height)
    var background by mutableStateOf(background)
    var name by mutableStateOf(name)

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

    /** 방향: 세로로 길면 true, 가로로 길면 false, 정사각형이거나 칸이 비었으면 null */
    val portrait: Boolean?
        get() {
            val w = width.toIntOrNull() ?: return null
            val h = height.toIntOrNull() ?: return null
            return when {
                w < h -> true
                w > h -> false
                else -> null
            }
        }

    fun choose(preset: CanvasPreset) {
        width = preset.width.toString()
        height = preset.height.toString()
    }

    /** 방향을 [portrait]로 바꾼다(필요하면 가로·세로를 맞바꾼다). 정사각형은 그대로다. */
    fun orient(portrait: Boolean) {
        val current = this.portrait ?: return
        if (current != portrait) {
            val oldWidth = width
            width = height
            height = oldWidth
        }
    }

    companion object {
        /** 접기·펼치기나 백그라운드 종료 뒤에도 입력이 남게 문자열로 저장한다. */
        val Saver = listSaver<NewCanvasInput, String>(
            save = { listOf(it.width, it.height, it.background.name, it.name) },
            restore = { NewCanvasInput(it[0], it[1], CanvasBackground.valueOf(it[2]), it.getOrElse(3) { "" }) }
        )
    }
}

/**
 * 새 캔버스 입력값. 처음에는 정사각형 프리셋, 흰 배경, 이름 없음이다.
 * 홈에 있는 동안 창을 닫았다 다시 열면 마지막 값이 남고, 편집 화면에 다녀오면 홈이 새로 그려져 처음 값으로 돌아간다.
 */
@Composable
fun rememberNewCanvasInput(): NewCanvasInput =
    rememberSaveable(saver = NewCanvasInput.Saver) {
        NewCanvasInput(CanvasPreset.SQUARE.width.toString(), CanvasPreset.SQUARE.height.toString(), CanvasBackground.WHITE)
    }

/** 메인 화면 새 캔버스 창의 최대 폭. 두 칸(프리셋 목록 · 이름·크기·방향·배경)이 나란히 들어간다. */
private val WideDialogMaxWidth = 760.dp

/**
 * 새 캔버스 창(결정 기록 '새 캔버스 화면'). 프로크리에이트의 프리셋 목록과 포토샵의 세부 설정을 합쳤다(사용자 요청).
 *
 * - 커버 화면(힌지 없는 좁은 화면)은 아래에서 올라오는 시트에 한 줄로: 이름 → 프리셋 → 크기 → 방향 → 배경.
 * - 메인 화면은 넓은 창에 두 칸: 왼쪽 프리셋 목록, 오른쪽 이름·크기·방향·배경. 메인 가로는 창이 접히는 선 양쪽에 걸치고
 *   두 칸을 선 양쪽에 하나씩 놓는다(선 위에는 아무것도 없다). 메인 세로는 접히는 선 위에 두 칸이다.
 * - 칸마다 따로 밀어 본다. '만들기'를 누르면 [onCreate]에 가로·세로·배경·이름을 넘긴다. 크기는 한도 안일 때만 누를 수 있다.
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCanvasPrompt(
    input: NewCanvasInput,
    widthClass: WidthClass,
    onDismiss: () -> Unit,
    onCreate: (width: Int, height: Int, fill: CanvasFill, name: String) -> Unit
) {
    val title = stringResource(R.string.new_canvas_title)
    val create = { onCreate(input.width.toInt(), input.height.toInt(), input.background.fill, ProjectNames.clean(input.name)) }
    if (widthClass == WidthClass.Compact && LocalHinges.current.isEmpty()) {
        // 처음부터 끝까지 펼쳐야 '만들기'가 가려지지 않는다
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                // 내용만 스크롤되고 버튼은 아래에 고정된다
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    NameField(input)
                    Spacer(Modifier.height(8.dp))
                    PresetList(input)
                    Spacer(Modifier.height(8.dp))
                    CanvasDetails(input)
                }
                Spacer(Modifier.height(16.dp))
                NewCanvasButtons(input, onCancel = onDismiss, onCreate = create, Modifier.align(Alignment.End))
            }
        }
    } else {
        HingeAwareDialog(
            onDismissRequest = onDismiss,
            title = title,
            buttons = { NewCanvasButtons(input, onCancel = onDismiss, onCreate = create) },
            maxWidth = WideDialogMaxWidth,
            spanVerticalHinge = true,
            scrollContent = false
        ) {
            HingeSplitRow(
                first = { PresetList(input, Modifier.verticalScroll(rememberScrollState())) },
                second = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NameField(input)
                        Spacer(Modifier.height(8.dp))
                        CanvasDetails(input)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                gap = 24.dp,
                alignTop = true
            )
        }
    }
}

/** 이름 칸. 비워 두면 '제목 없음'이다(흐린 글자로 보인다). */
@Composable
private fun NameField(input: NewCanvasInput) {
    OutlinedTextField(
        value = input.name,
        onValueChange = { input.name = it.take(ProjectNames.MAX_LENGTH) },
        modifier = Modifier.fillMaxWidth().testTag(NewCanvasTags.NAME),
        label = { Text(stringResource(R.string.new_canvas_name)) },
        placeholder = { Text(stringResource(R.string.project_untitled)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )
}

/**
 * 프리셋 목록(프로크리에이트처럼): 묶음 제목 아래 줄마다 비율 모양 그림, 이름·비율, 오른쪽에 픽셀 크기. 고른 줄은 선택 배경이다.
 * 줄 어디를 눌러도 고른다. 지금 크기와 같은 프리셋이 고른 것으로 보인다(직접 입력한 크기면 고른 줄이 없다).
 */
@Composable
private fun PresetList(input: NewCanvasInput, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup().testTag(NewCanvasTags.PRESETS)) {
        for (group in PresetGroup.entries) {
            SectionTitle(stringResource(group.label))
            for (preset in CanvasPreset.entries.filter { it.group == group }) {
                PresetRow(preset, selected = input.preset == preset, onClick = { input.choose(preset) })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
        color = StratumTheme.colors.textDescription,
        style = MaterialTheme.typography.labelLarge
    )
}

/** 프리셋 한 줄: 비율 모양 그림, 이름(그 아래 비율), 오른쪽에 픽셀 크기. 고른 줄은 선택 배경·선택 색이다. */
@Composable
private fun PresetRow(preset: CanvasPreset, selected: Boolean, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    val accent = if (selected) colors.selectedContent else colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(StratumShapes.Control)
            .background(if (selected) colors.selectedBackground else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 8.dp)
            .testTag(NewCanvasTags.preset(preset)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AspectShape(preset.width, preset.height, accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(preset.label),
                color = if (selected) colors.selectedContent else colors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (preset.ratioWidth > 0) {
                Text(
                    text = stringResource(R.string.crop_ratio_format, preset.ratioWidth, preset.ratioHeight),
                    color = colors.textDescription,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Text(
            text = stringResource(R.string.home_project_size, preset.width, preset.height),
            color = colors.textDescription,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

/** 비율 모양 그림: 24dp 칸에 가로·세로 비율대로의 둥근 네모 테두리(프로크리에이트 목록처럼) */
@Composable
private fun AspectShape(width: Int, height: Int, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val long = maxOf(width, height).toFloat()
        val w = size.width * 0.9f * width / long
        val h = size.height * 0.9f * height / long
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(StratumRadius.Small.toPx() / 2),
            style = Stroke(stroke)
        )
    }
}

/** 크기(가로·세로 칸, 한도 밖이면 까닭), 방향(세로·가로), 배경(흰색·검은색·투명) */
@Composable
private fun CanvasDetails(input: NewCanvasInput) {
    Column {
        SectionTitle(stringResource(R.string.new_canvas_size))
        SizeFields(input)
        SectionTitle(stringResource(R.string.new_canvas_orientation))
        OrientationRow(input)
        SectionTitle(stringResource(R.string.new_canvas_background))
        StratumSegmentedRow(Modifier.fillMaxWidth()) {
            CanvasBackground.entries.forEachIndexed { index, background ->
                StratumSegment(
                    selected = input.background == background,
                    onClick = { input.background = background },
                    index = index,
                    count = CanvasBackground.entries.size,
                    modifier = Modifier.testTag(NewCanvasTags.background(background))
                ) {
                    Text(stringResource(background.label))
                }
            }
        }
    }
}

/** 방향: 세로·가로. 누르면 필요할 때 가로·세로를 맞바꾼다. 정사각형이면 어느 쪽도 고른 것으로 보이지 않는다(포토샵과 같다). */
@Composable
private fun OrientationRow(input: NewCanvasInput) {
    val portrait = input.portrait
    StratumSegmentedRow(Modifier.fillMaxWidth()) {
        listOf(true, false).forEachIndexed { index, isPortrait ->
            StratumSegment(
                selected = portrait == isPortrait,
                onClick = { input.orient(isPortrait) },
                index = index,
                count = 2,
                modifier = Modifier.testTag(NewCanvasTags.orientation(isPortrait))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (isPortrait) R.drawable.ic_crop_portrait else R.drawable.ic_crop_landscape),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (isPortrait) R.string.new_canvas_portrait else R.string.new_canvas_landscape))
                }
            }
        }
    }
}

/** 가로·세로 입력 칸. 크기가 한도 밖이면 아래에 까닭을 보인다. */
@Composable
private fun SizeFields(input: NewCanvasInput) {
    val check = input.check
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SizeField(
            value = input.width,
            onValueChange = { input.width = sizeDigits(it) },
            label = stringResource(R.string.new_canvas_width),
            isError = check != CanvasSizeCheck.OK,
            imeAction = ImeAction.Next,
            modifier = Modifier.weight(1f).testTag(NewCanvasTags.WIDTH)
        )
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
            color = StratumTheme.colors.error,
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
        QuietButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
        AccentButton(
            onClick = onCreate,
            enabled = input.check == CanvasSizeCheck.OK,
            modifier = Modifier.testTag(NewCanvasTags.CREATE)
        ) {
            Text(stringResource(R.string.new_canvas_create))
        }
    }
}

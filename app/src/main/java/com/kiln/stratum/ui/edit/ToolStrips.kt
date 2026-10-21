package com.kiln.stratum.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.selection.ColorRegion
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.tone.ToneRange
import com.kiln.stratum.settings.ColorSlot
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/*
 * 메인 화면 도구 옵션 띠의 칸들(결정 기록 '편집 화면 도구 배치'). 커버 화면 옵션과 같은 값·할 일(…Controls)을 쓰고,
 * 옵션 창의 슬라이더·고르기는 커버 옵션과 같은 부품·이름표다. 그래서 바꾸는 값과 기록은 커버와 똑같다.
 * 고르기 칸의 옵션 창은 고르면 닫는다([close]).
 * 하위 도구는 띠에 두지 않고 도구 막대의 하위 도구 메뉴(꾹 누르기·다시 누르기)에서 고른다(사용자 피드백 7차).
 * 칸 순서(사용자 피드백 9차: "1.크기 2.경도 3. 투명도.4 강도 순서로 배치해주세요(없을 땐 제외)"): 수치 칸을 크기 → 경도 → 불투명도 → 강도
 * (명암의 노출·모자이크의 셀 크기도 강도 자리) 순서로 맨 위에 두고, 구분선 아래에 나머지 칸(색·가져올 곳·범위 등)을 둔다([numbersFirst]).
 */

/** 수치 칸 [numbers] 다음에, 나머지 칸 [rest]가 있으면 구분선을 두고 그 칸들을 둔다 */
private fun numbersFirst(numbers: List<StripItem>, rest: List<StripItem>): List<StripItem> =
    if (rest.isEmpty()) numbers else numbers + StripDivider("afterNumbers") + rest

/** 브러시·지우개의 띠: 크기, 경도, 불투명도, (브러시만) 구분선 아래 색·바꾸기·스포이드 */
@Composable
internal fun brushStrip(controls: BrushControls): List<StripItem> {
    val settings = controls.settings
    val colors = controls.colors.takeIf { !controls.erase }
    return numbersFirst(
        numbers = listOf(
            sizeCell(settings.size, EditScreenTags.BRUSH_SIZE, controls.sizePreview) { controls.onChange(settings.copy(size = it)) },
            hardnessCell(settings.hardness, EditScreenTags.BRUSH_HARDNESS, controls.sizePreview) { controls.onChange(settings.copy(hardness = it)) },
            opacityCell(settings.opacity, EditScreenTags.BRUSH_OPACITY) { controls.onChange(settings.copy(opacity = it)) }
        ),
        rest = colors?.let { colorCells(it) }.orEmpty()
    )
}

/** 글자의 띠: 크기, 구분선 아래 색·바꾸기·스포이드. 넣을 자리를 누르라는 안내는 고를 때 알림으로 보인다. */
@Composable
internal fun textStrip(controls: TextControls): List<StripItem> {
    val style = controls.style
    return numbersFirst(
        numbers = listOf(
            SliderCell(
                key = "textSize",
                caption = stringResource(R.string.brush_size),
                value = stringResource(R.string.brush_size_value, style.size.roundToInt()),
                slider = StripSlider(
                    fraction = textSizeToFraction(style.size),
                    onFraction = { controls.onChange(style.copy(size = fractionToTextSize(it))) },
                    tag = EditScreenTags.TEXT_SIZE
                )
            )
        ),
        rest = controls.colors?.let { colorCells(it) }.orEmpty()
    )
}

/**
 * 자르기·변형의 띠: 자르기면 비율·가로세로 바꾸기·취소·적용(파란 버튼), 회전·뒤집기면 왼쪽·오른쪽 회전과 좌우·상하 뒤집기.
 * 하위 도구(자르기 / 회전·뒤집기 / 크기 변경…)는 띠에 두지 않고 도구 막대의 하위 도구 메뉴에서 고른다(사용자 피드백 7차).
 * 자르기 테두리가 아직 없으면(첫 화면) 자르기 칸은 다음 화면에 보인다.
 */
@Composable
internal fun transformStrip(
    mode: SubTool,
    busy: Boolean,
    onEdit: (EditCommand) -> Unit,
    crop: CropControls?,
    close: () -> Unit
): List<StripItem> =
    buildList {
        when (mode) {
            SubTool.CROP -> if (crop != null) {
                add(
                    ValueCell("ratio", stringResource(R.string.strip_crop_ratio), cropRatioLabel(crop.frame.ratio)) {
                        CropRatioFlow(crop, onChosen = close)
                    }
                )
                add(IconCell("swap", R.drawable.ic_swap_horiz, stringResource(R.string.crop_swap), crop.onSwap, tag = EditScreenTags.CROP_SWAP))
                add(StripDivider("cropActions"))
                add(IconCell("cancel", R.drawable.ic_close, stringResource(R.string.action_cancel), crop.onCancel, tag = EditScreenTags.CROP_CANCEL))
                add(
                    IconCell(
                        key = "apply",
                        icon = R.drawable.ic_check,
                        description = stringResource(R.string.action_apply),
                        onClick = crop.onApply,
                        enabled = crop.canApply && !busy,
                        accent = true,
                        tag = EditScreenTags.CROP_APPLY
                    )
                )
            }
            SubTool.ROTATE -> {
                add(IconCell("rotateLeft", R.drawable.ic_rotate_90_degrees_ccw, stringResource(R.string.rotate_left), { onEdit(RotateImage(clockwise = false)) }, enabled = !busy, tag = EditScreenTags.ROTATE_LEFT))
                add(IconCell("rotateRight", R.drawable.ic_rotate_90_degrees_cw, stringResource(R.string.rotate_right), { onEdit(RotateImage(clockwise = true)) }, enabled = !busy, tag = EditScreenTags.ROTATE_RIGHT))
                add(IconCell("flipHorizontal", R.drawable.ic_flip, stringResource(R.string.flip_horizontal), { onEdit(FlipImage(horizontal = true)) }, enabled = !busy, tag = EditScreenTags.FLIP_HORIZONTAL))
                // 상하 뒤집기 아이콘은 좌우 뒤집기 아이콘을 90° 돌려 쓴다(커버 옵션과 같다)
                add(IconCell("flipVertical", R.drawable.ic_flip, stringResource(R.string.flip_vertical), { onEdit(FlipImage(horizontal = false)) }, enabled = !busy, iconRotation = 90f, tag = EditScreenTags.FLIP_VERTICAL))
            }
            // 크기 변경은 창을 띄우는 일이고, 다른 하위 도구는 이 도구의 것이 아니다
            else -> Unit
        }
    }

/**
 * 선택의 띠: 합치는 방법(새로·더하기·빼기·교차), 페더(자동 선택은 허용치와 '이어진 곳만'),
 * 그리고 모두 선택·해제·반전·지우기·새 레이어로 복사·잘라내기·AI 채우기(모두 선택만 늘, 나머지는 고른 곳이 있을 때).
 */
@Composable
internal fun selectionStrip(mode: SubTool, controls: SelectionControls, close: () -> Unit): List<StripItem> {
    val options = controls.options
    val ready = !controls.busy
    val selected = ready && controls.hasSelection
    val actions = controls.actions
    return buildList {
        add(
            ValueCell("mode", stringResource(R.string.strip_selection_mode), stringResource(selectionModeLabel(options.mode))) {
                ChoiceList(
                    choices = SelectionMode.entries,
                    selected = options.mode,
                    label = { stringResource(selectionModeLabel(it)) },
                    icon = { null },
                    tag = { EditScreenTags.selectionMode(it) }
                ) {
                    controls.onOptions(options.copy(mode = it))
                    close()
                }
            }
        )
        if (mode == SubTool.SELECT_WAND) {
            val label = stringResource(R.string.selection_tolerance)
            add(
                SliderCell(
                    key = "tolerance",
                    caption = label,
                    value = options.tolerance.toString(),
                    slider = StripSlider(
                        fraction = options.tolerance / ColorRegion.MAX_TOLERANCE.toFloat(),
                        onFraction = { controls.onOptions(options.copy(tolerance = (it * ColorRegion.MAX_TOLERANCE).roundToInt())) },
                        tag = EditScreenTags.SELECTION_TOLERANCE
                    ),
                    // '이어진 곳만'은 값 표시 아래에 둔다
                    extra = { ContiguousRow(options.contiguous) { controls.onOptions(options.copy(contiguous = it)) } }
                )
            )
        } else if (mode != SubTool.SELECT_AI) {
            // AI 선택은 경계가 이미 부드러워 페더가 없다
            val label = stringResource(R.string.selection_feather)
            val shown = stringResource(R.string.selection_feather_value, options.feather.roundToInt())
            add(
                SliderCell(
                    key = "feather",
                    caption = label,
                    value = shown,
                    slider = StripSlider(
                        fraction = featherToFraction(options.feather),
                        onFraction = { controls.onOptions(options.copy(feather = fractionToFeather(it))) },
                        tag = EditScreenTags.SELECTION_FEATHER
                    )
                )
            )
        }
        add(StripDivider("selectionActions"))
        add(IconCell("all", R.drawable.ic_select_all, stringResource(R.string.selection_all_description), actions::all, enabled = ready, tag = EditScreenTags.SELECTION_ALL))
        add(IconCell("none", R.drawable.ic_deselect, stringResource(R.string.selection_none), actions::none, enabled = selected, tag = EditScreenTags.SELECTION_NONE))
        add(IconCell("invert", R.drawable.ic_flip_to_front, stringResource(R.string.selection_invert), actions::invert, enabled = selected, tag = EditScreenTags.SELECTION_INVERT))
        add(IconCell("delete", R.drawable.ic_delete, stringResource(R.string.selection_delete_description), actions::delete, enabled = selected, tag = EditScreenTags.SELECTION_DELETE))
        add(IconCell("copy", R.drawable.ic_content_copy, stringResource(R.string.selection_copy_description), { actions.copyToLayer(cut = false) }, enabled = selected, tag = EditScreenTags.SELECTION_COPY))
        add(IconCell("cut", R.drawable.ic_content_cut, stringResource(R.string.selection_cut_description), { actions.copyToLayer(cut = true) }, enabled = selected, tag = EditScreenTags.SELECTION_CUT))
        add(IconCell("aiFill", R.drawable.ic_auto_awesome, stringResource(R.string.selection_ai_fill_description), actions::aiFill, enabled = selected, tag = EditScreenTags.SELECTION_AI_FILL))
    }
}

/**
 * 리터칭의 띠: 크기, (AI 제거가 아니면) 경도, (복제만) 불투명도, 구분선 아래 (복구·복제) 가져올 곳 정하기(정하는 중이면 켜진 모양,
 * 커버 옵션과 같은 이름표), (AI 제거) 고른 물체 지우기.
 * 가져올 곳을 아직 정하지 않았으면 정하는 중으로 보인다(누르면 끄지 못하고 캔버스를 눌러 정해야 한다, 커버와 같다).
 */
@Composable
internal fun retouchStrip(mode: SubTool, controls: RetouchControls): List<StripItem> {
    val settings = controls.settings
    val numbers = buildList {
        add(sizeCell(settings.size, EditScreenTags.RETOUCH_SIZE, controls.sizePreview) { controls.onChange(settings.copy(size = it)) })
        // AI 제거는 칠한 곳을 단단한 원으로 보고 AI가 채우므로 경도가 없다
        if (mode != SubTool.RETOUCH_REMOVE) {
            add(hardnessCell(settings.hardness, EditScreenTags.RETOUCH_HARDNESS, controls.sizePreview) { controls.onChange(settings.copy(hardness = it)) })
        }
        // 복구는 둘레에 맞춰 섞으므로 불투명도가 없다(포토샵과 같다)
        if (mode == SubTool.RETOUCH_CLONE) {
            add(opacityCell(settings.opacity, EditScreenTags.RETOUCH_OPACITY) { controls.onChange(settings.copy(opacity = it)) })
        }
    }
    val rest = buildList {
        if (mode == SubTool.RETOUCH_HEAL || mode == SubTool.RETOUCH_CLONE) {
            add(
                IconCell(
                    key = "source",
                    icon = R.drawable.ic_gps_fixed,
                    description = stringResource(R.string.retouch_source),
                    onClick = controls.onPick,
                    checked = controls.picking,
                    tag = EditScreenTags.RETOUCH_SOURCE
                )
            )
        }
        if (mode == SubTool.RETOUCH_REMOVE) {
            // 톡 눌러 고른 물체를 지운다(고른 것이 있을 때만)
            add(
                IconCell(
                    key = "removePicked",
                    icon = R.drawable.ic_auto_awesome,
                    description = stringResource(R.string.retouch_remove_picked_description),
                    onClick = controls.onRemovePicked,
                    enabled = controls.objectPicked && !controls.busy,
                    tag = EditScreenTags.RETOUCH_REMOVE_PICKED
                )
            )
        }
    }
    return numbersFirst(numbers, rest) + helpCells(EditTool.Retouch, mode, controls.picking)
}

/** 명암의 띠: 크기, 경도, 노출(강도 자리), 구분선 아래 범위(어두운·중간·밝은 영역, 아이콘), 맨 아래 물음표 도움말 */
@Composable
internal fun toneStrip(mode: SubTool, controls: ToneControls, close: () -> Unit): List<StripItem> {
    val options = controls.options
    val brush = options.brush
    val rangeCaption = stringResource(R.string.strip_tone_range)
    val exposureLabel = stringResource(R.string.tone_exposure)
    val exposureShown = stringResource(R.string.layer_percent, (options.exposure * 100).roundToInt())
    return numbersFirst(
        numbers = listOf(
            sizeCell(brush.size, EditScreenTags.TONE_SIZE, controls.sizePreview) { controls.onChange(options.copy(brush = brush.copy(size = it))) },
            hardnessCell(brush.hardness, EditScreenTags.TONE_HARDNESS, controls.sizePreview) { controls.onChange(options.copy(brush = brush.copy(hardness = it))) },
            SliderCell(
                key = "exposure",
                caption = exposureLabel,
                value = exposureShown,
                slider = StripSlider(
                    fraction = options.exposure,
                    // 0%면 아무것도 바뀌지 않으니 1%에서 멈춘다(커버 옵션과 같다)
                    onFraction = { controls.onChange(options.copy(exposure = ((it * 100).roundToInt() / 100f).coerceAtLeast(0.01f))) },
                    tag = EditScreenTags.TONE_EXPOSURE
                )
            )
        ),
        rest = listOf(
            ChoiceCell(
                key = "range",
                icon = toneRangeIcon(options.range),
                caption = rangeCaption,
                description = stringResource(R.string.strip_choice_description, rangeCaption, stringResource(toneRangeLabel(options.range)))
            ) {
                ChoiceList(
                    choices = ToneRange.entries,
                    selected = options.range,
                    label = { stringResource(toneRangeLabel(it)) },
                    icon = { toneRangeIcon(it) },
                    tag = { EditScreenTags.toneRange(it) }
                ) {
                    controls.onChange(options.copy(range = it))
                    close()
                }
            }
        )
    ) + helpCells(EditTool.Tone, mode, retouchPicking = false)
}

/** 흐림의 띠: 크기, 경도, 강도(하위 도구 흐림·선명·스머지는 도구 막대의 하위 도구 메뉴에서 고른다) */
@Composable
internal fun blurStrip(controls: BlurControls): List<StripItem> {
    val options = controls.options
    val brush = options.brush
    return listOf(
        sizeCell(brush.size, EditScreenTags.BLUR_SIZE, controls.sizePreview) { controls.onChange(options.copy(brush = brush.copy(size = it))) },
        hardnessCell(brush.hardness, EditScreenTags.BLUR_HARDNESS, controls.sizePreview) { controls.onChange(options.copy(brush = brush.copy(hardness = it))) },
        SliderCell(
            key = "strength",
            caption = stringResource(R.string.effect_strength),
            value = stringResource(R.string.layer_percent, (options.strength * 100).roundToInt()),
            slider = StripSlider(
                fraction = options.strength,
                onFraction = { controls.onChange(options.copy(strength = strengthOf(it))) },
                tag = EditScreenTags.BLUR_STRENGTH
            )
        )
    )
}

/** 모자이크의 띠: 크기, 경도, 셀 크기(강도 자리, 2~512px, 로그 눈금) */
@Composable
internal fun mosaicStrip(controls: MosaicControls): List<StripItem> {
    val options = controls.options
    val brush = options.brush
    return listOf(
        sizeCell(brush.size, EditScreenTags.MOSAIC_SIZE, controls.sizePreview) { controls.onChange(options.copy(brush = brush.copy(size = it))) },
        hardnessCell(brush.hardness, EditScreenTags.MOSAIC_HARDNESS, controls.sizePreview) { controls.onChange(options.copy(brush = brush.copy(hardness = it))) },
        SliderCell(
            key = "cell",
            caption = stringResource(R.string.mosaic_cell),
            value = stringResource(R.string.brush_size_value, options.cell),
            slider = StripSlider(
                fraction = cellToFraction(options.cell),
                onFraction = { controls.onChange(options.copy(cell = fractionToCell(it))) },
                tag = EditScreenTags.MOSAIC_CELL
            )
        )
    )
}

/** 크기 칸(1~1000px, 로그 눈금). 끄는 동안 캔버스 가운데에 크기 원([preview]), 손을 떼거나 슬라이더 창이 닫히면 사라진다 */
@Composable
private fun sizeCell(size: Float, sliderTag: String, preview: SizePreviewState?, onSize: (Float) -> Unit): StripItem =
    SliderCell(
        key = "size",
        caption = stringResource(R.string.brush_size),
        value = stringResource(R.string.brush_size_value, size.roundToInt()),
        slider = StripSlider(
            fraction = sizeToFraction(size),
            onFraction = {
                preview?.onChange()
                onSize(fractionToSize(it))
            },
            tag = sliderTag,
            onFinished = { preview?.onFinished() },
            preview = preview
        )
    )

/** 경도 칸(0~100%). 끄는 동안 캔버스 가운데에 크기 원과 경도 자리([preview]) */
@Composable
private fun hardnessCell(hardness: Float, sliderTag: String, preview: SizePreviewState?, onHardness: (Float) -> Unit): StripItem =
    SliderCell(
        key = "hardness",
        caption = stringResource(R.string.brush_hardness),
        value = stringResource(R.string.layer_percent, (hardness * 100).roundToInt()),
        slider = StripSlider(
            fraction = hardness,
            onFraction = {
                preview?.onChange()
                onHardness((it * 100).roundToInt() / 100f)
            },
            tag = sliderTag,
            onFinished = { preview?.onFinished() },
            preview = preview
        )
    )

/**
 * 전경색·배경색 칸(누르면 옵션 창 없이 바로 그 색의 팔레트), 바꾸기 버튼(두 색을 맞바꿈), 스포이드 버튼(켜고 끔).
 * 바꾸기·스포이드는 커버 색 줄과 같은 이름표다. 띠에서는 두 색이 위아래라 바꾸기 화살표를 세로로 돌린다.
 */
@Composable
private fun colorCells(colors: ColorControls): List<StripItem> =
    buildList {
        for (slot in ColorSlot.entries) {
            val color = colors.colors[slot]
            add(
                ColorCell(
                    key = colorCellKey(slot),
                    color = color,
                    caption = stringResource(if (slot == ColorSlot.FOREGROUND) R.string.strip_foreground else R.string.strip_background),
                    description = stringResource(colorSlotDescription(slot), colorHex(color))
                ) { colors.onOpenPalette(slot) }
            )
        }
        add(
            IconCell(
                key = "swapColors",
                icon = R.drawable.ic_swap_horiz,
                description = stringResource(R.string.color_swap),
                onClick = colors.onSwap,
                iconRotation = 90f,
                tag = EditScreenTags.COLOR_SWAP
            )
        )
        colors.onEyedropper?.let { toggle ->
            add(
                IconCell(
                    key = "eyedropper",
                    icon = R.drawable.ic_colorize,
                    description = stringResource(R.string.color_eyedropper),
                    onClick = toggle,
                    checked = colors.eyedropperOn,
                    tag = EditScreenTags.COLOR_EYEDROPPER
                )
            )
        }
    }

/** 띠의 전경색·배경색 칸 이름(화면 테스트 이름표 `strip_foreground`·`strip_background`) */
internal fun colorCellKey(slot: ColorSlot): String = when (slot) {
    ColorSlot.FOREGROUND -> "foreground"
    ColorSlot.BACKGROUND -> "background"
}

/** 불투명도 칸(1~100%, 0%면 아무것도 칠해지지 않아 1%에서 멈춘다). 브러시·리터칭(복제)이 쓴다. */
@Composable
private fun opacityCell(opacity: Float, sliderTag: String, onOpacity: (Float) -> Unit): StripItem =
    SliderCell(
        key = "opacity",
        caption = stringResource(R.string.layer_opacity),
        value = stringResource(R.string.layer_percent, (opacity * 100).roundToInt()),
        slider = StripSlider(
            fraction = opacity,
            onFraction = { onOpacity(((it * 100).roundToInt() / 100f).coerceAtLeast(0.01f)) },
            tag = sliderTag
        )
    )

/** 고르기 옵션 창의 목록 폭 */
private val ChoiceListWidth = 200.dp

/**
 * 고르기 옵션 창의 목록: 줄마다 아이콘(있으면)과 이름, 고른 줄은 선택 배경과 선택 색(모서리 8dp). 줄 전체를 누를 수 있다(48dp).
 * 화면 읽기 기능에는 라디오 버튼 묶음으로 알린다.
 */
@Composable
private fun <T> ChoiceList(
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    icon: (T) -> Int?,
    tag: (T) -> String,
    onChoose: (T) -> Unit
) {
    val colors = StratumTheme.colors
    Column(Modifier.width(ChoiceListWidth).selectableGroup()) {
        for (choice in choices) {
            val chosen = choice == selected
            val color = if (chosen) colors.selectedContent else colors.textPrimary
            Row(
                modifier = Modifier
                    .width(ChoiceListWidth)
                    .heightIn(min = 48.dp)
                    .clip(StratumShapes.Control)
                    .background(if (chosen) colors.selectedBackground else Color.Transparent)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onChoose(choice) })
                    .padding(horizontal = 12.dp)
                    .testTag(tag(choice)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon(choice)?.let {
                    Icon(painterResource(it), contentDescription = null, tint = if (chosen) colors.selectedContent else colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                }
                Text(text = label(choice), color = color, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

/**
 * 물음표 도움말의 안내 글(사용자 피드백 8차 1-7: 설명 줄을 빼고 조작이 모호한 도구에만 물음표, 사용자 결정 'AI 제거·리터칭·명암').
 * [mode]는 고른 하위 도구, [retouchPicking]은 리터칭이 가져올 곳을 정하는 중인지다. 물음표가 없는 도구는 null이다.
 * 커버는 옵션 칸 맨 아래 하위 도구 줄 끝([HelpButton]), 메인 화면은 띠 맨 아래 칸([HelpCell])에서 쓴다.
 */
@Composable
internal fun toolHelpText(tool: EditTool?, mode: SubTool?, retouchPicking: Boolean): String? {
    val hint = when (tool) {
        EditTool.Retouch -> when {
            mode == SubTool.RETOUCH_SPOT -> R.string.retouch_hint_spot
            mode == SubTool.RETOUCH_REMOVE -> R.string.retouch_hint_remove
            retouchPicking -> R.string.retouch_hint_pick
            mode == SubTool.RETOUCH_HEAL -> R.string.retouch_hint_heal
            else -> R.string.retouch_hint_clone
        }
        EditTool.Tone -> if (mode == SubTool.TONE_BURN) R.string.tone_hint_burn else R.string.tone_hint_dodge
        else -> null
    }
    return hint?.let { stringResource(it) }
}

/** 띠 맨 아래의 물음표 칸(누르면 옆에 안내 글 창). 안내 글이 없는 도구는 빈 목록이다. */
@Composable
private fun helpCells(tool: EditTool, mode: SubTool, retouchPicking: Boolean): List<StripItem> =
    toolHelpText(tool, mode, retouchPicking)?.let { listOf(StripDivider("beforeHelp"), HelpCell("help", stringResource(R.string.tool_help), it)) }.orEmpty()

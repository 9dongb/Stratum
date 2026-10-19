package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.StratumFilterChip
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.components.ToolActionButton
import com.kiln.stratum.ui.layout.HingeAvoidingColumn

/**
 * 자르기 옵션이 쓰는 지금 테두리와 할 일. 편집 화면이 자르기 테두리를 들고 있어 여기로 넘긴다.
 *
 * @property canApply 적용하면 바뀌는 것이 있는지(테두리가 사진 전체가 아닌지)
 */
class CropControls(
    val frame: CropFrame,
    val canApply: Boolean,
    val onRatio: (CropRatio) -> Unit,
    val onSwap: () -> Unit,
    val onCancel: () -> Unit,
    val onApply: () -> Unit
)

/**
 * 고른 도구의 옵션. 커버 화면은 캔버스와 탭 줄 사이, 메인 세로는 아래 패널, 메인 가로는 오른쪽 패널에 놓인다.
 * 줄은 가로로 지나가는 힌지의 띠를 피해 쌓는다(CLAUDE.md 6장).
 *
 * 조정·레이어는 커버 화면에서만 도구 옵션으로 보인다. 메인 화면에서는 패널에 늘 있다(편집 화면).
 *
 * @param busy 편집을 처리하는 중이면 편집 버튼을 막는다.
 * @param onEdit 엔진에 보낼 편집 명령
 * @param crop 자르기 중이면 그 테두리와 할 일. 자르기를 시작하기 전(첫 프레임)에는 null일 수 있다.
 * @param adjust 조정 옵션이 쓰는 값과 할 일. 문서가 없으면 null이다.
 * @param adjustItem 커버 화면 조정 옵션에서 고른 항목
 * @param layers 레이어 옵션이 쓰는 값과 할 일. 문서가 없으면 null이다.
 * @param brush 고른 도구(브러시·지우개)의 설정과 바꾸기
 * @param text 글자 도구의 모양과 바꾸기
 * @param select 선택 도구의 설정과 할 일
 * @param retouch 리터칭 도구의 설정과 가져올 곳
 * @param tone 명암 도구의 설정과 바꾸기
 * @param blur 흐림 도구(흐림·선명)의 설정과 바꾸기
 * @param mosaic 모자이크 도구의 설정과 바꾸기
 */
@Composable
fun ToolOptions(
    selection: ToolSelection,
    busy: Boolean,
    onEdit: (EditCommand) -> Unit,
    onChoose: (SubTool) -> Unit,
    modifier: Modifier = Modifier,
    crop: CropControls? = null,
    adjust: AdjustControls? = null,
    adjustItem: AdjustItem = AdjustItem.EXPOSURE,
    onAdjustItem: (AdjustItem) -> Unit = {},
    layers: LayerControls? = null,
    brush: BrushControls? = null,
    text: TextControls? = null,
    select: SelectionControls? = null,
    retouch: RetouchControls? = null,
    tone: ToneControls? = null,
    blur: BlurControls? = null,
    mosaic: MosaicControls? = null
) {
    when (selection.active) {
        EditTool.Transform -> TransformOptions(selection.transformMode, busy, onEdit, onChoose, crop, modifier)
        EditTool.Adjust -> if (adjust != null) AdjustCompact(adjust, adjustItem, onAdjustItem, modifier)
        EditTool.Layers -> if (layers != null) LayerCompact(layers, modifier)
        EditTool.Brush, EditTool.Eraser -> if (brush != null) BrushOptions(brush, modifier)
        EditTool.Text -> if (text != null) TextOptions(text, modifier)
        EditTool.Select -> if (select != null) {
            SelectionToolOptions(checkNotNull(selection.modeOf(EditTool.Select)), select, onChoose, modifier)
        }
        EditTool.Retouch -> if (retouch != null) {
            RetouchOptions(checkNotNull(selection.modeOf(EditTool.Retouch)), retouch, onChoose, modifier)
        }
        EditTool.Tone -> if (tone != null) {
            ToneToolOptions(checkNotNull(selection.modeOf(EditTool.Tone)), tone, onChoose, modifier)
        }
        EditTool.Blur -> if (blur != null) {
            BlurToolOptions(checkNotNull(selection.modeOf(EditTool.Blur)), blur, onChoose, modifier)
        }
        EditTool.Mosaic -> if (mosaic != null) MosaicToolOptions(mosaic, modifier)
        else -> Unit
    }
}

/** 자르기·변형 옵션. 위쪽에서 하위 도구를 바꾸고, 그 아래에 하위 도구의 옵션을 보인다. */
@Composable
private fun TransformOptions(
    mode: SubTool,
    busy: Boolean,
    onEdit: (EditCommand) -> Unit,
    onChoose: (SubTool) -> Unit,
    crop: CropControls?,
    modifier: Modifier = Modifier
) {
    // 패널 안에서 내용만큼만 차지한다. 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다.
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.TOOL_OPTIONS), gap = 8.dp, fillHeight = false) {
        SubToolRow(mode, onChoose)
        when (mode) {
            SubTool.ROTATE -> RotateButtons(enabled = !busy, onEdit = onEdit)
            SubTool.CROP -> if (crop != null) {
                CropRatioRow(crop)
                CropActionRow(crop, busy)
            }
            // 크기 변경은 창을 띄우는 일이고, 선택 하위 도구는 이 옵션에 오지 않는다
            else -> Unit
        }
    }
}

/**
 * 닫힌 화면 자르기 창(결정 기록 '닫힌 화면 새 창')의 옵션: 하위 도구 줄(자르기·회전, 크기 변경)과 비율 칩 줄(옆으로 밀어 봄)·가로세로 바꾸기.
 * 취소·적용은 창 맨 아래 줄의 X·✓([CoverWorkBar])다. 회전으로 바꾸면 자르기 창을 나와 옵션 칸(회전·뒤집기)으로 간다.
 */
@Composable
internal fun CoverCropOptions(mode: SubTool, onChoose: (SubTool) -> Unit, crop: CropControls, modifier: Modifier = Modifier) {
    Column(modifier.testTag(EditScreenTags.COVER_CROP_OPTIONS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubToolRow(mode, onChoose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CropRatioRow(crop) }
            StratumIconButton(onClick = crop.onSwap, modifier = Modifier.testTag(EditScreenTags.CROP_SWAP)) {
                Icon(painterResource(R.drawable.ic_swap_horiz), contentDescription = stringResource(R.string.crop_swap))
            }
        }
    }
}

/** 자르기 비율 칩. 폭이 좁으면 옆으로 밀어 본다. 고른 비율이 선택된 것으로 보인다. */
@Composable
private fun CropRatioRow(crop: CropControls) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CropRatioChips(crop)
    }
}

/**
 * 자르기 비율 칩들(자유·원본·1:1…). 커버는 한 줄로 밀어 보고, 메인 화면 띠의 옵션 창은 접어 쌓는다([CropRatioFlow]).
 * 누르면 [onChosen]도 부른다(옵션 창을 닫는다).
 */
@Composable
private fun CropRatioChips(crop: CropControls, onChosen: () -> Unit = {}) {
    for (ratio in CropRatio.entries) {
        StratumFilterChip(
            selected = crop.frame.ratio == ratio,
            onClick = {
                crop.onRatio(ratio)
                onChosen()
            },
            label = { Text(cropRatioLabel(ratio)) },
            modifier = Modifier.testTag(EditScreenTags.cropRatio(ratio))
        )
    }
}

/** 메인 화면 띠의 비율 옵션 창: 비율 칩을 좁은 폭에 접어 쌓는다 */
@Composable
internal fun CropRatioFlow(crop: CropControls, onChosen: () -> Unit) {
    FlowRow(
        modifier = Modifier.width(FlyoutSliderWidth),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CropRatioChips(crop, onChosen)
    }
}

/** 비율 칩의 이름. 자유·원본은 말로, 나머지는 '가로:세로'로 보인다. */
@Composable
internal fun cropRatioLabel(ratio: CropRatio): String =
    when (ratio) {
        CropRatio.FREE -> stringResource(R.string.crop_ratio_free)
        CropRatio.ORIGINAL -> stringResource(R.string.crop_ratio_original)
        else -> stringResource(R.string.crop_ratio_format, ratio.width, ratio.height)
    }

/** 가로·세로 바꾸기, 취소, 적용. 적용은 테두리가 사진 전체가 아니고 처리 중이 아닐 때만 누를 수 있다. */
@Composable
private fun CropActionRow(crop: CropControls, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StratumIconButton(onClick = crop.onSwap, modifier = Modifier.testTag(EditScreenTags.CROP_SWAP)) {
            Icon(painterResource(R.drawable.ic_swap_horiz), contentDescription = stringResource(R.string.crop_swap))
        }
        QuietButton(onClick = crop.onCancel, modifier = Modifier.testTag(EditScreenTags.CROP_CANCEL)) {
            Text(stringResource(R.string.action_cancel))
        }
        AccentButton(
            onClick = crop.onApply,
            enabled = crop.canApply && !busy,
            modifier = Modifier.testTag(EditScreenTags.CROP_APPLY)
        ) {
            Text(stringResource(R.string.action_apply))
        }
    }
}

/**
 * 하위 도구 바꾸기. 길게 누르는 메뉴(CLAUDE.md 6장)를 모르는 사람도 찾을 수 있게 옵션 위쪽에도 둔다.
 * 자르기와 회전·뒤집기는 옵션이 이어지는 하위 도구라 한 줄에서 하나를 고르고, 크기 변경은 창을 띄우는 버튼이다.
 * 폭이 모자라면 크기 변경 버튼이 다음 줄로 내려간다.
 */
@Composable
private fun SubToolRow(mode: SubTool, onChoose: (SubTool) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        val modes = EditTool.Transform.subTools.filter { !it.oneShot }
        StratumSegmentedRow {
            modes.forEachIndexed { index, subTool ->
                StratumSegment(
                    selected = subTool == mode,
                    onClick = { onChoose(subTool) },
                    index = index,
                    count = modes.size,
                    modifier = Modifier.testTag(EditScreenTags.subToolOption(subTool))
                ) {
                    // 버튼 줄은 이름을 가장 좁게 줄인 폭(최소 고유 폭)으로 칸을 나눈다. 한국어는 글자마다 줄을 바꿀 수 있어
                    // 그 폭이 아주 좁으므로, 이름 전체 폭(최대 고유 폭)을 쓰게 한다.
                    Text(
                        stringResource(subTool.shortLabel),
                        modifier = Modifier.width(IntrinsicSize.Max),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        QuietButton(
            onClick = { onChoose(SubTool.RESIZE) },
            modifier = Modifier.testTag(EditScreenTags.subToolOption(SubTool.RESIZE))
        ) {
            Text(stringResource(SubTool.RESIZE.shortLabel), maxLines = 1, softWrap = false)
        }
    }
}

/** 회전·뒤집기 버튼 네 개. 누르면 바로 적용되고, 실행 취소 한 단계로 남는다. 폭이 모자라면 두 줄로 놓인다. */
@Composable
private fun RotateButtons(enabled: Boolean, onEdit: (EditCommand) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IconLabelButton(R.drawable.ic_rotate_90_degrees_ccw, R.string.rotate_left, enabled, EditScreenTags.ROTATE_LEFT) {
            onEdit(RotateImage(clockwise = false))
        }
        IconLabelButton(R.drawable.ic_rotate_90_degrees_cw, R.string.rotate_right, enabled, EditScreenTags.ROTATE_RIGHT) {
            onEdit(RotateImage(clockwise = true))
        }
        IconLabelButton(R.drawable.ic_flip, R.string.flip_horizontal, enabled, EditScreenTags.FLIP_HORIZONTAL) {
            onEdit(FlipImage(horizontal = true))
        }
        // 상하 뒤집기 아이콘은 좌우 뒤집기 아이콘을 90° 돌려 쓴다
        IconLabelButton(R.drawable.ic_flip, R.string.flip_vertical, enabled, EditScreenTags.FLIP_VERTICAL, iconRotation = 90f) {
            onEdit(FlipImage(horizontal = false))
        }
    }
}

/** 아이콘 아래에 이름이 있는 버튼(두 줄까지). 누르는 영역은 48dp 이상이다. */
@Composable
private fun IconLabelButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    enabled: Boolean,
    tag: String,
    iconRotation: Float = 0f,
    onClick: () -> Unit
) {
    ToolActionButton(
        icon = icon,
        label = stringResource(label),
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        enabled = enabled,
        minWidth = 72.dp,
        maxLines = 2,
        iconRotation = iconRotation
    )
}

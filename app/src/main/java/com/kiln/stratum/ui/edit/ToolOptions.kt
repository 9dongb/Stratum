package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.StratumFilterChip
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.components.ToolActionButton

/**
 * 자르기·회전 화면(사용자 피드백 8차 1)이 쓰는 지금 테두리와 할 일. 편집 화면이 테두리와 아직 적용하지 않은 방향을 들고 있어 여기로 넘긴다.
 *
 * @property canApply 적용하면 바뀌는 것이 있는지(돌리거나 뒤집었거나, 테두리가 사진 전체가 아닌지)
 * @property canResize 크기 변경 창을 띄울 수 있는지. 바꾼 것이 없을 때만이다(크기를 바꾸면 고르던 테두리·방향이 사라지므로).
 * @property onTurn 보이는 사진을 그 방향(왼쪽·오른쪽 90°, 좌우·상하 뒤집기)으로 바꾼다. ✓를 누를 때 자르기와 함께 적용한다.
 */
class CropControls(
    val frame: CropFrame,
    val canApply: Boolean,
    val canResize: Boolean,
    val onRatio: (CropRatio) -> Unit,
    val onSwap: () -> Unit,
    val onTurn: (Orientation) -> Unit,
    val onCancel: () -> Unit,
    val onApply: () -> Unit
)

/** 닫힌 화면 자르기·회전 창의 옵션 탭(맨 아래 X와 ✓ 사이, 사용자 피드백 8차 1-1). 자르기는 비율, 회전은 돌리기·뒤집기를 보인다. */
enum class CropTab(@get:StringRes val label: Int) {
    CROP(R.string.crop_tab_crop),
    ROTATE(R.string.crop_tab_rotate)
}

/**
 * 고른 도구의 옵션. 커버 화면은 캔버스와 탭 줄 사이, 메인 세로는 아래 패널, 메인 가로는 오른쪽 패널에 놓인다.
 * 줄은 가로로 지나가는 힌지의 띠를 피해 쌓는다(CLAUDE.md 6장).
 *
 * 조정·레이어는 커버 화면에서만 도구 옵션으로 보인다. 메인 화면에서는 패널에 늘 있다(편집 화면).
 * 자르기·변형은 옵션 칸이 아니라 자르기·회전 창이다(편집 화면의 [CoverCropOptions]).
 *
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
    onChoose: (SubTool) -> Unit,
    modifier: Modifier = Modifier,
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
        // 이동 도구는 고를 옵션이 없어 옵션 칸을 띄우지 않는다(편집 화면, 사용자 피드백 8차 1-7).
        // 자르기·변형은 자르기·회전 창이 옵션을 보인다.
        else -> Unit
    }
}

/**
 * 닫힌 화면 자르기·회전 창(결정 기록 '닫힌 화면 새 창')의 옵션: 고른 탭([CropTab])이 자르기면 비율 칩 줄(옆으로 밀어 봄)·가로세로 바꾸기,
 * 회전이면 왼쪽·오른쪽 90°와 좌우·상하 뒤집기다(사용자 피드백 8차 1). 탭과 크기 변경, 취소·적용은 창 맨 아래 줄([CoverCropTabs]·[CoverWorkBar])이다.
 */
@Composable
internal fun CoverCropOptions(tab: CropTab, crop: CropControls, busy: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.testTag(EditScreenTags.COVER_CROP_OPTIONS)) {
        when (tab) {
            CropTab.CROP -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CropRatioRow(crop) }
                StratumIconButton(onClick = crop.onSwap, modifier = Modifier.testTag(EditScreenTags.CROP_SWAP)) {
                    Icon(painterResource(R.drawable.ic_swap_horiz), contentDescription = stringResource(R.string.crop_swap))
                }
            }
            CropTab.ROTATE -> CropTurnButtons(crop, enabled = !busy)
        }
    }
}

/**
 * 닫힌 화면 자르기·회전 창 맨 아래 줄 가운데(X와 ✓ 사이, 사용자 피드백 8차 1-1): [자르기 | 회전] 탭과 크기 변경 버튼.
 * 크기 변경은 창을 띄우는 일이라, 바꾼 것이 없을 때만 누를 수 있다([resizeEnabled]).
 */
@Composable
internal fun CoverCropTabs(tab: CropTab, onTab: (CropTab) -> Unit, resizeEnabled: Boolean, onResize: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StratumSegmentedRow {
            CropTab.entries.forEachIndexed { index, entry ->
                StratumSegment(
                    selected = entry == tab,
                    onClick = { onTab(entry) },
                    index = index,
                    count = CropTab.entries.size,
                    modifier = Modifier.testTag(EditScreenTags.cropTab(entry))
                ) {
                    // 버튼 줄은 이름을 가장 좁게 줄인 폭으로 칸을 나눈다. 한국어는 글자마다 줄을 바꿀 수 있어 이름 전체 폭을 쓰게 한다.
                    Text(stringResource(entry.label), modifier = Modifier.width(IntrinsicSize.Max), maxLines = 1, softWrap = false)
                }
            }
        }
        QuietButton(
            onClick = onResize,
            enabled = resizeEnabled,
            modifier = Modifier.testTag(EditScreenTags.subToolOption(SubTool.RESIZE))
        ) {
            Text(stringResource(SubTool.RESIZE.shortLabel), maxLines = 1, softWrap = false)
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
            label = { Text(cropRatioLabel(ratio, crop.frame.swapped)) },
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

/**
 * 비율 칩의 이름. 자유·원본은 말로, 나머지는 '가로:세로'로 보인다.
 * 가로·세로를 바꿨으면([swapped]) 뒤집어 보인다(4:5 → 5:4). 고른 테두리 모양과 이름이 맞게(Claude 판단, 사용자 피드백 10차).
 */
@Composable
internal fun cropRatioLabel(ratio: CropRatio, swapped: Boolean = false): String =
    when (ratio) {
        CropRatio.FREE -> stringResource(R.string.crop_ratio_free)
        CropRatio.ORIGINAL -> stringResource(R.string.crop_ratio_original)
        else -> if (swapped) {
            stringResource(R.string.crop_ratio_format, ratio.height, ratio.width)
        } else {
            stringResource(R.string.crop_ratio_format, ratio.width, ratio.height)
        }
    }

/**
 * 자르기·회전 창의 돌리기·뒤집기 버튼 네 개(사용자 피드백 8차 1). 누르면 보이는 사진만 돌리고, ✓를 누를 때 자르기와 함께 실행 취소 한 단계로 적용한다.
 * 폭이 모자라면 두 줄로 놓인다.
 */
@Composable
private fun CropTurnButtons(crop: CropControls, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IconLabelButton(R.drawable.ic_rotate_90_degrees_ccw, R.string.rotate_left, enabled, EditScreenTags.ROTATE_LEFT) {
            crop.onTurn(Orientation.ROTATE_COUNTERCLOCKWISE)
        }
        IconLabelButton(R.drawable.ic_rotate_90_degrees_cw, R.string.rotate_right, enabled, EditScreenTags.ROTATE_RIGHT) {
            crop.onTurn(Orientation.ROTATE_CLOCKWISE)
        }
        IconLabelButton(R.drawable.ic_flip, R.string.flip_horizontal, enabled, EditScreenTags.FLIP_HORIZONTAL) {
            crop.onTurn(Orientation.FLIP_HORIZONTAL)
        }
        // 상하 뒤집기 아이콘은 좌우 뒤집기 아이콘을 90° 돌려 쓴다
        IconLabelButton(R.drawable.ic_flip, R.string.flip_vertical, enabled, EditScreenTags.FLIP_VERTICAL, iconRotation = 90f) {
            crop.onTurn(Orientation.FLIP_VERTICAL)
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

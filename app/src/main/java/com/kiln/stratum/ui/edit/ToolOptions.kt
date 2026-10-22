package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.kiln.stratum.ui.components.horizontalScrollFadeEdges
import com.kiln.stratum.ui.components.scrollFadeEdges
import com.kiln.stratum.ui.theme.StratumTheme

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

/**
 * 닫힌 화면 자르기·회전 창의 옵션 탭(맨 아래 X와 ✓ 사이, 사용자 피드백 8차 1-1). 자르기는 비율, 회전은 돌리기·뒤집기를 보인다.
 * [icon]은 커버를 가로로 돌렸을 때 세로 탭 칸에 이름과 함께 보인다(가로 탭은 이름만).
 */
enum class CropTab(@get:StringRes val label: Int, @get:DrawableRes val icon: Int) {
    CROP(R.string.crop_tab_crop, R.drawable.ic_crop),
    ROTATE(R.string.crop_tab_rotate, R.drawable.ic_rotate_90_degrees_cw)
}

/**
 * 닫힌 화면 자르기·회전 창(결정 기록 '닫힌 화면 새 창')의 옵션: 고른 탭([CropTab])이 자르기면 비율 칩 줄(옆으로 밀어 봄)·가로세로 바꾸기,
 * 회전이면 왼쪽·오른쪽 90°와 좌우·상하 뒤집기다(사용자 피드백 8차 1). 탭과 크기 변경, 취소·적용은 창 맨 아래 줄([CoverCropTabs]·[CoverWorkBar])이다.
 * 높이는 편집 화면의 한 줄 막대와 같고(편집 화면이 정함, 결정 기록 '커버 화면 개편'), 두 탭 모두 한 줄이라 탭을 바꿔도 사진 크기가 그대로다.
 */
@Composable
internal fun CoverCropOptions(tab: CropTab, crop: CropControls, busy: Boolean, modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) {
        // 커버를 가로로 돌렸으면(사용자 피드백 12차 3) 같은 것을 위아래로 쌓은 기둥이다. 폭이 늘 같아 탭을 바꿔도 사진 크기가 그대로다.
        Box(modifier.testTag(EditScreenTags.COVER_CROP_OPTIONS), contentAlignment = Alignment.TopCenter) {
            when (tab) {
                CropTab.CROP -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f, fill = false)) { CropRatioColumn(crop) }
                    StratumIconButton(onClick = crop.onSwap, modifier = Modifier.testTag(EditScreenTags.CROP_SWAP)) {
                        Icon(painterResource(R.drawable.ic_swap_horiz), contentDescription = stringResource(R.string.crop_swap))
                    }
                }
                CropTab.ROTATE -> CropTurnColumn(crop, enabled = !busy)
            }
        }
    } else {
        Box(modifier.testTag(EditScreenTags.COVER_CROP_OPTIONS), contentAlignment = Alignment.CenterStart) {
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

/**
 * 커버를 가로로 돌렸을 때 자르기·회전 창 오른쪽 기둥의 가운데(✓와 X 사이, 사용자 피드백 12차 3): [자르기]·[회전] 탭 칸과 크기 변경 칸을 위아래로.
 * 칸은 아이콘과 이름이고 고른 탭은 선택 배경이다. 이름표·할 일은 가로 탭([CoverCropTabs])과 같다.
 */
@Composable
internal fun CoverCropTabColumn(tab: CropTab, onTab: (CropTab) -> Unit, resizeEnabled: Boolean, onResize: () -> Unit) {
    val colors = StratumTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (entry in CropTab.entries) {
            val selected = entry == tab
            val label = stringResource(entry.label)
            CellBox(EditScreenTags.cropTab(entry), label, null, open = selected, enabled = true, onClick = { onTab(entry) }) {
                Icon(painterResource(entry.icon), contentDescription = null, tint = if (selected) colors.selectedContent else colors.textSecondary)
                CompactLabel(label, if (selected) colors.selectedContent else colors.textDescription)
            }
        }
        val resize = stringResource(SubTool.RESIZE.shortLabel)
        CellBox(EditScreenTags.subToolOption(SubTool.RESIZE), resize, null, open = false, enabled = resizeEnabled, onClick = onResize) {
            Icon(
                painterResource(SubTool.RESIZE.icon),
                contentDescription = null,
                tint = if (resizeEnabled) colors.textSecondary else colors.textDisabled
            )
            CompactLabel(resize, if (resizeEnabled) colors.textDescription else colors.textDisabled)
        }
    }
}

/** 자르기 비율 칩. 폭이 좁으면 옆으로 밀어 보고, 더 있는 쪽 끝을 흐리게 그린다. 고른 비율이 선택된 것으로 보인다. */
@Composable
private fun CropRatioRow(crop: CropControls) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.horizontalScrollFadeEdges(scroll, StratumTheme.colors.panel).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CropRatioChips(crop)
    }
}

/** 커버를 가로로 돌렸을 때의 비율 칸들(위아래로, 넘치면 밀어 보고 더 있는 쪽 끝을 흐리게). 고른 비율은 선택 배경이다. */
@Composable
private fun CropRatioColumn(crop: CropControls) {
    val colors = StratumTheme.colors
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.scrollFadeEdges(scroll, colors.panel).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (ratio in CropRatio.entries) {
            val selected = crop.frame.ratio == ratio
            val label = cropRatioLabel(ratio, crop.frame.swapped)
            CellBox(EditScreenTags.cropRatio(ratio), label, null, open = selected, enabled = true, onClick = { crop.onRatio(ratio) }) {
                CompactLabel(label, if (selected) colors.selectedContent else colors.textSecondary)
            }
        }
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
 * 한 줄이고, 폭이 모자라면 옆으로 밀어 본다(옵션 줄 높이가 늘 같게).
 */
@Composable
private fun CropTurnButtons(crop: CropControls, enabled: Boolean) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.horizontalScrollFadeEdges(scroll, StratumTheme.colors.panel).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (turn in CropTurns) {
            IconLabelButton(turn.icon, turn.label, enabled, turn.tag, turn.iconRotation) { crop.onTurn(turn.orientation) }
        }
    }
}

/** 커버를 가로로 돌렸을 때의 돌리기·뒤집기 칸 네 개(위아래로, 아이콘과 이름). 이름표·할 일은 가로 줄([CropTurnButtons])과 같다. */
@Composable
private fun CropTurnColumn(crop: CropControls, enabled: Boolean) {
    val colors = StratumTheme.colors
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.scrollFadeEdges(scroll, colors.panel).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (turn in CropTurns) {
            val label = stringResource(turn.label)
            CellBox(turn.tag, label, null, open = false, enabled = enabled, onClick = { crop.onTurn(turn.orientation) }) {
                Icon(
                    painterResource(turn.icon),
                    contentDescription = null,
                    tint = if (enabled) colors.textSecondary else colors.textDisabled,
                    modifier = Modifier.rotate(turn.iconRotation)
                )
                CompactLabel(label, if (enabled) colors.textDescription else colors.textDisabled)
            }
        }
    }
}

/** 자르기·회전 창의 돌리기·뒤집기 하나(가로 줄과 세로 기둥이 같이 쓴다) */
private class CropTurn(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val tag: String,
    val orientation: Orientation,
    val iconRotation: Float = 0f
)

/** 왼쪽·오른쪽 90°, 좌우·상하 뒤집기. 상하 뒤집기 아이콘은 좌우 뒤집기 아이콘을 90° 돌려 쓴다. */
private val CropTurns = listOf(
    CropTurn(R.drawable.ic_rotate_90_degrees_ccw, R.string.rotate_left, EditScreenTags.ROTATE_LEFT, Orientation.ROTATE_COUNTERCLOCKWISE),
    CropTurn(R.drawable.ic_rotate_90_degrees_cw, R.string.rotate_right, EditScreenTags.ROTATE_RIGHT, Orientation.ROTATE_CLOCKWISE),
    CropTurn(R.drawable.ic_flip, R.string.flip_horizontal, EditScreenTags.FLIP_HORIZONTAL, Orientation.FLIP_HORIZONTAL),
    CropTurn(R.drawable.ic_flip, R.string.flip_vertical, EditScreenTags.FLIP_VERTICAL, Orientation.FLIP_VERTICAL, iconRotation = 90f)
)

/** 아이콘 아래에 이름이 있는 버튼(한 줄, 위아래 여백을 줄여 한 줄 막대 높이에 든다). 누르는 영역은 48dp 이상이다. */
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
        iconRotation = iconRotation,
        compact = true
    )
}

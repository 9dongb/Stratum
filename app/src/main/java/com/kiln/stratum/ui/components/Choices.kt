package com.kiln.stratum.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 칩(커버 조정 항목·자르기 비율·가져올 곳 정하기): 테두리형, 모서리 8dp, 높이 32dp(M3 그대로).
 * 고르면 선택 배경과 선택 칩 테두리, 선택 색 글자다(결정 기록 '디자인(시안)').
 */
@Composable
fun StratumFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val colors = StratumTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = StratumShapes.Control,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.textSecondary,
            iconColor = colors.textSecondary,
            disabledLabelColor = colors.textDisabled,
            disabledLeadingIconColor = colors.textDisabled,
            disabledTrailingIconColor = colors.textDisabled,
            selectedContainerColor = colors.selectedBackground,
            selectedLabelColor = colors.selectedContent,
            selectedLeadingIconColor = colors.selectedContent,
            selectedTrailingIconColor = colors.selectedContent,
            disabledSelectedContainerColor = colors.divider
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.fieldBorder,
            selectedBorderColor = colors.chipSelectedBorder,
            disabledBorderColor = colors.divider,
            disabledSelectedBorderColor = colors.divider,
            borderWidth = StratumDimens.Divider,
            selectedBorderWidth = StratumDimens.Divider
        )
    )
}

/** 분할 버튼 줄. 칸은 [StratumSegment]로 넣는다. */
@Composable
fun StratumSegmentedRow(modifier: Modifier = Modifier, content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = modifier, content = content)
}

/**
 * 분할 버튼의 한 칸([count]칸 중 [index]번째): 모서리 8dp, 테두리, 고른 칸은 선택 배경과 선택 색 글자, 체크 표시 없음.
 * 보이는 높이만 28dp로 줄이고 차지하는 자리는 48dp 그대로다(배치가 움직이지 않는다). 글자 크기도 그대로다.
 * [contentPadding]의 좌우 여백은 M3와 같은 12dp가 기본이라 칸 폭이 그대로다.
 */
@Composable
fun SingleChoiceSegmentedButtonRowScope.StratumSegment(
    selected: Boolean,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = StratumDimens.SegmentPadding,
    label: @Composable () -> Unit
) {
    val colors = StratumTheme.colors
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = SegmentedButtonDefaults.itemShape(index, count, baseShape = StratumShapes.Control),
        modifier = modifier.heightIn(min = StratumDimens.ControlHeight),
        enabled = enabled,
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = colors.selectedBackground,
            activeContentColor = colors.selectedContent,
            activeBorderColor = colors.fieldBorder,
            inactiveContainerColor = Color.Transparent,
            inactiveContentColor = colors.textSecondary,
            inactiveBorderColor = colors.fieldBorder,
            disabledActiveContainerColor = colors.divider,
            disabledActiveContentColor = colors.textDisabled,
            disabledActiveBorderColor = colors.divider,
            disabledInactiveContainerColor = Color.Transparent,
            disabledInactiveContentColor = colors.textDisabled,
            disabledInactiveBorderColor = colors.divider
        ),
        contentPadding = contentPadding,
        icon = {},
        label = label
    )
}

/**
 * 작은 선택 상자(혼합 모드): 모서리 6dp, 안은 입력칸 바탕, 테두리, 오른쪽에 ▾. 누르면 [onClick](메뉴를 연다).
 * 보이는 높이는 28dp이고 누르는 영역과 차지하는 자리는 48dp다. 폭은 글자에 맞춘다.
 */
@Composable
fun SelectBox(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = StratumTheme.colors
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = StratumDimens.ControlHeight)
            .clip(StratumShapes.Field)
            .background(colors.fieldBackground)
            .border(BorderStroke(StratumDimens.Divider, if (enabled) colors.fieldBorder else colors.divider), StratumShapes.Field)
            .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = if (enabled) colors.textPrimary else colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        Icon(
            painterResource(R.drawable.ic_arrow_drop_down),
            contentDescription = null,
            tint = if (enabled) colors.textSecondary else colors.textDisabled
        )
    }
}

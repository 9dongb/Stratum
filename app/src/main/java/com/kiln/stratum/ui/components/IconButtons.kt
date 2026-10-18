package com.kiln.stratum.ui.components

import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 아이콘 버튼. 누를 때 번지는 모양과 고른 표시가 동그라미가 아니라 모서리 8dp 사각형(40dp)이다(결정 기록 '디자인(시안)').
 * 아이콘은 [contentColor](처음은 보조 색)이고 꺼지면 비활성 색이다.
 * [selected]면 선택 배경 위에 선택 색 아이콘으로 보인다(도구 막대의 고른 도구).
 */
@Composable
fun StratumIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentColor: Color = StratumTheme.colors.textSecondary,
    content: @Composable () -> Unit
) {
    val colors = StratumTheme.colors
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) colors.selectedBackground else Color.Transparent,
            contentColor = if (selected) colors.selectedContent else contentColor,
            disabledContentColor = colors.textDisabled
        ),
        shape = StratumShapes.Control,
        content = content
    )
}

/**
 * 켜고 끄는 아이콘 버튼. 켜지면 선택 색 아이콘이고, [checkedBackground]면 선택 배경 사각형(모서리 8dp)도 깐다(스포이드 버튼).
 * 꺼진 동안 아이콘은 [contentColor](처음은 보조 색)다.
 */
@Composable
fun StratumIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedBackground: Boolean = true,
    contentColor: Color = StratumTheme.colors.textSecondary,
    checkedContentColor: Color = StratumTheme.colors.selectedContent,
    content: @Composable () -> Unit
) {
    val colors = StratumTheme.colors
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = contentColor,
            disabledContentColor = colors.textDisabled,
            checkedContainerColor = if (checkedBackground) colors.selectedBackground else Color.Transparent,
            checkedContentColor = checkedContentColor
        ),
        shape = StratumShapes.Control,
        content = content
    )
}

package com.kiln.stratum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

// 작업 화면 상단 바(편집·유동화·커버 새 창)의 버튼(사용자 피드백 10차 6). 바가 상태 표시줄 높이(펼친 화면 약 28dp)로 낮아,
// 누르는 자리는 가로 48dp × 바 높이다(CLAUDE.md 6장 '최소 48dp'의 예외, 사용자 결정). 보이는 사각형은 폭 40dp, 바 높이 − 4dp다.

/** 바 버튼의 누르는 자리 폭(앱의 다른 버튼과 같은 48dp) */
private val TopBarButtonWidth = 48.dp

/** 바 버튼의 보이는 사각형(누를 때 번짐·켜진 표시) 폭 */
private val TopBarHighlightWidth = 40.dp

/** 바 버튼의 보이는 사각형과 바 위아래 사이 여백(한쪽) */
private val TopBarHighlightInset = 2.dp

/** 바가 이보다 낮으면 아이콘을 [CompactIconSize]로 줄인다 */
private val CompactBarHeight = 32.dp
private val CompactIconSize = 20.dp
private val IconSize = 24.dp

/**
 * 상단 바의 아이콘 버튼. [height]는 바 높이다. 꺼지면 아이콘이 비활성 색이고, 누를 때 번지는 표시는 보이는 사각형 안에만 그린다.
 * 아이콘은 바가 낮으면(32dp 미만) 20dp로 줄인다.
 */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    TopBarButtonBox(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        height = height,
        interaction = interaction,
        background = Color.Transparent,
        contentColor = if (enabled) StratumTheme.colors.textSecondary else StratumTheme.colors.textDisabled,
        content = content
    )
}

/**
 * 상단 바의 켜고 끄는 아이콘 버튼(조정·레이어 패널). 켜지면 선택 배경 사각형 위에 선택 색 아이콘이다(앱의 아이콘 토글 버튼과 같다).
 */
@Composable
fun TopBarIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = StratumTheme.colors
    val interaction = remember { MutableInteractionSource() }
    TopBarButtonBox(
        modifier = modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange
        ),
        height = height,
        interaction = interaction,
        background = if (checked) colors.selectedBackground else Color.Transparent,
        contentColor = when {
            !enabled -> colors.textDisabled
            checked -> colors.selectedContent
            else -> colors.textSecondary
        },
        content = content
    )
}

/** 누르는 자리(가로 48dp × 바 높이) 가운데에 보이는 사각형과 아이콘을 둔다 */
@Composable
private fun TopBarButtonBox(
    modifier: Modifier,
    height: Dp,
    interaction: MutableInteractionSource,
    background: Color,
    contentColor: Color,
    content: @Composable () -> Unit
) {
    val iconSize = if (height < CompactBarHeight) CompactIconSize else IconSize
    Box(modifier.size(TopBarButtonWidth, height), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(TopBarHighlightWidth, height - TopBarHighlightInset * 2)
                .clip(StratumShapes.Control)
                .background(background)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) { content() }
            }
        }
    }
}

/**
 * 상단 바의 주요 버튼(내보내기·적용, 파란 채움). 바 높이([height])보다 4dp 낮게 보이고(앱 버튼의 30dp를 넘지 않음), 글자 크기는 그대로다.
 * 바 안에서는 48dp 자리를 차지하지 않는다(누르는 높이는 버튼 높이).
 */
@Composable
fun TopBarAccentButton(
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        AccentButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            minHeight = minOf(TopBarAccentMaxHeight, height - TopBarHighlightInset * 2),
            contentPadding = TopBarAccentPadding,
            content = content
        )
    }
}

/** 주요 버튼의 가장 큰 높이(앱 버튼과 같은 30dp) */
private val TopBarAccentMaxHeight = 30.dp

/** 낮은 바의 주요 버튼 안쪽 여백: 좌우는 앱 버튼과 같고, 위아래는 없앤다(글자 높이만큼) */
private val TopBarAccentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

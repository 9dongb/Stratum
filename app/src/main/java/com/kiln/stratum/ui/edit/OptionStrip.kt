package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.components.AccentIconButton
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumIconToggleButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.layout.HingeAvoidingScrollColumn
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.theme.ColorPickerColors
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 도구 옵션 띠의 폭(결정 기록 '편집 화면 도구 배치'). 값 칸의 이름('Tolerance')과 값('1000 px')이 한 줄에 들어간다. */
internal val OptionStripWidth = 72.dp

/** 값·고르기·색 칸의 높이(누르는 영역 48dp 이상) */
private val CellHeight = 56.dp

/** 슬라이더 옵션 창 안의 폭. 옵션 창(여백 포함 240dp)이 메인 가로에서 접히는 선 왼쪽에 들어간다. */
internal val FlyoutSliderWidth = 216.dp

/**
 * 도구 옵션 띠의 칸 하나. [key]는 띠 안에서 겹치지 않는 이름이고, 화면 테스트 이름표는 `strip_<key>`([EditScreenTags.strip])다.
 * 옵션 창([flyout])이 있는 칸은 누르면 그 창을 열고 닫는다. 없는 칸은 누르면 바로 한다.
 */
sealed interface StripItem {
    val key: String

    /** 옵션 창의 이름(화면 읽기 기능이 창 이름으로 읽는다). 옵션 창이 없으면 null. */
    val title: String?
        get() = null

    /** 누르면 뜨는 옵션 창의 내용. 없으면 바로 하는 칸이다. */
    val flyout: (@Composable () -> Unit)?
        get() = null
}

/** 값 칸: 위에 작은 이름([caption]), 아래에 값([value]). 누르면 옵션 창(보통 슬라이더) */
class ValueCell(
    override val key: String,
    val caption: String,
    val value: String,
    val enabled: Boolean = true,
    override val flyout: @Composable () -> Unit
) : StripItem {
    override val title: String
        get() = caption
}

/**
 * 고르기 칸: 지금 고른 것의 아이콘([icon])과 이름([caption]), 오른쪽 아래 작은 삼각형. 누르면 옵션 창에 고를 것의 목록.
 * 화면 읽기 기능은 [description](무엇을 고르는지와 지금 고른 것)으로 읽는다.
 */
class ChoiceCell(
    override val key: String,
    @DrawableRes val icon: Int,
    val caption: String,
    val description: String,
    val enabled: Boolean = true,
    override val title: String = caption,
    override val flyout: @Composable () -> Unit
) : StripItem

/** 색 칸: 지금 색([color], 무지개 테두리). 누르면 옵션 창에 지금 색(누르면 팔레트)과 최근 색 */
class ColorCell(
    override val key: String,
    val color: Int,
    val caption: String,
    val description: String,
    override val flyout: @Composable () -> Unit
) : StripItem {
    override val title: String
        get() = caption
}

/**
 * 아이콘 버튼 칸. 누르면 바로 [onClick]을 한다. [checked]가 있으면 켜고 끄는 버튼이다(켜지면 선택 배경).
 * [accent]는 파란 채움 버튼(자르기 적용). [tag]가 있으면 그 이름표를 쓴다(커버 옵션과 같은 이름표를 쓰는 버튼).
 */
class IconCell(
    override val key: String,
    @DrawableRes val icon: Int,
    val description: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val accent: Boolean = false,
    val iconRotation: Float = 0f,
    val tag: String? = null
) : StripItem

/** 칸 묶음 사이의 가는 구분선 */
class StripDivider(override val key: String) : StripItem

/**
 * 도구 옵션 띠: 메인 화면에서 도구 막대 오른쪽, 캔버스 위에 겹쳐 위아래 끝까지 놓는 좁은 띠(사진 4, 태블릿 포토샵).
 * 칸이 많으면 위아래로 밀어 보고, 가로로 지나가는 힌지의 띠에 걸리는 칸은 띠 아래로 비켜 놓는다.
 * 배경은 아래 끝까지 칠하고, 칸만 아래 시스템 바를 피한다(도구 막대와 같다).
 * 옵션 창은 [FlyoutHost]가 칸 오른쪽에 띄운다. 칸 자리는 [FloatingState.anchors]에 적는다.
 */
@Composable
fun OptionStrip(items: List<StripItem>, state: FloatingState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(OptionStripWidth)
            .edgeDivider(DividerEdge.End, StratumTheme.colors.divider)
            .testTag(EditScreenTags.OPTION_STRIP),
        color = StratumTheme.colors.panel
    ) {
        HingeAvoidingScrollColumn(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom))
                .padding(vertical = 8.dp),
            gap = 4.dp
        ) {
            for (item in items) {
                key(item.key) { StripCellView(item, state) }
            }
        }
    }
}

/** 칸 하나의 자리(띠 폭 전체)와 모양. 자리가 바뀌면 [FloatingState.anchors]에 적는다. */
@Composable
private fun StripCellView(item: StripItem, state: FloatingState) {
    val open = state.flyout == item.key
    Box(
        modifier = Modifier
            .width(OptionStripWidth)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val region = Region(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                if (state.anchors[item.key] != region) state.anchors[item.key] = region
            },
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is ValueCell -> ValueCellView(item, open) { state.toggleFlyout(item.key) }
            is ChoiceCell -> ChoiceCellView(item, open) { state.toggleFlyout(item.key) }
            is ColorCell -> ColorCellView(item, open) { state.toggleFlyout(item.key) }
            is IconCell -> IconCellView(item) {
                // 다른 칸의 옵션 창은 닫고 바로 한다
                state.closeFlyout()
                item.onClick()
            }
            is StripDivider -> HorizontalDivider(Modifier.width(40.dp).padding(vertical = 4.dp), color = StratumTheme.colors.divider)
        }
    }
}

/**
 * 누를 수 있는 칸의 바탕: 띠 폭에서 양쪽 4dp를 뺀 모서리 8dp 칸. 옵션 창이 열려 있으면 선택 배경이다.
 * 화면 읽기 기능에는 [description]과 [state](값)를 읽히고, 안쪽 글자는 따로 읽지 않는다.
 */
@Composable
private fun CellBox(
    tag: String,
    description: String,
    state: String?,
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = StratumTheme.colors
    Column(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .fillMaxWidth()
            .heightIn(min = CellHeight)
            .testTag(tag)
            .clearAndSetSemantics {
                contentDescription = description
                if (state != null) stateDescription = state
                role = Role.Button
                selected = open
                if (!enabled) disabled()
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            }
            .clip(StratumShapes.Control)
            .background(if (open) colors.selectedBackground else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}

@Composable
private fun ValueCellView(cell: ValueCell, open: Boolean, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    CellBox(EditScreenTags.strip(cell.key), cell.caption, cell.value, open, cell.enabled, onClick) {
        Text(
            text = cell.caption,
            color = if (cell.enabled) colors.textDescription else colors.textDisabled,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = cell.value,
            color = when {
                !cell.enabled -> colors.textDisabled
                open -> colors.selectedContent
                else -> colors.textPrimary
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChoiceCellView(cell: ChoiceCell, open: Boolean, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    val iconColor = when {
        !cell.enabled -> colors.textDisabled
        open -> colors.selectedContent
        else -> colors.textSecondary
    }
    CellBox(EditScreenTags.strip(cell.key), cell.description, null, open, cell.enabled, onClick) {
        CompositionLocalProvider(LocalContentColor provides iconColor) {
            Box {
                Icon(painter = painterResource(cell.icon), contentDescription = null)
                // 고를 것이 더 있다는 작은 삼각형(도구 막대의 하위 도구 표시와 같다)
                SubToolMark(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp))
            }
        }
        Text(
            text = cell.caption,
            color = if (cell.enabled) colors.textDescription else colors.textDisabled,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ColorCellView(cell: ColorCell, open: Boolean, onClick: () -> Unit) {
    CellBox(EditScreenTags.strip(cell.key), cell.description, null, open, enabled = true, onClick = onClick) {
        // 지금 색: 색 줄의 지금 색처럼 무지개 테두리가 '색 고르기'를 알린다
        Box(
            Modifier
                .size(28.dp)
                .border(BorderStroke(3.dp, Brush.sweepGradient(ColorPickerColors.Hues)), StratumShapes.Dot)
                .padding(4.dp)
                .background(Color(cell.color or OPAQUE_ALPHA), StratumShapes.Dot)
        )
        Text(
            text = cell.caption,
            color = StratumTheme.colors.textDescription,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IconCellView(cell: IconCell, onClick: () -> Unit) {
    val tag = cell.tag ?: EditScreenTags.strip(cell.key)
    val icon: @Composable () -> Unit = {
        Icon(
            painter = painterResource(cell.icon),
            contentDescription = cell.description,
            modifier = if (cell.iconRotation != 0f) Modifier.rotate(cell.iconRotation) else Modifier
        )
    }
    when {
        cell.checked != null -> StratumIconToggleButton(
            checked = cell.checked,
            onCheckedChange = { onClick() },
            enabled = cell.enabled,
            modifier = Modifier.testTag(tag),
            content = icon
        )
        cell.accent -> AccentIconButton(onClick = onClick, enabled = cell.enabled, modifier = Modifier.testTag(tag), content = icon)
        else -> StratumIconButton(onClick = onClick, enabled = cell.enabled, modifier = Modifier.testTag(tag), content = icon)
    }
}

/** 슬라이더 옵션 창의 내용 자리(폭 [FlyoutSliderWidth]) */
@Composable
internal fun FlyoutSlider(content: @Composable () -> Unit) {
    Box(Modifier.width(FlyoutSliderWidth)) { content() }
}

/** 불투명한 색의 알파 */
private const val OPAQUE_ALPHA = 0xFF000000.toInt()

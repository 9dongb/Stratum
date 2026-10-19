package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.AccentIconButton
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumIconToggleButton
import com.kiln.stratum.ui.components.StratumVerticalSlider
import com.kiln.stratum.ui.components.scrollFadeEdges
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.Span
import com.kiln.stratum.ui.layout.pieceAt
import com.kiln.stratum.ui.layout.stripTop
import com.kiln.stratum.ui.theme.ColorPickerColors
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.ui.theme.compactTextAutoSize
import kotlin.math.roundToInt

/**
 * 도구 옵션 띠의 폭: 도구 막대와 같다(사용자 요청, 결정 기록 '도구 옵션 띠').
 * 값 칸의 이름('Tolerance')·값('1000 px')이 넘치면 한 줄에 들어갈 때까지 글자를 줄인다([compactTextAutoSize]).
 */
internal val OptionStripWidth = ToolRailWidth

/** 도구 옵션 띠와 도구 막대 사이 간격(떠 있는 카드로 보이게) */
internal val OptionStripGap = 4.dp

/** 도구 옵션 띠와 캔버스 위아래 끝 사이 여백 */
private val OptionStripMargin = 8.dp

/** 값·고르기·색 칸의 높이(누르는 영역 48dp 이상) */
private val CellHeight = 56.dp

/** 옵션 창 안 내용의 폭(자르기 비율 칸 등). 옵션 창(여백 포함 240dp)이 메인 가로에서 접히는 선 왼쪽에 들어간다. */
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

/**
 * 슬라이더 칸: 값 칸처럼 위에 작은 이름([caption]), 아래에 값([value])을 보이고, 누르면 칸 옆에 얇은 세로 슬라이더 창이 뜬다
 * (사용자 요청 '세로 슬라이더', 결정 기록 '도구 옵션 띠'). [extra]는 값 표시 아래에 함께 둘 것(허용치의 '이어진 곳만')이다.
 */
class SliderCell(
    override val key: String,
    val caption: String,
    val value: String,
    val slider: StripSlider,
    val enabled: Boolean = true,
    val extra: (@Composable () -> Unit)? = null
) : StripItem {
    override val title: String
        get() = caption
}

/**
 * 슬라이더 칸의 값과 할 일. [fraction]은 0(아래 끝)~1(위 끝), [onFraction]은 끄는 대로 부르고 [onFinished]는 손을 뗄 때 부른다.
 * [tag]는 슬라이더 이름표(커버 옵션의 슬라이더와 같다). [preview]가 있으면 슬라이더 창이 사라질 때 크기 원을 끈다.
 */
class StripSlider(
    val fraction: Float,
    val onFraction: (Float) -> Unit,
    val tag: String,
    val onFinished: () -> Unit = {},
    val preview: SizePreviewState? = null
)

/** 칸 묶음 사이의 가는 구분선 */
class StripDivider(override val key: String) : StripItem

/**
 * 도구 옵션 띠를 고른 도구([tool]) 옆에 띄우는 자리(사진 4, 태블릿 포토샵). 받은 영역(캔버스)을 다 차지하지만 터치는 띠만 받는다.
 * 띠는 도구 막대에서 [OptionStripGap]만큼 띄우고, 세로로는 도구 버튼을 가운데로 두되 위쪽 자리가 모자라면 버튼에서 시작하고
 * 아래쪽이 모자라면 버튼에서 끝낸다([stripTop]). 가로 힌지가 있으면 도구가 있는 쪽 조각 안에 두고, 조각보다 길면 띠 안에서 밀어 본다.
 * 자리는 배치하는 순간 도구 버튼 자리([FloatingState.toolAnchors], 창 좌표)와 자기 위치, 힌지를 읽어 정한다(한 프레임 늦지 않음).
 */
@Composable
fun OptionStripHost(items: List<StripItem>, state: FloatingState, tool: EditTool, modifier: Modifier = Modifier) {
    val hinges = LocalHinges.current
    Layout(content = { OptionStrip(items, state) }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val measurable = measurables.firstOrNull() ?: return@layout
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val top = origin.y.roundToInt()
            val margin = OptionStripMargin.roundToPx()
            val area = Span(top + margin, top + height - margin)
            // 고른 도구 버튼의 위아래(창 좌표). 아직 재지 않았으면 영역 위 끝에 붙인다.
            val anchor = state.toolAnchors[tool]?.let { Span(it.top, it.bottom) } ?: Span(area.start, area.start)
            val bands = hinges.filter { !it.isVertical }.map { it.band(HingeMargin.roundToPx()) }
            val piece = pieceAt(area, bands, (anchor.start + anchor.end) / 2)
            val placeable = measurable.measure(Constraints(maxWidth = width, maxHeight = (piece.end - piece.start).coerceAtLeast(0)))
            placeable.place(OptionStripGap.roundToPx(), stripTop(anchor, placeable.height, piece) - top)
        }
    }
}

/**
 * 도구 옵션 띠: 칸만큼의 높이로 떠 있는 좁은 카드(도구 막대와 같은 폭, 패널 색, 모서리 8dp, 테두리·그림자). 자리는 [OptionStripHost]가 정한다.
 * 받은 높이보다 칸이 많으면 위아래로 밀어 본다. 옵션 창은 [FlyoutHost]가 칸 오른쪽에 띄운다. 칸 자리는 [FloatingState.anchors]에 적는다.
 */
@Composable
private fun OptionStrip(items: List<StripItem>, state: FloatingState) {
    Surface(
        modifier = Modifier
            .width(OptionStripWidth)
            .testTag(EditScreenTags.OPTION_STRIP),
        shape = StratumShapes.Card,
        color = StratumTheme.colors.panel,
        border = BorderStroke(StratumDimens.Divider, StratumTheme.colors.fieldBorder),
        shadowElevation = 6.dp
    ) {
        // 받은 높이보다 칸이 많으면(메인 세로에서 접히는 선 위에 다 들어가지 않는 선택 도구 등) 밀어 보고, 더 있는 쪽 끝을 흐리게 알린다
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .scrollFadeEdges(scroll, StratumTheme.colors.panel)
                .verticalScroll(scroll)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val region = Region(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                if (state.anchors[item.key] != region) state.anchors[item.key] = region
            },
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is ValueCell -> ValueCellView(item.key, item.caption, item.value, item.enabled, open) { state.toggleFlyout(item.key) }
            is SliderCell -> ValueCellView(item.key, item.caption, item.value, item.enabled, open) { state.toggleFlyout(item.key) }
            is ChoiceCell -> ChoiceCellView(item, open) { state.toggleFlyout(item.key) }
            is ColorCell -> ColorCellView(item, open) { state.toggleFlyout(item.key) }
            is IconCell -> IconCellView(item) {
                // 다른 칸의 옵션 창은 닫고 바로 한다
                state.closeFlyout()
                item.onClick()
            }
            is StripDivider -> HorizontalDivider(Modifier.width(32.dp).padding(vertical = 4.dp), color = StratumTheme.colors.divider)
        }
    }
}

/**
 * 누를 수 있는 칸의 바탕: 띠 폭에서 양쪽 4dp를 뺀 모서리 8dp 칸(폭 48dp, 누르는 영역 그대로). 옵션 창이 열려 있으면 선택 배경이다.
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

/** 값 칸·슬라이더 칸의 모양: 위에 작은 이름, 아래에 값 */
@Composable
private fun ValueCellView(key: String, caption: String, value: String, enabled: Boolean, open: Boolean, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    CellBox(EditScreenTags.strip(key), caption, value, open, enabled, onClick) {
        CompactLabel(caption, if (enabled) colors.textDescription else colors.textDisabled)
        Text(
            text = value,
            color = when {
                !enabled -> colors.textDisabled
                open -> colors.selectedContent
                else -> colors.textPrimary
            },
            style = MaterialTheme.typography.labelLarge,
            autoSize = compactTextAutoSize(MaterialTheme.typography.labelLarge),
            maxLines = 1,
            overflow = TextOverflow.Clip
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
        CompactLabel(cell.caption, if (cell.enabled) colors.textDescription else colors.textDisabled)
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
        CompactLabel(cell.caption, StratumTheme.colors.textDescription)
    }
}

/** 칸의 작은 이름. 칸 폭(48dp)에 한 줄로 다 들어가지 않으면 글자를 줄인다('Hardness'·'Spot heal' 등). */
@Composable
private fun CompactLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        autoSize = compactTextAutoSize(MaterialTheme.typography.labelSmall),
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
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

/** 세로 슬라이더 창의 슬라이더 카드 크기: 폭은 누르는 영역(48dp), 높이는 받은 영역보다 길면 줄인다 */
private val VerticalSliderWidth = 48.dp
private val VerticalSliderHeight = 240.dp

/**
 * 슬라이더 칸의 옵션 창(사진: 태블릿 포토샵): 얇은 세로 슬라이더 카드와 그 오른쪽 위의 값 표시('경도 82%'),
 * 값 표시 아래에는 [SliderCell.extra]. 카드를 가리키는 삼각형은 [FlyoutHost]가 칸 쪽에 그린다.
 * 값 표시는 슬라이더와 같은 내용이라 화면 읽기 기능에서는 빼고, 슬라이더가 이름·값을 읽는다.
 */
@Composable
internal fun VerticalSliderFlyout(cell: SliderCell) {
    val slider = cell.slider
    EndPreviewOnDispose(slider.preview)
    val colors = StratumTheme.colors
    Row(Modifier.testTag(EditScreenTags.OPTION_FLYOUT).semantics { paneTitle = cell.caption }, verticalAlignment = Alignment.Top) {
        Surface(
            shape = StratumShapes.Card,
            color = colors.panel,
            border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
            shadowElevation = 6.dp
        ) {
            StratumVerticalSlider(
                value = slider.fraction,
                onValueChange = slider.onFraction,
                onValueChangeFinished = slider.onFinished,
                enabled = cell.enabled,
                modifier = Modifier
                    .width(VerticalSliderWidth)
                    .height(VerticalSliderHeight)
                    .testTag(slider.tag)
                    .semantics {
                        contentDescription = cell.caption
                        stateDescription = cell.value
                    }
            )
        }
        Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                modifier = Modifier.testTag(EditScreenTags.STRIP_SLIDER_VALUE).clearAndSetSemantics {},
                shape = StratumShapes.Card,
                color = colors.panel,
                border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = stringResource(R.string.strip_slider_value, cell.caption, cell.value),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
            cell.extra?.let { extra ->
                Surface(
                    shape = StratumShapes.Card,
                    color = colors.panel,
                    contentColor = colors.textPrimary,
                    border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
                    shadowElevation = 6.dp
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { extra() }
                }
            }
        }
    }
}

/** 세로 슬라이더 창이 칸을 가리키는 작은 삼각형의 크기 */
internal val FlyoutArrowWidth = 8.dp
private val FlyoutArrowHeight = 16.dp

/** 세로 슬라이더 창 왼쪽에서 칸을 가리키는 삼각형(패널 색, 바깥 두 변만 테두리). 창 카드의 테두리를 덮도록 조금 겹쳐 놓는다. */
@Composable
internal fun FlyoutArrow() {
    val colors = StratumTheme.colors
    Canvas(Modifier.size(width = FlyoutArrowWidth + 2.dp, height = FlyoutArrowHeight)) {
        val tip = Offset(0f, size.height / 2)
        val baseX = FlyoutArrowWidth.toPx()
        val fill = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(baseX, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(baseX, size.height)
            close()
        }
        drawPath(fill, colors.panel)
        val edge = Path().apply {
            moveTo(baseX, 0f)
            lineTo(tip.x, tip.y)
            lineTo(baseX, size.height)
        }
        drawPath(edge, colors.fieldBorder, style = Stroke(StratumDimens.Divider.toPx()))
    }
}


/** 불투명한 색의 알파 */
private const val OPAQUE_ALPHA = 0xFF000000.toInt()

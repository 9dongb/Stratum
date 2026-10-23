package com.kiln.stratum.ui.edit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.MenuLongPress
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumVerticalSlider
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.components.scrollFadeEdges
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.ui.theme.compactTextAutoSize

/**
 * 커버를 가로로 돌렸을 때의 한 줄 막대(사용자 피드백 12차 3: "커버화면을 가로로 돌리면 펼친화면 배치가 아닌, 커버화면 배치가 돌아간형태로",
 * 질문에서 "막대를 오른쪽 세로로"). 가로 막대([CoverToolBar])를 돌려 캔버스 오른쪽에 세로로 세운 것이다.
 * 칩은 위에서 아래로 쌓인다(넘치면 위아래로 밀어 보고 더 있는 쪽 끝을 흐리게 그림, 도구를 닫는 ✕는 없다, 2026-10-23). 칸은 펼친 화면 띠의 칸처럼
 * 두 줄(이름·값)이고([CellBox]), 값·할 일·이름표([EditScreenTags.bar])는 가로 막대와 같다.
 * 칩을 누르면 같은 기둥이 그 칩의 내용(세로 슬라이더·고를 것·동작·조정 슬라이더)이 되고 맨 아래 ✓로 칩 기둥으로 돌아온다.
 * 펼친 칩과 밀어 본 자리는 가로 막대와 같은 [state]·[content]에 딸린다. 칩이 없으면 [empty]를 세로 글자로 쓴다.
 * 폭은 가로 막대의 높이([coverRowHeight])와 같아 도구를 바꿔도 사진 크기가 그대로다. 아래 시스템 바는 피한다.
 */
@Composable
fun CoverToolColumn(
    items: List<BarItem>,
    state: CoverBarState,
    modifier: Modifier = Modifier,
    content: String = "",
    empty: String? = null
) {
    val colors = StratumTheme.colors
    val expandedKey = state.expandedFor(content)
    val open = items.firstOrNull { it.key == expandedKey }
    // 칩을 펼쳤다 ✓로 돌아와도 칩 기둥이 처음으로 돌아가지 않게 막대 내용마다 기억한다
    val scroll = remember(content) { ScrollState(0) }
    val expand: (String) -> Unit = { key -> state.expand(key, content) }
    Surface(
        modifier.fillMaxHeight().edgeDivider(DividerEdge.Start, colors.divider).testTag(EditScreenTags.COVER_TOOL_BAR),
        color = colors.panel
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom))
                .width(coverRowHeight())
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (open) {
                null -> {
                    if (items.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { VerticalHint(empty.orEmpty()) }
                    } else {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .scrollFadeEdges(scroll, colors.panel)
                                .verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 칸마다 막대 내용과 함께 따로 기억한다(크기·경도처럼 여러 도구에 있는 칸도, 끄는 도중 도구를 바꾸면
                            // 끄던 손가락 처리가 새 도구의 칸으로 넘어가지 않게)
                            for (item in items) key(content, item.key) { BarCell(item, expand) }
                        }
                    }
                }
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) { BarExpandedColumn(open, state) }
                    StratumIconButton(
                        onClick = state::collapse,
                        contentColor = colors.selectedContent,
                        modifier = Modifier.testTag(EditScreenTags.BAR_DONE)
                    ) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.action_done))
                    }
                }
            }
        }
    }
}

/**
 * 기둥의 칩 하나(띠 칸 모양). 값·고르기·묶음 칩은 누르면 [expand](같은 기둥이 그 칩의 내용)다.
 * 값 칸은 누른 채 왼쪽(캔버스 쪽)으로 끌면 값이 커지고(세로 커버의 위로 끌기를 돌린 것, 기둥은 위아래로 밀어 본다),
 * 0으로 되돌릴 값(-100~100)은 꾹 눌러 0이다(공통 수치 칸 기능 [valueGestures], 2026-10-23).
 */
@Composable
private fun BarCell(item: BarItem, expand: (String) -> Unit) {
    val colors = StratumTheme.colors
    val tag = EditScreenTags.bar(item.key)
    when (item) {
        is BarValue -> {
            val enabled = item.cell.enabled
            var scrubbing by remember { mutableStateOf(false) }
            val touch = if (enabled) {
                Modifier.valueGestures(item.cell.slider, ScrubAxis.HORIZONTAL, item.cell.caption, item.cell.value, showValue = true) { scrubbing = it }
            } else {
                Modifier
            }
            CellBox(
                tag, item.cell.caption, item.cell.value, open = scrubbing, enabled = enabled, onClick = { expand(item.key) },
                touch = touch, onReset = item.cell.slider.onReset
            ) {
                CompactLabel(item.cell.caption, if (enabled) colors.textDescription else colors.textDisabled)
                Text(
                    text = item.cell.value,
                    color = if (enabled) colors.textPrimary else colors.textDisabled,
                    style = MaterialTheme.typography.labelLarge,
                    autoSize = compactTextAutoSize(MaterialTheme.typography.labelLarge),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
        is BarChoice -> {
            val current = item.current
            val color = if (item.enabled) colors.textSecondary else colors.textDisabled
            // 꾹 누르면 칸 왼쪽에 고를 것 목록, 누른 채 끌어 떼면 고른다(사용자 요청 'C', 2026-10-23)
            val menu = LocalChipMenu.current
            val bounds = remember { arrayOfNulls<Region>(1) }
            MenuLongPress {
                CellBox(
                    tag, item.caption, current?.description, open = false, enabled = item.enabled, onClick = { expand(item.key) },
                    touch = Modifier.chipMenuOnHold(menu, item, MenuSide.START, bounds),
                    onLongClick = menu?.let { { openChipMenu(it, item, MenuSide.START, bounds) } }
                ) {
                    Box {
                        current?.icon?.let { Icon(painterResource(it), contentDescription = null, tint = color) }
                        // 고를 것이 더 있다는 작은 삼각형(띠의 고르기 칸과 같다)
                        SubToolMark(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp))
                    }
                    CompactLabel(current?.label.orEmpty(), if (item.enabled) colors.textDescription else colors.textDisabled)
                }
            }
        }
        // 전경색·배경색·바꾸기·스포이드를 위아래로(누르는 영역은 모두 48dp)
        is BarColors -> Column(Modifier.testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) { ColorButtons(item.controls) }
        is BarButton -> if (item.accent) {
            // 파란 버튼(AI 제거의 '지우기')은 아이콘만, 화면 읽기 기능은 이름을 읽는다
            AccentButton(
                onClick = item.onClick,
                enabled = item.enabled,
                modifier = Modifier
                    .testTag(item.tag ?: tag)
                    .semantics { contentDescription = item.description ?: item.label }
            ) {
                item.icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp)) }
            }
        } else {
            val color = if (item.enabled) colors.textSecondary else colors.textDisabled
            CellBox(item.tag ?: tag, item.description ?: item.label, null, open = false, enabled = item.enabled, onClick = item.onClick) {
                item.icon?.let { Icon(painterResource(it), contentDescription = null, tint = color, modifier = Modifier.rotate(item.iconRotation)) }
                CompactLabel(item.label, if (item.enabled) colors.textDescription else colors.textDisabled)
            }
        }
        // 켜고 끄는 칩은 켜지면 선택 배경이다
        is BarToggle -> CellBox(item.tag ?: tag, item.label, null, open = item.checked, enabled = item.enabled, onClick = { item.onChange(!item.checked) }) {
            CompactLabel(item.label, if (item.enabled) colors.textSecondary else colors.textDisabled)
        }
        is BarGroup -> CellBox(tag, item.caption, null, open = false, enabled = true, onClick = { expand(item.key) }) {
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = colors.textSecondary)
            CompactLabel(item.caption, colors.textDescription)
        }
        is BarCustom -> (item.cell ?: item.chip)({ expand(item.key) })
    }
}

/** 펼친 칩의 내용(맨 아래 ✓는 기둥이 둔다) */
@Composable
private fun BarExpandedColumn(item: BarItem, state: CoverBarState) {
    val colors = StratumTheme.colors
    when (item) {
        // 위에서 아래로 [이름 | 세로 슬라이더 | 값](세로 커버 막대의 [이름 | 슬라이더 | 값 | ✓]를 돌린 것, 맨 아래 ✓는 기둥이 둔다).
        // 이름은 기둥 위 끝과 슬라이더 사이, 값은 슬라이더 끝과 ✓ 사이의 가운데다(커버 수치 조절 공통, 2026-10-23 사용자 요청).
        // 슬라이더는 위로 끌면 커지는 띠의 세로 슬라이더 창과 같은 얇은 슬라이더다.
        is BarValue -> {
            val slider = item.cell.slider
            EndPreviewOnDispose(slider.preview)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(ColumnSliderTextSpace), contentAlignment = Alignment.Center) {
                    CompactLabel(item.cell.caption, colors.textDescription)
                }
                StratumVerticalSlider(
                    value = slider.fraction,
                    onValueChange = slider.onFraction,
                    onValueChangeFinished = slider.onFinished,
                    enabled = item.cell.enabled,
                    origin = slider.origin,
                    onThumbDoubleTap = slider.onReset,
                    modifier = Modifier
                        .weight(1f)
                        .width(SliderThickness)
                        .padding(vertical = 8.dp)
                        .testTag(slider.tag)
                        .sliderSemantics(item.cell.caption, item.cell.value, slider.onReset.takeIf { item.cell.enabled })
                )
                Box(Modifier.fillMaxWidth().height(ColumnSliderTextSpace), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.cell.value,
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        autoSize = compactTextAutoSize(MaterialTheme.typography.labelLarge),
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
        // 고를 것들을 위아래로. 고르면 칩 기둥으로 돌아간다.
        is BarChoice -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (option in item.options) {
                CellBox(
                    option.tag,
                    option.description,
                    null,
                    open = option.selected,
                    enabled = true,
                    onClick = {
                        option.onClick()
                        state.collapse()
                    }
                ) {
                    option.icon?.let {
                        Icon(painterResource(it), contentDescription = null, tint = if (option.selected) colors.selectedContent else colors.textSecondary)
                    }
                    CompactLabel(option.label, if (option.selected) colors.selectedContent else colors.textDescription)
                }
            }
        }
        is BarGroup -> {
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().scrollFadeEdges(scroll, colors.panel).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                (item.columnContent ?: item.content)()
            }
        }
        is BarCustom -> (item.columnContent ?: item.content)()
        // 색 버튼·버튼·켜고 끄는 칩은 펼치지 않는다
        is BarColors, is BarButton, is BarToggle -> Unit
    }
}

/** 칩이 없는 세로 기둥의 흐린 글자(칩이 없는 도구의 이름, 유동화 얼굴 모드의 안내): 아래에서 위로 읽는 세로 글자. 기둥보다 길면 줄임표. */
@Composable
private fun VerticalHint(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .testTag(EditScreenTags.BAR_HINT)
            .layout { measurable, constraints ->
                // 가로·세로를 바꿔 재고, 가운데를 맞춰 -90° 돌려 놓는다
                val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
                layout(placeable.height, placeable.width) {
                    placeable.placeWithLayer(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2) {
                        rotationZ = -90f
                    }
                }
            },
        color = StratumTheme.colors.textDescription,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 펼친 세로 슬라이더의 폭(누르는 영역) */
private val SliderThickness = 48.dp

/**
 * 펼친 세로 슬라이더 위 이름 칸·아래 값 칸의 높이. 글자를 칸 가운데에 두어 이름은 기둥 위 끝과 슬라이더 사이,
 * 값은 슬라이더 끝과 ✓ 사이의 가운데에 온다(커버 수치 조절 공통, 2026-10-23 사용자 요청). 조정 기둥도 같은 높이를 쓴다.
 */
internal val ColumnSliderTextSpace = 40.dp

package com.kiln.stratum.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.MenuLongPress
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 커버 화면(Compact) 아래 도구 탭 줄(결정 기록 '커버 화면 개편'): [자르기][선택][브러시][리터칭][유동화][더보기]
 * (사용자 지정: "기본 도구 목록은 자르기, 선택, 브러시, 리터칭, 유동화, 더보기 로 해주세요"). 여섯 칸이 폭을 똑같이 나누고 밀어 보지 않는다.
 * 도구 칸은 누르면 그 도구를 고르고, 다시 누르면 내려놓는다. 하위 도구가 있는 도구는 길게 누르거나 고른 뒤 다시 누르면 하위 도구 메뉴가 뜬다.
 * 도구 칸을 누르면 [onTabTap]을 먼저 부른다(편집 화면이 더보기 줄과 조정 막대를 닫는다).
 * 더보기 칸은 나머지 도구 줄([CoverMoreTools])을 열고 닫는다([onMore]). 더보기 줄의 도구를 골라 두면 더보기 칸이 그 도구의 아이콘·이름으로 켜진다.
 * 고른 칸은 도구 막대처럼 아이콘 뒤 모서리 8dp 사각형(선택 배경)과 선택 색 아이콘·이름이다. 아래 시스템 바는 피한다(키보드는 보지 않는다).
 *
 * @param enabled 문서가 열려 있어 도구를 쓸 수 있는지
 * @param moreOpen 더보기 줄이 열려 있는지(더보기 칸이 켜진 모양)
 */
@Composable
fun ToolTabBar(
    selection: ToolSelection,
    enabled: Boolean,
    onChoose: (SubTool) -> Unit,
    moreOpen: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onTabTap: () -> Unit = {},
    menu: SubToolMenuState = remember { SubToolMenuState() }
) {
    val colors = StratumTheme.colors
    Surface(
        modifier.fillMaxWidth().edgeDivider(DividerEdge.Top, colors.divider).testTag(EditScreenTags.TOOL_TAB_BAR),
        color = colors.background
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .height(coverRowHeight())
                .testTag(EditScreenTags.TOOL_TAB_ROW)
        ) {
            for (tool in EditTool.coverTabs) {
                key(tool) { TabToolItem(tool, selection, enabled, menu, onChoose, onTabTap) }
            }
            MoreTabItem(selection, enabled, moreOpen, onMore)
        }
    }
}

/**
 * 탭 칸 하나. 칸에는 짧은 이름을 보이고, 화면 읽기 기능은 긴 이름(예: 자르기·변형)을 읽는다.
 * 하위 도구가 있으면 길게 누르거나(메뉴 묶음 0.3초) 고른 뒤 다시 눌러 [menu]를 탭 위에 연다. 길게 누른 채 끌어 항목 위에서 떼면 고른다(도구 막대와 같다).
 */
@Composable
private fun RowScope.TabToolItem(
    tool: EditTool,
    selection: ToolSelection,
    enabled: Boolean,
    menu: SubToolMenuState,
    onChoose: (SubTool) -> Unit,
    onTabTap: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val menuLabel = stringResource(R.string.subtool_menu)
    val label = stringResource(tool.label)
    val itemEnabled = enabled && tool.available
    val selected = selection.active == tool
    // 칸 자리(창 좌표). 메뉴를 그 위에 띄운다.
    val bounds = remember { arrayOfNulls<Region>(1) }
    val openMenu = {
        bounds[0]?.let { menu.open(tool, it, MenuSide.ABOVE) }
        Unit
    }
    // 고른 도구를 다시 누르면 하위 도구 메뉴를 연다(하위 도구가 없는 도구는 내려놓는다)
    val tap = {
        onTabTap()
        if (selected && tool.hasSubTools) openMenu() else selection.tap(tool)
    }
    // 메뉴를 여는 꾹 누르기는 메뉴 묶음 시간(0.3초)이다(결정 기록 '꾹 누르기 시간')
    MenuLongPress {
        TabCell(
            selected = selected,
            enabled = itemEnabled,
            label = stringResource(tool.shortLabel),
            onClick = tap,
            modifier = Modifier
                .testTag(EditScreenTags.tabTool(tool))
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    val left = position.x.roundToInt()
                    val top = position.y.roundToInt()
                    bounds[0] = Region(left, top, left + coordinates.size.width, top + coordinates.size.height)
                }
                .then(
                    if (itemEnabled && tool.hasSubTools) {
                        Modifier.longPressFirst(
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                openMenu()
                            },
                            onDrag = { if (menu.hover(it)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                            onRelease = { position ->
                                menu.release(position)?.let {
                                    menu.close()
                                    onChoose(it)
                                }
                            }
                        )
                    } else {
                        Modifier
                    }
                )
                // 안쪽 누르기의 의미를 지우므로 역할·선택·누르기를 여기서 다시 알린다
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Tab
                    this.selected = selected
                    if (itemEnabled) {
                        onClick(label) {
                            tap()
                            true
                        }
                        if (tool.hasSubTools) {
                            onLongClick(menuLabel) {
                                openMenu()
                                true
                            }
                        }
                    } else {
                        disabled()
                    }
                }
        ) {
            ToolIcon(tool, contentDescription = null)
        }
    }
}

/**
 * 탭 줄 맨 끝의 더보기 칸. 누르면 더보기 줄을 열고 닫는다. 더보기 줄의 도구를 골라 두면 그 도구의 아이콘·이름으로 켜지고,
 * 화면 읽기 기능은 '더보기: 지우개'처럼 읽는다. 더보기 줄이 열려 있어도 켜진 모양이다.
 */
@Composable
private fun RowScope.MoreTabItem(selection: ToolSelection, enabled: Boolean, open: Boolean, onMore: () -> Unit) {
    val active = selection.active?.takeIf { it in EditTool.coverMoreTools }
    val moreLabel = stringResource(R.string.tool_more)
    val description = active?.let { stringResource(R.string.tool_more_active, stringResource(it.label)) } ?: moreLabel
    val selected = open || active != null
    TabCell(
        selected = selected,
        enabled = enabled,
        label = active?.let { stringResource(it.shortLabel) } ?: moreLabel,
        onClick = onMore,
        modifier = Modifier
            .testTag(EditScreenTags.MORE_TAB)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Tab
                this.selected = selected
                if (enabled) {
                    onClick(description) {
                        onMore()
                        true
                    }
                } else {
                    disabled()
                }
            }
    ) {
        // 더보기 칸은 하위 도구 메뉴를 열지 않아 삼각형 없이 아이콘만 그린다(하위 도구는 막대에서 고른다)
        Icon(painterResource(active?.icon ?: R.drawable.ic_grid_view), contentDescription = null)
    }
}

/**
 * 커버 화면 더보기 줄(결정 기록 '커버 화면 개편'): 탭 줄에 없는 도구(이동·지우개·텍스트·명암·흐림·모자이크)를 한 줄로 보인다.
 * 탭 줄의 더보기 칸을 누르면 캔버스 아래쪽 위에 뜨고, 하나를 고르면([onPick]) 편집 화면이 닫는다.
 * 칸 모양은 탭 줄과 같고 고른 도구는 선택 표시다. 화면 읽기 기능에는 창 이름 '도구 더보기'로 알린다.
 */
@Composable
fun CoverMoreTools(selection: ToolSelection, enabled: Boolean, onPick: (EditTool) -> Unit, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val title = stringResource(R.string.more_tools_title)
    Surface(
        modifier
            .fillMaxWidth()
            .edgeDivider(DividerEdge.Top, colors.divider)
            .testTag(EditScreenTags.COVER_MORE_TOOLS)
            .semantics { paneTitle = title },
        color = colors.panel
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                .height(coverRowHeight())
        ) {
            for (tool in EditTool.coverMoreTools) {
                key(tool) {
                    val label = stringResource(tool.label)
                    val selected = selection.active == tool
                    val itemEnabled = enabled && tool.available
                    TabCell(
                        selected = selected,
                        enabled = itemEnabled,
                        label = stringResource(tool.shortLabel),
                        onClick = { onPick(tool) },
                        modifier = Modifier
                            .testTag(EditScreenTags.moreTool(tool))
                            .clearAndSetSemantics {
                                contentDescription = label
                                role = Role.Button
                                this.selected = selected
                                if (itemEnabled) {
                                    onClick(label) {
                                        onPick(tool)
                                        true
                                    }
                                } else {
                                    disabled()
                                }
                            }
                    ) {
                        Icon(painterResource(tool.icon), contentDescription = null)
                    }
                }
            }
        }
    }
}

/**
 * 탭 줄·더보기 줄의 칸 모양: 아이콘(고르면 뒤에 모서리 8dp 사각형의 선택 배경)과 그 아래 짧은 이름. 칸 폭은 줄을 똑같이 나눈다.
 * 아이콘은 보조 색·이름은 설명색이고, 고른 칸은 선택 색, 꺼진 칸은 비활성 색이다. 누를 때 번지는 표시는 그리지 않는다(고른 칸은 사각형이 바로 보인다).
 * 화면 읽기 기능의 의미는 부르는 쪽([modifier])이 정한다.
 */
@Composable
private fun RowScope.TabCell(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit
) {
    val colors = StratumTheme.colors
    val iconColor = when {
        !enabled -> colors.textDisabled
        selected -> colors.selectedContent
        else -> colors.textSecondary
    }
    val labelColor = when {
        !enabled -> colors.textDisabled
        selected -> colors.selectedContent
        else -> colors.textDescription
    }
    Column(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(StratumDimens.TabToolSquare)
                .then(if (selected) Modifier.background(colors.selectedBackground, StratumShapes.Control) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides iconColor) { icon() }
        }
        Text(label, color = labelColor, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

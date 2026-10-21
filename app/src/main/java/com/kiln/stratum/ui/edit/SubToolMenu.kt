package com.kiln.stratum.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.flyoutOffset
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 하위 도구 메뉴가 뜨는 쪽: 도구 막대 버튼 오른쪽(메인 화면), 탭 위(커버) */
enum class MenuSide { END, ABOVE }

/**
 * 도구 막대·커버 탭 줄의 하위 도구 메뉴(사용자 피드백 7차, 결정 기록 '하위 도구 메뉴'). 열린 메뉴의 도구와 버튼 자리, 끄는 손가락 밑 항목을 든다.
 * 편집 화면 맨 위에 하나 두고([SubToolMenuHost]), 도구 버튼이 꾹 누를 때·고른 도구를 다시 누를 때 연다.
 * 항목 자리(창 좌표)는 메뉴가 놓일 때 적어, 꾹 누른 손가락을 끄는 동안 어느 항목 위인지 찾는다([hover]·[itemAt]).
 */
@Stable
class SubToolMenuState {

    /** 열린 메뉴의 도구. 닫혀 있으면 null. */
    var tool: EditTool? by mutableStateOf(null)
        private set

    /** 메뉴를 연 버튼의 자리(창 좌표) */
    var anchor: Region? by mutableStateOf(null)
        private set

    var side: MenuSide by mutableStateOf(MenuSide.END)
        private set

    /** 꾹 누른 채 끄는 손가락 밑의 항목(하이라이트). 없으면 null. */
    var hovered: SubTool? by mutableStateOf(null)
        private set

    /** 항목 자리(창 좌표). 메뉴가 놓일 때마다 고쳐 적는다. */
    internal val items = HashMap<SubTool, Region>()

    val isOpen: Boolean
        get() = tool != null

    fun open(tool: EditTool, anchor: Region, side: MenuSide) {
        items.clear()
        this.tool = tool
        this.anchor = anchor
        this.side = side
        hovered = null
    }

    fun close() {
        tool = null
        anchor = null
        hovered = null
        items.clear()
    }

    /** 창 자리 [position]에 있는 항목. 없으면 null. */
    fun itemAt(position: Offset): SubTool? =
        items.entries.firstOrNull { (_, r) -> position.x >= r.left && position.x < r.right && position.y >= r.top && position.y < r.bottom }?.key

    /** 꾹 누른 손가락이 창 자리 [position]에 왔다. 밑 항목을 하이라이트하고, 하이라이트가 다른 항목으로 바뀌었으면 true다. */
    fun hover(position: Offset): Boolean {
        val item = itemAt(position)
        if (item == hovered) return false
        hovered = item
        return item != null
    }

    /** 손가락을 뗐다. 항목 위면 그 항목(고를 것), 아니면 null이고 하이라이트를 지운다(메뉴는 열린 채 남는다). */
    fun release(position: Offset): SubTool? {
        val item = itemAt(position)
        hovered = null
        return item
    }
}

/** 메뉴 카드와 버튼·화면 가장자리 사이 */
private val MenuGap = 4.dp
private val MenuMargin = 8.dp

/**
 * 열린 하위 도구 메뉴. 편집 화면 맨 위(Box의 마지막 자식 쪽)에 화면 전체 크기로 둔다.
 * 메뉴 밖을 누르면 닫는 바탕(그 터치는 아래로 가지 않음)과 버튼 옆의 메뉴 카드: 메인 화면은 버튼 오른쪽(접히는 선은 보지 않음, 사용자 피드백 11차 1),
 * 커버는 탭 위. 항목을 누르면 [onChoose]로 고른다. 뒤로 가기로도 닫는다. [current]는 그 도구의 지금 하위 도구다(고른 것으로 보임).
 */
@Composable
fun SubToolMenuHost(state: SubToolMenuState, current: (EditTool) -> SubTool?, onChoose: (SubTool) -> Unit, modifier: Modifier = Modifier) {
    val tool = state.tool ?: return
    val anchor = state.anchor ?: return
    BackHandler { state.close() }
    Box(modifier.fillMaxSize()) {
        // 메뉴 밖을 누르면 닫기만 한다(손을 뗄 때까지 그 터치를 가진다)
        Box(
            Modifier
                .fillMaxSize()
                .testTag(EditScreenTags.SUB_TOOL_MENU_DISMISS)
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        state.close()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
        )
        Layout(content = { SubToolMenuCard(state, tool, current(tool), onChoose) }) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            layout(width, height) {
                val origin = coordinates?.positionInWindow() ?: return@layout
                val left = origin.x.roundToInt()
                val top = origin.y.roundToInt()
                val margin = MenuMargin.roundToPx()
                val gap = MenuGap.roundToPx()
                val area = Region(left + margin, top + margin, left + width - margin, top + height - margin)
                val (region, place) = when (state.side) {
                    MenuSide.END -> area to { w: Int, h: Int -> flyoutOffset(anchor, w, h, area, gap) }
                    MenuSide.ABOVE -> {
                        // 탭 위, 탭 가운데에 맞추고 화면 안으로 민다
                        val region = Region(area.left, area.top, area.right, anchor.top - gap)
                        region to { w: Int, h: Int ->
                            val x = ((anchor.left + anchor.right) / 2 - w / 2).coerceIn(area.left, (area.right - w).coerceAtLeast(area.left))
                            x to (anchor.top - gap - h).coerceAtLeast(area.top)
                        }
                    }
                }
                val card = measurables.first().measure(
                    Constraints(maxWidth = region.width.coerceAtLeast(0), maxHeight = region.height.coerceAtLeast(0))
                )
                val (x, y) = place(card.width, card.height)
                card.place(x - left, y - top)
            }
        }
    }
}

/** 메뉴 카드: 하위 도구마다 아이콘·이름 한 줄(48dp). 끄는 손가락 밑 줄은 선택 배경, 지금 하위 도구는 선택 색. */
@Composable
private fun SubToolMenuCard(state: SubToolMenuState, tool: EditTool, current: SubTool?, onChoose: (SubTool) -> Unit) {
    val colors = StratumTheme.colors
    val title = stringResource(R.string.subtool_menu)
    Surface(
        modifier = Modifier
            .testTag(EditScreenTags.SUB_TOOL_MENU)
            .semantics { paneTitle = title },
        shape = StratumShapes.Menu,
        color = colors.panel,
        contentColor = colors.textPrimary,
        border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
        shadowElevation = 3.dp
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp)) {
            for (subTool in tool.subTools) {
                val isCurrent = subTool == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(if (subTool == state.hovered) colors.selectedBackground else Color.Transparent)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            state.items[subTool] = Region(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                        }
                        .clickable(role = Role.Button) { onChoose(subTool) }
                        .semantics { selected = isCurrent }
                        .testTag(EditScreenTags.subTool(subTool))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tint = if (isCurrent) colors.selectedContent else colors.textSecondary
                    Icon(painterResource(subTool.icon), contentDescription = null, tint = tint)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(subTool.label),
                        color = if (isCurrent) colors.selectedContent else colors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

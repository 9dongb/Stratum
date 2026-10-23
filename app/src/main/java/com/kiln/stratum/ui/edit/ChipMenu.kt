package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 커버 막대 고르기 칩의 목록(사용자 요청 'C': 누른 채 끌어 고르기, 2026-10-23). 고르기 칩(명암 범위·선택 합치는 방법·유동화 얼굴 부위)을
 * 꾹 누르면(0.3초, 메뉴 묶음) 칩 위(가로 커버는 칩 왼쪽)에 고를 것 목록이 뜬다. 누른 채 옮기면 손가락 밑 항목을 하이라이트하고, 떼면 고른다.
 * 항목 밖에서 떼면 목록은 열린 채 남아 눌러 고른다(밖을 누르거나 뒤로 가기로 닫힘). 탭 줄의 하위 도구 메뉴와 같다.
 * 상태는 칩 이름([key])·자리·쪽만 들고, 고를 것은 그때의 막대에서 찾는다(고를 것의 할 일이 지난 값을 잡지 않게, [ChipMenuHost]).
 * 항목 자리(창 좌표)는 목록이 놓일 때 적어, 꾹 누른 손가락을 끄는 동안 어느 항목 위인지 찾는다.
 */
@Stable
class ChipMenuState {

    /** 목록을 연 고르기 칩의 이름([BarItem.key]). 닫혀 있으면 null. */
    var key: String? by mutableStateOf(null)
        private set

    /** 칩 자리(창 좌표) */
    var anchor: Region? by mutableStateOf(null)
        private set

    var side: MenuSide by mutableStateOf(MenuSide.ABOVE)
        private set

    /** 꾹 누른 채 끄는 손가락 밑의 고를 것([BarOption.tag]). 없으면 null. */
    var hovered: String? by mutableStateOf(null)
        private set

    /** 고를 것 자리(창 좌표, 이름표마다). 목록이 놓일 때마다 고쳐 적는다. */
    internal val items = HashMap<String, Region>()

    val isOpen: Boolean
        get() = key != null

    fun open(key: String, anchor: Region, side: MenuSide) {
        items.clear()
        this.key = key
        this.anchor = anchor
        this.side = side
        hovered = null
    }

    fun close() {
        key = null
        anchor = null
        hovered = null
        items.clear()
    }

    /** 창 자리 [position]에 있는 고를 것의 이름표. 없으면 null. */
    fun itemAt(position: Offset): String? =
        items.entries.firstOrNull { (_, r) -> position.x >= r.left && position.x < r.right && position.y >= r.top && position.y < r.bottom }?.key

    /** 꾹 누른 손가락이 창 자리 [position]에 왔다. 밑 항목을 하이라이트하고, 다른 항목으로 바뀌었으면 true다. */
    fun hover(position: Offset): Boolean {
        val item = itemAt(position)
        if (item == hovered) return false
        hovered = item
        return item != null
    }

    /** 손가락을 뗐다. 항목 위면 그 이름표(고를 것), 아니면 null이고 하이라이트를 지운다(목록은 열린 채 남는다). */
    fun release(position: Offset): String? {
        val item = itemAt(position)
        hovered = null
        return item
    }
}

/** 고르기 칩의 목록 상태(편집 화면과 유동화 작업 모드가 각자 넣는다). 없으면 고르기 칩은 꾹 눌러도 목록을 열지 않는다. */
val LocalChipMenu = staticCompositionLocalOf<ChipMenuState?> { null }

/**
 * 열린 고르기 칩 목록. 화면 전체를 덮는 자리에 둔다([AnchoredMenu]: 밖을 누르면 닫는 바탕, 칩 옆 카드, 뒤로 가기로 닫힘).
 * 고를 것은 지금 막대 [items]의 그 칩에서 찾고, 그 칩이 없어지거나 꺼지면 닫는다. 항목을 누르면 고르고 닫는다.
 */
@Composable
fun ChipMenuHost(state: ChipMenuState, items: List<BarItem>, modifier: Modifier = Modifier) {
    val key = state.key ?: return
    val anchor = state.anchor ?: return
    val choice = items.firstOrNull { it.key == key } as? BarChoice
    if (choice == null || !choice.enabled) {
        LaunchedEffect(key) { state.close() }
        return
    }
    AnchoredMenu(anchor, state.side, onDismiss = state::close, dismissTag = EditScreenTags.CHIP_MENU_DISMISS, modifier = modifier) {
        ChipMenuCard(state, choice)
    }
}

/** 목록 카드: 고를 것마다 (아이콘·)이름 한 줄(48dp). 끄는 손가락 밑 줄은 선택 배경, 지금 고른 것은 선택 색(하위 도구 메뉴 카드와 같은 모양). */
@Composable
private fun ChipMenuCard(state: ChipMenuState, choice: BarChoice) {
    val colors = StratumTheme.colors
    Surface(
        modifier = Modifier
            .testTag(EditScreenTags.CHIP_MENU)
            .semantics { paneTitle = choice.caption },
        shape = StratumShapes.Menu,
        color = colors.panel,
        contentColor = colors.textPrimary,
        border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
        shadowElevation = 3.dp
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp)) {
            for (option in choice.options) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(if (option.tag == state.hovered) colors.selectedBackground else Color.Transparent)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            state.items[option.tag] = Region(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                        }
                        .clickable(role = Role.Button) {
                            option.onClick()
                            state.close()
                        }
                        .semantics {
                            selected = option.selected
                            contentDescription = option.description
                        }
                        .testTag(EditScreenTags.chipMenu(option.tag))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tint = if (option.selected) colors.selectedContent else colors.textSecondary
                    option.icon?.let {
                        Icon(painterResource(it), contentDescription = null, tint = tint)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = option.label,
                        color = if (option.selected) colors.selectedContent else colors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 고르기 칩·칸의 꾹 누르기([com.kiln.stratum.ui.components.MenuLongPress] 안에서 쓴다, 메뉴 묶음 0.3초): [choice]의 목록을 칩 옆([side])에 연다.
 * 누른 채 끌면 손가락 밑 항목을 하이라이트하고(바뀔 때 짧은 진동), 항목 위에서 떼면 그것을 고르고 닫는다. 항목 밖에서 떼면 목록은 열린 채 남는다.
 * 칩 자리(창 좌표)는 늘 [bounds]에 적는다(화면 읽기 기능의 길게 누르기로 열 때도 쓴다). [menu]가 없거나 칩이 꺼져 있으면 꾹 누르기는 받지 않는다.
 */
@Composable
internal fun Modifier.chipMenuOnHold(menu: ChipMenuState?, choice: BarChoice, side: MenuSide, bounds: Array<Region?>): Modifier {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(choice)
    val positioned = onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        val left = position.x.roundToInt()
        val top = position.y.roundToInt()
        bounds[0] = Region(left, top, left + coordinates.size.width, top + coordinates.size.height)
    }
    if (menu == null || !choice.enabled) return positioned
    return positioned.longPressFirst(
        onLongPress = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            openChipMenu(menu, current, side, bounds)
        },
        onDrag = { if (menu.hover(it)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        onRelease = { position ->
            menu.release(position)?.let { tag ->
                current.options.firstOrNull { it.tag == tag }?.onClick?.invoke()
                menu.close()
            }
        }
    )
}

/** [choice]의 목록을 칩 자리([bounds], 창 좌표) 옆([side])에 연다. 칩이 아직 놓이지 않았으면 열지 않는다. */
internal fun openChipMenu(menu: ChipMenuState, choice: BarChoice, side: MenuSide, bounds: Array<Region?>) {
    bounds[0]?.let { menu.open(choice.key, it, side) }
}

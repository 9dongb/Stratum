package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.StratumActionChip
import com.kiln.stratum.ui.components.StratumFilterChip
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.components.horizontalScrollFadeEdges
import com.kiln.stratum.ui.theme.StratumTheme

/*
 * 커버 화면(Compact)의 한 줄 막대(결정 기록 '커버 화면 개편'). 탭 줄 바로 위에 늘 같은 높이로 있고, 캔버스는 그 위만 쓴다.
 * 틀은 모든 도구가 같다: [하위 도구 ▾] [색] [값 칩…] [동작 ▾] … [✕]. 칩을 누르면 같은 줄이 그 칩의 내용(슬라이더·고를 것·색·동작)이 되고,
 * 오른쪽 끝 ✓로 칩 줄로 돌아온다. 값·할 일·이름표는 메인 화면 띠의 칸(ToolStrips.kt)과 같은 것을 쓴다.
 * 상단 바의 조정을 켜면 같은 막대가 조정 칩(항목·모두 초기화, [adjustBar])이 되고 ✕는 조정을 끈다(Q1 A).
 */

/** 한 줄 막대·탭 줄의 가장 낮은 높이 */
private val CoverRowMinHeight = 56.dp

/** 아이콘 칸과 위아래 여백. 아이콘 아래 이름 한 줄과 더하면 줄 높이가 된다. */
private val CoverRowIconSpace = 40.dp

/**
 * 커버 화면 한 줄 막대와 탭 줄의 높이: 56dp. 글자를 크게 쓰는 기기에서는 아이콘 아래 이름(labelMedium 한 줄, 탭 칸·선택 동작 버튼)이
 * 잘리지 않게 그만큼 높인다. 도구와 상관없이 같아, 도구를 바꿔도 사진 크기가 그대로다(결정 기록 '커버 화면 개편'). 칩의 누르는 영역은 48dp 이상이다.
 */
@Composable
internal fun coverRowHeight(): Dp {
    val label = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
    return maxOf(CoverRowMinHeight, CoverRowIconSpace + label)
}

/** 막대의 칸 하나. [key]는 막대 안에서 겹치지 않는 이름이고, 화면 테스트 이름표는 `bar_<key>`([EditScreenTags.bar])다. */
sealed interface BarItem {
    val key: String
}

/** 값 칩: 이름과 값(크기 60 px). 누르면 같은 줄이 그 슬라이더가 된다. 값과 할 일은 메인 띠의 슬라이더 칸([cell])과 같다. */
class BarValue(val cell: SliderCell) : BarItem {
    override val key: String
        get() = cell.key
}

/**
 * 고르기 칩: 지금 고른 것(아이콘·이름)과 ▾. 누르면 같은 줄이 고를 것들([options])이 되고, 하나를 고르면 돌아온다.
 * [caption]은 무엇을 고르는지(화면 읽기 기능이 '합치는 방법: 새로'처럼 읽는다)다.
 */
class BarChoice(
    override val key: String,
    val caption: String,
    val options: List<BarOption>,
    val enabled: Boolean = true
) : BarItem {
    /** 지금 고른 것 */
    val current: BarOption?
        get() = options.firstOrNull { it.selected }
}

/** 고르기 칩의 고를 것 하나. [tag]는 화면 테스트 이름표다(전 커버 옵션과 같은 이름표를 쓴다). */
class BarOption(
    val label: String,
    @DrawableRes val icon: Int?,
    val selected: Boolean,
    val tag: String,
    val description: String = label,
    val onClick: () -> Unit
)

/**
 * 색 버튼들: 전경색·배경색(누르면 바로 그 색의 팔레트), 바꾸기, 스포이드([ColorButtons]). 펼치지 않고 막대에 그대로 둔다
 * (사용자 피드백 7차: 브러시 탭을 누르면 두 색이 바로 보이고, 각 색을 누르면 색 고르기 창).
 */
class BarColors(val controls: ColorControls) : BarItem {
    override val key: String
        get() = "colors"
}

/**
 * 버튼 칩: 누르면 바로 한다(AI 제거의 '지우기', 이동 도구의 돌리기·뒤집기). [accent]면 파란 채움 버튼이고, 아니면 [icon]을 앞에 둔 칩이다
 * ([iconRotation]만큼 돌려 그림). [description]은 화면 읽기 기능이 읽는 이름이다.
 */
class BarButton(
    override val key: String,
    val label: String,
    @DrawableRes val icon: Int?,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val accent: Boolean = false,
    val description: String? = null,
    val tag: String? = null,
    val iconRotation: Float = 0f
) : BarItem

/** 켜고 끄는 칩(자동 선택의 '이어진 곳만'). 켜지면 선택 배경이다. */
class BarToggle(
    override val key: String,
    val label: String,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
    val enabled: Boolean = true,
    val tag: String? = null
) : BarItem

/**
 * 묶음 칩(선택 도구의 동작): 누르면 같은 줄이 [content](버튼들, 옆으로 밀어 봄)가 되고, 버튼을 눌러도 남아 ✓로 돌아온다.
 * 세로 막대(커버 가로, [CoverToolColumn])는 [columnContent](좁은 기둥에 맞는 칸들)를 쓰고, 없으면 [content]를 쓴다.
 */
class BarGroup(
    override val key: String,
    val caption: String,
    val columnContent: (@Composable () -> Unit)? = null,
    val content: @Composable () -> Unit
) : BarItem

/**
 * 직접 그리는 칩(조정 항목·모두 초기화). [chip]은 칩 줄의 칩이고, 받은 함수를 부르면 같은 줄이 [content]가 된다(✓로 돌아옴).
 * 이름표·화면 읽기는 [chip]이 스스로 단다. 세로 막대(커버 가로, [CoverToolColumn])는 [cell]·[columnContent]를 쓰고, 없으면 [chip]·[content]를 쓴다.
 */
class BarCustom(
    override val key: String,
    val chip: @Composable (expand: () -> Unit) -> Unit,
    val content: @Composable () -> Unit,
    val cell: (@Composable (expand: () -> Unit) -> Unit)? = null,
    val columnContent: (@Composable () -> Unit)? = null
) : BarItem

/**
 * 막대에서 펼친 칩. 칩을 누르면 펼치고, ✓·뒤로 가기로 접는다. 펼친 칩은 그때의 막대 내용([owner]: 도구·하위 도구·조정)에 딸려,
 * 내용이 바뀌면(도구를 바꿈) 곧바로 칩 줄이 보인다([expandedFor]). 화면이 다시 만들어져도(접기·펼치기) 남는다.
 */
@Stable
class CoverBarState(expanded: String? = null, owner: String? = null) {
    /** 펼친 칩의 이름([BarItem.key]). 없으면 칩 줄이다. */
    var expanded: String? by mutableStateOf(expanded)
        private set

    /** 칩을 펼칠 때의 막대 내용 */
    var owner: String? by mutableStateOf(owner)
        private set

    /** [content] 막대에서 펼친 칩. 다른 내용의 막대에서 펼친 것이면 없다. */
    fun expandedFor(content: String): String? = expanded?.takeIf { owner == content }

    fun expand(key: String, content: String) {
        expanded = key
        owner = content
    }

    fun collapse() {
        expanded = null
        owner = null
    }

    companion object {
        /** 막대 내용과 펼친 칩의 이름을 줄바꿈으로 이어 저장한다(없으면 빈 문자열) */
        val Saver: Saver<CoverBarState, String> = Saver(
            save = { "${it.owner.orEmpty()}\n${it.expanded.orEmpty()}" },
            restore = { saved ->
                val parts = saved.split('\n', limit = 2)
                CoverBarState(parts.getOrNull(1)?.ifEmpty { null }, parts[0].ifEmpty { null })
            }
        )
    }
}

/** 처음에는 칩 줄이다 */
@Composable
fun rememberCoverBarState(): CoverBarState = rememberSaveable(saver = CoverBarState.Saver) { CoverBarState() }

/**
 * 커버 화면의 한 줄 막대. 칩 줄은 [items]를 차례로 놓고(넘치면 칩만 옆으로 밀어 봄) 오른쪽 끝에 ✕([onClose], 없으면 없음, 화면 읽기 이름 [closeDescription])를 둔다.
 * 칩이 없으면 [empty](도구를 고르지 않았을 때의 안내, 이동 도구의 이름)를 흐린 글자로 보인다.
 * 펼친 칩([state])이 있으면 그 칩의 내용과 오른쪽 끝 ✓다. 옆쪽 시스템 바·카메라 구멍은 피한다.
 * [content]는 막대 내용의 이름(도구·하위 도구·조정)이다. 펼친 칩과 칩 줄의 밀어 본 자리는 이 내용에 딸린다(✓로 돌아와도 보던 칩 그대로).
 */
@Composable
fun CoverToolBar(
    items: List<BarItem>,
    state: CoverBarState,
    modifier: Modifier = Modifier,
    content: String = "",
    onClose: (() -> Unit)? = null,
    closeDescription: String = stringResource(R.string.bar_close_tool),
    empty: String? = null
) {
    val colors = StratumTheme.colors
    val expandedKey = state.expandedFor(content)
    val open = items.firstOrNull { it.key == expandedKey }
    // 칩을 펼쳤다 ✓로 돌아와도 칩 줄이 처음으로 돌아가지 않게 막대 내용마다 기억한다
    val scroll = remember(content) { ScrollState(0) }
    val expand: (String) -> Unit = { key -> state.expand(key, content) }
    Surface(
        modifier.fillMaxWidth().edgeDivider(DividerEdge.Top, colors.divider).testTag(EditScreenTags.COVER_TOOL_BAR),
        color = colors.panel
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                .height(coverRowHeight())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (open) {
                null -> {
                    if (items.isEmpty()) {
                        Text(
                            text = empty.orEmpty(),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag(EditScreenTags.BAR_HINT),
                            color = colors.textDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        // 넘치면 칩만 옆으로 밀어 보고, 더 있는 쪽 끝을 흐리게 그려 알린다
                        Row(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScrollFadeEdges(scroll, colors.panel)
                                .horizontalScroll(scroll),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.width(2.dp))
                            for (item in items) BarChip(item, expand)
                            Spacer(Modifier.width(2.dp))
                        }
                    }
                    if (onClose != null) {
                        StratumIconButton(onClick = onClose, modifier = Modifier.testTag(EditScreenTags.BAR_CLOSE)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = closeDescription)
                        }
                    }
                }
                else -> {
                    Box(Modifier.weight(1f)) { BarExpanded(open, state) }
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

/** 칩 줄의 칩 하나. 값·고르기·동작 칩은 누르면 [expand](같은 줄이 그 칩의 내용)이고, 화면 읽기 기능은 '이름: 값'으로 읽는다. */
@Composable
private fun BarChip(item: BarItem, expand: (String) -> Unit) {
    val colors = StratumTheme.colors
    val tag = Modifier.testTag(EditScreenTags.bar(item.key))
    when (item) {
        is BarValue -> {
            val description = stringResource(R.string.strip_choice_description, item.cell.caption, item.cell.value)
            StratumActionChip(
                onClick = { expand(item.key) },
                enabled = item.cell.enabled,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.cell.caption, color = colors.textDescription, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text(item.cell.value, maxLines = 1)
                    }
                },
                modifier = tag.semantics { contentDescription = description }
            )
        }
        is BarChoice -> {
            val current = item.current
            val description = stringResource(R.string.strip_choice_description, item.caption, current?.description.orEmpty())
            StratumActionChip(
                onClick = { expand(item.key) },
                enabled = item.enabled,
                leadingIcon = current?.icon?.let { icon -> { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) } },
                label = { Text(current?.label.orEmpty(), maxLines = 1) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = tag.semantics { contentDescription = description }
            )
        }
        is BarColors -> Row(tag, verticalAlignment = Alignment.CenterVertically) { ColorButtons(item.controls) }
        is BarButton -> if (item.accent) {
            AccentButton(
                onClick = item.onClick,
                enabled = item.enabled,
                modifier = Modifier
                    .testTag(item.tag ?: EditScreenTags.bar(item.key))
                    .semantics { item.description?.let { contentDescription = it } }
            ) {
                item.icon?.let {
                    Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(item.label, maxLines = 1)
            }
        } else {
            StratumActionChip(
                onClick = item.onClick,
                enabled = item.enabled,
                label = { Text(item.label, maxLines = 1) },
                modifier = Modifier
                    .testTag(item.tag ?: EditScreenTags.bar(item.key))
                    .semantics { item.description?.let { contentDescription = it } },
                leadingIcon = item.icon?.let { icon ->
                    { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp).rotate(item.iconRotation)) }
                }
            )
        }
        is BarToggle -> StratumFilterChip(
            selected = item.checked,
            onClick = { item.onChange(!item.checked) },
            enabled = item.enabled,
            label = { Text(item.label, maxLines = 1) },
            modifier = Modifier.testTag(item.tag ?: EditScreenTags.bar(item.key))
        )
        is BarGroup -> StratumActionChip(
            onClick = { expand(item.key) },
            label = { Text(item.caption, maxLines = 1) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = tag
        )
        is BarCustom -> item.chip { expand(item.key) }
    }
}

/** 펼친 칩의 내용(오른쪽 끝 ✓는 막대가 둔다) */
@Composable
private fun BarExpanded(item: BarItem, state: CoverBarState) {
    when (item) {
        is BarValue -> {
            val slider = item.cell.slider
            EndPreviewOnDispose(slider.preview)
            OptionSlider(
                label = item.cell.caption,
                shown = item.cell.value,
                fraction = slider.fraction,
                onFraction = slider.onFraction,
                tag = slider.tag,
                onFinished = slider.onFinished,
                origin = slider.origin,
                onReset = slider.onReset.takeIf { item.cell.enabled }
            )
        }
        is BarChoice -> Row(
            Modifier.fillMaxWidth().horizontalScrollFade(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(2.dp))
            for (option in item.options) {
                StratumFilterChip(
                    selected = option.selected,
                    onClick = {
                        option.onClick()
                        state.collapse()
                    },
                    leadingIcon = option.icon?.let { icon -> { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) } },
                    label = { Text(option.label, maxLines = 1) },
                    modifier = Modifier.testTag(option.tag).semantics { contentDescription = option.description }
                )
            }
        }
        is BarGroup -> Row(
            Modifier.fillMaxWidth().horizontalScrollFade(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item.content()
        }
        is BarCustom -> item.content()
        // 색 버튼·버튼·켜고 끄는 칩은 펼치지 않는다
        is BarColors, is BarButton, is BarToggle -> Unit
    }
}

/** 옆으로 밀어 보고 더 있는 쪽 끝을 흐리게 그리는 줄(펼친 칩의 고를 것·동작 줄) */
@Composable
private fun Modifier.horizontalScrollFade(): Modifier {
    val scroll = rememberScrollState()
    return horizontalScrollFadeEdges(scroll, StratumTheme.colors.panel).horizontalScroll(scroll)
}

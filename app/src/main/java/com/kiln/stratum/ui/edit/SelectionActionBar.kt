package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.ToolActionButton
import com.kiln.stratum.ui.layout.Span
import com.kiln.stratum.ui.layout.actionBarStarts
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 메인 화면 선택 도구의 작업 표시줄(사용자 피드백 10차 1: "그외 액션은 화면 중앙 하단에 가로로 보여주세요.(사진1 참고)", 태블릿 포토샵의
 * 상황별 작업 표시줄). 모두 선택·해제·반전·지우기·새 레이어로 복사·잘라내기·AI 채우기를 커버 옵션과 같은 버튼(아이콘·짧은 이름)으로 한 줄에 둔다.
 * 모두 선택은 늘, 나머지는 고른 곳이 있을 때만 켠다.
 * 화면 가로 정중앙([screenCenterX], 창 x), 받은 영역(캔버스)의 아래쪽에 패널 색 카드로 뜬다([actionBarStarts]). 접히는 선이 지나가도 나누지 않고
 * 한 줄이다(사용자 피드백 11차 1). 받은 영역을 다 차지하지만 터치는 카드만 받는다(버튼 사이를 눌러도 캔버스로 가지 않음).
 * 아래에서 차지하는 높이(카드 + 아래 여백, px)를 [onInset]으로 알린다(알림 막대·떠 있는 패널을 그 위에 둔다). 사라지면 0을 알린다.
 */
@Composable
fun SelectionActionBar(controls: SelectionControls, screenCenterX: Float, modifier: Modifier = Modifier, onInset: (Int) -> Unit = {}) {
    val ready = !controls.busy
    val selected = ready && controls.hasSelection
    val actions = controls.actions
    val colors = StratumTheme.colors
    val currentOnInset by rememberUpdatedState(onInset)
    DisposableEffect(Unit) { onDispose { currentOnInset(0) } }
    Layout(
        content = {
            // 카드 바탕(버튼 뒤). 누를 수 없는 Surface라 그 아래 캔버스로 터치가 가지 않는다.
            Surface(
                modifier = Modifier.testTag(EditScreenTags.SELECTION_ACTION_BAR),
                shape = StratumShapes.Card,
                color = colors.panel,
                border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
                shadowElevation = ActionBarElevation
            ) {}
            BarButton(R.drawable.ic_select_all, R.string.selection_all, R.string.selection_all_description, ready, EditScreenTags.SELECTION_ALL, actions::all)
            BarButton(R.drawable.ic_deselect, R.string.selection_none, null, selected, EditScreenTags.SELECTION_NONE, actions::none)
            BarButton(R.drawable.ic_flip_to_front, R.string.selection_invert, null, selected, EditScreenTags.SELECTION_INVERT, actions::invert)
            BarButton(R.drawable.ic_delete, R.string.selection_delete, R.string.selection_delete_description, selected, EditScreenTags.SELECTION_DELETE, actions::delete)
            BarButton(R.drawable.ic_content_copy, R.string.selection_copy, R.string.selection_copy_description, selected, EditScreenTags.SELECTION_COPY) {
                actions.copyToLayer(cut = false)
            }
            BarButton(R.drawable.ic_content_cut, R.string.selection_cut, R.string.selection_cut_description, selected, EditScreenTags.SELECTION_CUT) {
                actions.copyToLayer(cut = true)
            }
            BarButton(R.drawable.ic_auto_awesome, R.string.selection_ai_fill, R.string.selection_ai_fill_description, selected, EditScreenTags.SELECTION_AI_FILL, actions::aiFill)
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val buttons = measurables.drop(1).map { it.measure(loose) }
        val gap = ActionBarGap.roundToPx()
        val padding = ActionBarPadding.roundToPx()
        val margin = ActionBarMargin.roundToPx()
        val rowHeight = buttons.maxOf { it.height }
        val cardHeight = rowHeight + padding * 2
        val inset = cardHeight + margin
        layout(width, height) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val starts = actionBarStarts(
                widths = buttons.map { it.width },
                center = screenCenterX.roundToInt() - origin.x.roundToInt(),
                area = Span(margin + padding, width - margin - padding),
                gap = gap
            )
            val cardLeft = starts.first() - padding
            val cardRight = starts.last() + buttons.last().width + padding
            val cardTop = height - margin - cardHeight
            val card = measurables.first().measure(Constraints.fixed((cardRight - cardLeft).coerceAtLeast(0), cardHeight))
            card.place(cardLeft, cardTop)
            buttons.forEachIndexed { index, button -> button.place(starts[index], cardTop + padding + (rowHeight - button.height) / 2) }
            currentOnInset(inset)
        }
    }
}

/** 작업 표시줄의 버튼: 아이콘 아래 짧은 이름(커버 옵션의 동작 버튼과 같다). [description]이 있으면 화면 읽기 기능은 그 긴 이름으로 읽는다. */
@Composable
private fun BarButton(icon: Int, label: Int, description: Int?, enabled: Boolean, tag: String, onClick: () -> Unit) {
    ToolActionButton(
        icon = icon,
        label = stringResource(label),
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        enabled = enabled,
        description = description?.let { stringResource(it) }
    )
}

/** 작업 표시줄 버튼 사이 */
private val ActionBarGap = 4.dp

/** 카드 안쪽 여백 */
private val ActionBarPadding = 4.dp

/** 카드와 캔버스 아래 끝·옆 끝 사이 */
private val ActionBarMargin = 8.dp

/** 카드 그림자(도구 옵션 띠와 같다) */
private val ActionBarElevation = 6.dp

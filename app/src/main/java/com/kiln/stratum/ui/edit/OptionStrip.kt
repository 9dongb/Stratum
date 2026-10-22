package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import com.kiln.stratum.settings.StripPosition
import com.kiln.stratum.ui.components.AccentIconButton
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumIconToggleButton
import com.kiln.stratum.ui.components.StratumVerticalSlider
import com.kiln.stratum.ui.components.scrollFadeEdges
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.placeInside
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

/** 옵션 창 안 내용의 폭(자르기 비율 칸 등). 옵션 창은 여백 포함 240dp다. */
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

/** 색 칸: 색([color], 무지개 테두리)과 작은 이름([caption]). 누르면 옵션 창 없이 바로 [onClick](전경색·배경색의 팔레트를 연다) */
class ColorCell(
    override val key: String,
    val color: Int,
    val caption: String,
    val description: String,
    val onClick: () -> Unit
) : StripItem

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
 * [origin]은 0의 자리(0..1)다: 0에서 양쪽으로 바꾸는 값(얼굴 보정)은 0.5를 넘겨 그 자리부터 손잡이까지 칠한다.
 * [onReset]이 있으면 손잡이를 두 번 톡하면 그것을 부른다(0으로, 조정 슬라이더와 같다). 화면 읽기 기능은 '초기화' 동작으로 쓴다.
 */
class StripSlider(
    val fraction: Float,
    val onFraction: (Float) -> Unit,
    val tag: String,
    val onFinished: () -> Unit = {},
    val preview: SizePreviewState? = null,
    val origin: Float = 0f,
    val onReset: (() -> Unit)? = null
)

/** 칸 묶음 사이의 가는 구분선 */
class StripDivider(override val key: String) : StripItem

/** 띠를 처음 띄우는 자리: 캔버스 영역 왼쪽 위에서 가로 [OptionStripGap](도구 막대 옆), 세로 [OptionStripMargin] */
private val DefaultStripX = OptionStripGap
private val DefaultStripY = OptionStripMargin

/**
 * 도구 옵션 띠를 캔버스 위에 띄우는 자리(사용자 피드백 9차: "도구를 눌렀을 때 나오는 옵션은 이제 좌측 상단에 따로 해당 부분을 고정으로 띄워두세요").
 * 처음에는 캔버스 왼쪽 위이고, 띠 맨 위 손잡이를 끌면 캔버스 안에서 따라온다(사진 3, 태블릿 포토샵). 놓은 자리([position],
 * 캔버스 영역 왼쪽 위에서의 dp)는 [onPosition]으로 알려 설정에 남는다. 그래서 도구를 바꿔도·앱을 다시 켜도 그 자리다.
 * 캔버스 영역 안 어디든 놓고(접히는 선에 걸쳐도 됨, 사용자 피드백 11차 1), 영역보다 길면 띠 안에서 밀어 본다.
 * 화면 크기가 바뀌면(접기·돌리기) 영역 안으로 맞춘다. 받은 영역(캔버스)을 다 차지하지만 터치는 띠만 받는다.
 * 자리는 배치하는 순간 자기 위치를 읽어 정한다(한 프레임 늦지 않음).
 */
@Composable
fun OptionStripHost(
    items: List<StripItem>,
    state: FloatingState,
    position: StripPosition?,
    onPosition: (StripPosition) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val placed = remember { PlacedStrip() }
    // 저장된 자리. 손을 뗀 자리를 바로 쓰고(설정 값이 돌아오기 전 한 프레임 튀지 않게), 설정이 바뀌면 따른다.
    var saved by remember { mutableStateOf(position) }
    LaunchedEffect(position) { saved = position }
    // 손잡이를 끄는 동안 바라는 자리(영역 왼쪽 위 기준 px). 끌지 않을 때는 null이다.
    var dragging by remember { mutableStateOf<Offset?>(null) }
    val currentOnPosition by rememberUpdatedState(onPosition)
    // 놓인 자리는 배율 표시가 피한다([FloatingState.stripRegion]). 띠가 사라지면 지운다.
    DisposableEffect(state) { onDispose { state.stripRegion = null } }
    // 손을 뗀 자리는 끈 만큼 옮긴 자리다(배치를 기다리지 않는다: 빠르게 끌고 떼면 그사이 배치가 없을 수 있다).
    val finish = {
        dragging?.let { end ->
            val spot = with(density) { StripPosition(end.x.toDp().value, end.y.toDp().value) }
            saved = spot
            currentOnPosition(spot)
        }
        dragging = null
    }
    // 손잡이 끌기. 누른 자리에서부터 손가락을 그대로 따라온다(터치 허용 거리만큼 늦지 않게, 움직인 양을 처음부터 모두 더한다).
    val handle = Modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = Offset.Zero
            var base: Offset? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // 띠가 손가락을 따라 옮겨져도 움직인 양은 창에서의 양이다(Compose가 지금 자리로 바꿔 준다)
                moved += change.positionChange()
                if (base == null && moved.getDistance() > viewConfiguration.touchSlop) {
                    // 옵션 창은 칸 옆에 붙어 있어 띠를 옮기면 닫는다
                    state.closeFlyout()
                    base = Offset(placed.x.toFloat(), placed.y.toFloat())
                }
                val start = base ?: continue
                change.consume()
                // 영역 밖으로 끈 만큼은 쌓지 않는다(돌아올 때 바로 따라오게)
                dragging = Offset(
                    (start.x + moved.x).coerceIn(0f, placed.maxX.toFloat()),
                    (start.y + moved.y).coerceIn(0f, placed.maxY.toFloat())
                ).also { clamped -> if (clamped != start + moved) { base = clamped - moved } }
            }
            if (base != null) finish()
        }
    }
    Layout(content = { OptionStrip(items, state, handle) }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val measurable = measurables.firstOrNull() ?: return@layout
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val left = origin.x.roundToInt()
            val top = origin.y.roundToInt()
            val gap = OptionStripGap.roundToPx()
            val margin = OptionStripMargin.roundToPx()
            val stripWidth = OptionStripWidth.roundToPx()
            val wanted = dragging ?: saved?.let { Offset(it.x.dp.toPx(), it.y.dp.toPx()) } ?: Offset(DefaultStripX.toPx(), DefaultStripY.toPx())
            val area = Region(left + gap, top + margin, left + width - gap, top + height - margin)
            val placeable = measurable.measure(Constraints(maxWidth = width, maxHeight = area.height.coerceAtLeast(0)))
            val (x, y) = placeInside(left + wanted.x.roundToInt(), top + wanted.y.roundToInt(), placeable.width, placeable.height, area)
            placed.x = x - left
            placed.y = y - top
            placed.maxX = (width - placeable.width).coerceAtLeast(0)
            placed.maxY = (height - placeable.height).coerceAtLeast(0)
            val region = Region(x, y, x + placeable.width, y + placeable.height)
            if (state.stripRegion != region) state.stripRegion = region
            placeable.place(x - left, y - top)
        }
    }
}

/** 마지막으로 놓은 띠 자리와 옮길 수 있는 끝(영역 왼쪽 위 기준 px). 배치할 때 적어 두고 손잡이 끌기가 읽는다(다시 그리지 않음). */
private class PlacedStrip {
    var x = 0
    var y = 0
    var maxX = 0
    var maxY = 0
}

/** 띠 맨 위 손잡이의 높이(누르는 영역 48dp, Material 끌기 손잡이와 같다)와 그 안의 가는 막대 */
private val StripHandleHeight = 48.dp
private val StripHandleBarWidth = 24.dp
private val StripHandleBarHeight = 4.dp

/** 띠 맨 위 손잡이(사진 3): 가는 가로 막대. 끌면 띠를 옮긴다([OptionStripHost]). 띠 안을 밀어 보아도 늘 맨 위에 있다. */
@Composable
private fun StripHandle(modifier: Modifier) {
    val description = stringResource(R.string.strip_handle_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(StripHandleHeight)
            .testTag(EditScreenTags.STRIP_HANDLE)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = StripHandleBarWidth, height = StripHandleBarHeight)
                .clip(StratumShapes.Dot)
                .background(StratumTheme.colors.textDisabled)
        )
    }
}

/**
 * 도구 옵션 띠: 칸만큼의 높이로 떠 있는 좁은 카드(도구 막대와 같은 폭, 패널 색, 모서리 8dp, 테두리·그림자). 자리는 [OptionStripHost]가 정한다.
 * 맨 위는 손잡이([handle]로 끌기)이고, 받은 높이보다 칸이 많으면 칸들만 위아래로 밀어 본다. 옵션 창은 [FlyoutHost]가 칸 옆에 띄운다.
 * 칸 자리는 [FloatingState.anchors]에 적는다.
 */
@Composable
private fun OptionStrip(items: List<StripItem>, state: FloatingState, handle: Modifier) {
    Surface(
        modifier = Modifier
            .width(OptionStripWidth)
            .testTag(EditScreenTags.OPTION_STRIP),
        shape = StratumShapes.Card,
        color = StratumTheme.colors.panel,
        border = BorderStroke(StratumDimens.Divider, StratumTheme.colors.fieldBorder),
        shadowElevation = 6.dp
    ) {
        Column {
            StripHandle(handle)
            // 받은 높이보다 칸이 많으면(낮은 화면의 긴 띠 등) 밀어 보고, 더 있는 쪽 끝을 흐리게 알린다
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .scrollFadeEdges(scroll, StratumTheme.colors.panel)
                    .verticalScroll(scroll)
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (item in items) {
                    key(item.key) { StripCellView(item, state) }
                }
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
            is SliderCell -> ValueCellView(item.key, item.caption, item.value, item.enabled, open, scrub = item.slider) { state.toggleFlyout(item.key) }
            is ChoiceCell -> ChoiceCellView(item, open) { state.toggleFlyout(item.key) }
            is ColorCell -> ColorCellView(item) {
                // 다른 칸의 옵션 창은 닫고 바로 팔레트를 연다
                state.closeFlyout()
                item.onClick()
            }
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
 * [touch]는 누르기보다 먼저 터치를 받는 것(수치 끌기)이다. 그것이 터치를 가지면(끌면) 누르기는 되지 않는다.
 * 커버를 가로로 돌렸을 때의 세로 막대([CoverToolColumn])도 같은 칸을 쓴다(사용자 피드백 12차 3).
 */
@Composable
internal fun CellBox(
    tag: String,
    description: String,
    state: String?,
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    touch: Modifier = Modifier,
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
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // 누르기 안쪽에 두어 끌기가 먼저 터치를 받는다(끌어서 가진 터치는 누르기가 아니다)
            .then(touch),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}

/**
 * 수치 끌기(사용자 피드백 9차: "수치를 터치하고 위나 아래로 슬라이딩하면 해당 옵션값(100~0)이 조절되도록 해주세요."):
 * 슬라이더 칸을 누른 채 이만큼 끌면 값이 슬라이더 처음부터 끝까지 바뀐다(위로 끌면 커짐, 슬라이더와 같은 눈금).
 * 폴드8에서 써 보고 고칠 수 있게 한곳에 둔다.
 */
internal val ScrubFullRange = 200.dp

/**
 * 값 칸·슬라이더 칸의 모양: 위에 작은 이름, 아래에 값. 누르면 [onClick](옵션 창).
 * [scrub]가 있으면(슬라이더 칸) 칸을 누른 채 위아래로 끌어 값을 바꾼다: 끄는 동안 [StripSlider.onFraction], 손을 떼면
 * [StripSlider.onFinished](슬라이더와 같다, 크기·경도는 크기 원도). 끄는 동안 칸은 선택 배경이고, 끈 터치로는 옵션 창이 뜨지 않는다.
 */
@Composable
private fun ValueCellView(
    key: String,
    caption: String,
    value: String,
    enabled: Boolean,
    open: Boolean,
    scrub: StripSlider? = null,
    onClick: () -> Unit
) {
    val colors = StratumTheme.colors
    var scrubbing by remember { mutableStateOf(false) }
    // 끄는 동안 값이 바뀌어 칸이 다시 그려져도 끌기를 다시 시작하지 않고 지금 슬라이더에 보낸다
    val currentScrub by rememberUpdatedState(scrub)
    val touch = if (scrub != null && enabled) {
        Modifier.pointerInput(key) {
            val fullRange = ScrubFullRange.toPx()
            var start = 0f
            var moved = 0f
            detectVerticalDragGestures(
                onDragStart = {
                    start = currentScrub?.fraction ?: 0f
                    moved = 0f
                    scrubbing = true
                },
                onDragEnd = {
                    scrubbing = false
                    currentScrub?.onFinished?.invoke()
                },
                onDragCancel = {
                    scrubbing = false
                    currentScrub?.onFinished?.invoke()
                },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    moved += dy
                    // 화면에서 위가 작은 y라, 위로 끈 만큼(-dy) 커진다
                    currentScrub?.onFraction?.invoke((start - moved / fullRange).coerceIn(0f, 1f))
                }
            )
        }
    } else {
        Modifier
    }
    CellBox(EditScreenTags.strip(key), caption, value, open || scrubbing, enabled, onClick, touch) {
        CompactLabel(caption, if (enabled) colors.textDescription else colors.textDisabled)
        Text(
            text = value,
            color = when {
                !enabled -> colors.textDisabled
                open || scrubbing -> colors.selectedContent
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
private fun ColorCellView(cell: ColorCell, onClick: () -> Unit) {
    CellBox(EditScreenTags.strip(cell.key), cell.description, null, open = false, enabled = true, onClick = onClick) {
        // 색 줄의 전경색·배경색처럼 무지개 테두리가 '색 고르기'를 알린다
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
internal fun CompactLabel(text: String, color: Color) {
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

/** 값 표시 줄 아래에 남기는 여백: 밀어 보는 줄이 끝에서 잘라 그리므로, 맨 아래 카드의 그림자가 들어갈 자리 */
private val ValueShadowRoom = 8.dp

/**
 * 슬라이더 칸의 옵션 창(사진: 태블릿 포토샵): 얇은 세로 슬라이더 카드와 그 옆 위의 값 표시('경도 82%'),
 * 값 표시 아래에는 [SliderCell.extra]. 카드를 가리키는 삼각형은 [FlyoutHost]가 칸 쪽에 그린다.
 * 칸 오른쪽에 뜨면 값 표시는 카드 오른쪽, 띠를 오른쪽 끝에 옮겨 칸 왼쪽에 뜨면([valueOnLeft]) 카드 왼쪽이다(카드가 늘 칸 쪽).
 * 값 표시는 슬라이더와 같은 내용이라 화면 읽기 기능에서는 빼고, 슬라이더가 이름·값을 읽는다.
 */
@Composable
internal fun VerticalSliderFlyout(cell: SliderCell, valueOnLeft: Boolean = false) {
    val slider = cell.slider
    EndPreviewOnDispose(slider.preview)
    val colors = StratumTheme.colors
    val sliderCard: @Composable () -> Unit = {
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
                origin = slider.origin,
                onThumbDoubleTap = slider.onReset,
                modifier = Modifier
                    .width(VerticalSliderWidth)
                    .height(VerticalSliderHeight)
                    .testTag(slider.tag)
                    .sliderSemantics(cell.caption, cell.value, slider.onReset.takeIf { cell.enabled })
            )
        }
    }
    val valueColumn: @Composable () -> Unit = {
        // 폭은 내용만큼이다('이어진 곳만'처럼 폭을 채우는 칸이 창 영역 끝까지 늘어나지 않게).
        // 창 영역이 낮으면 아래로 넘치지 않고 그 안에서 밀어 본다(아래 여백은 카드 그림자 자리).
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .verticalScroll(rememberScrollState())
                .padding(start = if (valueOnLeft) 0.dp else 6.dp, end = if (valueOnLeft) 6.dp else 0.dp, bottom = ValueShadowRoom),
            horizontalAlignment = if (valueOnLeft) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
    Row(Modifier.testTag(EditScreenTags.OPTION_FLYOUT).semantics { paneTitle = cell.caption }, verticalAlignment = Alignment.Top) {
        if (valueOnLeft) {
            valueColumn()
            sliderCard()
        } else {
            sliderCard()
            valueColumn()
        }
    }
}

/**
 * 슬라이더의 화면 읽기: 이름 [caption]과 값 [value]. [onReset]이 있으면 '<이름> 초기화' 동작을 준다(손잡이 두 번 톡과 같다).
 */
@Composable
internal fun Modifier.sliderSemantics(caption: String, value: String, onReset: (() -> Unit)?): Modifier {
    val resetLabel = stringResource(R.string.adjust_reset_item, caption)
    return semantics {
        contentDescription = caption
        stateDescription = value
        if (onReset != null) {
            customActions = listOf(
                CustomAccessibilityAction(resetLabel) {
                    onReset()
                    true
                }
            )
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

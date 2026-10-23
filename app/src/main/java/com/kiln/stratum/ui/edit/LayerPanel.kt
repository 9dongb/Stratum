package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.platform.DocumentColors
import com.kiln.stratum.ui.components.MenuLongPress
import com.kiln.stratum.ui.components.SelectBox
import com.kiln.stratum.ui.components.StratumDropdownMenu
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.StratumIconToggleButton
import com.kiln.stratum.ui.components.StratumSlider
import com.kiln.stratum.ui.components.onSecondaryClick
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.HiddenContentAlpha
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 레이어 패널이 엔진 쪽(ViewModel)에 보내는 일 */
interface LayerActions {

    /** [id] 레이어를 고른다 */
    fun select(id: LayerId)

    /** 고른 레이어 위에 빈 레이어를 더한다 */
    fun add()

    /** 고른 레이어를 지운다 */
    fun delete()

    /** 고른 레이어를 바로 위에 복제한다 */
    fun duplicate()

    /** 고른 레이어를 바로 아래 레이어에 합친다 */
    fun mergeDown()

    /** 보이는 레이어를 모두 가장 아래 보이는 레이어 하나로 합친다 */
    fun mergeVisible()

    /** [id] 레이어(그룹이면 자손과 함께)를 [toIndex](0이 맨 아래) 자리, [parent] 그룹 안(null이면 그룹 밖)으로 옮긴다 */
    fun move(id: LayerId, toIndex: Int, parent: LayerId?)

    /** 불투명도 슬라이더를 끄는 동안 미리 본다(0..1) */
    fun previewOpacity(opacity: Float)

    /** 끄던 불투명도를 기록한다(놓을 때) */
    fun commitOpacity()

    /** 고른 레이어의 혼합 모드를 바꾼다 */
    fun setBlendMode(mode: BlendMode)

    /** [id] 레이어를 보이거나 숨긴다(줄의 눈) */
    fun setVisible(id: LayerId, visible: Boolean)

    /** 고른 글자 레이어를 픽셀 레이어로 바꾼다(레이어 메뉴의 '픽셀로 바꾸기', 사용자 피드백 8차 1-6) */
    fun rasterize() {}

    /** [id] 레이어의 이름을 [name]으로 바꾼다(레이어 메뉴의 '이름 바꾸기' 창, 2026-10-22 사용자 요청) */
    fun rename(id: LayerId, name: String) {}

    /** 고른 레이어에 레이어 마스크를 더하고 그 마스크를 칠한다(선택 영역이 있으면 그 모양, 결정 기록 '피부 보정'의 1d) */
    fun addMask() {}

    /** 고른 레이어 위에 조정 레이어를 더하고 고른다(선택 영역이 있으면 마스크가 그 모양, 결정 기록 '피부 보정'의 1f) */
    fun addAdjustment() {}

    /** 고른 레이어(그룹이면 자손과 함께)를 새 그룹으로 묶는다(레이어 메뉴 '그룹으로 묶기', 결정 기록 '피부 보정'의 1h) */
    fun group() {}

    /** 고른 그룹을 푼다(레이어 메뉴 '그룹 풀기'). 안의 레이어는 자리 그대로 그룹 밖으로 나온다. */
    fun ungroup() {}

    /** 고른 레이어의 마스크를 뒤집는다 */
    fun invertMask() {}

    /** 고른 레이어의 마스크를 지운다(굳히지 않고 버림) */
    fun deleteMask() {}

    /** [id] 레이어를 고르고 그 마스크를 칠한다(브러시는 보이게, 지우개는 가리게). 레이어 줄의 마스크 작은 그림을 누를 때다. */
    fun editMask(id: LayerId) = select(id)

    /** [id] 레이어를 고르고 픽셀을 칠한다(마스크 칠하기를 끝냄). 마스크가 있는 레이어 줄의 작은 그림을 누를 때다. */
    fun editPixels(id: LayerId) = select(id)

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : LayerActions {
        override fun select(id: LayerId) = Unit
        override fun add() = Unit
        override fun delete() = Unit
        override fun duplicate() = Unit
        override fun mergeDown() = Unit
        override fun mergeVisible() = Unit
        override fun move(id: LayerId, toIndex: Int, parent: LayerId?) = Unit
        override fun previewOpacity(opacity: Float) = Unit
        override fun commitOpacity() = Unit
        override fun setBlendMode(mode: BlendMode) = Unit
        override fun setVisible(id: LayerId, visible: Boolean) = Unit
    }
}

/**
 * 레이어 패널이 쓰는 값과 할 일. 편집 화면이 엔진 상태로 만든다.
 *
 * @property layers 레이어(아래부터)
 * @property active 고른 레이어
 * @param liveOpacity 끄는 동안의 불투명도(없으면 null). 값을 읽는 패널만 다시 그려지도록 함수로 받는다.
 * @property enabled 바꿀 수 있는지. 자르기 중에는 막는다(보정 패널과 같은 까닭).
 * @property busy 편집을 처리하는 중이면 더하기·지우기·옮기기·혼합 모드를 막는다(다른 편집 버튼과 같다).
 * @property colorSpace 문서 색 공간. 작은 그림을 그 꼬리표로 보인다.
 * @property editingMask 고른 레이어의 마스크를 칠하는 중인지(결정 기록 '피부 보정'의 1d). 그 줄의 마스크 작은 그림에 테두리를 그린다.
 * @property collapsed 접은 그룹(결정 기록 '피부 보정'의 1h). 그 자손 줄은 목록에서 뺀다. 문서가 아니라 화면 상태다.
 * @property maskOverlay 가린 곳의 빨간 막을 보이는 중인지. 조정 레이어의 마스크 작은 그림은 이것을 켜고 끈다(1i).
 */
@Stable
class LayerControls(
    val layers: List<LayerSummary>,
    val active: LayerId?,
    private val liveOpacity: () -> Float?,
    val enabled: Boolean,
    val busy: Boolean,
    val actions: LayerActions,
    val colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB,
    val editingMask: Boolean = false,
    /** 화면 반전(보기). 레이어 탭 버튼 줄에 그 버튼이 있어 함께 넘긴다(사용자 피드백 7차, 결정 기록 '화면 반전'). */
    val viewFlip: ViewFlip = ViewFlip.None,
    val onViewFlip: (ViewFlip) -> Unit = {},
    /** 레이어 메뉴의 '이름 바꾸기': 편집 화면이 그 레이어의 이름 바꾸기 창을 띄운다 */
    val onRename: (LayerId) -> Unit = {},
    /** 사진 넣기 버튼(사용자 피드백 12차): 편집 화면이 사진 선택기를 띄우고, 고른 사진을 고른 레이어 위 새 레이어로 넣는다 */
    val onAddPhoto: () -> Unit = {},
    /** 레이어 메뉴의 '조정 레이어 더하기': 편집 화면은 더한 뒤 조정 패널을 연다(결정 기록 '피부 보정'의 1f) */
    val onAddAdjustment: () -> Unit = { actions.addAdjustment() },
    val collapsed: Set<LayerId> = emptySet(),
    /** 그룹 줄의 폴더 아이콘을 누르면 접고 펼친다 */
    val onToggleGroup: (LayerId) -> Unit = {},
    val maskOverlay: Boolean = false
) {
    /** 고른 레이어의 속성 */
    val activeLayer: LayerSummary?
        get() = layers.firstOrNull { it.id == active }

    /** 보일 불투명도: 끄는 동안의 값, 없으면 고른 레이어의 값 */
    val opacity: Float
        get() = liveOpacity() ?: activeLayer?.opacity ?: 1f

    /** 고른 레이어의 자리(0이 맨 아래). 없으면 -1 */
    val activeIndex: Int
        get() = layers.indexOfFirst { it.id == active }

    /** 버튼(더하기·지우기·옮기기·혼합 모드)을 누를 수 있는지 */
    val canEdit: Boolean
        get() = enabled && !busy

    /** 고른 레이어를 지울 수 있는지: 지워도(그룹이면 자손과 함께) 레이어가 남는다 */
    val canDeleteActive: Boolean
        get() = activeIndex >= 0 && LayerRows.blockSize(layers, activeIndex) < layers.size
}

/** 레이어 줄 하나의 높이. 작은 그림(40dp)과 위아래 여백, 누르는 영역 48dp 이상. */
private val RowHeight = 56.dp

/** 작은 그림 칸의 크기와 그 바탕 체크 무늬 한 칸 */
private val ThumbnailSize = 40.dp

/** 마스크 작은 그림과 레이어 작은 그림 사이, 칠하는 쪽 작은 그림의 테두리(결정 기록 '피부 보정'의 1d) */
private val MaskGap = 4.dp
private val TargetBorder = 2.dp

/** 그룹 안 줄을 들여 쓰는 한 단계와 들여 쓰는 깊이 한도(결정 기록 '피부 보정'의 1h). 깊이 둔 그룹도 이름 칸이 남게 4단계까지만 들여 쓴다. */
private val IndentStep = 12.dp
private const val MaxIndentLevels = 4

/** 작은 그림 구석의 글자 레이어 표시 크기 */
private val TextBadgeSize = 16.dp
private val ThumbnailChecker = 5.dp

/**
 * 레이어 패널의 내용(메인 화면 떠 있는 패널, 커버 화면 레이어 시트): 레이어 목록, 그 아래 고른 레이어의 불투명도와 혼합 모드.
 * 목록은 줄이 적으면 그만큼만 차지하고(패널도 작아진다), 많으면 남는 높이에서 밀어 본다. 버튼 줄은 패널 제목 줄에 놓는다([LayerToolbar]).
 */
@Composable
fun LayerColumn(controls: LayerControls, modifier: Modifier = Modifier) {
    CommitOpacityOnDispose(controls.actions)
    Column(modifier.testTag(EditScreenTags.LAYER_PANEL)) {
        LayerList(controls, Modifier.fillMaxWidth().weight(1f, fill = false))
        LayerOpacityRow(controls)
        BlendModeRow(controls)
    }
}

/**
 * 레이어 버튼 줄: 더하기, 지우기(고른 레이어에 한다), 화면 상하 반전, 화면 좌우 반전(켜고 끔, 좌우가 오른쪽 끝, 2026-10-20 사용자 요청).
 * 지우기는 레이어가 둘 이상일 때만 누를 수 있고, 실행 취소로 되돌릴 수 있어 따로 묻지 않는다.
 * 반전은 레이어가 아니라 캔버스 보기를 뒤집는다(픽셀·기록은 그대로, 결정 기록 '화면 반전'). 처리 중에도 누를 수 있다.
 * 전에 있던 위로·아래로 버튼은 사용자 요청으로 뺐다(2026-10-20). 순서는 손잡이 끌기와 화면 읽기 기능의 '위로'·'아래로' 동작으로 바꾼다.
 */
@Composable
fun LayerToolbar(controls: LayerControls, modifier: Modifier = Modifier) {
    val actions = controls.actions
    val flip = controls.viewFlip
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ToolbarButton(R.drawable.ic_add, R.string.layer_add, controls.canEdit, EditScreenTags.LAYER_ADD) { actions.add() }
        // + 오른쪽에 사진 넣기(사용자 요청: "레이어 패널의 + 오른쪽에 이미지 아이콘이나 카메라 아이콘으로 사진 추가 가능하도록 하세요.")
        ToolbarButton(R.drawable.ic_add_photo_alternate, R.string.layer_add_photo, controls.canEdit, EditScreenTags.LAYER_ADD_PHOTO) {
            controls.onAddPhoto()
        }
        ToolbarButton(R.drawable.ic_delete, R.string.layer_delete, controls.canEdit && controls.canDeleteActive, EditScreenTags.LAYER_DELETE) {
            actions.delete()
        }
        // 상하는 같은 아이콘을 90° 돌린다(자르기·변형의 상하 뒤집기와 같다)
        FlipToggle(R.string.view_flip_vertical, flip.vertical, controls.enabled, EditScreenTags.VIEW_FLIP_VERTICAL, iconRotation = 90f) {
            controls.onViewFlip(flip.copy(vertical = it))
        }
        FlipToggle(R.string.view_flip_horizontal, flip.horizontal, controls.enabled, EditScreenTags.VIEW_FLIP_HORIZONTAL, iconRotation = 0f) {
            controls.onViewFlip(flip.copy(horizontal = it))
        }
    }
}

/** 화면 반전 켜고 끄는 버튼. 켜진 동안 선택 배경·선택 색 아이콘이다. */
@Composable
private fun FlipToggle(@StringRes label: Int, checked: Boolean, enabled: Boolean, tag: String, iconRotation: Float, onCheckedChange: (Boolean) -> Unit) {
    StratumIconToggleButton(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(painterResource(R.drawable.ic_flip), contentDescription = stringResource(label), modifier = Modifier.rotate(iconRotation))
    }
}

@Composable
private fun ToolbarButton(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, tag: String, onClick: () -> Unit) {
    StratumIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

/**
 * 끄는 도중에 패널이 사라지면(접기·펼치기로 배치가 바뀜) 슬라이더가 '놓음'을 알리지 못한다.
 * 그때는 놓은 것으로 보고 끌던 값을 기록한다(보정 패널과 같다). 끄는 중이 아니면 아무 일도 없다.
 */
@Composable
private fun CommitOpacityOnDispose(actions: LayerActions) {
    DisposableEffect(actions) {
        onDispose { actions.commitOpacity() }
    }
}

/**
 * 레이어 목록. 위 레이어가 위에 보인다(포토샵과 같다). 줄을 누르면 그 레이어를 고르고,
 * 오른쪽 손잡이를 위아래로 끌면 순서를 바꾼다(놓은 자리로 옮기기, 실행 취소 한 단계).
 * 끄는 동안 끄는 줄은 손가락을 따라가고, 사이의 줄은 한 칸씩 비켜 자리를 보인다.
 * 화면 읽기 기능에서는 줄마다 '위로'·'아래로' 동작으로 옮긴다.
 * 그룹(결정 기록 '피부 보정'의 1h): 그룹 안의 줄은 들여 쓰고, 접은 그룹의 자손은 빼고 보인다. 그룹 줄을 끌면 보이는 자손과 함께 움직이고,
 * 놓은 자리에 따라 그룹에 들어가거나 나온다(펼친 그룹 줄 바로 아래면 그 그룹 안 맨 위, [LayerRows.drop]).
 */
@Composable
private fun LayerList(controls: LayerControls, modifier: Modifier = Modifier) {
    // 화면은 위 레이어부터 보인다. 문서 순서(아래부터)와 반대이고, 접은 그룹의 자손은 빠진다(결정 기록 '피부 보정'의 1h)
    val rows = LayerRows.of(controls.layers, controls.collapsed)
    val rowHeight = with(LocalDensity.current) { RowHeight.toPx() }
    var dragging by remember { mutableStateOf<LayerId?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 끄는 줄과 그 보이는 자손(펼친 그룹)이 함께 움직이고, 사이의 줄은 그만큼 비켜 자리를 보인다
    val from = rows.indexOfFirst { it.layer.id == dragging }
    val unit = if (from < 0) 0 else LayerRows.unitSize(rows, from)
    val target = if (from < 0) -1 else (from + (dragOffset / rowHeight).roundToInt()).coerceIn(0, rows.size - unit)
    Column(modifier.verticalScroll(rememberScrollState()).testTag(EditScreenTags.LAYER_LIST)) {
        rows.forEachIndexed { index, row ->
            val layer = row.layer
            key(layer.id.value) {
                val shift = when {
                    from < 0 -> 0f
                    index in from until from + unit -> dragOffset
                    target > from && index in (from + unit) until (target + unit) -> -rowHeight * unit
                    target < from && index in target until from -> rowHeight * unit
                    else -> 0f
                }
                LayerRow(
                    layer = layer,
                    selected = layer.id == controls.active,
                    controls = controls,
                    row = row,
                    rows = rows,
                    displayIndex = index,
                    modifier = Modifier
                        .zIndex(if (index in from until from + unit) 1f else 0f)
                        .graphicsLayer { translationY = shift },
                    onDragStart = {
                        dragging = layer.id
                        dragOffset = 0f
                    },
                    onDrag = { delta -> dragOffset += delta },
                    onDragEnd = {
                        val start = rows.indexOfFirst { it.layer.id == layer.id }
                        val size = if (start < 0) 0 else LayerRows.unitSize(rows, start)
                        val end = (start + (dragOffset / rowHeight).roundToInt()).coerceIn(0, rows.size - size)
                        dragging = null
                        dragOffset = 0f
                        // 놓은 틈을 문서 자리와 들어갈 그룹으로
                        val move = if (start < 0) null else LayerRows.drop(controls.layers, rows, start, end, controls.collapsed)
                        if (move != null) controls.actions.move(move.id, move.toIndex, move.parent)
                    },
                    onDragCancel = {
                        dragging = null
                        dragOffset = 0f
                    }
                )
            }
        }
    }
}

/**
 * 레이어 줄 하나: 눈(보이기·숨기기), 작은 그림, 이름, 혼합 모드·불투명도, 순서 손잡이. 고른 줄은 바탕을 칠하고, 숨긴 줄(숨긴 그룹 안 포함)은 작은 그림·이름을 흐리게 보인다.
 * 그룹 줄은 작은 그림 대신 폴더 아이콘이고, 누르면 접고 펼친다. 혼합 모드 자리는 '통과'다.
 * 눈을 누르면 그 레이어를 보이거나 숨긴다(고르지는 않는다, 포토샵과 같다).
 * 작은 그림·이름 쪽을 길게 누르면 그 레이어를 고르고 메뉴([LayerMenu])를 띄운다. 손잡이는 끌기라 길게 누르기에서 뺀다.
 * 화면 읽기 기능에서는 '레이어 메뉴' 동작(길게 누르기)으로 연다.
 */
@Composable
private fun LayerRow(
    layer: LayerSummary,
    selected: Boolean,
    controls: LayerControls,
    row: LayerRows.Row,
    rows: List<LayerRows.Row>,
    displayIndex: Int,
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val colors = StratumTheme.colors
    val actions = controls.actions
    val documentIndex = row.documentIndex
    val percent = (layer.opacity * 100).roundToInt()
    // 그룹은 통과 그룹뿐이라 혼합 모드 대신 '통과'다(결정 기록 '피부 보정'의 1h)
    val mode = stringResource(if (layer.group) R.string.blend_pass_through else blendModeLabel(layer.blendMode))
    val expanded = layer.group && layer.id !in controls.collapsed
    val description = stringResource(R.string.layer_row_description, layer.name, mode, percent)
    val selectedLabel = stringResource(R.string.layer_selected)
    val hiddenLabel = stringResource(R.string.layer_hidden)
    val textLabel = stringResource(R.string.layer_text_badge)
    val liquifyLabel = stringResource(R.string.layer_liquify_badge)
    val maskLabel = stringResource(R.string.layer_mask)
    val maskEditingLabel = stringResource(R.string.layer_mask_editing)
    // 조정 레이어는 늘 마스크를 칠해, 마스크 작은 그림은 빨간 막을 켜고 끈다(결정 기록 '피부 보정'의 1i)
    val maskEditLabel = stringResource(if (layer.adjustment && selected && controls.maskOverlay) R.string.layer_mask_overlay_hide else R.string.layer_mask_edit)
    val pixelsLabel = stringResource(R.string.layer_pixels_edit)
    val adjustmentLabel = stringResource(R.string.layer_adjustment)
    val groupLabel = stringResource(R.string.layer_group_label)
    val collapsedLabel = stringResource(R.string.layer_group_collapsed)
    val expandLabel = stringResource(if (expanded) R.string.layer_group_collapse else R.string.layer_group_expand)
    val rowState = listOfNotNull(
        selectedLabel.takeIf { selected },
        hiddenLabel.takeIf { !row.shown },
        groupLabel.takeIf { layer.group },
        collapsedLabel.takeIf { layer.group && !expanded },
        adjustmentLabel.takeIf { layer.adjustment },
        textLabel.takeIf { layer.text != null },
        liquifyLabel.takeIf { layer.liquify }
    ).joinToString(", ")
    val upLabel = stringResource(R.string.layer_move_up)
    val downLabel = stringResource(R.string.layer_move_down)
    val menuLabel = stringResource(R.string.layer_menu)
    var menuOpen by remember { mutableStateOf(false) }
    val openMenu = {
        if (controls.enabled) {
            actions.select(layer.id)
            menuOpen = true
        }
    }
    // 제스처는 한 번 시작해 계속 돌므로, 그 안에서는 늘 지금 값을 부른다
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val canMove = controls.canEdit
    // 메뉴가 줄을 따라 움직이도록(끄는 동안 등) 줄과 메뉴를 함께 감싼다
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                // 둥근 카드가 아니라 평평한 줄이고, 고르면 배경만 칠한다(시안)
                .background(if (selected) colors.selectedBackground else Color.Transparent)
                .selectable(selected = selected, enabled = controls.enabled, onClick = { actions.select(layer.id) })
                .semantics {
                    contentDescription = description
                    if (rowState.isNotEmpty()) stateDescription = rowState
                    if (canMove) {
                        // 위로·아래로는 한 자리씩 옮긴다. 그룹의 처음·마지막 자리에서는 그룹 밖으로 나오고, 펼친 그룹 옆에서는 그 안으로 들어간다(LayerRows)
                        val up = LayerRows.up(controls.layers, rows, displayIndex, controls.collapsed)
                        val down = LayerRows.down(controls.layers, rows, displayIndex, controls.collapsed)
                        customActions = listOfNotNull(
                            up?.let { move ->
                                CustomAccessibilityAction(upLabel) {
                                    actions.move(move.id, move.toIndex, move.parent)
                                    true
                                }
                            },
                            down?.let { move ->
                                CustomAccessibilityAction(downLabel) {
                                    actions.move(move.id, move.toIndex, move.parent)
                                    true
                                }
                            }
                        )
                    }
                    if (controls.enabled) {
                        onLongClick(label = menuLabel) {
                            openMenu()
                            true
                        }
                    }
                }
                .testTag(EditScreenTags.layerRow(layer.id)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 눈: 누르면 보이기·숨기기(누르는 영역 48dp). 화면 읽기 기능은 '레이어 보이기' 켜짐·꺼짐으로 읽는다.
            // 켜진 눈도 강조색이 아니라 보조색이고(줄마다 있어 눈에 띄지 않게), 숨긴 눈은 설명색이다
            StratumIconToggleButton(
                checked = layer.visible,
                onCheckedChange = { actions.setVisible(layer.id, it) },
                enabled = controls.canEdit,
                checkedBackground = false,
                contentColor = colors.textDescription,
                checkedContentColor = colors.textSecondary,
                modifier = Modifier.testTag(EditScreenTags.layerVisibility(layer.id))
            ) {
                Icon(
                    painterResource(if (layer.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    contentDescription = stringResource(R.string.layer_visibility)
                )
            }
            // 작은 그림·이름: 길게 누르면 메뉴. 줄의 누르기(고르기)보다 먼저 알아채고, 길게 누른 뒤에는 누르기가 일어나지 않는다.
            // 숨긴 레이어는 흐리게 보인다
            // 메뉴를 여는 꾹 누르기는 메뉴 묶음 시간(0.3초)이다(결정 기록 '꾹 누르기 시간')
            MenuLongPress {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 숨긴 그룹 안의 레이어도 흐리게 보인다(1h)
                        .alpha(if (row.shown) 1f else HiddenContentAlpha)
                        // 마우스 오른쪽 버튼으로도 메뉴를 연다(사용자 피드백 12차 4)
                        .onSecondaryClick(openMenu)
                        .longPressFirst(openMenu),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 그룹 안의 줄은 깊이만큼 들여 쓴다(결정 기록 '피부 보정'의 1h)
                    if (row.depth > 0) Spacer(Modifier.width(IndentStep * minOf(row.depth, MaxIndentLevels)))
                    // 글자 레이어(다시 고칠 수 있음, 사용자 피드백 8차 1-6)는 작은 그림 오른쪽 아래에 글자 표시를,
                    // 다시 고칠 수 있는 유동화가 있는 레이어(결정 기록 '얼굴 인식 보정' 3단계)는 유동화 표시(유동화 도구 아이콘)를 단다.
                    // 마스크가 있으면 작은 그림을 누르면 픽셀을, 마스크 작은 그림을 누르면 마스크를 칠하고, 칠하는 쪽에 테두리를 그린다
                    // (결정 기록 '피부 보정'의 1d, 포토샵과 같다)
                    val maskTarget = selected && controls.editingMask && layer.hasMask
                    Box(
                        Modifier
                            .size(ThumbnailSize)
                            .then(if (layer.hasMask && selected && !maskTarget) Modifier.border(TargetBorder, colors.selectedContent, StratumShapes.Thumbnail) else Modifier)
                            .then(
                                when {
                                    // 그룹은 폴더 아이콘을 누르면 접고 펼친다(결정 기록 '피부 보정'의 1h)
                                    layer.group ->
                                        Modifier
                                            .clickable(onClickLabel = expandLabel) { controls.onToggleGroup(layer.id) }
                                            .semantics { contentDescription = expandLabel }
                                    // 조정 레이어는 픽셀이 없어 마스크만 칠한다(아이콘을 눌러도 고르기만, 결정 기록 '피부 보정'의 1f)
                                    layer.hasMask && !layer.adjustment ->
                                        Modifier.clickable(enabled = controls.enabled, onClickLabel = pixelsLabel) { actions.editPixels(layer.id) }
                                    else -> Modifier
                                }
                            )
                            .testTag(if (layer.group) EditScreenTags.layerGroupToggle(layer.id) else EditScreenTags.layerThumbnail(layer.id))
                    ) {
                        if (layer.adjustment || layer.group) {
                            // 조정 레이어는 작은 그림 대신 조정 아이콘, 그룹은 폴더 아이콘(펼치면 열린 폴더)이다
                            val icon = when {
                                layer.adjustment -> R.drawable.ic_tune
                                expanded -> R.drawable.ic_folder_open
                                else -> R.drawable.ic_folder
                            }
                            Box(Modifier.fillMaxSize().background(colors.fieldBackground, StratumShapes.Thumbnail), contentAlignment = Alignment.Center) {
                                Icon(painterResource(icon), contentDescription = null, tint = colors.textSecondary)
                            }
                        } else {
                            LayerThumbnail(layer.thumbnail, controls.colorSpace, Modifier.fillMaxSize())
                        }
                        val badge = when {
                            layer.text != null -> R.drawable.ic_text_fields to EditScreenTags.layerTextBadge(layer.id)
                            layer.liquify -> R.drawable.ic_waves to EditScreenTags.layerLiquifyBadge(layer.id)
                            else -> null
                        }
                        if (badge != null) {
                            Icon(
                                painterResource(badge.first),
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(TextBadgeSize)
                                    .background(colors.panel, StratumShapes.Thumbnail)
                                    .testTag(badge.second),
                                tint = colors.textSecondary
                            )
                        }
                    }
                    // 마스크 작은 그림: 흰 곳은 보이고 검은 곳은 가린다
                    if (layer.hasMask) {
                        Spacer(Modifier.width(MaskGap))
                        Box(
                            Modifier
                                .size(ThumbnailSize)
                                .then(if (maskTarget) Modifier.border(TargetBorder, colors.selectedContent, StratumShapes.Thumbnail) else Modifier)
                                .clickable(enabled = controls.enabled, onClickLabel = maskEditLabel) { actions.editMask(layer.id) }
                                .semantics {
                                    contentDescription = maskLabel
                                    if (maskTarget) stateDescription = maskEditingLabel
                                }
                                .testTag(EditScreenTags.layerMask(layer.id))
                        ) {
                            LayerThumbnail(layer.mask, DocumentColorSpace.SRGB, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = layer.name,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.layer_row_detail, mode, percent),
                            color = colors.textDescription,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            // 순서 손잡이: 위아래로 끌면 옮긴다(누르는 영역 48dp)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag(EditScreenTags.layerHandle(layer.id))
                    .pointerInput(layer.id, canMove) {
                        if (!canMove) return@pointerInput
                        detectVerticalDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragCancel() }
                        ) { change, delta ->
                            change.consume()
                            currentOnDrag(delta)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = if (canMove) colors.textSecondary else colors.textDisabled
                )
            }
        }
        LayerMenu(expanded = menuOpen, controls = controls, documentIndex = documentIndex, onDismiss = { menuOpen = false })
    }
}

/**
 * 레이어 줄을 길게 누르면 뜨는 메뉴: 이름 바꾸기, 복제, 조정 레이어 더하기, 그룹으로 묶기, 그룹 풀기(그룹 줄에만 보임),
 * 픽셀로 바꾸기(글자 레이어·다시 고칠 수 있는 유동화가 있는 레이어에만 보임),
 * 마스크 더하기(마스크가 없을 때, 그룹은 빼고) 또는 마스크 반전·지우기(있을 때), 아래 레이어와 병합, 보이는 레이어 병합, 삭제.
 * 모두 고른 레이어(길게 누른 레이어)에 하고 실행 취소 한 단계다(이름 바꾸기는 창에서 이름을 받은 뒤). 할 수 없는 항목은 꺼 둔다
 * (맨 아래 레이어나 둘 중 하나를 숨겼으면 아래와 병합, 보이는 레이어가 하나뿐이면 보이는 레이어 병합, 한 장뿐이면 삭제).
 */
@Composable
private fun LayerMenu(expanded: Boolean, controls: LayerControls, documentIndex: Int, onDismiss: () -> Unit) {
    val actions = controls.actions
    val canEdit = controls.canEdit
    StratumDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val layers = controls.layers
        // 이름 바꾸기: 창에서 새 이름을 받는다(2026-10-22 사용자 요청: "옵션에 레이어 이름 변경 옵션도 넣으려 합니다")
        LayerMenuItem(R.string.layer_rename, canEdit, EditScreenTags.LAYER_MENU_RENAME, onDismiss) { controls.onRename(layers[documentIndex].id) }
        LayerMenuItem(R.string.layer_duplicate, canEdit, EditScreenTags.LAYER_MENU_DUPLICATE, onDismiss, actions::duplicate)
        // 조정 레이어(결정 기록 '피부 보정'의 1f): 고른 레이어 위에 더한다
        LayerMenuItem(R.string.layer_adjustment_add, canEdit, EditScreenTags.LAYER_MENU_ADJUSTMENT_ADD, onDismiss, controls.onAddAdjustment)
        // 그룹(결정 기록 '피부 보정'의 1h): 묶기는 모든 줄(그룹이면 그 그룹째), 풀기는 그룹 줄만
        LayerMenuItem(R.string.layer_group, canEdit, EditScreenTags.LAYER_MENU_GROUP, onDismiss, actions::group)
        if (layers[documentIndex].group) {
            LayerMenuItem(R.string.layer_ungroup, canEdit, EditScreenTags.LAYER_MENU_UNGROUP, onDismiss, actions::ungroup)
        }
        // 픽셀로 바꾸기는 글자 레이어에만 보인다(그 뒤로는 칠할 수 있지만 글자를 다시 고칠 수 없다, 사용자 피드백 8차 1-6).
        // 다른 레이어에서는 꺼 두지 않고 뺀다(2026-10-22 사용자 요청: "글자 레이어 에서만 해당 옵션이 보이게 해주세요").
        // 다시 고칠 수 있는 유동화도 같은 항목으로 직접 굳힌다(결정 기록 '얼굴 인식 보정' 3단계)
        if (layers[documentIndex].text != null || layers[documentIndex].liquify) {
            LayerMenuItem(R.string.layer_rasterize, canEdit, EditScreenTags.LAYER_MENU_RASTERIZE, onDismiss, actions::rasterize)
        }
        // 레이어 마스크(결정 기록 '피부 보정'의 1d): 없으면 더하기, 있으면 반전·지우기. 조정 레이어는 늘 마스크를 칠해 지우지 않는다(1f).
        // 그룹에는 마스크를 두지 않는다(1h)
        if (layers[documentIndex].hasMask) {
            LayerMenuItem(R.string.layer_mask_invert, canEdit, EditScreenTags.LAYER_MENU_MASK_INVERT, onDismiss, actions::invertMask)
            if (!layers[documentIndex].adjustment) {
                LayerMenuItem(R.string.layer_mask_delete, canEdit, EditScreenTags.LAYER_MENU_MASK_DELETE, onDismiss, actions::deleteMask)
            }
        } else if (!layers[documentIndex].group) {
            LayerMenuItem(R.string.layer_mask_add, canEdit, EditScreenTags.LAYER_MENU_MASK_ADD, onDismiss, actions::addMask)
        }
        // 숨긴 레이어는 아래와 합치지 않는다(숨긴 내용을 모르고 합치거나 잃지 않게)
        // 조정 레이어 위로는 합치지 않는다(조정 레이어를 아래 픽셀 레이어에 굳히는 것은 된다).
        // 그룹은 합치지 않고, 같은 그룹 안의 이웃끼리만 합친다(엔진의 MergeDown과 같다, 1h)
        val lower = layers.getOrNull(documentIndex - 1)
        val upper = layers[documentIndex]
        val canMergeDown = lower != null && upper.visible && lower.visible && !lower.adjustment && !lower.group && !upper.group &&
            upper.parent == lower.parent
        LayerMenuItem(R.string.layer_merge_down, canEdit && canMergeDown, EditScreenTags.LAYER_MENU_MERGE_DOWN, onDismiss, actions::mergeDown)
        // 보이는 레이어 병합은 가장 아래 보이는 픽셀 레이어부터 위로 합친다(그 아래 조정 레이어는 그대로, 그룹이 물려준 보이기·불투명도까지,
        // 엔진의 MergeVisible.mergedLayers와 같다)
        LayerMenuItem(
            R.string.layer_merge_visible,
            canEdit && LayerRows.mergeVisibleCount(layers) >= 2,
            EditScreenTags.LAYER_MENU_MERGE_VISIBLE,
            onDismiss,
            actions::mergeVisible
        )
        LayerMenuItem(R.string.layer_delete, canEdit && LayerRows.blockSize(layers, documentIndex) < layers.size, EditScreenTags.LAYER_MENU_DELETE, onDismiss, actions::delete)
    }
}

/** 메뉴 항목 하나. 누르면 메뉴를 닫고 [onClick]을 한다. */
@Composable
private fun LayerMenuItem(@StringRes label: Int, enabled: Boolean, tag: String, onDismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.testTag(tag)
    )
}

/**
 * 레이어의 작은 그림. 비율을 지켜 칸 가운데에 놓고, 그림 자리에만 캔버스와 같은 체크 무늬를 깔아 투명한 곳을 보인다.
 * 남는 칸(사진 비율과 칸 비율이 다른 곳)은 패널 바탕색이라 투명한 픽셀과 헷갈리지 않는다.
 * 빈 레이어(작은 그림 없음)는 칸 전체가 체크 무늬다.
 */
@Composable
private fun LayerThumbnail(thumbnail: Bitmap?, colorSpace: DocumentColorSpace, modifier: Modifier = Modifier) {
    val image = remember(thumbnail, colorSpace) { thumbnail?.let { DocumentColors.forScreen(it, colorSpace).asImageBitmap() } }
    val colors = StratumTheme.colors
    Canvas(modifier.clip(StratumShapes.Thumbnail)) {
        drawRect(colors.fieldBackground)
        val scale = if (image == null) 1f else minOf(size.width / image.width, size.height / image.height)
        val width = if (image == null) size.width.roundToInt() else (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = if (image == null) size.height.roundToInt() else (image.height * scale).roundToInt().coerceAtLeast(1)
        val left = ((size.width - width) / 2).roundToInt()
        val top = ((size.height - height) / 2).roundToInt()
        // 그림 자리의 체크 무늬
        clipRect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()) {
            val square = ThumbnailChecker.toPx()
            drawRect(CanvasOverlayColors.CheckerLight)
            for (row in 0..(height / square).toInt()) {
                for (column in 0..(width / square).toInt()) {
                    if ((row + column) % 2 == 1) drawRect(CanvasOverlayColors.CheckerDark, Offset(left + column * square, top + row * square), Size(square, square))
                }
            }
        }
        if (image != null) {
            drawImage(image = image, dstOffset = IntOffset(left, top), dstSize = IntSize(width, height), filterQuality = FilterQuality.Medium)
        }
        drawRect(colors.divider, Offset(left.toFloat(), top.toFloat()), Size(width.toFloat(), height.toFloat()), style = Stroke(width = 1.dp.toPx()))
    }
}

/** 고른 레이어의 불투명도(0~100%). 끄는 동안 미리 보고 놓으면 기록한다(보정 슬라이더와 같다). */
@Composable
private fun LayerOpacityRow(controls: LayerControls) {
    val name = stringResource(R.string.layer_opacity)
    val percent = (controls.opacity * 100).roundToInt()
    val shown = stringResource(R.string.layer_percent, percent)
    val enabled = controls.enabled && controls.activeLayer != null
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            modifier = Modifier.width(88.dp),
            color = if (enabled) StratumTheme.colors.textSecondary else StratumTheme.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        StratumSlider(
            value = controls.opacity,
            onValueChange = { controls.actions.previewOpacity((it * 100).roundToInt() / 100f) },
            onValueChangeFinished = controls.actions::commitOpacity,
            valueRange = 0f..1f,
            enabled = enabled,
            centered = false,
            modifier = Modifier
                .weight(1f)
                .testTag(EditScreenTags.LAYER_OPACITY)
                .semantics {
                    contentDescription = name
                    stateDescription = shown
                }
        )
        Text(
            text = shown,
            modifier = Modifier.width(48.dp),
            color = if (enabled) StratumTheme.colors.textPrimary else StratumTheme.colors.textDisabled,
            textAlign = TextAlign.End,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** 고른 레이어의 혼합 모드. 버튼을 누르면 네 가지 중에서 고른다. */
@Composable
private fun BlendModeRow(controls: LayerControls) {
    var open by remember { mutableStateOf(false) }
    val current = controls.activeLayer?.blendMode ?: BlendMode.NORMAL
    // 그룹은 통과 그룹뿐이라 고를 혼합 모드가 없다(결정 기록 '피부 보정'의 1h)
    val group = controls.activeLayer?.group == true
    val enabled = controls.canEdit && controls.activeLayer != null && !group
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.layer_blend_mode),
            modifier = Modifier.width(88.dp),
            color = if (enabled) StratumTheme.colors.textSecondary else StratumTheme.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        Box {
            // 알약 버튼 대신 모서리 6dp의 작은 선택 상자(시안)
            SelectBox(
                text = stringResource(if (group) R.string.blend_pass_through else blendModeLabel(current)),
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.testTag(EditScreenTags.LAYER_BLEND)
            )
            StratumDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (mode in BlendMode.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(blendModeLabel(mode))) },
                        onClick = {
                            open = false
                            if (mode != current) controls.actions.setBlendMode(mode)
                        },
                        leadingIcon = {
                            if (mode == current) Icon(painterResource(R.drawable.ic_check), contentDescription = null) else Spacer(Modifier.size(24.dp))
                        },
                        modifier = Modifier.testTag(EditScreenTags.blendMode(mode))
                    )
                }
            }
        }
    }
}

/** 혼합 모드의 이름 */
@StringRes
private fun blendModeLabel(mode: BlendMode): Int =
    when (mode) {
        BlendMode.NORMAL -> R.string.blend_normal
        BlendMode.MULTIPLY -> R.string.blend_multiply
        BlendMode.SCREEN -> R.string.blend_screen
        BlendMode.OVERLAY -> R.string.blend_overlay
    }

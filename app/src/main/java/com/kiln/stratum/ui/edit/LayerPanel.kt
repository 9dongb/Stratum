package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.platform.DocumentColors
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
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

    /** [id] 레이어를 [toIndex](0이 맨 아래) 자리로 옮긴다 */
    fun move(id: LayerId, toIndex: Int)

    /** 불투명도 슬라이더를 끄는 동안 미리 본다(0..1) */
    fun previewOpacity(opacity: Float)

    /** 끄던 불투명도를 기록한다(놓을 때) */
    fun commitOpacity()

    /** 고른 레이어의 혼합 모드를 바꾼다 */
    fun setBlendMode(mode: BlendMode)

    /** [id] 레이어를 보이거나 숨긴다(줄의 눈) */
    fun setVisible(id: LayerId, visible: Boolean)

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : LayerActions {
        override fun select(id: LayerId) = Unit
        override fun add() = Unit
        override fun delete() = Unit
        override fun duplicate() = Unit
        override fun mergeDown() = Unit
        override fun mergeVisible() = Unit
        override fun move(id: LayerId, toIndex: Int) = Unit
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
 */
@Stable
class LayerControls(
    val layers: List<LayerSummary>,
    val active: LayerId?,
    private val liveOpacity: () -> Float?,
    val enabled: Boolean,
    val busy: Boolean,
    val actions: LayerActions,
    val colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB
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
}

/** 레이어 줄 하나의 높이. 작은 그림(40dp)과 위아래 여백, 누르는 영역 48dp 이상. */
private val RowHeight = 56.dp

/** 숨긴 레이어 줄의 작은 그림·이름 흐림 정도 */
private const val HiddenAlpha = 0.4f

/** 작은 그림 칸의 크기와 그 바탕 체크 무늬 한 칸 */
private val ThumbnailSize = 40.dp
private val ThumbnailChecker = 5.dp

/** 커버 화면 레이어 목록의 최대 높이(줄 세 개). 캔버스를 너무 가리지 않게 한다. */
private val CompactListHeight = 168.dp

/**
 * 커버 화면(Compact)의 레이어 옵션: 버튼 줄(더하기·지우기·위로·아래로), 레이어 목록(줄 세 개까지 보이고 밀어 봄),
 * 고른 레이어의 불투명도와 혼합 모드. 기획서대로 레이어 패널은 아래에서 올라오는 옵션 자리에 연다.
 */
@Composable
fun LayerCompact(controls: LayerControls, modifier: Modifier = Modifier) {
    CommitOpacityOnDispose(controls.actions)
    Column(modifier.testTag(EditScreenTags.LAYER_PANEL), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LayerToolbar(controls)
        LayerList(controls, Modifier.fillMaxWidth().heightIn(max = CompactListHeight))
        LayerOpacityRow(controls)
        BlendModeRow(controls)
    }
}

/**
 * 메인 세로 아래 패널의 레이어 탭 내용: 왼쪽은 레이어 목록, 오른쪽은 고른 레이어의 불투명도와 혼합 모드.
 * 버튼 줄은 탭 줄 오른쪽에 놓는다([LayerToolbar]). 가로 힌지의 띠에 걸리면 띠 아래로 비켜 놓는다(보정 탭과 같다).
 */
@Composable
fun LayerTwoColumns(controls: LayerControls, modifier: Modifier = Modifier) {
    CommitOpacityOnDispose(controls.actions)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.LAYER_PANEL), fillHeight = false) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val propertiesWidth = min(maxWidth * 0.45f, 300.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LayerList(controls, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(propertiesWidth)) {
                    LayerOpacityRow(controls)
                    BlendModeRow(controls)
                }
            }
        }
    }
}

/**
 * 메인 가로 오른쪽 패널의 레이어 칸 내용: 레이어 목록(남는 높이), 그 아래 고른 레이어의 불투명도와 혼합 모드.
 * 버튼 줄은 칸 제목 줄 오른쪽에 놓는다([LayerToolbar]).
 */
@Composable
fun LayerColumn(controls: LayerControls, modifier: Modifier = Modifier) {
    CommitOpacityOnDispose(controls.actions)
    Column(modifier.testTag(EditScreenTags.LAYER_PANEL)) {
        LayerList(controls, Modifier.fillMaxWidth().weight(1f))
        LayerOpacityRow(controls)
        BlendModeRow(controls)
    }
}

/**
 * 레이어 버튼 줄: 더하기, 지우기, 위로, 아래로. 고른 레이어에 한다.
 * 지우기는 레이어가 둘 이상일 때만 누를 수 있고, 실행 취소로 되돌릴 수 있어 따로 묻지 않는다.
 */
@Composable
fun LayerToolbar(controls: LayerControls, modifier: Modifier = Modifier) {
    val actions = controls.actions
    val index = controls.activeIndex
    val active = controls.activeLayer
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ToolbarButton(R.drawable.ic_add, R.string.layer_add, controls.canEdit, EditScreenTags.LAYER_ADD) { actions.add() }
        ToolbarButton(R.drawable.ic_delete, R.string.layer_delete, controls.canEdit && controls.layers.size > 1, EditScreenTags.LAYER_DELETE) {
            actions.delete()
        }
        ToolbarButton(R.drawable.ic_arrow_upward, R.string.layer_move_up, controls.canEdit && active != null && index < controls.layers.lastIndex, EditScreenTags.LAYER_UP) {
            if (active != null) actions.move(active.id, index + 1)
        }
        ToolbarButton(R.drawable.ic_arrow_downward, R.string.layer_move_down, controls.canEdit && active != null && index > 0, EditScreenTags.LAYER_DOWN) {
            if (active != null) actions.move(active.id, index - 1)
        }
    }
}

@Composable
private fun ToolbarButton(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
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
 */
@Composable
private fun LayerList(controls: LayerControls, modifier: Modifier = Modifier) {
    // 화면은 위 레이어부터 보인다. 문서 순서(아래부터)와 반대다.
    val rows = controls.layers.asReversed()
    val rowHeight = with(LocalDensity.current) { RowHeight.toPx() }
    var dragging by remember { mutableStateOf<LayerId?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val from = rows.indexOfFirst { it.id == dragging }
    val target = if (from < 0) -1 else (from + (dragOffset / rowHeight).roundToInt()).coerceIn(0, rows.lastIndex)
    Column(modifier.verticalScroll(rememberScrollState()).testTag(EditScreenTags.LAYER_LIST)) {
        rows.forEachIndexed { index, layer ->
            key(layer.id.value) {
                val shift = when {
                    layer.id == dragging -> dragOffset
                    from < 0 -> 0f
                    index in (from + 1)..target -> -rowHeight
                    index in target until from -> rowHeight
                    else -> 0f
                }
                LayerRow(
                    layer = layer,
                    selected = layer.id == controls.active,
                    controls = controls,
                    displayIndex = index,
                    modifier = Modifier
                        .zIndex(if (layer.id == dragging) 1f else 0f)
                        .graphicsLayer { translationY = shift },
                    onDragStart = {
                        dragging = layer.id
                        dragOffset = 0f
                    },
                    onDrag = { delta -> dragOffset += delta },
                    onDragEnd = {
                        val start = rows.indexOfFirst { it.id == layer.id }
                        val end = (start + (dragOffset / rowHeight).roundToInt()).coerceIn(0, rows.lastIndex)
                        dragging = null
                        dragOffset = 0f
                        // 화면 자리(위부터)를 문서 자리(아래부터)로
                        if (start >= 0 && end != start) controls.actions.move(layer.id, rows.lastIndex - end)
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
 * 레이어 줄 하나: 눈(보이기·숨기기), 작은 그림, 이름, 혼합 모드·불투명도, 순서 손잡이. 고른 줄은 바탕을 칠하고, 숨긴 줄은 작은 그림·이름을 흐리게 보인다.
 * 눈을 누르면 그 레이어를 보이거나 숨긴다(고르지는 않는다, 포토샵과 같다).
 * 작은 그림·이름 쪽을 길게 누르면 그 레이어를 고르고 메뉴([LayerMenu])를 띄운다. 손잡이는 끌기라 길게 누르기에서 뺀다.
 * 화면 읽기 기능에서는 '레이어 메뉴' 동작(길게 누르기)으로 연다.
 */
@Composable
private fun LayerRow(
    layer: LayerSummary,
    selected: Boolean,
    controls: LayerControls,
    displayIndex: Int,
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val actions = controls.actions
    val documentIndex = controls.layers.lastIndex - displayIndex
    val percent = (layer.opacity * 100).roundToInt()
    val mode = stringResource(blendModeLabel(layer.blendMode))
    val description = stringResource(R.string.layer_row_description, layer.name, mode, percent)
    val selectedLabel = stringResource(R.string.layer_selected)
    val hiddenLabel = stringResource(R.string.layer_hidden)
    val rowState = listOfNotNull(selectedLabel.takeIf { selected }, hiddenLabel.takeIf { !layer.visible }).joinToString(", ")
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
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .selectable(selected = selected, enabled = controls.enabled, onClick = { actions.select(layer.id) })
                .semantics {
                    contentDescription = description
                    if (rowState.isNotEmpty()) stateDescription = rowState
                    if (canMove) {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(upLabel) {
                                actions.move(layer.id, documentIndex + 1)
                                true
                            }.takeIf { documentIndex < controls.layers.lastIndex },
                            CustomAccessibilityAction(downLabel) {
                                actions.move(layer.id, documentIndex - 1)
                                true
                            }.takeIf { documentIndex > 0 }
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
            IconToggleButton(
                checked = layer.visible,
                onCheckedChange = { actions.setVisible(layer.id, it) },
                enabled = controls.canEdit,
                modifier = Modifier.testTag(EditScreenTags.layerVisibility(layer.id))
            ) {
                Icon(
                    painterResource(if (layer.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    contentDescription = stringResource(R.string.layer_visibility),
                    // 켜진 눈도 강조색이 아니라 글자색으로 둔다(줄마다 있어 눈에 띄지 않게)
                    tint = when {
                        !layer.visible -> colors.onSurfaceVariant
                        selected -> colors.onSecondaryContainer
                        else -> colors.onSurface
                    }
                )
            }
            // 작은 그림·이름: 길게 누르면 메뉴. 줄의 누르기(고르기)보다 먼저 알아채고, 길게 누른 뒤에는 누르기가 일어나지 않는다.
            // 숨긴 레이어는 흐리게 보인다
            Row(
                Modifier.weight(1f).fillMaxHeight().alpha(if (layer.visible) 1f else HiddenAlpha).longPressFirst(openMenu),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LayerThumbnail(layer.thumbnail, controls.colorSpace, Modifier.size(ThumbnailSize))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = layer.name,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.layer_row_detail, mode, percent),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall
                    )
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
                    tint = if (canMove) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f)
                )
            }
        }
        LayerMenu(expanded = menuOpen, controls = controls, documentIndex = documentIndex, onDismiss = { menuOpen = false })
    }
}

/**
 * 레이어 줄을 길게 누르면 뜨는 메뉴: 복제, 아래 레이어와 병합, 보이는 레이어 병합, 삭제.
 * 모두 고른 레이어(길게 누른 레이어)에 하고 실행 취소 한 단계다. 할 수 없는 항목은 꺼 둔다
 * (맨 아래 레이어나 둘 중 하나를 숨겼으면 아래와 병합, 보이는 레이어가 하나뿐이면 보이는 레이어 병합, 한 장뿐이면 삭제).
 */
@Composable
private fun LayerMenu(expanded: Boolean, controls: LayerControls, documentIndex: Int, onDismiss: () -> Unit) {
    val actions = controls.actions
    val canEdit = controls.canEdit
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        LayerMenuItem(R.string.layer_duplicate, canEdit, EditScreenTags.LAYER_MENU_DUPLICATE, onDismiss, actions::duplicate)
        val layers = controls.layers
        // 숨긴 레이어는 아래와 합치지 않는다(숨긴 내용을 모르고 합치거나 잃지 않게)
        val canMergeDown = documentIndex > 0 && layers[documentIndex].visible && layers[documentIndex - 1].visible
        LayerMenuItem(R.string.layer_merge_down, canEdit && canMergeDown, EditScreenTags.LAYER_MENU_MERGE_DOWN, onDismiss, actions::mergeDown)
        LayerMenuItem(
            R.string.layer_merge_visible,
            canEdit && layers.count { it.visible && it.opacity > 0f } >= 2,
            EditScreenTags.LAYER_MENU_MERGE_VISIBLE,
            onDismiss,
            actions::mergeVisible
        )
        LayerMenuItem(R.string.layer_delete, canEdit && controls.layers.size > 1, EditScreenTags.LAYER_MENU_DELETE, onDismiss, actions::delete)
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
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        drawRect(colors.surfaceContainerHighest)
        val scale = if (image == null) 1f else minOf(size.width / image.width, size.height / image.height)
        val width = if (image == null) size.width.roundToInt() else (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = if (image == null) size.height.roundToInt() else (image.height * scale).roundToInt().coerceAtLeast(1)
        val left = ((size.width - width) / 2).roundToInt()
        val top = ((size.height - height) / 2).roundToInt()
        // 그림 자리의 체크 무늬
        clipRect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()) {
            val square = ThumbnailChecker.toPx()
            drawRect(CheckerLight)
            for (row in 0..(height / square).toInt()) {
                for (column in 0..(width / square).toInt()) {
                    if ((row + column) % 2 == 1) drawRect(CheckerDark, Offset(left + column * square, top + row * square), Size(square, square))
                }
            }
        }
        if (image != null) {
            drawImage(image = image, dstOffset = IntOffset(left, top), dstSize = IntSize(width, height), filterQuality = FilterQuality.Medium)
        }
        drawRect(colors.outlineVariant, Offset(left.toFloat(), top.toFloat()), Size(width.toFloat(), height.toFloat()), style = Stroke(width = 1.dp.toPx()))
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
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        ThinSlider(
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
    val enabled = controls.canEdit && controls.activeLayer != null
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.layer_blend_mode),
            modifier = Modifier.width(88.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(EditScreenTags.LAYER_BLEND)) {
                Text(stringResource(blendModeLabel(current)), maxLines = 1)
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

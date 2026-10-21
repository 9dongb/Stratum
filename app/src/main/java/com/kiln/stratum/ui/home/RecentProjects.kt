package com.kiln.stratum.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.project.ProjectNames
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.MenuLongPress
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.StratumDropdownMenu
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.StratumDialog
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 홈 화면의 화면 테스트용 이름표 */
object HomeTags {
    const val PROJECT_GRID = "projectGrid"
    const val DELETE_CONFIRM = "deleteProjectConfirm"
    const val TOP_BAR = "homeTopBar"

    /** 위 줄의 제목(앱 이름, 선택 중이면 고른 수) */
    const val TITLE = "homeTitle"
    const val SELECT = "homeSelect"
    const val DONE = "homeSelectDone"
    const val DELETE_SELECTED = "homeDeleteSelected"
    const val OPEN_PHOTO = "homeOpenPhoto"
    const val NEW_CANVAS = "homeNewCanvas"
    const val SETTINGS = "homeSettings"
    const val RENAME_FIELD = "homeRenameField"
    const val RENAME_OK = "homeRenameOk"

    /** 프로젝트가 없을 때 가운데의 두 버튼 */
    const val EMPTY_OPEN_PHOTO = "homeEmptyOpenPhoto"
    const val EMPTY_NEW_CANVAS = "homeEmptyNewCanvas"

    /** [id] 프로젝트 카드(작은 그림 칸) */
    fun project(id: String): String = "project_$id"

    /** [id] 프로젝트의 이름·크기 칸(누르면 이름 바꾸기) */
    fun projectName(id: String): String = "projectName_$id"
}

/** 격자 바깥 여백, 칸 사이, 줄 사이 */
private val GridPadding = 16.dp
private val GridSpacing = 16.dp
private val GridRowSpacing = 20.dp

/** 투명한 그림 바탕 체크 무늬 한 칸 */
private val ThumbnailChecker = 6.dp

/**
 * 프로젝트 격자(홈 갤러리). 칸마다 작은 그림(그림 비율대로, 칸 아래쪽에 맞춤), 이름, 크기를 보인다.
 * 그림을 누르면 열고([selecting]이면 고르고 푼다), 이름·크기 칸을 누르면 이름 바꾸기, 길게 누르면 메뉴(이름 바꾸기·지우기)를 띄운다.
 * 칸은 모두 같은 크기다(접히는 선은 보지 않는다, 사용자 피드백 11차 1).
 */
@Composable
fun RecentProjectsGrid(
    projects: List<ProjectInfo>,
    columns: Int,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: List<String> = emptyList(),
    onToggle: (String) -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxWidth()
            .testTag(HomeTags.PROJECT_GRID),
        contentPadding = PaddingValues(GridPadding),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridRowSpacing)
    ) {
        items(projects, key = { it.id }) { info ->
            ProjectCard(
                info = info,
                selecting = selecting,
                isSelected = info.id in selected,
                onOpen = { onOpen(info.id) },
                onToggle = { onToggle(info.id) },
                onRename = { onRename(info.id) },
                onDelete = { onDelete(info.id) },
                modifier = Modifier
            )
        }
    }
}

/**
 * 프로젝트 카드 하나. 화면 읽기 기능은 이름·날짜·크기를 한 번에 읽고, '이름 바꾸기'·'지우기' 동작도 쓸 수 있다.
 * 고르는 중에는 그림과 이름 칸 어디를 눌러도 고르고 풀며, 고른 카드는 파란 테두리와 체크 동그라미로 보인다.
 */
@Composable
private fun ProjectCard(
    info: ProjectInfo,
    selecting: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val name = info.name.ifEmpty { stringResource(R.string.project_untitled) }
    val date = remember(info.modifiedMillis) {
        DateUtils.formatDateTime(
            context,
            info.modifiedMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
    }
    val description = stringResource(R.string.home_project_description, name, date, info.width, info.height)
    val renameLabel = stringResource(R.string.home_rename)
    val deleteLabel = stringResource(R.string.action_delete)
    val menuLabel = stringResource(R.string.home_project_menu)
    var menuOpen by remember { mutableStateOf(false) }
    val colors = StratumTheme.colors
    Box(modifier) {
        Column(Modifier.fillMaxWidth()) {
            // 메뉴를 여는 꾹 누르기는 메뉴 묶음 시간(0.3초)이다(결정 기록 '꾹 누르기 시간')
            MenuLongPress {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(StratumShapes.Card)
                        .combinedClickable(
                            onClick = if (selecting) onToggle else onOpen,
                            onLongClick = if (selecting) onToggle else ({ menuOpen = true }),
                            onLongClickLabel = if (selecting) null else menuLabel
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            if (selecting) {
                                selected = isSelected
                            } else {
                                customActions = listOf(
                                    CustomAccessibilityAction(renameLabel) { onRename(); true },
                                    CustomAccessibilityAction(deleteLabel) { onDelete(); true }
                                )
                            }
                        }
                        .testTag(HomeTags.project(info.id)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ProjectThumbnail(info, isSelected)
                    if (selecting) SelectionMark(isSelected, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
            // 이름과 크기: 누르면 이름 바꾸기(프로크리에이트와 같다). 누르는 영역은 48dp 이상이다.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(StratumShapes.Control)
                    .clickable(onClickLabel = if (selecting) null else renameLabel, onClick = if (selecting) onToggle else onRename)
                    .testTag(HomeTags.projectName(info.id))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = name,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.home_project_size, info.width, info.height),
                    color = colors.textDescription,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        StratumDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(renameLabel) },
                onClick = {
                    menuOpen = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text(deleteLabel) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

/**
 * 프로젝트의 작은 그림. 정사각형 칸 안에 그림 비율대로(저장한 크기로 미리 자리를 잡아 읽는 동안 칸이 튀지 않음) 아래쪽에 맞춰 놓고,
 * 투명한 곳은 체크 무늬로 보인다. 파일을 백그라운드에서 읽고, 저장할 때마다(고친 시각이 바뀌면) 다시 읽는다. 고른 카드는 파란 테두리다.
 */
@Composable
private fun ProjectThumbnail(info: ProjectInfo, isSelected: Boolean) {
    val image by produceState<ImageBitmap?>(null, info.thumbnail.path, info.modifiedMillis) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(info.thumbnail.path)?.asImageBitmap() }
    }
    val ratio = (info.width.toFloat() / info.height).coerceIn(0.05f, 20f)
    val selectedBorder = if (isSelected) BorderStroke(2.dp, StratumTheme.colors.accent) else null
    Box(
        Modifier
            .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
            .clip(StratumShapes.Card)
            .checkerBackground()
            .then(if (selectedBorder != null) Modifier.border(selectedBorder, StratumShapes.Card) else Modifier)
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
    }
}

/** 고르는 중인 카드의 동그라미: 고르면 파란 동그라미에 체크, 아니면 흰 테두리 동그라미 */
@Composable
private fun SelectionMark(isSelected: Boolean, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Box(
        modifier = modifier
            .size(24.dp)
            .background(if (isSelected) colors.accent else colors.scrim, StratumShapes.Dot)
            .border(BorderStroke(2.dp, if (isSelected) colors.accent else colors.textPrimary), StratumShapes.Dot),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
    }
}

/** 체크 무늬 바탕(투명한 그림 아래). 칸 크기는 [ThumbnailChecker]이고 캔버스와 같은 색이다. */
private fun Modifier.checkerBackground(): Modifier =
    drawWithCache {
        val square = ThumbnailChecker.toPx()
        val tile = Bitmap.createBitmap(
            intArrayOf(CanvasOverlayColors.CheckerLight.toArgb(), CanvasOverlayColors.CheckerDark.toArgb(), CanvasOverlayColors.CheckerDark.toArgb(), CanvasOverlayColors.CheckerLight.toArgb()),
            2,
            2,
            Bitmap.Config.ARGB_8888
        )
        val brush = ShaderBrush(
            BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                filterMode = BitmapShader.FILTER_MODE_NEAREST
                setLocalMatrix(Matrix().apply { setScale(square, square) })
            }
        )
        onDrawBehind { drawRect(brush) }
    }

/**
 * 지울지 묻는 창. 되돌릴 수 없어서 한 번 더 묻는다. [count]가 둘 이상이면('선택') 몇 개인지 제목에 보인다.
 * 화면 전체를 덮는 자리에 놓는다.
 */
@Composable
fun DeleteProjectDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, count: Int = 1) {
    StratumDialog(
        onDismissRequest = onDismiss,
        title = if (count > 1) pluralStringResource(R.plurals.home_delete_projects_title, count, count) else stringResource(R.string.home_delete_title),
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                // 되돌릴 수 없는 지우기는 주요 버튼이 아니라 오류 색 글자 버튼이다
                QuietButton(onClick = onConfirm, color = StratumTheme.colors.error, modifier = Modifier.testTag(HomeTags.DELETE_CONFIRM)) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    ) {
        Text(stringResource(R.string.home_delete_message))
    }
}

/**
 * 프로젝트 이름을 바꾸는 창. 커버 화면(좁은 화면)은 아래에서 올라오는 시트, 그 밖에는 화면 가운데의 창이다(글자 창과 같다).
 * 비우면 '제목 없음'이 된다. 적은 글자는 접기·펼치기에도 남는다([projectId]마다 따로).
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameProjectPrompt(projectId: String, initial: String, widthClass: WidthClass, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable(projectId) { mutableStateOf(initial) }
    val title = stringResource(R.string.home_rename_title)
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(ProjectNames.MAX_LENGTH) },
            label = { Text(stringResource(R.string.home_rename_label)) },
            placeholder = { Text(stringResource(R.string.project_untitled)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onRename(text) }),
            modifier = Modifier.fillMaxWidth().testTag(HomeTags.RENAME_FIELD)
        )
    }
    val buttons: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            AccentButton(onClick = { onRename(text) }, modifier = Modifier.testTag(HomeTags.RENAME_OK)) { Text(stringResource(R.string.action_ok)) }
        }
    }
    if (widthClass == WidthClass.Compact) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                field()
                Spacer(Modifier.height(16.dp))
                buttons(Modifier.align(Alignment.End))
            }
        }
    } else {
        StratumDialog(onDismissRequest = onDismiss, title = title, buttons = { buttons(Modifier) }) {
            field()
        }
    }
}

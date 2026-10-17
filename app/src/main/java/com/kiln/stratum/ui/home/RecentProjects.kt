package com.kiln.stratum.ui.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.Span
import com.kiln.stratum.ui.layout.insetsAvoiding
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 홈 화면의 화면 테스트용 이름표 */
object HomeTags {
    const val PROJECT_GRID = "projectGrid"
    const val DELETE_CONFIRM = "deleteProjectConfirm"

    /** [id] 프로젝트 카드 */
    fun project(id: String): String = "project_$id"
}

/** 격자 바깥 여백, 칸 사이, 줄 사이 */
private val GridPadding = 16.dp
private val GridSpacing = 12.dp
private val GridRowSpacing = 16.dp

/**
 * 최근 프로젝트 격자. 칸마다 작은 그림, 고친 날짜·시각, 크기를 보인다. 누르면 열고, 길게 누르면 메뉴(지우기)를 띄운다.
 *
 * 세로 힌지(메인 가로)가 지나면 그 띠에 걸리는 칸을 띠 바깥으로 줄여 놓는다(칸 자리는 스크롤해도 그대로다).
 * 가로 힌지(메인 세로)는 줄이 스크롤되며 지나가므로 피하지 않는다(결정 기록 '홈 최근 프로젝트').
 */
@Composable
fun RecentProjectsGrid(
    projects: List<ProjectInfo>,
    columns: Int,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val bands = LocalHinges.current.filter { it.isVertical }.map { it.band(with(density) { HingeMargin.roundToPx() }) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxWidth()
            .testTag(HomeTags.PROJECT_GRID),
        contentPadding = PaddingValues(start = GridPadding, end = GridPadding, bottom = GridPadding),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridRowSpacing)
    ) {
        items(projects, key = { it.id }) { info ->
            // 칸의 실제 창 x 구간을 재서, 세로 힌지 띠와 겹치면 카드를 그쪽에서 줄인다(칸 안쪽 여백이라 칸 자리는 그대로다)
            var cell by remember { mutableStateOf<Span?>(null) }
            val (start, end) = cell?.takeIf { bands.isNotEmpty() }?.let { insetsAvoiding(it, bands) } ?: (0 to 0)
            Box(
                Modifier.onGloballyPositioned {
                    val left = it.positionInWindow().x.roundToInt()
                    cell = Span(left, left + it.size.width)
                }
            ) {
                ProjectCard(
                    info = info,
                    onOpen = { onOpen(info.id) },
                    onDelete = { onDelete(info.id) },
                    modifier = Modifier.padding(start = with(density) { start.toDp() }, end = with(density) { end.toDp() })
                )
            }
        }
    }
}

/** 프로젝트 카드 하나. 화면 읽기 기능은 날짜·크기를 한 번에 읽고, '지우기' 동작도 쓸 수 있다. */
@Composable
private fun ProjectCard(info: ProjectInfo, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val date = remember(info.modifiedMillis) {
        DateUtils.formatDateTime(
            context,
            info.modifiedMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
    }
    val description = stringResource(R.string.home_project_description, date, info.width, info.height)
    val deleteLabel = stringResource(R.string.action_delete)
    val menuLabel = stringResource(R.string.home_project_menu)
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }, onLongClickLabel = menuLabel)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
                .testTag(HomeTags.project(info.id))
        ) {
            ProjectThumbnail(info, Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium))
            Text(
                text = date,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.home_project_size, info.width, info.height),
                modifier = Modifier.padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

/** 프로젝트의 작은 그림. 파일을 백그라운드에서 읽고, 저장할 때마다(고친 시각이 바뀌면) 다시 읽는다. 없으면 빈 칸이다. */
@Composable
private fun ProjectThumbnail(info: ProjectInfo, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, info.thumbnail.path, info.modifiedMillis) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(info.thumbnail.path)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

/** 프로젝트를 지울지 묻는 창. 되돌릴 수 없어서 한 번 더 묻는다. 화면 전체를 덮는 자리에 놓는다. */
@Composable
fun DeleteProjectDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    HingeAwareDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_delete_title),
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onConfirm, modifier = Modifier.testTag(HomeTags.DELETE_CONFIRM)) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    ) {
        Text(stringResource(R.string.home_delete_message))
    }
}

package com.kiln.stratum.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.SecondaryButton
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.components.stratumTopAppBarColors
import com.kiln.stratum.ui.layout.HoleAvoidingBarRow
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.layoutClassOf
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.layout.windowInsets
import com.kiln.stratum.ui.theme.AppTitleTextStyle
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 홈 화면(갤러리). 앱을 새로 실행하면 항상 여기서 시작한다. 프로크리에이트 갤러리를 본떴다(사용자 요청, 결정 기록 '홈 갤러리').
 *
 * - 위 줄: 앱 이름과 오른쪽의 [선택] [사진 열기] [+](새 캔버스). 설정은 편집 화면 상단 바에서 연다(사용자 피드백 11차 4).
 * - 그 아래 프로젝트 격자(커버 2열, 메인 4열, 기획서): 그림 비율대로의 작은 그림과 이름·크기. 누르면 열고, 이름을 누르면 이름 바꾸기,
 *   길게 누르면 메뉴(이름 바꾸기·지우기)
 * - 선택: [선택]을 누르면 카드를 눌러 여러 개 고르고 [지우기]로 한꺼번에 지운다(되돌릴 수 없어 한 번 더 묻는다). 뒤로 가기는 선택을 끝낸다.
 * - 프로젝트가 없으면 가운데에 안내와 사진 열기·새 캔버스 버튼을 보인다.
 *
 * @param onCreateCanvas 새 캔버스 창에서 '만들기'를 눌렀다. 고른 가로·세로(픽셀), 배경, 다듬은 이름(빈 글자는 '제목 없음')을 받는다.
 * @param projects 최근 프로젝트. 고친 시각이 늦은 것부터다.
 * @param onOpenProject 프로젝트 카드를 눌렀다.
 * @param onDeleteProjects 지울지 묻는 창에서 '지우기'를 눌렀다(하나 또는 '선택'으로 고른 여럿).
 * @param onRenameProject 이름 바꾸기 창에서 '확인'을 눌렀다(적은 이름 그대로, 다듬기는 받는 쪽이 한다).
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPhoto: () -> Unit,
    onCreateCanvas: (width: Int, height: Int, fill: CanvasFill, name: String) -> Unit,
    modifier: Modifier = Modifier,
    projects: List<ProjectInfo> = emptyList(),
    onOpenProject: (String) -> Unit = {},
    onDeleteProjects: (List<String>) -> Unit = {},
    onRenameProject: (id: String, name: String) -> Unit = { _, _ -> }
) {
    // 새 캔버스 창을 열었는지와 입력값. 접기·펼치기로 시트↔창이 바뀌어도 이어지도록 창 밖에서 기억한다.
    var showNewCanvas by rememberSaveable { mutableStateOf(false) }
    val newCanvasInput = rememberNewCanvasInput()
    // '선택' 중인지와 고른 프로젝트. 접기·펼치기에도 남는다.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 지울지 묻는 프로젝트들과 이름을 바꾸는 프로젝트. 접기·펼치기에도 창이 남는다.
    var confirmDelete by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    // 목록에서 사라진(지워진) 프로젝트는 고른 것에서 빼고, 프로젝트가 없으면 선택을 끝낸다
    LaunchedEffect(projects) {
        val ids = projects.map { it.id }.toSet()
        selected = selected.filter { it in ids }
        if (projects.isEmpty()) selecting = false
        if (renaming != null && renaming !in ids) renaming = null
    }
    val endSelecting = {
        selecting = false
        selected = emptyList()
    }

    // 창 전체 폭으로 시트·창을 고르므로 Scaffold 바깥에서 잰다
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 격자 칸 수는 넓은 화면을 쓰게 폭으로만 고르고(가로로 돌린 폰은 4열), 창(시트 ↔ 가운데 창)은 편집 화면과 같이 짧은 쪽으로 고른다
        // (커버·폰을 가로로 돌려도 아래 시트, 사용자 피드백 12차 3)
        val widthClass = widthClassOf(maxWidth.value)
        val promptClass = layoutClassOf(maxWidth.value, maxHeight.value)
        // 뒤로 가기는 선택을 먼저 끝낸다. 창보다 먼저 등록돼, 창이 열려 있으면 창이 먼저 닫힌다.
        BackHandler(enabled = selecting, onBack = endSelecting)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                HomeTopBar(
                    selecting = selecting,
                    selectedCount = selected.size,
                    canSelect = projects.isNotEmpty(),
                    onSelect = { selecting = true },
                    onDone = endSelecting,
                    onDeleteSelected = { confirmDelete = selected },
                    onOpenPhoto = onOpenPhoto,
                    onNewCanvas = { showNewCanvas = true }
                )
            }
        ) { innerPadding ->
            if (projects.isEmpty()) {
                EmptyGallery(onOpenPhoto = onOpenPhoto, onNewCanvas = { showNewCanvas = true }, modifier = Modifier.padding(innerPadding))
            } else {
                RecentProjectsGrid(
                    projects = projects,
                    columns = if (widthClass == WidthClass.Compact) 2 else 4,
                    selecting = selecting,
                    selected = selected,
                    onOpen = onOpenProject,
                    onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                    onRename = { renaming = it },
                    onDelete = { confirmDelete = listOf(it) },
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                )
            }
        }

        // 화면 전체를 덮어야 해서 Scaffold 밖, 같은 Box의 마지막에 둔다
        confirmDelete?.let { ids ->
            DeleteProjectDialog(
                count = ids.size,
                onConfirm = {
                    confirmDelete = null
                    endSelecting()
                    onDeleteProjects(ids)
                },
                onDismiss = { confirmDelete = null }
            )
        }
        renaming?.let { id ->
            val info = projects.firstOrNull { it.id == id }
            if (info != null) {
                RenameProjectPrompt(
                    projectId = id,
                    initial = info.name,
                    widthClass = promptClass,
                    onDismiss = { renaming = null },
                    onRename = { name ->
                        renaming = null
                        onRenameProject(id, name)
                    }
                )
            }
        }
        if (showNewCanvas) {
            NewCanvasPrompt(
                input = newCanvasInput,
                widthClass = promptClass,
                onDismiss = { showNewCanvas = false },
                onCreate = { width, height, fill, name ->
                    showNewCanvas = false
                    onCreateCanvas(width, height, fill, name)
                }
            )
        }
    }
}

/**
 * 홈 위 줄: 왼쪽에 앱 이름(굵고 크게, 사용자 피드백 10차. 선택 중이면 고른 수), 오른쪽에 [선택] [사진 열기] [+](선택 중이면 [지우기] [완료]).
 * 상태 표시줄을 숨겨 바가 맨 위에 붙는다. 위쪽 카메라 구멍에 걸리는 버튼은 구멍 왼쪽으로 비켜 놓고, 그래도 걸릴 때만 바를 내린다
 * (결정 기록 '전체 화면', [HoleAvoidingBarRow]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    selecting: Boolean,
    selectedCount: Int,
    canSelect: Boolean,
    onSelect: () -> Unit,
    onDone: () -> Unit,
    onDeleteSelected: () -> Unit,
    onOpenPhoto: () -> Unit,
    onNewCanvas: () -> Unit
) {
    val barInsets = rememberTopBarInsets(TopAppBarDefaults.TopAppBarExpandedHeight)
    val title = when {
        !selecting -> stringResource(R.string.app_name)
        selectedCount > 0 -> pluralStringResource(R.plurals.home_selected_count, selectedCount, selectedCount)
        else -> stringResource(R.string.home_select_title)
    }
    // 버튼마다 따로 자리를 알려, 구멍이 버튼 사이에 있으면 바를 내리지 않는다
    val actions: List<Pair<String, @Composable () -> Unit>> = if (selecting) {
        listOf(
            // 되돌릴 수 없는 지우기는 오류 색 글자 버튼이다(한 번 더 묻는다)
            "delete" to {
                QuietButton(
                    onClick = onDeleteSelected,
                    enabled = selectedCount > 0,
                    color = StratumTheme.colors.error,
                    modifier = Modifier.testTag(HomeTags.DELETE_SELECTED)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            "done" to {
                QuietButton(onClick = onDone, modifier = Modifier.padding(end = 4.dp).testTag(HomeTags.DONE)) {
                    Text(stringResource(R.string.home_done))
                }
            }
        )
    } else {
        listOf(
            "select" to {
                QuietButton(onClick = onSelect, enabled = canSelect, modifier = Modifier.testTag(HomeTags.SELECT)) {
                    Text(stringResource(R.string.home_select))
                }
            },
            "openPhoto" to {
                QuietButton(onClick = onOpenPhoto, modifier = Modifier.testTag(HomeTags.OPEN_PHOTO)) {
                    Text(stringResource(R.string.home_open_photo))
                }
            },
            "newCanvas" to {
                StratumIconButton(onClick = onNewCanvas, modifier = Modifier.testTag(HomeTags.NEW_CANVAS)) {
                    Icon(painter = painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.home_new_canvas))
                }
            }
        )
    }
    TopAppBar(
        title = {
            HoleAvoidingBarRow(
                insets = barInsets,
                title = {
                    Text(
                        title,
                        modifier = Modifier.testTag(HomeTags.TITLE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = if (selecting) MaterialTheme.typography.titleLarge else AppTitleTextStyle
                    )
                },
                actions = actions
            )
        },
        modifier = Modifier.edgeDivider(DividerEdge.Bottom, StratumTheme.colors.divider).testTag(HomeTags.TOP_BAR),
        windowInsets = barInsets.windowInsets(),
        colors = stratumTopAppBarColors()
    )
}

/**
 * 프로젝트가 없을 때: 가운데에 안내와 사진 열기(주요)·새 캔버스(보조) 버튼(같은 폭, 사이 12dp).
 * 접히는 선은 보지 않는다(사용자 피드백 11차 1, 전에는 선이 버튼 줄을 지나면 두 버튼을 선 양쪽에 하나씩 놓았다).
 */
@Composable
private fun EmptyGallery(onOpenPhoto: () -> Unit, onNewCanvas: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.home_recent_projects_empty),
            modifier = Modifier.widthIn(max = 480.dp),
            color = StratumTheme.colors.textDescription,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        // 버튼 안 아이콘은 글자가 버튼 이름을 말해 주므로 따로 이름(contentDescription)을 붙이지 않는다
        Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton(onClick = onOpenPhoto, modifier = Modifier.weight(1f).testTag(HomeTags.EMPTY_OPEN_PHOTO)) {
                Icon(painter = painterResource(R.drawable.ic_photo_library), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.home_open_photo))
            }
            SecondaryButton(onClick = onNewCanvas, modifier = Modifier.weight(1f).testTag(HomeTags.EMPTY_NEW_CANVAS)) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.home_new_canvas))
            }
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    StratumTheme {
        HomeScreen(onOpenPhoto = {}, onCreateCanvas = { _, _, _, _ -> })
    }
}

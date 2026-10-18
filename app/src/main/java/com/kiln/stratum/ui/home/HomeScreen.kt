package com.kiln.stratum.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.ui.layout.HingeSplitRow
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.topBarItem
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.layout.windowInsets
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 홈 화면. 앱을 새로 실행하면 항상 여기서 시작한다.
 *
 * 사진 열기·새 캔버스로 편집을 시작하고, 아래에 최근 프로젝트(자동 저장한 것)를 격자로 보여 준다.
 * 버튼과 제목은 위에 고정하고 격자만 스크롤한다. 격자는 커버 화면 2열, 메인 화면 4열이다(기획서 화면 목록).
 *
 * @param onCreateCanvas 새 캔버스 창에서 '만들기'를 눌렀다. 고른 가로·세로(픽셀)와 배경을 받는다.
 * @param projects 최근 프로젝트. 고친 시각이 늦은 것부터다.
 * @param onOpenProject 프로젝트 카드를 눌렀다.
 * @param onDeleteProject 지울지 묻는 창에서 '지우기'를 눌렀다.
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPhoto: () -> Unit,
    onCreateCanvas: (width: Int, height: Int, fill: CanvasFill) -> Unit,
    modifier: Modifier = Modifier,
    projects: List<ProjectInfo> = emptyList(),
    onOpenProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    // 새 캔버스 창을 열었는지와 입력값. 접기·펼치기로 시트↔창이 바뀌어도 이어지도록 창 밖에서 기억한다.
    var showNewCanvas by rememberSaveable { mutableStateOf(false) }
    val newCanvasInput = rememberNewCanvasInput()
    // 지울지 묻는 프로젝트. 접기·펼치기에도 창이 남는다.
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }

    // 창 전체 폭으로 시트·창을 고르므로 Scaffold 바깥에서 잰다
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = widthClassOf(maxWidth.value)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                // 상태 표시줄을 숨겨 바가 맨 위에 붙는다. 카메라 구멍이 제목·버튼에 걸릴 때만 내린다(결정 기록 '전체 화면').
                val barInsets = rememberTopBarInsets(TopAppBarDefaults.TopAppBarExpandedHeight)
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), Modifier.topBarItem(barInsets, "title")) },
                    actions = {
                        IconButton(onClick = onOpenSettings, modifier = Modifier.topBarItem(barInsets, "settings")) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.action_settings)
                            )
                        }
                    },
                    windowInsets = barInsets.windowInsets()
                )
            }
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                // 버튼과 제목은 위에 고정한다. 넓은 화면에서 버튼이 너무 길게 늘어나지 않게 폭을 제한한다.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                        // 버튼 안 아이콘은 글자가 버튼 이름을 말해 주므로 따로 이름(contentDescription)을 붙이지 않는다.
                        // 펼친 화면 가운데의 접히는 부분(힌지)이 버튼 줄을 지나면, 두 버튼을 힌지 양쪽에 하나씩 놓는다.
                        HingeSplitRow(
                            first = {
                                Button(
                                    onClick = onOpenPhoto,
                                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_photo_library),
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize)
                                    )
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.home_open_photo))
                                }
                            },
                            second = {
                                FilledTonalButton(
                                    onClick = { showNewCanvas = true },
                                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize)
                                    )
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.home_new_canvas))
                                }
                            },
                            gap = 12.dp
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.home_recent_projects),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                if (projects.isEmpty()) {
                    // 아직 프로젝트가 없으면 안내만 보인다
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Text(
                                text = stringResource(R.string.home_recent_projects_empty),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 48.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    RecentProjectsGrid(
                        projects = projects,
                        columns = if (widthClass == WidthClass.Compact) 2 else 4,
                        onOpen = onOpenProject,
                        onDelete = { confirmDelete = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 화면 전체를 덮어야 해서 Scaffold 밖, 같은 Box의 마지막에 둔다
        confirmDelete?.let { id ->
            DeleteProjectDialog(
                onConfirm = {
                    confirmDelete = null
                    onDeleteProject(id)
                },
                onDismiss = { confirmDelete = null }
            )
        }
        if (showNewCanvas) {
            NewCanvasPrompt(
                input = newCanvasInput,
                widthClass = widthClass,
                onDismiss = { showNewCanvas = false },
                onCreate = { width, height, fill ->
                    showNewCanvas = false
                    onCreateCanvas(width, height, fill)
                }
            )
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    StratumTheme {
        HomeScreen(onOpenPhoto = {}, onCreateCanvas = { _, _, _ -> })
    }
}

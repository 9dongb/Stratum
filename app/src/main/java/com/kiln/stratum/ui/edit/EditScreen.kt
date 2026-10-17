package com.kiln.stratum.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 편집 화면. 상단 바 아래를 화면 폭에 따라 세 가지로 배치한다(CLAUDE.md 6장).
 *
 * - Compact(커버·폰 세로): 위 캔버스, 아래 도구 탭 줄
 * - Medium(메인 세로): 왼쪽 도구 막대, 캔버스, 아래 접이식 패널
 * - Expanded(메인 가로): 왼쪽 도구 막대, 캔버스, 오른쪽 고정 패널
 *
 * 도구와 패널 탭은 아직 기능이 없어 비활성이다.
 *
 * @param onCanvasSize 캔버스 영역의 크기(픽셀)가 정해지거나 바뀌면 알린다. 엔진이 알맞은 축소본을 그리는 데 쓴다.
 * @param onChooseSize 큰 사진 선택 창에서 고른 크기. true면 원본 크기, false면 줄여서 연다.
 */
@Composable
fun EditScreen(
    state: EditorState,
    onBack: () -> Unit,
    onCanvasSize: (width: Int, height: Int) -> Unit,
    onChooseSize: (original: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 아래 패널을 펼쳤는지. 접기·펼치기나 회전으로 배치가 바뀌어도 이어진다.
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    // 캔버스 보기(확대·이동). 화면 폭에 따라 배치가 바뀌어도 이어지도록 배치를 고르기 전에 둔다.
    val viewState = rememberCanvasViewState((state as? EditorState.Ready)?.documentSize)

    // 창 전체 폭으로 배치를 고르므로 Scaffold 바깥에서 잰다
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = widthClassOf(maxWidth.value)
        Scaffold(
            topBar = { EditTopBar(onBack) },
            // 시스템 바·카메라 구멍은 영역마다 바깥쪽만 비켜 두므로, Scaffold는 상단 바 높이만 비운다
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            val body = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
            when (widthClass) {
                WidthClass.Compact -> Column(body) {
                    EditCanvas(state, viewState, onCanvasSize, WindowInsetsSides.Horizontal, Modifier.weight(1f))
                    ToolTabBar()
                }

                WidthClass.Medium -> Row(body) {
                    ToolRail()
                    Column(Modifier.weight(1f)) {
                        EditCanvas(state, viewState, onCanvasSize, WindowInsetsSides.End, Modifier.weight(1f))
                        BottomPanel(expanded = panelExpanded, onToggle = { panelExpanded = !panelExpanded })
                    }
                }

                WidthClass.Expanded -> Row(body) {
                    ToolRail()
                    EditCanvas(state, viewState, onCanvasSize, WindowInsetsSides.Bottom, Modifier.weight(1f))
                    SidePanel()
                }
            }
        }

        // 화면 전체를 덮어야 해서 Scaffold 밖, 같은 Box의 마지막에 둔다
        if (state is EditorState.AskSize) {
            LargePhotoDialog(state = state, onChooseSize = onChooseSize, onCancel = onBack)
        }
    }
}

/** 편집 화면 상단 바: 뒤로, 실행 취소·다시 실행·내보내기(아직 비활성) */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        actions = {
            // 실행 취소·다시 실행·내보내기는 아직 기능이 없어서, 자리만 두고 누를 수 없게 한다
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    painter = painterResource(R.drawable.ic_undo),
                    contentDescription = stringResource(R.string.action_undo)
                )
            }
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    painter = painterResource(R.drawable.ic_redo),
                    contentDescription = stringResource(R.string.action_redo)
                )
            }
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    painter = painterResource(R.drawable.ic_ios_share),
                    contentDescription = stringResource(R.string.action_export)
                )
            }
        },
        // 캔버스와 구분되게 상단 바를 한 단계 밝게 칠한다
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

/**
 * 캔버스 영역. 엔진이 그린 축소본을 보기 상태([viewState])대로 보여 주고, 두 손가락으로 확대·이동한다.
 * 화면 코드는 픽셀을 계산하지 않는다(CLAUDE.md 3장).
 * 배경은 영역 끝까지 칠하고, 안의 내용만 [insetSides] 쪽 시스템 바·카메라 구멍을 피한다.
 */
@Composable
private fun EditCanvas(
    state: EditorState,
    viewState: CanvasViewState,
    onCanvasSize: (width: Int, height: Int) -> Unit,
    insetSides: WindowInsetsSides,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(insetSides))
            .onSizeChanged { onCanvasSize(it.width, it.height) }
            .testTag(EditScreenTags.CANVAS),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            EditorState.Empty, is EditorState.AskSize -> Unit
            EditorState.Opening -> ProgressIndicator(stringResource(R.string.edit_opening_photo))
            EditorState.Creating -> ProgressIndicator(stringResource(R.string.edit_creating_canvas))
            is EditorState.Ready -> CanvasViewport(state, viewState, Modifier.fillMaxSize())
            is EditorState.Failed -> Text(
                text = stringResource(
                    when (state.reason) {
                        EditorFailure.PHOTO_UNREADABLE -> R.string.edit_open_failed
                        EditorFailure.PHOTO_NOT_ENOUGH_MEMORY -> R.string.edit_open_failed_memory
                        EditorFailure.CANVAS_NOT_ENOUGH_MEMORY -> R.string.edit_create_failed_memory
                    }
                ),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/** 문서를 열거나 만드는 동안 보이는 표시와 [message]. */
@Composable
private fun ProgressIndicator(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 큰 사진을 원본 크기로 열지 줄여서 열지 고르는 창. 취소하면 홈으로 돌아간다.
 * 기기 메모리로 원본 크기가 무리면 '원본 크기'를 누를 수 없게 하고 까닭을 알려 준다.
 * 펼친 화면 가운데의 힌지를 피해 뜬다.
 */
@Composable
private fun LargePhotoDialog(
    state: EditorState.AskSize,
    onChooseSize: (original: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    HingeAwareDialog(
        onDismissRequest = onCancel,
        title = stringResource(R.string.large_photo_title),
        // 버튼 세 개가 한 줄에 다 들어가지 않으면 순서가 뒤섞여 줄바꿈되므로, 처음부터 세로로 쌓는다.
        // 위에서부터 메모리를 덜 쓰는 '줄여서', '원본 크기', '취소'
        buttons = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onChooseSize(false) }) {
                    Text(stringResource(R.string.large_photo_open_reduced, state.reducedMegapixels))
                }
                TextButton(onClick = { onChooseSize(true) }, enabled = state.originalAllowed) {
                    Text(stringResource(R.string.large_photo_open_original, state.originalMegapixels))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    ) {
        Text(
            stringResource(
                if (state.originalAllowed) R.string.large_photo_message else R.string.large_photo_message_no_memory,
                state.originalMegapixels
            )
        )
    }
}

// 화면 폭별 배치 미리보기(폴드8 커버·메인 세로·메인 가로와 비슷한 크기)
@Preview(name = "Compact", widthDp = 411, heightDp = 800)
@Preview(name = "Medium", widthDp = 700, heightDp = 900)
@Preview(name = "Expanded", widthDp = 930, heightDp = 700)
@Composable
private fun EditScreenPreview() {
    StratumTheme {
        EditScreen(state = EditorState.Empty, onBack = {}, onCanvasSize = { _, _ -> }, onChooseSize = {})
    }
}

@Preview
@Composable
private fun EditScreenOpeningPreview() {
    StratumTheme {
        EditScreen(state = EditorState.Opening, onBack = {}, onCanvasSize = { _, _ -> }, onChooseSize = {})
    }
}

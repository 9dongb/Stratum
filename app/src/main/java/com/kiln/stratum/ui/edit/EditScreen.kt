package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.importing.ImportFailure
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 편집 화면. 상단 바와 캔버스 영역이 있다.
 *
 * 캔버스에는 엔진이 그린 축소본을 화면에 맞춰 보여 준다. 화면 코드는 픽셀을 계산하지 않는다(CLAUDE.md 3장).
 * 도구 막대, 레이어·조정 패널은 다음 작업에서 붙인다.
 *
 * @param onCanvasSize 캔버스 영역의 크기(픽셀)가 정해지거나 바뀌면 알린다. 엔진이 알맞은 축소본을 그리는 데 쓴다.
 * @param onChooseSize 큰 사진 선택 창에서 고른 크기. true면 원본 크기, false면 줄여서 연다.
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    state: EditorState,
    onBack: () -> Unit,
    onCanvasSize: (width: Int, height: Int) -> Unit,
    onChooseSize: (original: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
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
    ) { innerPadding ->
        // 캔버스 영역. 배경은 내비게이션 바 뒤까지 칠하고, 안에 놓을 내용만 innerPadding으로 상단 바·시스템 바를 피한다.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(innerPadding)
                .onSizeChanged { onCanvasSize(it.width, it.height) },
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                EditorState.Empty, is EditorState.AskSize -> Unit
                EditorState.Opening -> OpeningIndicator()
                is EditorState.Ready -> {
                    // 축소본이 바뀔 때만 Compose용 이미지로 감싼다
                    val image = remember(state.preview) { state.preview.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.edit_canvas_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                is EditorState.Failed -> Text(
                    text = stringResource(
                        when (state.reason) {
                            ImportFailure.NOT_ENOUGH_MEMORY -> R.string.edit_open_failed_memory
                            ImportFailure.UNREADABLE -> R.string.edit_open_failed
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

    if (state is EditorState.AskSize) {
        LargePhotoDialog(state = state, onChooseSize = onChooseSize, onCancel = onBack)
    }
}

@Composable
private fun OpeningIndicator() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.edit_opening_photo),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 큰 사진을 원본 크기로 열지 줄여서 열지 고르는 창. 취소하면 홈으로 돌아간다.
 * 기기 메모리로 원본 크기가 무리면 '원본 크기'를 누를 수 없게 하고 까닭을 알려 준다.
 */
@Composable
private fun LargePhotoDialog(
    state: EditorState.AskSize,
    onChooseSize: (original: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.large_photo_title)) },
        text = {
            Text(
                stringResource(
                    if (state.originalAllowed) R.string.large_photo_message else R.string.large_photo_message_no_memory,
                    state.originalMegapixels
                )
            )
        },
        // 버튼 세 개가 한 줄에 다 들어가지 않으면 순서가 뒤섞여 줄바꿈되므로, 처음부터 세로로 쌓는다.
        // 위에서부터 메모리를 덜 쓰는 '줄여서', '원본 크기', '취소'
        confirmButton = {
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
    )
}

@Preview
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

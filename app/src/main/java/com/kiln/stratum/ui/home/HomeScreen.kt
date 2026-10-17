package com.kiln.stratum.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.layout.HingeSplitRow
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 홈 화면. 앱을 새로 실행하면 항상 여기서 시작한다.
 *
 * 사진 열기·새 캔버스로 편집을 시작하고, 아래에 최근 프로젝트를 보여 준다.
 * 프로젝트 저장을 만들기 전이라 최근 프로젝트는 빈 상태 안내만 있다.
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPhoto: () -> Unit,
    onNewCanvas: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // 설정 화면은 아직 없어서, 자리만 두고 누를 수 없게 한다
                    IconButton(onClick = {}, enabled = false) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.action_settings)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        // 가로 화면이나 큰 글씨에서도 잘리지 않게 세로로 스크롤한다
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 넓은 화면에서 버튼이 너무 길게 늘어나지 않게 폭을 제한한다.
            // 화면 크기별 배치(최근 프로젝트 썸네일 2열·4열)는 최근 프로젝트를 만들 때 정한다(기획서 화면 목록).
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
                            onClick = onNewCanvas,
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

                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.home_recent_projects),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                // 최근 프로젝트 썸네일이 들어갈 자리. 지금은 빈 상태 안내만 보여 준다.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
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
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    StratumTheme {
        HomeScreen(onOpenPhoto = {}, onNewCanvas = {})
    }
}

package com.kiln.stratum.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 편집 화면. 지금은 상단 바와 빈 캔버스 영역만 있다.
 *
 * 도구 막대, 레이어·조정 패널, 엔진 연결은 다음 작업에서 붙인다.
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라, TopAppBar를 쓰려면 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    onBack: () -> Unit,
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
        // 빈 캔버스 영역. 나중에 엔진이 합성한 이미지를 여기에 그린다.
        // 배경은 내비게이션 바 뒤까지 칠하고, 안에 놓을 내용만 innerPadding으로 상단 바·시스템 바를 피한다.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(innerPadding)
        )
    }
}

@Preview
@Composable
private fun EditScreenPreview() {
    StratumTheme {
        EditScreen(onBack = {})
    }
}

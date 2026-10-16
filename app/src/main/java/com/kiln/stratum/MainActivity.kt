package com.kiln.stratum

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.kiln.stratum.ui.theme.StratumTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱은 항상 다크 테마라서, 시스템이 라이트 모드여도 상태 표시줄·내비게이션 바 아이콘을 밝은색으로 둔다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            StratumTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNamePlaceholder(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// 홈 화면을 만들기 전까지 쓰는 임시 화면: 앱 이름만 가운데에 보여준다.
@Composable
fun AppNamePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

@Preview
@Composable
fun AppNamePlaceholderPreview() {
    StratumTheme {
        Surface {
            AppNamePlaceholder()
        }
    }
}

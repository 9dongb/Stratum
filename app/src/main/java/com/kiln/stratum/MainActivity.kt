package com.kiln.stratum

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kiln.stratum.ui.StratumApp
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
                StratumApp()
            }
        }
    }
}

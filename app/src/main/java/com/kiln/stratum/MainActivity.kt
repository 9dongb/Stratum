package com.kiln.stratum

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kiln.stratum.ui.StratumApp
import com.kiln.stratum.ui.edit.EditorViewModel
import com.kiln.stratum.ui.theme.StratumTheme

class MainActivity : ComponentActivity() {

    // 편집 상태(엔진의 문서·실행 취소 기록)를 들고 있다. 접기·펼치기로 Activity가 다시 만들어져도 남는다.
    private val editor: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱은 항상 다크 테마라서, 시스템이 라이트 모드여도 상태 표시줄·내비게이션 바 아이콘을 밝은색으로 둔다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            StratumTheme {
                StratumApp(editor)
            }
        }
    }
}

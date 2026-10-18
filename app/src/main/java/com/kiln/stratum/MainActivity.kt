package com.kiln.stratum

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import com.kiln.stratum.ui.StratumApp
import com.kiln.stratum.ui.edit.EditorViewModel
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.hideStatusBar
import com.kiln.stratum.ui.layout.rememberWindowHinges
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
        // 전체 화면: 상태 표시줄을 숨긴다. 위에서 밀면 잠깐 보인다(결정 기록 '전체 화면').
        hideStatusBar(window)
        setContent {
            // 접히는 부분(힌지) 정보를 화면 어디서나 읽을 수 있게 넘긴다(LocalHinges)
            CompositionLocalProvider(LocalHinges provides rememberWindowHinges(this)) {
                StratumTheme {
                    StratumApp(editor)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 사진 선택기·공유 창 같은 다른 화면에서 돌아오면 다시 숨긴다
        if (hasFocus) hideStatusBar(window)
    }

    override fun onStop() {
        super.onStop()
        // 앱이 화면에서 사라지면(홈 버튼, 다른 앱으로) 기다리지 않고 지금 작업을 저장한다
        editor.flush()
    }
}

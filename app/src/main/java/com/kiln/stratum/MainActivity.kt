package com.kiln.stratum

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import com.kiln.stratum.settings.AppSettings
import com.kiln.stratum.ui.StratumApp
import com.kiln.stratum.ui.edit.EditorViewModel
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.hideStatusBar
import com.kiln.stratum.ui.layout.rememberWindowHinges
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.ui.theme.stratumColors

class MainActivity : ComponentActivity() {

    // 편집 상태(엔진의 문서·실행 취소 기록)를 들고 있다. 접기·펼치기로 Activity가 다시 만들어져도 남는다.
    private val editor: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 테마(설정의 '테마': 다크·라이트·휴대폰 설정 따름). 그리기 전에 창 바탕과 시스템 막대 아이콘 색을 맞춘다.
        val settings = AppSettings.get(this)
        applyWindowTheme(settings.values.value.themeMode.isDark(systemInDarkTheme()))
        // 전체 화면: 상태 표시줄을 숨긴다. 위에서 밀면 잠깐 보인다(결정 기록 '전체 화면').
        hideStatusBar(window)
        setContent {
            val values by settings.values.collectAsState()
            val dark = values.themeMode.isDark(isSystemInDarkTheme())
            // 설정에서 테마를 바꾸면(또는 휴대폰 설정이 바뀌면) 창 바탕·막대 아이콘 색도 따라 바꾼다
            LaunchedEffect(dark) { applyWindowTheme(dark) }
            // 접히는 부분(힌지) 정보를 화면 어디서나 읽을 수 있게 넘긴다(LocalHinges)
            CompositionLocalProvider(LocalHinges provides rememberWindowHinges(this)) {
                StratumTheme(darkTheme = dark) {
                    StratumApp(editor)
                }
            }
        }
    }

    /** 휴대폰이 다크 모드인지(그리기 전이라 Compose 대신 구성에서 읽는다) */
    private fun systemInDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * 창 바탕을 테마 바탕색으로, 아래 제스처 막대 아이콘을 테마에 맞는 색(다크는 밝게, 라이트는 어둡게)으로 둔다.
     * 창 바탕을 맞춰야 라이트에서 접고 펼 때(Activity를 다시 만드는 동안) 어두운 화면이 번쩍이지 않는다.
     * 시작 화면(아이콘 화면)은 안드로이드가 설정을 읽기 전에 그려서 늘 아이콘 바탕색이다.
     */
    private fun applyWindowTheme(dark: Boolean) {
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        window.setBackgroundDrawable(ColorDrawable(stratumColors(dark).background.toArgb()))
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 앱 화면이 모두 사라지면 AI 모델을 내려놓는다(수백 MB). 접기·펼치기(화면을 다시 만듦)에는 오지 않는다.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) editor.releaseAiModels()
    }
}

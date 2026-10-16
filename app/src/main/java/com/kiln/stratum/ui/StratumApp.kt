package com.kiln.stratum.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kiln.stratum.ui.edit.EditScreen
import com.kiln.stratum.ui.home.HomeScreen

/**
 * 앱의 화면.
 *
 * 기획상 화면은 홈·편집(나중에 설정)뿐이다. 새 캔버스, 도구 옵션, 내보내기 같은 나머지는
 * 이 화면들 안의 시트·패널로 띄운다.
 */
enum class AppScreen { Home, Edit }

/** 앱 전체. 지금 보여 줄 화면을 고르고 화면 사이 이동을 맡는다. */
@Composable
fun StratumApp() {
    // 앱을 새로 실행하면 항상 홈에서 시작한다.
    // rememberSaveable이라 접기·펼치기로 Activity가 다시 만들어지거나, 안드로이드가 백그라운드의 앱을
    // 종료했다가 되살려도 보던 화면이 그대로 나온다.
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }

    when (screen) {
        AppScreen.Home -> HomeScreen(
            // 사진 선택기와 새 캔버스 시트는 다음 작업이다. 지금은 빈 편집 화면으로 이동만 한다.
            onOpenPhoto = { screen = AppScreen.Edit },
            onNewCanvas = { screen = AppScreen.Edit }
        )

        AppScreen.Edit -> {
            val goHome = { screen = AppScreen.Home }
            // 시스템 뒤로(버튼·제스처)도 상단 바의 뒤로 버튼과 똑같이 홈으로 돌아간다
            BackHandler(onBack = goHome)
            EditScreen(onBack = goHome)
        }
    }
}

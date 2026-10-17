package com.kiln.stratum.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kiln.stratum.ui.edit.EditScreen
import com.kiln.stratum.ui.edit.EditorViewModel
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
fun StratumApp(editor: EditorViewModel) {
    // 앱을 새로 실행하면 항상 홈에서 시작한다.
    // rememberSaveable이라 접기·펼치기로 Activity가 다시 만들어지거나, 안드로이드가 백그라운드의 앱을
    // 종료했다가 되살려도 보던 화면이 그대로 나온다.
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }

    // 안드로이드 사진 선택기(저장소 권한이 필요 없다, CLAUDE.md 5장).
    // 화면과 상관없이 늘 등록해 두어야, 선택기를 연 사이 Activity가 다시 만들어져도 고른 사진을 받는다.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 선택기에서 아무것도 고르지 않고 돌아오면 홈에 그대로 있는다
        if (uri != null) {
            editor.openPhoto(uri)
            screen = AppScreen.Edit
        }
    }

    when (screen) {
        AppScreen.Home -> HomeScreen(
            onOpenPhoto = {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCreateCanvas = { width, height, fill ->
                editor.newCanvas(width, height, fill)
                screen = AppScreen.Edit
            }
        )

        AppScreen.Edit -> {
            // 홈으로 갈 때 문서를 닫아 메모리를 비운다. 자동 저장은 아직 없다.
            val goHome = {
                editor.close()
                screen = AppScreen.Home
            }
            // 시스템 뒤로(버튼·제스처)도 상단 바의 뒤로 버튼과 똑같이 홈으로 돌아간다
            BackHandler(onBack = goHome)
            val state by editor.state.collectAsState()
            EditScreen(
                state = state,
                onBack = goHome,
                onCanvasSize = editor::onCanvasSize,
                onChooseSize = editor::chooseSize
            )
        }
    }
}

package com.kiln.stratum.ui.layout

import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 전체 화면(결정 기록 '전체 화면', 앱 전체·커버와 펼친 화면 모두): [window]의 상태 표시줄을 숨긴다.
 * 위에서 아래로 밀면 잠깐 보였다가 다시 숨는다. 아래 제스처 막대(내비게이션 바)는 그대로다.
 * 창이 아직 화면에 붙기 전에 불러도 붙을 때 적용된다.
 */
fun hideStatusBar(window: Window) {
    window.insetsController?.let(::hideStatusBar)
}

private fun hideStatusBar(controller: WindowInsetsController) {
    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsets.Type.statusBars())
}

/**
 * 아래 시트를 연 동안에도 상태 표시줄을 숨긴다. 시트 내용 안에 둔다.
 *
 * 시트(`ModalBottomSheet`)는 화면 전체를 덮는 따로 창이라, 시트가 앞에 오면 상태 표시줄을 시트 창의 설정대로 보인다.
 * 화면을 덮지 않는 메뉴(팝업)와 작은 창은 앱 창의 설정을 따라 따로 할 일이 없다.
 */
@Composable
fun HideStatusBarInSheet() {
    val view = LocalView.current
    DisposableEffect(view) {
        // 시트 내용은 시트 창에 붙어 있어, 그 창의 설정을 바꾼다
        view.windowInsetsController?.let(::hideStatusBar)
        onDispose {}
    }
}

package com.kiln.stratum.ui.layout

import android.view.WindowInsets

/**
 * 작업 화면 상단 바(편집·유동화·커버 새 창)의 내용 줄 높이(px). [workTopBarHeight]와 같은 규칙이다:
 * 상태 표시줄 높이(숨겨도 그 자리)를 24~48dp 안으로, 상태 표시줄이 없으면 32dp.
 * [density]는 바 노드의 밀도다(강제 크기가 기기보다 크면 밀도가 낮아지고, 창 여백은 기기 픽셀 그대로다).
 */
fun workBarContentPx(insets: WindowInsets, density: Float): Float {
    val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
    return (if (status > 0) status / density else 32f).coerceIn(24f, 48f) * density
}

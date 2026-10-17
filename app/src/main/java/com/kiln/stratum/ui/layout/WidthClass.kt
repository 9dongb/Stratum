package com.kiln.stratum.ui.layout

/** 화면 폭에 따른 편집 화면 배치(CLAUDE.md 6장). 기기 이름이 아니라 폭으로 고른다. */
enum class WidthClass {
    /** 600dp 미만: 폴드8 커버, 일반 폰 세로 */
    Compact,

    /** 600~840dp: 폴드8 메인 세로, 폰·커버 가로 */
    Medium,

    /** 840dp 이상: 폴드8 메인 가로, 태블릿 */
    Expanded
}

// 경계값은 WindowManager의 WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND·WIDTH_DP_EXPANDED_LOWER_BOUND와 같다.
// 그 상수가 든 window-core는 컴파일할 때 보이지 않아(window가 내보내지 않음), 라이브러리를 더하지 않고 값을 옮겨 둔다.
private const val MEDIUM_MIN_WIDTH_DP = 600f
private const val EXPANDED_MIN_WIDTH_DP = 840f

/** [widthDp] 폭에 맞는 배치. */
fun widthClassOf(widthDp: Float): WidthClass =
    when {
        widthDp < MEDIUM_MIN_WIDTH_DP -> WidthClass.Compact
        widthDp < EXPANDED_MIN_WIDTH_DP -> WidthClass.Medium
        else -> WidthClass.Expanded
    }

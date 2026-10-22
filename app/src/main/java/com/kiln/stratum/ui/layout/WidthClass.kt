package com.kiln.stratum.ui.layout

/**
 * 화면 크기에 따른 편집 화면 배치(CLAUDE.md 6장). 기기 이름이 아니라 크기로 고른다.
 * 편집 화면과 창은 [layoutClassOf](짧은 쪽으로 커버인지 정함), 홈 격자 칸 수·설정 2단은 [widthClassOf](폭만)로 고른다.
 */
enum class WidthClass {
    /** 커버 배치: 폴드8 커버, 일반 폰(가로로 돌려도, [layoutClassOf]). 폭만 보면([widthClassOf]) 600dp 미만 */
    Compact,

    /** 600~840dp: 폴드8 메인 세로 */
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

/**
 * 편집 화면·창의 배치(사용자 피드백 12차 3: "커버화면을 가로로 돌리면 펼친화면 배치가 아닌, 커버화면 배치가 돌아간형태로 보여주는 걸로 하세요").
 * 화면 짧은 쪽이 600dp보다 작으면 커버 배치([WidthClass.Compact])다. 그래서 커버·폰을 가로로 돌려도 커버 배치이고, 가로가 더 길면
 * 돌아간 형태([isCoverLandscape])로 그린다. 짧은 쪽이 600dp 이상이면(펼친 화면·태블릿) 지금처럼 폭으로 메인 세로·가로를 고른다.
 */
fun layoutClassOf(widthDp: Float, heightDp: Float): WidthClass =
    if (minOf(widthDp, heightDp) < MEDIUM_MIN_WIDTH_DP) WidthClass.Compact else widthClassOf(widthDp)

/** 커버 배치를 돌아간 형태(캔버스 오른쪽에 세로 막대·탭 줄)로 그릴지: 커버 배치이면서 가로가 더 길 때(사용자 피드백 12차 3) */
fun isCoverLandscape(widthDp: Float, heightDp: Float): Boolean =
    layoutClassOf(widthDp, heightDp) == WidthClass.Compact && widthDp > heightDp

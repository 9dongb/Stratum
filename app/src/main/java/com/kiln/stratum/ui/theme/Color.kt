package com.kiln.stratum.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 앱의 색 토큰(결정 기록 '디자인(시안)'). 화면 코드는 색을 직접 쓰지 않고 [StratumTheme.colors]의 이 이름으로 쓴다.
 * 다크는 사용자가 정한 시안 색이고, 라이트는 같은 역할을 밝게 옮긴 색이다(계획 승인).
 * M3 부품이 쓰는 색 역할도 모두 이 토큰으로 정한다([stratumColorScheme]).
 *
 * @property isDark 다크 테마인지(시스템 막대 아이콘 색 등)
 * @property workspace 캔버스 주변 작업 공간
 * @property background 기본 배경: 상단 바·도구 막대·커버 탭 줄·홈·설정 바탕
 * @property panel 패널: 아래·오른쪽 패널, 커버 옵션 자리, 창·시트·메뉴
 * @property divider 구분선
 * @property fieldBorder 입력칸·칩·분할 버튼·선택 상자 테두리
 * @property fieldBackground 입력칸·선택 상자·곡선 편집기 안
 * @property textPrimary 기본 글자(제목·값·이름)
 * @property textSecondary 보조 글자(슬라이더 이름)와 보통 아이콘
 * @property textDescription 설명 글자(안내·부제·고르지 않은 탭)
 * @property textDisabled 꺼진 글자·아이콘
 * @property accent 주요 버튼(내보내기·적용·확인), 스위치·라디오
 * @property onAccent 주요 버튼 위 글자
 * @property selectedContent 선택된 아이콘·글자, 글자 버튼
 * @property selectedBackground 선택된 항목 배경
 * @property chipSelectedBorder 선택된 칩 테두리
 * @property sliderFill 슬라이더 채움(다크 흰색·라이트 #242424, 2026-10-24 사용자 요청)
 * @property adjustAccent 조정 곡선·점과 바뀐 조정 항목 점(#0076FE, 슬라이더 채움과 따로)
 * @property tabIndicator 선택된 탭 밑줄
 * @property sliderTrack 슬라이더 빈 트랙
 * @property sliderThumb 슬라이더 손잡이(#A5A5A4, 2026-10-24 사용자 요청)
 * @property sliderThumbBorder 손잡이 테두리(라이트는 흰 패널 위에서 보이게, 다크는 없음)
 * @property canvasOutline 캔버스 둘레 1dp(라이트에서 흰 캔버스와 작업 공간을 가름)
 * @property inverseBackground 알림(제스처 알림·알림 막대) 바탕
 * @property inverseText 알림 글자
 * @property inverseAccent 알림 위의 버튼 글자
 * @property error 잘못된 입력·지우기
 * @property onError 오류 색 위 글자
 * @property errorContainer 오류 칸 바탕(M3 부품용)
 * @property onErrorContainer 오류 칸 글자(M3 부품용)
 * @property scrim 창 뒤를 어둡게 하는 막(투명도 포함)
 */
@Immutable
class StratumColors(
    val isDark: Boolean,
    val workspace: Color,
    val background: Color,
    val panel: Color,
    val divider: Color,
    val fieldBorder: Color,
    val fieldBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDescription: Color,
    val textDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    val selectedContent: Color,
    val selectedBackground: Color,
    val chipSelectedBorder: Color,
    val sliderFill: Color,
    val adjustAccent: Color,
    val tabIndicator: Color,
    val sliderTrack: Color,
    val sliderThumb: Color,
    val sliderThumbBorder: Color,
    val canvasOutline: Color,
    val inverseBackground: Color,
    val inverseText: Color,
    val inverseAccent: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val scrim: Color
)

/** 다크: 사용자가 정한 시안 색 */
internal val DarkStratumColors = StratumColors(
    isDark = true,
    workspace = Color(0xFF121315),
    background = Color(0xFF1B1C1F),
    panel = Color(0xFF232428),
    divider = Color(0xFF2E3036),
    fieldBorder = Color(0xFF3A3C43),
    fieldBackground = Color(0xFF1B1C1F),
    textPrimary = Color(0xFFE9EAEE),
    textSecondary = Color(0xFFC9CCD3),
    textDescription = Color(0xFF9EA3AD),
    textDisabled = Color(0xFF6C707A),
    accent = Color(0xFF2F6FE4),
    onAccent = Color(0xFFFFFFFF),
    selectedContent = Color(0xFF8DB6FF),
    selectedBackground = Color(0xFF2A3550),
    chipSelectedBorder = Color(0xFF4C7BD9),
    sliderFill = Color(0xFFFFFFFF),
    adjustAccent = Color(0xFF0076FE),
    tabIndicator = Color(0xFF0076FE),
    sliderTrack = Color(0xFF3A3C43),
    sliderThumb = Color(0xFFA5A5A4),
    sliderThumbBorder = Color.Transparent,
    canvasOutline = Color(0xFF3A3C43),
    inverseBackground = Color(0xFFE9EAEE),
    inverseText = Color(0xFF1B1C1F),
    inverseAccent = Color(0xFF2F6FE4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color(0x52000000)
)

/** 라이트: 다크와 같은 역할을 밝게 옮긴 색 */
internal val LightStratumColors = StratumColors(
    isDark = false,
    workspace = Color(0xFFDCDDE1),
    background = Color(0xFFF4F5F7),
    panel = Color(0xFFFFFFFF),
    divider = Color(0xFFE1E3E8),
    fieldBorder = Color(0xFFC5C8CF),
    fieldBackground = Color(0xFFF4F5F7),
    textPrimary = Color(0xFF1B1C1F),
    textSecondary = Color(0xFF3D4048),
    textDescription = Color(0xFF5F6470),
    textDisabled = Color(0xFFA3A7B0),
    accent = Color(0xFF2F6FE4),
    onAccent = Color(0xFFFFFFFF),
    selectedContent = Color(0xFF1F5BD0),
    selectedBackground = Color(0xFFDCE7FD),
    chipSelectedBorder = Color(0xFF4C7BD9),
    sliderFill = Color(0xFF242424),
    adjustAccent = Color(0xFF0076FE),
    tabIndicator = Color(0xFF0076FE),
    sliderTrack = Color(0xFFC5C8CF),
    sliderThumb = Color(0xFFA5A5A4),
    sliderThumbBorder = Color(0xFFC5C8CF),
    canvasOutline = Color(0xFFA3A7B0),
    inverseBackground = Color(0xFF2A2B30),
    inverseText = Color(0xFFF4F5F7),
    inverseAccent = Color(0xFF8DB6FF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0x52000000)
)

/** [dark]면 다크, 아니면 라이트 색 토큰 */
fun stratumColors(dark: Boolean): StratumColors = if (dark) DarkStratumColors else LightStratumColors

/** 숨긴 레이어 줄의 작은 그림·이름을 흐리게 보이는 정도 */
const val HiddenContentAlpha = 0.4f

/**
 * 캔버스 위에 그리는 표시(브러시 커서·자르기·선택 윤곽·스포이드·크기 미리 보기)의 색.
 * 어떤 사진 위에서도 보이게 밝은 선을 어두운 테두리로 감싸 그리고, 테마와 상관없이 같다.
 */
object CanvasOverlayColors {
    /** 밝은 선(테두리 짝의 안쪽) */
    val Light = Color.White

    /** 어두운 테두리 */
    val Dark = Color.Black

    /** 반투명 어두운 테두리(자르기 손잡이·스포이드 고리 둘레) */
    val DarkHalo = Color.Black.copy(alpha = 0.6f)

    /** 반투명 밝은 선(크기 미리 보기의 단단함 원) */
    val LightHalo = Color.White.copy(alpha = 0.6f)

    /** 자르기 3등분 안내선 */
    val Guide = Color.White.copy(alpha = 0.5f)

    /** 자르기 테두리 바깥을 어둡게 */
    val CropScrim = Color(0x8C000000)

    /** AI 제거에서 톡 눌러 고른 물체를 덮는 반투명 검정(칠하는 동안의 미리 보기와 같은 50%) */
    val RemovePick = Color.Black.copy(alpha = 0.5f)

    /** 레이어 마스크를 칠하는 동안 가린 곳을 덮는 반투명 빨강(포토샵 빠른 마스크처럼, 결정 기록 '피부 보정'의 1d) */
    val MaskHidden = Color(0xFFFF3B30).copy(alpha = 0.5f)

    /** 투명한 곳의 체크 무늬(밝은 칸·어두운 칸) */
    val CheckerLight = Color(0xFFFFFFFF)
    val CheckerDark = Color(0xFFCCCCCC)

    /** 스포이드가 아직 색을 읽지 못했거나 투명한 곳 */
    val Unknown = Color.Gray

    /** 얼굴 인식 보정: 고른 얼굴의 테두리(앱의 주요 색 #0076FE) */
    val FaceSelected = Color(0xFF0076FE)
}

/** 색 팔레트가 그리는 색. 고르는 색 자체를 보이는 그림이라 테마와 상관없다. */
object ColorPickerColors {
    /** 색상 띠: 빨강 → 노랑 → 초록 → 하늘 → 파랑 → 자홍 → 빨강 */
    val Hues = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

    /** 채도·밝기 네모: 채도 0 쪽(흰색)과 밝기 0 쪽(검은색) */
    val NoSaturation = Color.White
    val NoBrightness = Color.Black
}

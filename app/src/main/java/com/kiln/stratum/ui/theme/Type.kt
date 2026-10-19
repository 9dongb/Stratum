package com.kiln.stratum.ui.theme

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// M3 기본 글자를 바탕으로, 촘촘하게 보이도록 바 제목과 창 제목만 줄인다(결정 기록 '디자인(시안)').
// 버튼·분할 버튼·칩·본문 글자는 그대로 둔다. 줄이면 폭이 바뀌어 줄 바꿈과 옵션 위치가 움직인다.
private val MaterialDefaults = Typography()

val StratumTypography = Typography(
    // 상단 바 제목(유동화·홈·설정) 22 → 18sp
    titleLarge = MaterialDefaults.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
    // 창·아래 시트 제목 24 → 20sp
    headlineSmall = MaterialDefaults.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
    // 처음 프로젝트에서 쓰던 값 그대로(글자 높이가 바뀌어 줄이 움직이지 않게)
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

/** 좁은 칸(도구 옵션 띠)에서 한 줄에 다 들어가지 않는 글자를 줄일 수 있는 가장 작은 크기 */
private val CompactTextMinSize = 8.sp

/**
 * 좁은 칸(도구 옵션 띠, 폭 56dp)에 한 줄로 넣는 글자의 크기: [style] 크기에서 시작해, 넘치면 한 줄에 들어갈 때까지 줄인다.
 * 'Intersect'·'1000 px'처럼 긴 영어 이름·값도 자르지 않고 다 보이게 한다.
 */
fun compactTextAutoSize(style: TextStyle): TextAutoSize =
    TextAutoSize.StepBased(minFontSize = CompactTextMinSize, maxFontSize = style.fontSize, stepSize = 0.5.sp)

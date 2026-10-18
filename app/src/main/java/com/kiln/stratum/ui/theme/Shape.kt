package com.kiln.stratum.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 모서리 반지름(결정 기록 '디자인(시안)'). 둥근 알약 대신 작은 모서리로 평평하게 보인다. */
object StratumRadius {
    /** 작은 그림 */
    val Small = 4.dp

    /** 입력칸·혼합 모드 선택 상자·메뉴 */
    val Field = 6.dp

    /** 버튼·도구 선택·칩·분할 버튼·배율 표시·알림·카드 */
    val Control = 8.dp

    /** 창·아래 시트 */
    val Large = 12.dp
}

/** 화면 코드가 쓰는 모양. 모서리 값은 [StratumRadius]에서만 온다. */
object StratumShapes {
    /** 버튼·도구 선택 사각형·칩·분할 버튼 */
    val Control = RoundedCornerShape(StratumRadius.Control)

    /** 혼합 모드 선택 상자·입력칸 */
    val Field = RoundedCornerShape(StratumRadius.Field)

    /** 메뉴 */
    val Menu = RoundedCornerShape(StratumRadius.Field)

    /** 레이어 작은 그림·색상 띠 */
    val Thumbnail = RoundedCornerShape(StratumRadius.Small)

    /** 배율 표시·제스처 알림·홈 카드·색 비교 칸 */
    val Card = RoundedCornerShape(StratumRadius.Control)

    /** 창(힌지를 피하는 창) */
    val Dialog = RoundedCornerShape(StratumRadius.Large)

    /** 아래 시트의 위쪽 모서리 */
    val Sheet = RoundedCornerShape(topStart = StratumRadius.Large, topEnd = StratumRadius.Large)

    /** 색 동그라미·바뀐 항목 점 */
    val Dot = CircleShape
}

/** M3 부품이 테마에서 읽는 모양(메뉴·입력칸·알림 막대 6dp, 칩 8dp, 창·시트 12dp) */
internal val StratumMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(StratumRadius.Field),
    small = RoundedCornerShape(StratumRadius.Control),
    medium = RoundedCornerShape(StratumRadius.Control),
    large = RoundedCornerShape(StratumRadius.Large),
    extraLarge = RoundedCornerShape(StratumRadius.Large)
)

package com.kiln.stratum.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 부품의 크기(결정 기록 '디자인(시안)'). 보이는 높이만 촘촘하게 줄이고, 누르는 영역과 차지하는 자리는 48dp 그대로다
 * (M3 minimumInteractiveComponentSize). 그래서 부품을 바꿔도 화면 배치가 움직이지 않는다.
 */
object StratumDimens {
    /** 바·막대·패널 경계선 */
    val Divider = 1.dp

    /** 선택된 탭 밑줄 */
    val TabIndicator = 2.dp

    /** 슬라이더 트랙 두께와 손잡이(흰 원) 지름 */
    val SliderTrack = 3.dp
    val SliderThumb = 14.dp

    /** 버튼의 보이는 높이와 안쪽 여백(좌우 12dp는 M3 글자 버튼과 같아 버튼 줄의 폭이 그대로다) */
    val ButtonHeight = 30.dp
    val ButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

    /** 분할 버튼·혼합 모드 선택 상자의 보이는 높이 */
    val ControlHeight = 28.dp

    /** 분할 버튼 칸 안쪽 여백 */
    val SegmentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)

    /** 도구 막대의 고른 도구 사각형 */
    val ToolSquare = 40.dp

    /** 커버 탭 줄의 고른 도구 사각형(아이콘 뒤) */
    val TabToolSquare = DpSize(48.dp, 32.dp)
}

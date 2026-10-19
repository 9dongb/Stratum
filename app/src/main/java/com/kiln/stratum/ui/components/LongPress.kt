package com.kiln.stratum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration

/**
 * 꾹 누르기 시간 두 묶음(2026-10-20 사용자 요청: "꾹 누르기 시간 공통으로 두그룹으로 관리하세요. (메뉴 꾹 누르기나 레이어를 한 그룹,
 * 편집 작업 내에 사용하는 스포이드나 가져올곳 고정 같은 것을 한그룹) 메뉴 꾹 누르기나 레이어 꾹누르기 그룹 시간을 0.3초로 바꿔주세요.").
 *
 * - 메뉴 묶음([MENU_MILLIS], 0.3초): 메뉴를 여는 꾹 누르기. 도구 막대·커버 탭 줄의 하위 도구 메뉴, 레이어 줄 메뉴, 홈 프로젝트 카드 메뉴.
 *   그 자리를 [MenuLongPress]로 감싸면 안의 꾹 누르기(`longPressFirst`, `combinedClickable` 등)가 모두 이 시간을 쓴다.
 * - 편집 묶음: 캔버스 안에서 하는 꾹 누르기. 브러시로 꾹 눌러 스포이드, 가져올 곳 표시를 꾹 눌러 고정.
 *   휴대폰 설정의 길게 누르기 시간(기본 0.4초)을 그대로 쓴다([editLongPressMillis]).
 *
 * 새 꾹 누르기를 더할 때 메뉴를 여는 것이면 메뉴 묶음, 캔버스 안의 편집 동작이면 편집 묶음을 쓴다.
 */
object LongPressTimes {

    /** 메뉴 묶음의 꾹 누르기 시간(밀리초) */
    const val MENU_MILLIS = 300L
}

/** [content] 안의 꾹 누르기를 메뉴 묶음 시간([LongPressTimes.MENU_MILLIS])으로 한다. 다른 터치 설정(움직임 허용 거리 등)은 그대로다. */
@Composable
fun MenuLongPress(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val menu = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = LongPressTimes.MENU_MILLIS
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides menu, content = content)
}

/** 편집 묶음(캔버스 안 꾹 누르기)의 시간(밀리초): 휴대폰 설정의 길게 누르기 시간 그대로 */
fun ViewConfiguration.editLongPressMillis(): Long = longPressTimeoutMillis

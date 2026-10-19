package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 캔버스 보기 반전(사용자 피드백 7차, 결정 기록 '화면 반전'). 레이어 탭의 버튼으로 켜고 끈다.
 * 문서·레이어 픽셀·실행 취소·자동 저장·내보내기는 그대로이고, 캔버스 영역을 그릴 때 좌우(또는 상하)로 뒤집는 변환 하나만 더한다(보기·카메라 변환).
 * 그 변환은 Compose 그림 층([CanvasViewport]의 graphicsLayer)이라 터치 자리도 거꾸로 바뀌어 도구에 간다:
 * 좌우 반전된 화면의 오른쪽을 누르면 도구는 문서의 왼쪽 자리를 받는다.
 */
@Immutable
data class ViewFlip(val horizontal: Boolean = false, val vertical: Boolean = false) {

    /** 그림 층의 가로·세로 배율(뒤집으면 -1) */
    val scaleX: Float
        get() = if (horizontal) -1f else 1f

    val scaleY: Float
        get() = if (vertical) -1f else 1f

    val isFlipped: Boolean
        get() = horizontal || vertical

    /** 저장용 값(좌우 1, 상하 2) */
    val bits: Int
        get() = (if (horizontal) 1 else 0) or (if (vertical) 2 else 0)

    companion object {
        val None = ViewFlip()

        fun fromBits(bits: Int): ViewFlip = ViewFlip(horizontal = bits and 1 != 0, vertical = bits and 2 != 0)
    }
}

/**
 * 캔버스 영역 안의 지금 반전. 캔버스 위 표시 중 방향이 있는 것(스포이드 고리의 위·아래 반)이나
 * 문서가 아니라 캔버스 가운데에 두는 것(크기 미리 보기 원)은 이것으로 반전을 되돌린다.
 */
val LocalViewFlip = staticCompositionLocalOf { ViewFlip.None }

/**
 * 캔버스 영역 안에서 이 표시의 반전을 되돌린다(같은 반전을 한 번 더 걸면 원래대로다).
 * 캔버스 영역과 같은 크기(fillMaxSize)의 표시에 쓴다(뒤집는 기준점이 같아야 한다).
 */
fun Modifier.undoViewFlip(flip: ViewFlip): Modifier =
    if (flip.isFlipped) graphicsLayer { scaleX = flip.scaleX; scaleY = flip.scaleY } else this

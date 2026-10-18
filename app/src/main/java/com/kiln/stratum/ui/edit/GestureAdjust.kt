package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.brush.BrushSettings
import kotlin.math.roundToInt

// 세 손가락으로 밀어 바꾸는 값(결정 기록 '세 손가락 제스처').
// 밀기를 시작할 때의 값에서 움직인 거리만큼 바꾸므로, 밀다가 되돌아오면 처음 값으로 돌아온다.

/**
 * 이만큼(dp) 밀면 눈금 전체를 지난다: 크기는 슬라이더와 같은 1~1000px 로그 눈금, 단단함은 0~100%, 압력은 1~100%.
 * 커버 화면(폭 약 475dp)에서도 한 번에 눈금 전체를 밀 수 있다.
 */
const val THREE_FINGER_SWIPE_RANGE_DP = 300f

/** 위로(+) [distanceDp]만큼 밀었을 때의 브러시 크기(1~1000px, 1px 단위). [start]는 밀기를 시작할 때의 크기다. */
internal fun swipedSize(start: Float, distanceDp: Float): Float =
    if (distanceDp == 0f) start else fractionToSize(sizeToFraction(start) + distanceDp / THREE_FINGER_SWIPE_RANGE_DP)

/** 오른쪽으로(+) 밀었을 때의 단단함(0~1, 1% 단위) */
internal fun swipedHardness(start: Float, distanceDp: Float): Float =
    if (distanceDp == 0f) start else toPercent(start + distanceDp / THREE_FINGER_SWIPE_RANGE_DP)

/** 오른쪽으로(+) 밀었을 때의 유동화 압력(1~100%, 1% 단위). 0%면 아무것도 비틀지 않으니 1%에서 멈춘다(슬라이더와 같다). */
internal fun swipedStrength(start: Float, distanceDp: Float): Float =
    if (distanceDp == 0f) start else toPercent(start + distanceDp / THREE_FINGER_SWIPE_RANGE_DP).coerceAtLeast(0.01f)

/** 0..1로 자르고 1% 단위로 맞춘다 */
private fun toPercent(value: Float): Float = (value.coerceIn(0f, 1f) * 100).roundToInt() / 100f

/** 브러시 설정에 세 손가락 밀기를 건다: 위아래는 크기, 좌우는 단단함. 나머지(불투명도·색)는 그대로다. */
internal fun BrushSettings.swiped(axis: SwipeAxis, distanceDp: Float): BrushSettings = when (axis) {
    SwipeAxis.VERTICAL -> copy(size = swipedSize(size, distanceDp))
    SwipeAxis.HORIZONTAL -> copy(hardness = swipedHardness(hardness, distanceDp))
}

/**
 * 세 손가락으로 미는 동안 시작할 때의 값을 기억한다. 한 번 밀 때 처음 [from]을 부른 때의 값을 잡아 두고, [end]에서 잊는다.
 * 화면이 다시 그려지기 전에 여러 번 불려도 늘 같은 시작 값에서 계산한다.
 */
internal class SwipeAnchor<T : Any> {
    private var start: T? = null

    /** 이번 밀기의 시작 값. 처음 부르면 [current]를 시작 값으로 잡는다. */
    fun from(current: T): T = start ?: current.also { start = it }

    /** 밀기가 끝났다 */
    fun end() {
        start = null
    }
}

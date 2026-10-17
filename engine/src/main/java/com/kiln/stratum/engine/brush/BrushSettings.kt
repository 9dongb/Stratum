package com.kiln.stratum.engine.brush

import kotlin.math.max

/**
 * 둥근 브러시 한 가지의 설정. 브러시와 지우개가 같이 쓴다(지우개는 색을 쓰지 않는다).
 *
 * @property size 지름(문서 픽셀). [MIN_SIZE]..[MAX_SIZE]
 * @property hardness 가장자리의 단단함 0..1. 1이면 경계가 또렷하고, 0이면 가운데부터 부드럽게 옅어진다.
 * @property opacity 한 획의 최대 불투명도 0..1. 한 획 안에서 자국이 겹쳐도 이보다 짙어지지 않는다(포토샵의 불투명도와 같다).
 * @property color 칠할 색(`0xAARRGGBB`). 알파는 쓰지 않고 [opacity]가 대신한다.
 */
data class BrushSettings(
    val size: Float = 40f,
    val hardness: Float = 0.8f,
    val opacity: Float = 1f,
    val color: Int = 0xFF000000.toInt()
) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "Brush size out of range: $size" }
        require(hardness in 0f..1f) { "Hardness out of range: $hardness" }
        require(opacity in 0f..1f) { "Opacity out of range: $opacity" }
    }

    /** 반지름(문서 픽셀) */
    val radius: Float
        get() = size / 2f

    /** 자국 사이 간격(문서 픽셀). 지름의 [SPACING]배이고 1픽셀보다 좁지 않다. 좁을수록 선이 매끈하다. */
    val spacing: Float
        get() = max(1f, size * SPACING)

    /** 칠할 색의 불투명한 값(알파를 255로) */
    val opaqueColor: Int
        get() = color or 0xFF000000.toInt()

    companion object {
        const val MIN_SIZE = 1f

        /** 50MP 사진(긴 변 약 8000px)의 1/8쯤. 더 크면 한 자국이 타일 수십 장을 덮어 느려진다. */
        const val MAX_SIZE = 1000f

        /** 자국 간격(지름에 대한 몫). 포토샵 기본(25%)보다 촘촘해 부드러운 브러시도 줄무늬가 보이지 않는다. */
        const val SPACING = 0.1f
    }
}

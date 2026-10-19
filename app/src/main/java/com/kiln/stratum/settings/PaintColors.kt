package com.kiln.stratum.settings

/** 전경색·배경색 중 하나(결정 기록 '전경색·배경색') */
enum class ColorSlot { FOREGROUND, BACKGROUND }

/**
 * 전경색·배경색(사용자 피드백 7차, 결정 기록 '전경색·배경색'). 브러시·글자가 함께 쓰고, 칠할 때는 전경색을 쓴다(포토샵과 같다).
 * 색은 불투명한 `0xAARRGGBB`다(브러시·글자 색은 알파를 쓰지 않는다). 설정 저장소에 남아 앱을 다시 켜도 그대로다.
 */
data class PaintColors(val foreground: Int = BLACK, val background: Int = WHITE) {

    /** [slot]의 색 */
    operator fun get(slot: ColorSlot): Int = when (slot) {
        ColorSlot.FOREGROUND -> foreground
        ColorSlot.BACKGROUND -> background
    }

    /** [slot]을 [color](불투명하게 맞춤)로 바꾼 두 색 */
    fun with(slot: ColorSlot, color: Int): PaintColors = when (slot) {
        ColorSlot.FOREGROUND -> copy(foreground = opaque(color))
        ColorSlot.BACKGROUND -> copy(background = opaque(color))
    }

    /** 전경색과 배경색을 맞바꾼 두 색 */
    fun swapped(): PaintColors = PaintColors(foreground = background, background = foreground)

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** 처음 두 색: 전경색 검정, 배경색 흰색(포토샵과 같다) */
        val DEFAULT = PaintColors()

        /** 알파를 255로 맞춘 색 */
        fun opaque(color: Int): Int = color or BLACK
    }
}

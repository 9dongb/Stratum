package com.kiln.stratum.engine.text

/**
 * 글자 넣기의 모양.
 *
 * @property size 글자 크기(문서 픽셀, 글꼴의 em 크기). [MIN_SIZE]..[MAX_SIZE]
 * @property color 글자 색(`0xAARRGGBB`, 알파는 쓰지 않는다)
 */
data class TextStyle(val size: Float = 120f, val color: Int = 0xFF000000.toInt()) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "Text size out of range: $size" }
    }

    companion object {
        const val MIN_SIZE = 8f

        /** 50MP 사진(긴 변 약 8000px)에 제목을 크게 넣을 수 있는 크기 */
        const val MAX_SIZE = 2000f

        /** 한 레이어에 넣는 글자 수의 한도. 아주 긴 글은 타일을 많이 쓰고 느리다. */
        const val MAX_LENGTH = 2000
    }
}

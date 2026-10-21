package com.kiln.stratum.engine.text

/**
 * 다시 고칠 수 있는 글자 레이어의 글자(사용자 피드백 8차 1-6: "다시 그 텍스트를 누르면 편집가능하도록").
 * 픽셀은 지금처럼 레이어 타일에 그려 두고(그리기·내보내기·저장 길이가 그대로다), 이것으로 같은 자리에 다시 그려 고친다.
 * 레이어 픽셀을 바꾸는 편집(브러시·지우개·리터칭 등)을 하면 지운다(`Layer.withPixels`). 그 뒤로는 보통 픽셀 레이어다.
 *
 * @property x 글자 상자 왼쪽 위(문서 좌표)
 * @property style 고른 크기와 색(sRGB로 고른 색 그대로. 넓은 색 문서에는 그릴 때 바꾼다)
 * @property width 그린 글자 상자의 크기(문서 픽셀). 화면이 누른 자리에 글자가 있는지 보는 데 쓴다.
 */
data class TextSpec(val text: String, val x: Float, val y: Float, val style: TextStyle, val width: Float, val height: Float) {
    init {
        require(text.isNotBlank()) { "Text must not be blank" }
        require(text.length <= TextStyle.MAX_LENGTH) { "Text too long: ${text.length}" }
        require(width > 0f && height > 0f) { "Text box must not be empty: ${width}x$height" }
    }

    /** 문서 좌표 ([px], [py])가 글자 상자 안인지 */
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + width && py >= y && py < y + height

    /** 글자 상자를 ([dx], [dy]) 문서 픽셀만큼 옮긴 것 */
    fun moved(dx: Float, dy: Float): TextSpec = copy(x = x + dx, y = y + dy)
}

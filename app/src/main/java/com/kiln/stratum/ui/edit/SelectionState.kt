package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.selection.ColorRegion
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape

/**
 * 선택 도구 설정. 앱을 끌 때까지 남는다(다른 문서를 열어도).
 *
 * @property mode 새로 그린 모양을 앞 선택 영역과 합치는 방법
 * @property feather 사각형·원형·올가미 경계를 흐리는 정도(픽셀, 0이면 또렷하다)
 * @property tolerance 자동 선택이 비슷하다고 보는 색 차이(0~255)
 * @property contiguous 자동 선택이 누른 곳과 이어진 곳만 고르는지
 */
data class SelectionOptions(
    val mode: SelectionMode = SelectionMode.REPLACE,
    val feather: Float = 0f,
    val tolerance: Int = ColorRegion.DEFAULT_TOLERANCE,
    val contiguous: Boolean = true
)

/**
 * 선택 영역 윤곽선(개미 행렬). [lines]는 문서 좌표의 점들(x0, y0, x1, y1, …)이다.
 * [region]이 있으면 확대해 볼 때 그 사각형 안만 더 정확하게 다시 찾은 것이다(부분 그림과 같은 자리).
 * 비교(equals)는 같은 객체인지만 본다.
 */
class SelectionOutline(val lines: List<FloatArray>, val region: PixelRect? = null)

/** 선택 도구(캔버스 손가락과 옵션 버튼)가 보내는 일 */
interface SelectionActions {
    /** 모양으로 고른다(설정의 합치는 방법·페더) */
    fun shape(shape: SelectionShape)

    /** 문서 좌표 ([x], [y])의 색과 비슷한 곳을 고른다(자동 선택) */
    fun similar(x: Float, y: Float)

    /** 모두 선택 */
    fun all()

    /** 선택 해제 */
    fun none()

    /** 선택 반전 */
    fun invert()

    /** 고른 레이어에서 고른 곳을 지운다 */
    fun delete()

    /** 고른 곳을 새 레이어로 복사한다([cut]이면 잘라 낸다) */
    fun copyToLayer(cut: Boolean)

    /** 고른 레이어의 고른 곳을 AI(채우기 모델)로 둘레에 맞게 채운다. 선택은 그대로 남는다. */
    fun aiFill() {}

    /** 가장 마지막 AI 채우기에 둘레의 고운 결을 더한다(질감 향상) */
    fun enhanceTexture() {}

    /** 문서 좌표 ([x], [y])를 누른 물체를 AI(분할 모델)가 모양대로 고른다(설정의 합치는 방법, AI 선택) */
    fun objectAt(x: Float, y: Float) {}

    /** AI 선택을 고르면 보이는 그림을 미리 읽어 둔다(처음 누를 때 오래 기다리지 않게) */
    fun prepareObjects() {}

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : SelectionActions {
        override fun shape(shape: SelectionShape) = Unit
        override fun similar(x: Float, y: Float) = Unit
        override fun all() = Unit
        override fun none() = Unit
        override fun invert() = Unit
        override fun delete() = Unit
        override fun copyToLayer(cut: Boolean) = Unit
    }
}

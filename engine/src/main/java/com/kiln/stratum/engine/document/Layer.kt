package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.adjust.Adjustments

import com.kiln.stratum.engine.raster.Raster

/**
 * 레이어를 구별하는 번호. 문서의 [Document.nextLayerId]에서 하나씩 늘려 가며 정한다.
 *
 * 실행 취소한 뒤 새 레이어를 만들면 버려진 레이어의 번호를 다시 쓸 수 있다.
 * 그래서 썸네일·GPU 텍스처 같은 캐시는 이 번호가 아니라 [Raster]·타일 객체를 기준으로 삼는다.
 */
@JvmInline
value class LayerId(val value: Long)

/** 레이어 혼합 모드. 1단계에서는 이 네 가지만 쓴다. */
enum class BlendMode {
    /** 표준 */
    NORMAL,

    /** 곱하기 */
    MULTIPLY,

    /** 스크린 */
    SCREEN,

    /** 오버레이 */
    OVERLAY
}

/** 레이어가 담는 내용. 나중에 텍스트·조정 레이어 같은 종류가 더해진다. */
sealed interface LayerContent

/** 픽셀로 된 레이어 내용. */
data class RasterContent(val raster: Raster) : LayerContent

/**
 * 문서 안의 레이어 하나. 한 번 만들면 바꾸지 않고, 바꿀 때는 copy로 새 레이어를 만든다.
 *
 * @property name 화면에 보이는 이름. 엔진은 문구를 만들지 않으므로 앱이 strings.xml에서 만들어 넘긴다.
 * @property opacity 불투명도. 0(투명)부터 1(불투명)까지.
 * @property adjustments 기본 보정 값(비파괴). 픽셀은 그대로 두고 그릴 때 이 값대로 보정한다.
 * @property gainmap HDR 사진에서 온 레이어의 게인맵(밝기 지도). 픽셀과 함께 다닌다(결정 기록 'HDR'). 없으면 null.
 * @property visible 보이는지(레이어 줄의 눈 버튼). 불투명도와 따로이고, 숨긴 레이어는 그리기·내보내기·병합에서 빠진다(결정 기록 '레이어 보이기').
 */
data class Layer(
    val id: LayerId,
    val name: String,
    val content: LayerContent,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val adjustments: Adjustments = Adjustments.NONE,
    val gainmap: HdrGainmap? = null,
    val visible: Boolean = true
) {
    init {
        require(opacity in 0f..1f) { "Opacity must be within 0..1: $opacity" }
    }

    /** 합친 그림에 보이는지: 숨기지 않았고 불투명도가 0보다 크다. 그리기·내보내기·병합은 이것으로 거른다. */
    val isShown: Boolean
        get() = visible && opacity > 0f
}

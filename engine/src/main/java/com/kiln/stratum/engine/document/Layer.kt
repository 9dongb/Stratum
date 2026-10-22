package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.adjust.Adjustments

import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.text.TextSpec

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
 * @property text 다시 고칠 수 있는 글자 레이어의 글자(사용자 피드백 8차 1-6). 픽셀은 그 글자를 그린 것이고, 픽셀을 바꾸면 지운다([withPixels]).
 *   없으면 보통 픽셀 레이어다.
 * @property liquify 다시 고칠 수 있는 픽셀 유동화의 기록(결정 기록 '얼굴 인식 보정' 3단계). 픽셀은 그 기록을 원래 픽셀에 건 결과이고,
 *   픽셀을 바꾸면 지운다([withPixels], 사용자 결정 "묻지 않고 굳히기"). 없으면 null.
 */
data class Layer(
    val id: LayerId,
    val name: String,
    val content: LayerContent,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val adjustments: Adjustments = Adjustments.NONE,
    val gainmap: HdrGainmap? = null,
    val visible: Boolean = true,
    val text: TextSpec? = null,
    val liquify: LiquifyRecord? = null
) {
    init {
        require(opacity in 0f..1f) { "Opacity must be within 0..1: $opacity" }
    }

    /** 합친 그림에 보이는지: 숨기지 않았고 불투명도가 0보다 크다. 그리기·내보내기·병합은 이것으로 거른다. */
    val isShown: Boolean
        get() = visible && opacity > 0f

    /**
     * 픽셀을 [raster](게인맵은 [gainmap])로 바꾼 레이어. 글자 정보와 유동화 기록은 지운다: 바꾼 픽셀은 더 이상 그 글자를 그린 것도,
     * 그 유동화의 결과도 아니다(사용자 피드백 8차 1-6, 유동화는 사용자 결정 "묻지 않고 굳히기").
     * 레이어 픽셀을 바꾸는 명령은 모두 이것(또는 글자를 다시 그린 [withText], 유동화를 다시 건 [withLiquify])을 쓴다(`LayerPixelsGuardTest`가 지킨다).
     */
    fun withPixels(raster: Raster, gainmap: HdrGainmap? = this.gainmap): Layer =
        copy(content = RasterContent(raster), gainmap = gainmap, text = null, liquify = null)

    /** 글자 [text]를 다시 그린 픽셀 [raster]로 바꾼 글자 레이어(글자 고치기·옮기기). 글자 레이어에는 게인맵·유동화 기록이 없다. */
    fun withText(raster: Raster, text: TextSpec): Layer =
        copy(content = RasterContent(raster), gainmap = null, text = text, liquify = null)

    /** 유동화 기록 [record]를 원래 픽셀에 건 결과 [raster](게인맵은 [gainmap])로 바꾼 레이어(유동화 적용). 글자 정보는 지운다. */
    fun withLiquify(raster: Raster, gainmap: HdrGainmap?, record: LiquifyRecord): Layer =
        copy(content = RasterContent(raster), gainmap = gainmap, text = null, liquify = record)
}

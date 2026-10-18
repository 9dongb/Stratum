package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.raster.Raster
import kotlin.math.ln
import kotlin.math.pow

/**
 * HDR 사진의 게인맵(밝기 지도, 결정 기록 'HDR'). 사진의 각 자리를 HDR 화면에서 얼마나 더 밝힐지 담은 작은 그림이다.
 *
 * 크기가 레이어와 달라도(보통 가로·세로 1/4) 레이어 전체를 늘여 덮는다(안드로이드 Gainmap과 같다).
 * 그래서 문서 크기를 바꿔도 고칠 것이 없고, 돌리기·뒤집기·자르기만 같이 한다.
 * 픽셀은 늘 불투명한 ARGB 타일이다. 1채널 게인맵은 R=G=B인 회색으로 담는다([HdrInfo.singleChannel]).
 * 한 번 만들면 바꾸지 않는다(레이어와 같다).
 */
data class HdrGainmap(val width: Int, val height: Int, val raster: Raster) {
    init {
        require(width > 0 && height > 0) { "Gainmap size must be positive: ${width}x$height" }
    }
}

/** 빨강·초록·파랑마다 다른 값 하나씩(게인맵 값은 채널마다 다를 수 있다) */
data class GainRgb(val r: Float, val g: Float, val b: Float) {
    constructor(all: Float) : this(all, all, all)
}

/** 게인맵을 적용할 색 영역(HDR 쪽 원색). 이름 있는 것만 지닌다. */
enum class HdrPrimaries { SRGB, DISPLAY_P3, BT2020 }

/**
 * 게인맵을 읽는 값(Ultra HDR·ISO 21496-1 메타데이터, 안드로이드 Gainmap과 같은 뜻). 문서에 하나다(문서에는 사진이 하나뿐).
 *
 * 게인맵 값 v(0..1)는 HDR = (SDR + [epsilonSdr]) × exp(mix(ln [ratioMin], ln [ratioMax], v^(1/[gamma]))) − [epsilonHdr]로 읽는다.
 *
 * @property displayRatioForFullHdr 화면이 SDR보다 이만큼 밝을 수 있으면 게인맵을 다 적용한다
 * @property minDisplayRatioForHdrTransition 화면이 이보다 덜 밝으면 적용하지 않는다(그 사이는 조금씩)
 * @property singleChannel 1채널(회색) 게인맵인지. 내보낼 때 1채널로 쓴다.
 * @property alternatePrimaries 게인맵을 적용할 색 영역(안드로이드 16의 대체 색 영역). 없으면 사진의 색 영역이다.
 */
data class HdrInfo(
    val ratioMin: GainRgb,
    val ratioMax: GainRgb,
    val gamma: GainRgb,
    val epsilonSdr: GainRgb,
    val epsilonHdr: GainRgb,
    val displayRatioForFullHdr: Float,
    val minDisplayRatioForHdrTransition: Float,
    val singleChannel: Boolean,
    val alternatePrimaries: HdrPrimaries? = null
) {
    /**
     * '밝히지 않음'(배율 1, HDR = SDR)에 해당하는 게인맵 값(0..1, 채널마다). 게인맵이 없는 레이어·칠한 곳이 이 값이다.
     * 보통 [ratioMin]이 1이라 0이다. 배율이 하나뿐이면(최소 = 최대) 어느 값이든 같아 0으로 둔다.
     */
    val neutral: GainRgb
        get() = GainRgb(neutralOf(ratioMin.r, ratioMax.r, gamma.r), neutralOf(ratioMin.g, ratioMax.g, gamma.g), neutralOf(ratioMin.b, ratioMax.b, gamma.b))

    private fun neutralOf(min: Float, max: Float, gamma: Float): Float {
        val low = ln(min)
        val high = ln(max)
        if (!(high > low) || gamma <= 0f) return 0f
        val recovery = ((0f - low) / (high - low)).coerceIn(0f, 1f)
        return recovery.pow(gamma)
    }
}

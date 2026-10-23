package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.selection.Selection

/**
 * 문서를 그리는 차례(합성 계획, 결정 기록 '피부 보정'의 1단계). 레이어를 합치는 모든 곳(축소본·레이어 축소본 합치기·HDR 게인맵·병합·
 * 내보내기·AI 선택, 스포이드는 축소본 그리기를 거침)이 이 차례를 따른다. 그래서 무엇이 보이는지(숨김·불투명도 0)와 어떤 불투명도로
 * 얹는지가 한곳에서 정해진다. 레이어 마스크·조정 레이어·그룹을 더할 때도 이곳과 그것을 그리는 쪽만 고친다.
 */
object CompositePlan {

    /** [document]를 아래부터 그릴 단계. 보이지 않는 레이어(숨김·불투명도 0)는 빠진다. */
    fun of(document: Document): List<CompositeStep> = of(document.layers)

    /** [layers](아래부터)를 그릴 단계. 병합처럼 문서의 일부만 그릴 때 쓴다. */
    fun of(layers: List<Layer>): List<CompositeStep> =
        layers.mapNotNull { layer ->
            if (!layer.isShown) return@mapNotNull null
            when (val content = layer.content) {
                is RasterContent -> CompositeStep.Draw(layer, content.raster, layer.opacity, layer.mask)
            }
        }

    /** [document]에서 그려지는 레이어들(아래부터). 병합·스포이드처럼 '보이는 레이어'를 다루는 곳이 쓴다. */
    fun shownLayers(document: Document): List<Layer> = of(document).map { it.layer }
}

/** 합성 차례의 한 단계 */
sealed interface CompositeStep {

    /** 이 단계를 만든 레이어 */
    val layer: Layer

    /**
     * 픽셀 레이어 하나를 그 레이어의 보정을 건 채 [opacity]와 혼합 모드로 아래에 얹는다.
     * [opacity]는 얹을 때 쓰는 불투명도다(지금은 레이어 불투명도, 그룹이 생기면 그룹 불투명도를 곱한 값).
     * [mask]가 있으면 보정을 건 색에 그 알파를 곱한다(레이어 마스크, 결정 기록 '피부 보정'의 1c). 마스크 타일이 없는 자리는 그리지 않는다.
     */
    class Draw(override val layer: Layer, val raster: Raster, val opacity: Float, val mask: Selection? = null) : CompositeStep {

        val blendMode: BlendMode
            get() = layer.blendMode

        val adjustments: Adjustments
            get() = layer.adjustments

        /** 타일을 바로 그리지 않고 따로 그린 뒤 얹어야 하는지(불투명도가 1보다 작거나 표준이 아니면) */
        val needsOwnLayer: Boolean
            get() = opacity < 1f || blendMode != BlendMode.NORMAL

        /** 그대로 보이는지(불투명도 1, 보정·마스크 없음). 투명한 바탕 위에서는 혼합 모드와 상관없이 픽셀 그대로다. */
        val isPlain: Boolean
            get() = opacity == 1f && adjustments.isIdentity && mask == null
    }
}

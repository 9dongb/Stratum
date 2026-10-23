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

    /** [document]를 아래부터 그릴 단계. 보이지 않는 레이어(숨김·불투명도 0, 숨기거나 불투명도가 0인 그룹 안)는 빠진다. */
    fun of(document: Document): List<CompositeStep> = of(document.layers)

    /** [layers](아래부터)를 그릴 단계. 병합처럼 문서의 일부만 그릴 때 쓴다(그때 목록에 없는 그룹은 보지 않는다). */
    fun of(layers: List<Layer>): List<CompositeStep> {
        val groups = layers.filter { it.content is GroupContent }.associateBy { it.id }
        return layers.mapNotNull { layer ->
            // 그룹(통과)은 그리지 않고, 안의 레이어에 보이기와 불투명도를 물려준다(결정 기록 '피부 보정'의 1g)
            var visible = layer.visible
            var opacity = layer.opacity
            var parent = layer.parent?.let(groups::get)
            var depth = 0
            while (parent != null && depth++ < layers.size) {
                visible = visible && parent.visible
                opacity *= parent.opacity
                parent = parent.parent?.let(groups::get)
            }
            if (!visible || opacity <= 0f) return@mapNotNull null
            when (val content = layer.content) {
                is RasterContent -> CompositeStep.Draw(layer, content.raster, opacity, layer.mask)
                // 바꾸는 것이 없는 표준 조정 레이어는 그릴 것이 없다(다른 혼합 모드는 보정이 없어도 아래를 자기와 섞어 바꾼다)
                AdjustmentContent ->
                    if (layer.adjustments.isIdentity && layer.blendMode == BlendMode.NORMAL) null else CompositeStep.Adjust(layer, opacity, layer.mask)
                GroupContent -> null
            }
        }
    }

    /** [document]에서 그려지는 레이어들(아래부터). 병합·스포이드처럼 '보이는 레이어'를 다루는 곳이 쓴다. */
    fun shownLayers(document: Document): List<Layer> = of(document).map { it.layer }
}

/** 합성 차례의 한 단계 */
sealed interface CompositeStep {

    /** 이 단계를 만든 레이어 */
    val layer: Layer

    /** 얹는 불투명도: 레이어 불투명도에 감싼 그룹들의 불투명도를 곱한 값(결정 기록 '피부 보정'의 1g) */
    val opacity: Float

    /**
     * 픽셀 레이어 하나를 그 레이어의 보정을 건 채 [opacity]와 혼합 모드로 아래에 얹는다.
     * [opacity]는 얹을 때 쓰는 불투명도다(레이어 불투명도에 감싼 그룹들의 불투명도를 곱한 값, 결정 기록 '피부 보정'의 1g).
     * [mask]가 있으면 보정을 건 색에 그 알파를 곱한다(레이어 마스크, 결정 기록 '피부 보정'의 1c). 마스크 타일이 없는 자리는 그리지 않는다.
     */
    class Draw(override val layer: Layer, val raster: Raster, override val opacity: Float, val mask: Selection? = null) : CompositeStep {

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

    /**
     * 조정 레이어 하나(결정 기록 '피부 보정'의 1e): 그 아래에 합친 그림에 레이어의 보정을 걸고, 혼합 모드로 섞은 색을 [opacity] × [mask]만큼 바꿔 넣는다.
     * 알파는 아래 그대로라 투명한 곳은 그대로다. 그리는 쪽은 아래를 그림으로 만들어 다시 그린다(여러 번 그리기, `AdjustLayerShader`).
     * [mask]가 없으면 모든 곳에 건다. [opacity]는 얹을 때 쓰는 불투명도다(그리기 단계와 같다).
     */
    class Adjust(override val layer: Layer, override val opacity: Float, val mask: Selection? = null) : CompositeStep {

        val blendMode: BlendMode
            get() = layer.blendMode

        val adjustments: Adjustments
            get() = layer.adjustments
    }
}

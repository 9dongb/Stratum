package com.kiln.stratum.engine.document

import java.lang.ref.WeakReference

/**
 * 합친 그림을 정하는 것(그림의 지문): 문서 크기와, 합성 계획([CompositePlan])의 단계마다 픽셀·마스크 객체와 불투명도·혼합 모드·보정.
 * 같은 지문이면 합친 그림이 같다(문서·레이어·타일은 한 번 만들면 바꾸지 않으므로 객체가 같으면 픽셀이 같다).
 * 객체는 약하게 붙잡아, 실행 취소 기록이 버린 타일의 메모리를 지문이 붙들지 않는다(버려졌으면 다른 그림이다).
 *
 * 쓰는 곳: AI 선택이 읽어 둔 특징(같은 그림이면 다시 읽지 않음), 피부 정돈이 만든 바탕(바뀌었으면 '다시 만들기', 결정 기록 '피부 보정'의 4단계).
 */
class CompositeKey private constructor(private val width: Int, private val height: Int, private val steps: List<StepKey>) {

    /** [document]를 합친 그림이 이 지문의 그림과 같은지 */
    fun matches(document: Document): Boolean {
        if (document.width != width || document.height != height) return false
        val plan = CompositePlan.of(document)
        return plan.size == steps.size && plan.indices.all { steps[it].matches(plan[it]) }
    }

    companion object {
        fun of(document: Document) =
            CompositeKey(
                document.width, document.height,
                CompositePlan.of(document).map { step ->
                    when (step) {
                        is CompositeStep.Draw -> DrawKey(step)
                        is CompositeStep.Adjust -> AdjustKey(step)
                    }
                }
            )
    }

    /** 합성 단계 하나의 열쇠 */
    private interface StepKey {
        fun matches(step: CompositeStep): Boolean
    }

    /** 조정 레이어 단계의 열쇠(결정 기록 '피부 보정'의 1e). 마스크 객체는 같은 객체인지만 본다(약하게 붙잡음). */
    private class AdjustKey(step: CompositeStep.Adjust) : StepKey {
        private val mask = WeakReference(step.mask)
        private val opacity = step.opacity
        private val blendMode = step.blendMode
        private val adjustments = step.adjustments
        private val skin = step.skin

        override fun matches(step: CompositeStep): Boolean =
            step is CompositeStep.Adjust && mask.get() === step.mask && step.opacity == opacity &&
                step.blendMode == blendMode && step.adjustments == adjustments && step.skin == skin
    }

    /** 그리는 단계 하나의 열쇠. 픽셀·마스크 객체는 같은 객체인지만 본다(약하게 붙잡음). */
    private class DrawKey(step: CompositeStep.Draw) : StepKey {
        private val pixels = WeakReference(step.raster)
        private val mask = WeakReference(step.mask)
        private val opacity = step.opacity
        private val blendMode = step.blendMode
        private val adjustments = step.adjustments

        override fun matches(step: CompositeStep): Boolean =
            step is CompositeStep.Draw && pixels.get() === step.raster && mask.get() === step.mask && step.opacity == opacity &&
                step.blendMode == blendMode && step.adjustments == adjustments
    }
}

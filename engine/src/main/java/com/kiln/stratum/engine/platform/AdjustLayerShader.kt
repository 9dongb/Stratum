package com.kiln.stratum.engine.platform

import android.graphics.RuntimeShader
import android.graphics.Shader
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.CompositeStep
import com.kiln.stratum.engine.document.DocumentColorSpace

/**
 * 조정 레이어 한 겹을 그리는 셰이더(AGSL, 결정 기록 '피부 보정'의 1e). 아래에 합친 그림 [below]와 그것을 보정한 그림 [adjusted]를 받아,
 * 혼합 모드로 섞은 색을 불투명도 × 마스크만큼 아래 위에 바꿔 넣는다: 결과 = 아래 + (섞은 색 - 아래) × 몫. 알파는 아래 그대로다.
 * 그래서 투명한 곳은 그대로이고(포토샵 조정 레이어와 같다), 반투명한 곳도 덮이지 않는다. 그린 그림을 그대로 바꿔 넣으므로(SRC) 그린다.
 * 섞는 계산은 [com.kiln.stratum.engine.document.BlendMath]와 같다(알파를 곱하지 않은 색, 아래가 바탕).
 * 피부 색 종류면 기본 보정을 건 뒤 피부 색 보정([SkinColorShader])을 건다(결정 기록 '피부 보정'의 3단계). 몫이 0인 곳(마스크 밖)은 보정을 계산하지 않는다.
 */
internal object AdjustLayerShader {

    /**
     * 조정 레이어 [step]을 [below](아래에 합친 그림)에 건 셰이더. [mask]는 같은 자리의 마스크 알파 그림 셰이더(없으면 다 보임).
     * [space]·[originX]·[originY]·[unit]은 피부 색 보정이 쓴다: 문서 색 공간, 그리고 셰이더 자리 (x, y)의 문서 자리
     * ([originX] + x × [unit], [originY] + y × [unit])([SkinColorShader]).
     */
    fun of(
        step: CompositeStep.Adjust,
        below: Shader,
        mask: Shader?,
        space: DocumentColorSpace = DocumentColorSpace.SRGB,
        originX: Float = 0f,
        originY: Float = 0f,
        unit: Float = 1f
    ): Shader {
        val shader = RuntimeShader(SOURCE)
        shader.setInputShader("below", below)
        val basic = AdjustShader.of(step.adjustments)?.wrap(below) ?: below
        val adjusted = step.skin?.let { SkinColorShader.of(it, space, originX, originY, unit, basic) } ?: basic
        shader.setInputShader("adjusted", adjusted)
        shader.setInputShader("mask", mask ?: below)
        shader.setFloatUniform("hasMask", if (mask == null) 0f else 1f)
        shader.setFloatUniform("opacity", step.opacity)
        shader.setFloatUniform("mode", modeCode(step.blendMode))
        return shader
    }

    private fun modeCode(mode: BlendMode): Float =
        when (mode) {
            BlendMode.NORMAL -> 0f
            BlendMode.MULTIPLY -> 1f
            BlendMode.SCREEN -> 2f
            BlendMode.OVERLAY -> 3f
        }

    private const val SOURCE = """
        uniform shader below;
        uniform shader adjusted;
        uniform shader mask;
        uniform float hasMask;
        uniform float opacity;
        uniform float mode;

        half4 main(float2 position) {
            half4 b = below.eval(position);
            float alpha = float(b.a);
            if (alpha <= 0.0) {
                return b;
            }
            float weight = opacity * (hasMask > 0.5 ? float(mask.eval(position).a) : 1.0);
            if (weight <= 0.0) {
                return b;
            }
            half4 s = adjusted.eval(position);
            float3 cb = clamp(float3(b.rgb) / alpha, 0.0, 1.0);
            float3 cs = clamp(float3(s.rgb) / max(float(s.a), 0.00001), 0.0, 1.0);
            float3 mixed = cs;
            if (mode > 0.5 && mode < 1.5) {
                mixed = cb * cs;
            } else if (mode > 1.5 && mode < 2.5) {
                mixed = cb + cs - cb * cs;
            } else if (mode > 2.5) {
                mixed = mix(2.0 * cb * cs, 1.0 - 2.0 * (1.0 - cb) * (1.0 - cs), step(0.5, cb));
            }
            float3 c = mix(cb, mixed, weight);
            return half4(half3(c * alpha), half(alpha));
        }
    """
}

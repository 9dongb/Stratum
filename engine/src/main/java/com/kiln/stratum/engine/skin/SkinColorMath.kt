package com.kiln.stratum.engine.skin

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 피부 색 보정의 계산(결정 기록 '피부 보정'의 3단계, 0단계 맥 시험 colorlib.apply). OKLab에서 밝기 L은 그대로 두고 색(a·b)만 바꾼다.
 * GPU 셰이더(SkinColorShader)가 픽셀마다 같은 식을 쓰고, 이 순수 Kotlin 판은 그 식을 JVM 테스트로 지킨다.
 *
 * - 부분 붉은기([SkinColor.local]): 그 밝기의 보통 피부보다 붉은 만큼(색상이 빨강 쪽, 채도가 넘침)만 기준 쪽으로 당긴다.
 * - 톤 균일화([SkinColor.even]): 기준에서 벗어난 만큼을 줄인다. 아주 먼 색은 피부가 아니라 보고 덜 건다.
 * - 전체 붉은기([SkinColor.global]): 얼굴 전체를 목표(자연스러운 피부색, 스포이드면 고른 색) 쪽으로 옮긴다. 색상 상한([HUE_CAP])과
 *   채도 바닥([CHROMA_FLOOR])이 있어 연두·회색이 되지 않는다.
 * - 기준(href·cref)은 그 사람 피부의 가운데 색상과 그 밝기의 보통 채도다(스포이드로 고르면 그 색).
 */
object SkinColorMath {

    /** 자연스러운 피부색의 색상(측정값 CIELAB a* 10.5–11.8·b* 14.2–17.7 → OKLab 약 48–53°, 맥 시험에서 51°는 올리브빛이라 47°) */
    val TARGET_HUE: Float = Math.toRadians(47.0).toFloat()

    /** 그 채도(OKLab 0.049–0.058) */
    const val TARGET_CHROMA = 0.054f

    /** 이보다 진한 얼굴은 전체 붉은기가 채도도 이만큼까지 줄인다 */
    const val HIGH_CHROMA = 0.075f

    /** 색상 상한(넘으면 노랗게·연두로 보임) */
    val HUE_CAP: Float = Math.toRadians(62.0).toFloat()

    /** 채도 바닥(회색 막기) = 목표 채도의 60% */
    const val CHROMA_FLOOR = TARGET_CHROMA * 0.6f

    /** 톤 균일화를 다 올렸을 때 기준 쪽으로 옮기는 몫 */
    const val EVEN_STRENGTH = 0.7f

    /**
     * 기준 색상에서 이만큼(라디안) 넘게 먼 색은 피부가 아니라 보고 당기기·균일화를 줄이기 시작해 [FOREIGN_HUE_END]에서 그만둔다
     * (Claude 판단: 마스크에 남은 다른 색, 예를 들어 파란 화장·옷 끝을 피부색으로 돌리지 않게).
     */
    const val FOREIGN_HUE_START = 0.9f
    const val FOREIGN_HUE_END = 1.3f

    /** 셰이더가 받는 얼굴 수의 한도(얼굴 찾기와 같다) */
    const val MAX_FACES = 8

    /**
     * 얼굴 하나의 계산 값(슬라이더와 상관없는 것). 픽셀마다 얼굴 무게로 섞는다([blend]).
     *
     * @property referenceHue 기준 색상, [chromaRatio] 기준 채도 / 그 밝기의 보통 채도(자동이면 1)
     * @property globalHue 전체 붉은기를 다 올렸을 때 색상을 옮기는 양, [globalChroma] 그때 채도에 곱하는 수
     */
    class Terms(
        val lightness: Float,
        val chromaBase: Float,
        val chromaSlope: Float,
        val hueSpread: Float,
        val chromaSpread: Float,
        val referenceHue: Float,
        val chromaRatio: Float,
        val globalHue: Float,
        val globalChroma: Float
    ) {
        /** 셰이더에 넘기는 차례 */
        fun values(): FloatArray =
            floatArrayOf(lightness, chromaBase, chromaSlope, hueSpread, chromaSpread, referenceHue, chromaRatio, globalHue, globalChroma)

        companion object {
            /** [values]의 개수 */
            const val COUNT = 9
        }
    }

    /** [face]의 계산 값. 스포이드 기준이 있으면 그 색이 기준이자 전체 붉은기의 목표다. */
    fun terms(face: SkinFaceTone): Terms {
        val tone = face.tone
        val reference = face.reference
        val chroma = max(tone.chroma, 1e-4f)
        val globalHue: Float
        val globalChroma: Float
        if (reference == null) {
            // 붉은 쪽(목표보다 색상이 작음)일 때만 목표 쪽으로, 진하면 채도도 줄인다
            globalHue = max(0f, TARGET_HUE - tone.hue)
            globalChroma = if (tone.chroma > HIGH_CHROMA) HIGH_CHROMA / chroma else 1f
        } else {
            globalHue = SkinTone.wrap(reference.hue - tone.hue)
            globalChroma = reference.chroma / chroma
        }
        return Terms(
            lightness = tone.lightness,
            chromaBase = tone.chromaBase,
            chromaSlope = tone.chromaSlope,
            hueSpread = tone.hueSpread,
            chromaSpread = tone.chromaSpread,
            referenceHue = reference?.hue ?: tone.hue,
            chromaRatio = reference?.let { it.chroma / chroma } ?: 1f,
            globalHue = globalHue,
            globalChroma = globalChroma
        )
    }

    /**
     * 문서 자리 ([x], [y])에서 얼굴 [area]의 무게: 1 / ((거리 / 반지름)⁴ + 0.0001). 가까운 얼굴이 거의 다 가져가고, 얼굴 사이에서 매끈하게 바뀐다.
     * 얼굴이 하나면 어디서나 그 얼굴이다.
     */
    fun weight(area: SkinFaceArea, x: Float, y: Float): Float {
        val dx = (x - area.centerX) / area.radius
        val dy = (y - area.centerY) / area.radius
        val s = dx * dx + dy * dy
        return 1f / (s * s + 1e-4f)
    }

    /** 얼굴들의 계산 값을 [weights](합이 0보다 큼)로 섞은 것 */
    fun blend(terms: List<Terms>, weights: FloatArray): Terms {
        val total = weights.sum()
        val sums = FloatArray(Terms.COUNT)
        for ((i, t) in terms.withIndex()) {
            val values = t.values()
            for (k in sums.indices) sums[k] += values[k] * weights[i]
        }
        for (k in sums.indices) sums[k] /= total
        return Terms(sums[0], sums[1], sums[2], sums[3], sums[4], sums[5], sums[6], sums[7], sums[8])
    }

    /**
     * OKLab 색 ([l], [a], [b])에 피부 색 보정을 건 a·b를 [out]의 0·1에 쓴다(L은 그대로). [local]·[global]·[even]은 슬라이더 값(0..1).
     * 셰이더(SkinColorShader)와 같은 식이다.
     */
    fun apply(l: Float, a: Float, b: Float, terms: Terms, local: Float, global: Float, even: Float, out: FloatArray) {
        val chroma = hypot(a, b)
        val hue = atan2(b, a)
        val expected = max(terms.chromaBase + terms.chromaSlope * (l - terms.lightness), SkinTone.MIN_EXPECTED_CHROMA)
        val cref = expected * terms.chromaRatio
        val href = terms.referenceHue
        val dh = SkinTone.wrap(hue - href)
        val dc = chroma - cref
        // 피부가 아닌 먼 색은 건드리지 않는다
        val native = 1f - smoothstep(FOREIGN_HUE_START, FOREIGN_HUE_END, abs(dh))
        // 부분 붉은기: 보통보다 붉은(색상이 빨강 쪽, 채도가 넘침) 만큼만 기준 쪽으로
        val redness = max(-dh, 0f) / (2f * terms.hueSpread) + max(dc, 0f) / (2f * terms.chromaSpread)
        val pull = local * smoothstep(0.5f, 1.5f, redness) * native
        val h1 = hue - min(dh, 0f) * pull
        val c1 = chroma - max(dc, 0f) * pull
        // 톤 균일화: 기준에서 벗어난 만큼 줄인다(아주 먼 색은 덜)
        val far = hypot(SkinTone.wrap(h1 - href) / (4f * terms.hueSpread), (c1 - cref) / (4f * terms.chromaSpread))
        val k = even * EVEN_STRENGTH * (1f - smoothstep(1f, 2f, far)) * native
        val h2 = h1 + SkinTone.wrap(href - h1) * k
        val c2 = c1 + (cref - c1) * k
        // 전체 붉은기: 얼굴 전체를 목표 쪽으로, 색상 상한·채도 바닥
        val h3 = min(h2 + global * terms.globalHue, max(HUE_CAP, h2))
        val c3 = max(c2 * (1f + global * (terms.globalChroma - 1f)), min(c2, CHROMA_FLOOR))
        out[0] = c3 * cos(h3)
        out[1] = c3 * sin(h3)
    }

    /** GLSL의 smoothstep */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

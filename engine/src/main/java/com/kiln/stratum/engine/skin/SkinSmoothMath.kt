package com.kiln.stratum.engine.skin

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 피부 정돈의 계산(결정 기록 '피부 보정'의 4단계, 0단계 맥 시험 smooth_proto). OKLab에서
 * 결과 = 큰 띠 + 질감 유지 × 고운 결이다.
 *
 * - 고운 결(모공): 원래 − 가우스(σ = U/90, 0.8px 아래로 내리지 않음). 질감 유지만큼 되돌린다.
 * - 큰 띠(윤곽·음영): 마스크 무게(m² + 0.001)로만 평균을 내는, 흐린 밝기 L을 길잡이로 한 가이드 필터(상자 반지름 U/9, ε 0.02²).
 *   마스크 밖 색(머리카락·배경)이 피부로 번지지 않고, 그림자 경계(코 밑·턱선)와 진한 점(주근깨)처럼 뚜렷한 것은 지킨다.
 *   길잡이를 고운 결을 뺀(흐린) 그림으로 한다(맥 시험의 식은 원래 L이라, 뚜렷한 곳에서 큰 띠가 원래 그림이 되어 고운 결을 더하면 오히려 또렷해졌다:
 *   에뮬레이터 실제 사진의 피부 표준 편차가 0.029 → 0.036으로 늘었다).
 * - 그 사이의 가운데 띠(얼룩·요철)는 빠진다. 파운데이션을 바른 것처럼 된다.
 * - 고운 결의 흐림도 마스크 무게로만 평균을 낸다([maskedBlur]). 그냥 흐리면 피부 가장자리(머리선·턱선)에서 바깥 색(어두운 머리카락)이
 *   흐린 색에 섞여, 고운 결(원래 − 흐린 색)이 밝은 띠(헤일로)가 되고 큰 띠의 길잡이도 바깥 쪽으로 끌린다.
 * - 큰 띠의 계수는 줄인 단계에서 이 순수 Kotlin으로 구하고(빠른 가이드 필터, He & Sun), 원본 해상도에서는 GPU가 계수를 쌍선형으로 읽어 길잡이에 건다.
 */
object SkinSmoothMath {

    /** 가이드 필터의 ε(OKLab 밝기의 0.02) */
    const val EPSILON = 0.02f * 0.02f

    /** 마스크 무게의 바닥(마스크가 0인 곳만 있는 상자에서도 나눌 수 있게) */
    const val MASK_FLOOR = 1e-3f

    /** 고운 결의 가우스 σ(문서 픽셀): U/90, 0.8px 아래로 내리지 않는다 */
    fun fineSigma(unit: Float): Float = max(0.8f, unit / 90f)

    /** 큰 띠의 상자 반지름(문서 픽셀): U/9, 3px 아래로 내리지 않는다 */
    fun boxRadius(unit: Float): Float = max(3f, unit / 9f)

    /** 흐린 마스크 무게가 이보다 작으면 흐린 색 대신 원래 색을 쓴다(마스크 밖 먼 곳: 나눌 무게가 거의 없고, 8비트 그림이라 색이 거칠다) */
    const val BLUR_WEIGHT_LOW = 0.1f

    /** 흐린 마스크 무게가 이보다 크면 마스크 무게 평균을 다 쓴다(그 사이는 매끈하게 섞음) */
    const val BLUR_WEIGHT_HIGH = 0.3f

    /** 계수를 구할 줄인 단계: 상자 반지름이 그 단계에서 4~8칸이 되게(작은 얼굴은 원본 그대로, 0) */
    fun level(radius: Float): Int = max(0, floor(ln(radius / 4f) / ln(2f)).toInt())

    /**
     * 줄인 그림(OKLab [lightness]·[a]·[b], 마스크 [mask] 0..1, [width]×[height])에서 큰 띠의 계수를 구한다: 결과는 [lightness] 길잡이에 걸
     * 채널(L·a·b)마다 기울기 A와 절편 B를 상자 평균한 것(여섯 배열: A_L, A_a, A_b, B_L, B_a, B_b). 큰 띠 = A × L + B다.
     * [radius]는 그 단계에서의 상자 반지름(칸)이다.
     */
    fun coefficients(lightness: FloatArray, a: FloatArray, b: FloatArray, mask: FloatArray, width: Int, height: Int, radius: Float): Array<FloatArray> {
        val r = max(1, radius.roundToInt())
        val weight = FloatArray(mask.size) { mask[it] * mask[it] + MASK_FLOOR }
        val total = SkinMaps.box(weight, width, height, r)
        fun weighted(values: FloatArray): FloatArray {
            val sums = SkinMaps.box(FloatArray(values.size) { values[it] * weight[it] }, width, height, r)
            return FloatArray(values.size) { sums[it] / max(total[it], 1e-4f) }
        }
        val meanGuide = weighted(lightness)
        val meanSquare = weighted(FloatArray(lightness.size) { lightness[it] * lightness[it] })
        val variance = FloatArray(lightness.size) { meanSquare[it] - meanGuide[it] * meanGuide[it] }
        val slopes = ArrayList<FloatArray>(3)
        val offsets = ArrayList<FloatArray>(3)
        for (channel in listOf(lightness, a, b)) {
            val mean = weighted(channel)
            val product = weighted(FloatArray(channel.size) { lightness[it] * channel[it] })
            val slope = FloatArray(channel.size) { (product[it] - meanGuide[it] * mean[it]) / (variance[it] + EPSILON) }
            val offset = FloatArray(channel.size) { mean[it] - slope[it] * meanGuide[it] }
            slopes += SkinMaps.box(slope, width, height, r)
            offsets += SkinMaps.box(offset, width, height, r)
        }
        return arrayOf(slopes[0], slopes[1], slopes[2], offsets[0], offsets[1], offsets[2])
    }

    /** 흐린 마스크 무게 [weight](0..1)에서 마스크 무게 평균을 쓰는 몫(0..1, 나머지는 원래 색). GPU 셰이더의 smoothstep과 같다. */
    fun blurShare(weight: Float): Float {
        val t = ((weight - BLUR_WEIGHT_LOW) / (BLUR_WEIGHT_HIGH - BLUR_WEIGHT_LOW)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 고운 결의 흐린 그림(가우스 [sigma] 칸): 채널마다 마스크 [mask] 무게로 평균해 마스크 밖 색이 피부로 번지지 않게 하고,
     * 마스크가 거의 없는 곳은 원래 값 쪽으로 섞는다([blurShare]). GPU([com.kiln.stratum.engine.platform.SkinSmoother])는 알파를 곱한 색 × 마스크를
     * 흐려 알파로 나누는 같은 계산을 한다. 이것은 작은 그림·시험용이다.
     */
    fun maskedBlur(channels: Array<FloatArray>, mask: FloatArray, width: Int, height: Int, sigma: Float): Array<FloatArray> {
        val weight = SkinMaps.gaussian(mask, width, height, sigma)
        return Array(channels.size) { c ->
            val values = channels[c]
            val sums = SkinMaps.gaussian(FloatArray(values.size) { values[it] * mask[it] }, width, height, sigma)
            FloatArray(values.size) { i ->
                val share = blurShare(weight[i])
                if (share <= 0f) values[i] else values[i] + (sums[i] / weight[i] - values[i]) * share
            }
        }
    }

    /** 한 픽셀의 정돈: 큰 띠(계수 [slope]·[offset] × 흐린 L) + [texture] × 고운 결(원래 [lab] − 흐린 [blurred]). GPU 셰이더와 같은 식이다. */
    fun smooth(lab: FloatArray, blurred: FloatArray, slope: FloatArray, offset: FloatArray, texture: Float, out: FloatArray) {
        for (c in 0..2) out[c] = slope[c] * blurred[0] + offset[c] + texture * (lab[c] - blurred[c])
    }
}

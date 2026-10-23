package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 피부 보정의 얼굴 하나의 자리(문서 좌표, 결정 기록 '피부 보정'의 3단계): 얼굴 윤곽을 덮는 원(가운데 [centerX]·[centerY], 반지름 [radius])과
 * 길이 단위 [unit](두 눈꼬리 사이, 0단계 맥 시험의 U). 색 보정이 픽셀마다 어느 얼굴의 통계를 쓸지 고르고([SkinColorMath.weight]),
 * 피부 정돈·잡티 찾기가 얼마나 크게 볼지 정한다. 자르기·회전·크기 변경·옮기기를 하면 함께 옮긴다([mapped]).
 */
data class SkinFaceArea(val centerX: Float, val centerY: Float, val radius: Float, val unit: Float) {

    init {
        require(radius > 0f && unit > 0f && centerX.isFinite() && centerY.isFinite()) { "Broken face area: $this" }
    }

    /** 가운데를 [map](문서 좌표 → 새 문서 좌표)으로 옮기고 길이를 [scale]배 한 자리 */
    fun mapped(scale: Float, map: (Float, Float) -> Pair<Float, Float>): SkinFaceArea {
        val (x, y) = map(centerX, centerY)
        return SkinFaceArea(x, y, radius * scale, unit * scale)
    }

    companion object {
        /** 찾은 얼굴의 자리: 윤곽 점들을 덮는 상자의 가운데와 긴 변의 반, 두 눈꼬리 사이(1px보다 작게 잡지 않음) */
        fun of(face: FaceLandmarks): SkinFaceArea {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (i in FaceMeshIndex.FACE_OVAL) {
                left = min(left, face.x(i))
                top = min(top, face.y(i))
                right = max(right, face.x(i))
                bottom = max(bottom, face.y(i))
            }
            val unit = hypot(
                face.x(FaceMeshIndex.LEFT_EYE_OUTER) - face.x(FaceMeshIndex.RIGHT_EYE_OUTER),
                face.y(FaceMeshIndex.LEFT_EYE_OUTER) - face.y(FaceMeshIndex.RIGHT_EYE_OUTER)
            )
            return SkinFaceArea((left + right) / 2f, (top + bottom) / 2f, (max(right - left, bottom - top) / 2f).coerceAtLeast(1f), unit.coerceAtLeast(1f))
        }
    }
}

/**
 * 얼굴 하나의 피부 색 통계(OKLab, 결정 기록 '피부 보정'의 3단계, 0단계 맥 시험 colorlib.face_stats). 그 사람 자기 피부가 기준이다
 * (정해 둔 이상적인 색이 아니라, Retouch4me·라이트룸처럼).
 *
 * @property lightness 피부의 가운데 밝기 L(마스크 무게로)
 * @property hue 가운데 색상(라디안, a에서 b 쪽으로), [chroma] 가운데 채도
 * @property chromaBase 이 밝기의 보통 채도, [chromaSlope] 밝기에 따른 기울기: 보통 채도 = chromaBase + chromaSlope × (L − lightness)
 * @property hueSpread 색상의 퍼짐(1.4826 × 가운데 절대 편차), [chromaSpread] 보통 채도 직선에서의 채도 퍼짐
 */
data class SkinTone(
    val lightness: Float,
    val hue: Float,
    val chroma: Float,
    val chromaBase: Float,
    val chromaSlope: Float,
    val hueSpread: Float,
    val chromaSpread: Float
) {

    init {
        require(listOf(lightness, hue, chroma, chromaBase, chromaSlope, hueSpread, chromaSpread).all { it.isFinite() }) { "Broken skin tone: $this" }
        require(hueSpread > 0f && chromaSpread > 0f) { "Spreads must be positive: $this" }
    }

    /** 밝기 [l]인 이 사람 피부의 보통 채도(0.01 아래로 내리지 않음) */
    fun expectedChroma(l: Float): Float = max(chromaBase + chromaSlope * (l - lightness), MIN_EXPECTED_CHROMA)

    companion object {
        /** 피부로 보는 마스크 무게(이보다 큰 픽셀만 잰다) */
        const val SKIN_WEIGHT = 0.6f

        /** 잴 피부 픽셀이 이보다 적으면 재지 않는다 */
        const val MIN_SAMPLES = 64

        /** 보통 채도의 바닥(어두운 곳에서 직선이 0 아래로 가지 않게) */
        const val MIN_EXPECTED_CHROMA = 0.01f

        /** 가운데 절대 편차를 표준 편차로 바꾸는 수 */
        private const val MAD_SCALE = 1.4826f

        /** 퍼짐의 바닥(0으로 나누지 않게) */
        private const val MIN_SPREAD = 1e-4f

        /** 보통 채도 직선에서 이만큼(MAD의 배) 넘게 먼 픽셀은 한 번 빼고 다시 맞춘다 */
        private const val FIT_OUTLIER = 3f

        /**
         * OKLab 배열([lightness]·[a]·[b], 같은 크기)에서 마스크 무게 [weights](0..1)가 [SKIN_WEIGHT]보다 큰 피부 픽셀만으로 통계를 낸다.
         * 그런 픽셀이 [MIN_SAMPLES]보다 적으면 null이다. 가운데 값은 무게로, 퍼짐은 무게 없이 잰다(맥 시험과 같다).
         */
        fun measure(lightness: FloatArray, a: FloatArray, b: FloatArray, weights: FloatArray): SkinTone? {
            var count = 0
            for (weight in weights) if (weight > SKIN_WEIGHT) count++
            if (count < MIN_SAMPLES) return null
            val picked = IntArray(count)
            var next = 0
            for (i in weights.indices) if (weights[i] > SKIN_WEIGHT) picked[next++] = i
            val l = FloatArray(picked.size) { lightness[picked[it]] }
            val h = FloatArray(picked.size) { atan2(b[picked[it]], a[picked[it]]) }
            val c = FloatArray(picked.size) { hypot(a[picked[it]], b[picked[it]]) }
            val w = FloatArray(picked.size) { weights[picked[it]] }
            val lm = weightedMedian(l, w)
            val hm = weightedMedian(h, w)
            val cm = weightedMedian(c, w)
            val x = FloatArray(l.size) { l[it] - lm }
            // 보통 채도 = c0 + c1·x(무게 최소 제곱), 바깥 값은 한 번 빼고 다시 맞춘다
            var fit = fit(x, c, w, BooleanArray(x.size) { true })
            val residual = FloatArray(x.size) { abs(c[it] - (fit.first + fit.second * x[it])) }
            val limit = FIT_OUTLIER * MAD_SCALE * median(residual) + 1e-6f
            val keep = BooleanArray(x.size) { residual[it] < limit }
            if (keep.count { it } >= 2) fit = fit(x, c, w, keep)
            val hueSpread = MAD_SCALE * median(FloatArray(h.size) { abs(wrap(h[it] - hm)) }) + MIN_SPREAD
            val chromaSpread = MAD_SCALE * median(FloatArray(c.size) { abs(c[it] - (fit.first + fit.second * x[it])) }) + MIN_SPREAD
            return SkinTone(lm, hm, cm, fit.first, fit.second, hueSpread, chromaSpread)
        }

        /** 각도 [angle]를 −π..π로 */
        fun wrap(angle: Float): Float {
            var value = angle
            while (value > PI) value -= (2 * PI).toFloat()
            while (value < -PI) value += (2 * PI).toFloat()
            return value
        }

        /**
         * 무게 가운데 값: 값으로 늘어놓고 무게를 쌓아 반을 처음 넘는 값(맥 시험 wmedian과 같다). 값과 자리를 긴 정수 하나에 담아
         * 기본형 배열로 늘어놓는다(얼굴 하나에 피부 픽셀이 십만 개 안팎이라 상자에 담으면 느리다).
         */
        private fun weightedMedian(values: FloatArray, weights: FloatArray): Float {
            val keys = LongArray(values.size) { (sortable(values[it]).toLong() shl 32) or it.toLong() }
            keys.sort()
            val half = weights.sum() / 2f
            var sum = 0f
            for (key in keys) {
                val i = key.toInt()
                sum += weights[i]
                if (sum >= half) return values[i]
            }
            return values[keys.last().toInt()]
        }

        /** 실수의 크기 차례를 지키는 정수(음수는 비트를 뒤집는다) */
        private fun sortable(value: Float): Int {
            val bits = java.lang.Float.floatToIntBits(value)
            return if (bits < 0) bits xor 0x7FFFFFFF else bits
        }

        private fun median(values: FloatArray): Float {
            val sorted = values.sortedArray()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
        }

        /** [keep] 픽셀로 c ≈ c0 + c1·x를 무게 w로 맞춘다(행마다 w를 곱한 최소 제곱 = 무게 제곱). 기울기를 정할 수 없으면 c1 = 0. */
        private fun fit(x: FloatArray, c: FloatArray, w: FloatArray, keep: BooleanArray): Pair<Float, Float> {
            var s00 = 0.0
            var s01 = 0.0
            var s11 = 0.0
            var t0 = 0.0
            var t1 = 0.0
            for (i in x.indices) {
                if (!keep[i]) continue
                val ww = w[i].toDouble() * w[i]
                s00 += ww
                s01 += ww * x[i]
                s11 += ww * x[i] * x[i]
                t0 += ww * c[i]
                t1 += ww * x[i] * c[i]
            }
            val det = s00 * s11 - s01 * s01
            if (s00 <= 0.0) return 0f to 0f
            if (abs(det) < 1e-12 * max(1.0, s00 * s11)) return (t0 / s00).toFloat() to 0f
            val c0 = (t0 * s11 - t1 * s01) / det
            val c1 = (s00 * t1 - s01 * t0) / det
            return c0.toFloat() to c1.toFloat()
        }
    }
}

/** 스포이드로 고른 얼굴의 기준 피부색(OKLab 색상 [hue]·채도 [chroma], 결정 기록 '피부 보정'의 3단계). 없으면 그 사람 피부의 가운데 색이 기준이다. */
data class SkinReference(val hue: Float, val chroma: Float) {
    init {
        require(hue.isFinite() && chroma.isFinite() && chroma >= 0f) { "Broken skin reference: $this" }
    }
}

/** 피부 색 보정이 얼굴 하나에 쓰는 것: 자리, 피부 통계, 스포이드로 고른 기준(없으면 자동) */
data class SkinFaceTone(val area: SkinFaceArea, val tone: SkinTone, val reference: SkinReference? = null) {

    /** 자리를 옮긴 것(자르기·회전·크기 변경·옮기기, [SkinFaceArea.mapped]) */
    fun mapped(scale: Float, map: (Float, Float) -> Pair<Float, Float>): SkinFaceTone = copy(area = area.mapped(scale, map))
}

package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.face.FaceMeshIndex
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 사진 전체가 붉은지(붉은 조명·색 틀어짐, 결정 기록 '피부 보정'의 3단계, 0단계 맥 시험 redcast_proto). 그러면 피부만 고치기보다
 * 원본의 색온도·틴트를 먼저 맞추는 것이 자연스러워, 화면이 알린다. 원래 흰색·회색이어야 할 곳의 색으로 본다:
 * 흰자(눈 윤곽 안, 눈동자 밖의 밝은 곳)와 그림 전체의 채도 낮고 밝은 곳.
 */
object SkinRedCast {

    /** 흰자의 채도가 이보다 진하고 색상이 [SCLERA_HUE_MIN]..[SCLERA_HUE_MAX]이면 붉다(맥 시험: 붉은 조명 0.083·21°, 보통 사진 0.017–0.043) */
    const val SCLERA_CHROMA = 0.06f
    val SCLERA_HUE_MIN: Float = Math.toRadians(-40.0).toFloat()
    val SCLERA_HUE_MAX: Float = Math.toRadians(45.0).toFloat()

    /** 채도 낮고 밝은 곳의 채도가 이보다 진하고 색상이 [NEUTRAL_HUE_MIN]..[NEUTRAL_HUE_MAX]이면 붉다(맥 시험: 보통 사진 0.015 아래) */
    const val NEUTRAL_CHROMA = 0.03f
    val NEUTRAL_HUE_MIN: Float = Math.toRadians(-40.0).toFloat()
    val NEUTRAL_HUE_MAX: Float = Math.toRadians(50.0).toFloat()

    /** 눈동자 둘레 네 점의 평균 거리에 곱해 뺄 눈동자 원 */
    const val IRIS_MARGIN = 1.15f

    /** 흰자는 눈 윤곽 안·눈동자 밖에서 밝기가 이 몫(60번째 백분위) 넘는 곳 */
    const val SCLERA_BRIGHT = 0.6f

    /** 중립 곳: 밝기가 이보다 밝고 채도가 그림의 [NEUTRAL_LOW_CHROMA] 백분위보다 낮은 곳 */
    const val NEUTRAL_LIGHTNESS = 0.55f
    const val NEUTRAL_LOW_CHROMA = 0.25f

    /** 흰자로 보기에 픽셀이 이보다 적으면 그 눈은 보지 않는다 */
    private const val MIN_EYE_PIXELS = 10

    /** 흰자·중립 곳의 a·b를 모은 것 */
    class Samples {
        internal val a = ArrayList<Float>()
        internal val b = ArrayList<Float>()

        val size: Int
            get() = a.size

        internal fun add(x: Float, y: Float) {
            a += x
            b += y
        }
    }

    /**
     * 얼굴 정사각형 그림 [crop]([size]², [space] 값)에서 흰자 픽셀의 a·b를 [into]에 더한다. [points]는 그 그림의 특징점(칸 좌표)이다.
     * 눈마다 윤곽 안·눈동자 원 밖에서 밝은 40%만 쓴다.
     */
    fun addSclera(crop: IntArray, size: Int, points: FloatArray, space: OkLab.Space, into: Samples) {
        val lab = FloatArray(3)
        for ((eye, ring) in listOf(FaceMeshIndex.RIGHT_EYE to FaceMeshIndex.RIGHT_IRIS_RING, FaceMeshIndex.LEFT_EYE to FaceMeshIndex.LEFT_IRIS_RING)) {
            val inside = SkinMaps.polygon(points, eye, size, size)
            var cx = 0f
            var cy = 0f
            for (i in ring) {
                cx += points[2 * i]
                cy += points[2 * i + 1]
            }
            cx /= ring.size
            cy /= ring.size
            var radius = 0f
            for (i in ring) radius += hypot(points[2 * i] - cx, points[2 * i + 1] - cy)
            radius = radius / ring.size * IRIS_MARGIN
            val l = ArrayList<Float>()
            val a = ArrayList<Float>()
            val b = ArrayList<Float>()
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val index = y * size + x
                    if (!inside[index] || hypot(x + 0.5f - cx, y + 0.5f - cy) < radius) continue
                    val pixel = crop[index]
                    OkLab.fromRgb(((pixel shr 16) and 0xFF) / 255f, ((pixel shr 8) and 0xFF) / 255f, (pixel and 0xFF) / 255f, lab, space)
                    l += lab[0]
                    a += lab[1]
                    b += lab[2]
                }
            }
            if (l.size <= MIN_EYE_PIXELS) continue
            // 같은 밝기가 많아도(고르게 밝은 흰자) 남게 같은 값까지 넣는다
            val bright = percentile(l.toFloatArray(), SCLERA_BRIGHT)
            for (i in l.indices) if (l[i] >= bright) into.add(a[i], b[i])
        }
    }

    /** 그림 전체를 덮는 픽셀들 [pixels]([space] 값, [inside]가 그림 안인 자리)에서 채도 낮고 밝은 곳의 a·b를 [into]에 더한다 */
    fun addNeutral(pixels: IntArray, inside: BooleanArray, space: OkLab.Space, into: Samples) {
        val (l, a, b) = OkLab.fromPixels(pixels, space)
        val chroma = FloatArray(pixels.size) { hypot(a[it], b[it]) }
        val shown = chroma.indices.filter { inside[it] }
        if (shown.isEmpty()) return
        val low = percentile(FloatArray(shown.size) { chroma[shown[it]] }, NEUTRAL_LOW_CHROMA)
        for (i in shown) if (l[i] > NEUTRAL_LIGHTNESS && chroma[i] < low) into.add(a[i], b[i])
    }

    /** 흰자 [sclera]나 중립 곳 [neutral]의 가운데 색이 붉은지 */
    fun isRedCast(sclera: Samples, neutral: Samples): Boolean =
        isRed(sclera, SCLERA_CHROMA, SCLERA_HUE_MIN, SCLERA_HUE_MAX) || isRed(neutral, NEUTRAL_CHROMA, NEUTRAL_HUE_MIN, NEUTRAL_HUE_MAX)

    private fun isRed(samples: Samples, chroma: Float, hueMin: Float, hueMax: Float): Boolean {
        if (samples.size <= MIN_EYE_PIXELS) return false
        val a = median(samples.a.toFloatArray())
        val b = median(samples.b.toFloatArray())
        val hue = atan2(b, a)
        return hypot(a, b) > chroma && hue in hueMin..hueMax
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    /** [values]의 [fraction] 백분위(늘어놓은 값의 그 자리, 사이는 이음) */
    private fun percentile(values: FloatArray, fraction: Float): Float {
        val sorted = values.sortedArray()
        val position = fraction * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }
}

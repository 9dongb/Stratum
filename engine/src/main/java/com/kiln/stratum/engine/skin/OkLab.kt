package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.document.DocumentColorSpace
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * OKLab 색 공간(Björn Ottosson): 밝기 L(0..1)과 색 a(초록 −, 빨강 +)·b(파랑 −, 노랑 +). 피부 보정의 색 계산이 쓴다(결정 기록 '피부 보정').
 * 0단계 맥 시험(skinlib)과 같은 행렬이다. 입력은 문서 색 공간([Space])의 값(0..1, 전달 곡선은 sRGB와 같음)이다.
 */
object OkLab {

    /** 문서 색 공간마다 선형 빛 ↔ LMS 행렬(줄마다 3개씩 9개) */
    enum class Space(val toLms: FloatArray, val fromLms: FloatArray) {
        SRGB(SRGB_TO_LMS.toFloats(), SRGB_FROM_LMS.toFloats()),
        DISPLAY_P3(P3_TO_LMS.toFloats(), invert(P3_TO_LMS).toFloats());

        companion object {
            fun of(colorSpace: DocumentColorSpace): Space =
                when (colorSpace) {
                    DocumentColorSpace.SRGB -> SRGB
                    DocumentColorSpace.DISPLAY_P3 -> DISPLAY_P3
                }
        }
    }

    /** sRGB 값(0..1)을 선형 빛으로 */
    fun toLinear(value: Float): Float = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /** 선형 빛을 sRGB 값으로(음수는 0) */
    fun toSrgb(value: Float): Float {
        val linear = value.coerceAtLeast(0f)
        return if (linear <= 0.0031308f) linear * 12.92f else (1.055 * linear.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    /** [space] 값(0..1) ([r], [g], [b])의 L·a·b를 [out]에 쓴다 */
    fun fromRgb(r: Float, g: Float, b: Float, out: FloatArray, space: Space = Space.SRGB) {
        val m = space.toLms
        val lr = toLinear(r)
        val lg = toLinear(g)
        val lb = toLinear(b)
        val l = cbrt(m[0] * lr + m[1] * lg + m[2] * lb)
        val mm = cbrt(m[3] * lr + m[4] * lg + m[5] * lb)
        val s = cbrt(m[6] * lr + m[7] * lg + m[8] * lb)
        out[0] = 0.2104542553f * l + 0.7936177850f * mm - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * mm + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * mm - 0.8086757660f * s
    }

    /** L·a·b의 [space] 값(0..1로 자르지 않음)을 [out]에 쓴다 */
    fun toRgb(lightness: Float, a: Float, b: Float, out: FloatArray, space: Space = Space.SRGB) {
        val m = space.fromLms
        val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val mm = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        out[0] = toSrgb(m[0] * l + m[1] * mm + m[2] * s)
        out[1] = toSrgb(m[3] * l + m[4] * mm + m[5] * s)
        out[2] = toSrgb(m[6] * l + m[7] * mm + m[8] * s)
    }

    /** ARGB 픽셀들([space] 값)의 L·a·b(세 배열). 8비트 값의 선형 빛은 표로 찾는다(같은 값이 많아 빠르게). */
    fun fromPixels(pixels: IntArray, space: Space = Space.SRGB): Array<FloatArray> {
        val lightness = FloatArray(pixels.size)
        val a = FloatArray(pixels.size)
        val b = FloatArray(pixels.size)
        val m = space.toLms
        val linear = LINEAR_8BIT
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val lr = linear[(pixel shr 16) and 0xFF]
            val lg = linear[(pixel shr 8) and 0xFF]
            val lb = linear[pixel and 0xFF]
            val l = cbrt(m[0] * lr + m[1] * lg + m[2] * lb)
            val mm = cbrt(m[3] * lr + m[4] * lg + m[5] * lb)
            val s = cbrt(m[6] * lr + m[7] * lg + m[8] * lb)
            lightness[i] = 0.2104542553f * l + 0.7936177850f * mm - 0.0040720468f * s
            a[i] = 1.9779984951f * l - 2.4285922050f * mm + 0.4505937099f * s
            b[i] = 0.0259040371f * l + 0.7827717662f * mm - 0.8086757660f * s
        }
        return arrayOf(lightness, a, b)
    }

    /** 8비트 값(0..255)의 선형 빛 */
    private val LINEAR_8BIT = FloatArray(256) { toLinear(it / 255f) }
}

/** 선형 sRGB → LMS(Ottosson) */
private val SRGB_TO_LMS = doubleArrayOf(
    0.4122214708, 0.5363325363, 0.0514459929,
    0.2119034982, 0.6806995451, 0.1073969566,
    0.0883024619, 0.2817188376, 0.6299787005
)

/** LMS → 선형 sRGB(Ottosson) */
private val SRGB_FROM_LMS = doubleArrayOf(
    4.0767416621, -3.3077115913, 0.2309699292,
    -1.2684380046, 2.6097574011, -0.3413193965,
    -0.0041960863, -0.7034186147, 1.7076147010
)

/** 선형 Display P3 → XYZ(D65, CSS Color 4) */
private val P3_TO_XYZ = doubleArrayOf(
    0.4865709486482162, 0.26566769316909306, 0.1982172852343625,
    0.2289745640697488, 0.6917385218365064, 0.079286914093745,
    0.0, 0.04511338185890264, 1.043944368900976
)

/** XYZ(D65) → 선형 sRGB(CSS Color 4) */
private val XYZ_TO_SRGB = doubleArrayOf(
    3.2409699419045226, -1.537383177570094, -0.4986107602930034,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717559,
    0.05563007969699366, -0.20397695888897652, 1.0569715142428786
)

/** 선형 Display P3 → LMS: P3 → XYZ → sRGB → LMS */
private val P3_TO_LMS = multiply(SRGB_TO_LMS, multiply(XYZ_TO_SRGB, P3_TO_XYZ))

private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray =
    DoubleArray(9) { i ->
        val row = i / 3
        val column = i % 3
        a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
    }

/** 3×3 행렬(줄마다)의 역행렬 */
private fun invert(m: DoubleArray): DoubleArray {
    val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    return doubleArrayOf(
        (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
        (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
        (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det
    )
}

private fun DoubleArray.toFloats(): FloatArray = FloatArray(size) { this[it].toFloat() }

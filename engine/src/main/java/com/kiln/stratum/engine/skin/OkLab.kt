package com.kiln.stratum.engine.skin

import kotlin.math.cbrt
import kotlin.math.pow

/**
 * OKLab 색 공간(Björn Ottosson): 밝기 L(0..1)과 색 a(초록 −, 빨강 +)·b(파랑 −, 노랑 +). 피부 보정의 색 계산이 쓴다(결정 기록 '피부 보정').
 * 0단계 맥 시험(skinlib)과 같은 행렬이고, 입력은 sRGB 값(0..1)이다.
 */
object OkLab {

    /** sRGB 값(0..1)을 선형 빛으로 */
    fun toLinear(value: Float): Float = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /** 선형 빛을 sRGB 값으로(음수는 0) */
    fun toSrgb(value: Float): Float {
        val linear = value.coerceAtLeast(0f)
        return if (linear <= 0.0031308f) linear * 12.92f else (1.055 * linear.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    /** sRGB 값(0..1) ([r], [g], [b])의 L·a·b를 [out]에 쓴다 */
    fun fromRgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr = toLinear(r)
        val lg = toLinear(g)
        val lb = toLinear(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** L·a·b의 sRGB 값(0..1로 자르지 않음)을 [out]에 쓴다 */
    fun toRgb(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        out[0] = toSrgb(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s)
        out[1] = toSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s)
        out[2] = toSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s)
    }

    /** ARGB 픽셀들의 L·a·b(세 배열) */
    fun fromPixels(pixels: IntArray): Array<FloatArray> {
        val lightness = FloatArray(pixels.size)
        val a = FloatArray(pixels.size)
        val b = FloatArray(pixels.size)
        val lab = FloatArray(3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            fromRgb(((pixel shr 16) and 0xFF) / 255f, ((pixel shr 8) and 0xFF) / 255f, (pixel and 0xFF) / 255f, lab)
            lightness[i] = lab[0]
            a[i] = lab[1]
            b[i] = lab[2]
        }
        return arrayOf(lightness, a, b)
    }
}

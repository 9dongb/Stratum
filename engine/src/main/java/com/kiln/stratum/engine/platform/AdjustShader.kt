package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import com.kiln.stratum.engine.adjust.AdjustmentMath
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.ToneCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import kotlin.math.pow

/**
 * 기본 보정을 GPU에서 픽셀마다 계산하는 셰이더(AGSL). 계산은 [AdjustmentMath]와 같다.
 *
 * 그리는 그림(타일·레이어 축소본)의 셰이더를 입력([image])으로 받아 보정한 색을 낸다. 이웃 픽셀을 보지 않으므로
 * 타일마다 따로 걸어도 레이어 전체에 건 것과 같다. 색은 알파를 미리 곱한 값으로 받아, 나눠서 보정하고 다시 곱한다.
 * 비트맵이 sRGB라 셰이더가 받는 색도 sRGB 값이다(선형 빛 변환은 직접 한다).
 */
internal class AdjustShader private constructor(private val shader: RuntimeShader) {

    /** 입력 [image]를 보정한 셰이더. 같은 객체를 다시 쓰므로, 그리기를 기록한 뒤에 다음 입력으로 바꿔도 된다(기록은 그때 값을 담는다). */
    fun wrap(image: Shader): Shader {
        shader.setInputShader("image", image)
        return shader
    }

    companion object {

        /** 곡선 표 텍스처. 같은 곡선(값)은 한 번만 만든다. */
        private val curveTextures = WeakHashMap<ToneCurve, BitmapShader>()

        /** [adjustments]를 거는 셰이더. 보정이 없으면 null(그대로 그린다). */
        fun of(adjustments: Adjustments): AdjustShader? {
            if (adjustments.isIdentity) return null
            val shader = RuntimeShader(SOURCE)
            shader.setFloatUniform("gain", 2f.pow(adjustments.exposure))
            shader.setFloatUniform("temperature", adjustments.temperature)
            shader.setFloatUniform("tint", adjustments.tint)
            shader.setFloatUniform("shadows", adjustments.shadows)
            shader.setFloatUniform("highlights", adjustments.highlights)
            shader.setFloatUniform("gamma", 2f.pow(-adjustments.brightness))
            shader.setFloatUniform("contrast", 1f + adjustments.contrast)
            shader.setFloatUniform("saturation", 1f + adjustments.saturation)
            val curve = adjustments.curve
            shader.setFloatUniform("hasCurve", if (curve.isIdentity) 0f else 1f)
            shader.setInputShader("curve", curveTexture(if (curve.isIdentity) ToneCurve.IDENTITY else curve))
            return AdjustShader(shader)
        }

        /** 곡선 표(256칸)를 한 줄짜리 반정밀도(16비트 실수) 텍스처로. 칸 사이는 직선 필터로 잇는다. */
        @Synchronized
        private fun curveTexture(curve: ToneCurve): BitmapShader =
            curveTextures.getOrPut(curve) {
                val table = curve.table()
                // 색 공간을 sRGB로 정해 둔다. 정하지 않으면 16비트 비트맵은 선형 색 공간이 되어 GPU가 표 값을 바꿔 읽는다.
                val bitmap = Bitmap.createBitmap(table.size, 1, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                val pixels = ByteBuffer.allocate(table.size * 8).order(ByteOrder.nativeOrder())
                val one = Half.toHalf(1f)
                for (value in table) {
                    val half = Half.toHalf(value)
                    pixels.putShort(half).putShort(half).putShort(half).putShort(one)
                }
                pixels.rewind()
                bitmap.copyPixelsFromBuffer(pixels)
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                }
            }

        private const val SOURCE = """
            uniform shader image;
            uniform shader curve;
            uniform float gain;
            uniform float temperature;
            uniform float tint;
            uniform float shadows;
            uniform float highlights;
            uniform float gamma;
            uniform float contrast;
            uniform float saturation;
            uniform float hasCurve;

            float toLinear(float c) {
                return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
            }

            float toSrgb(float c) {
                c = clamp(c, 0.0, 1.0);
                return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
            }

            float luma(float3 c) {
                return dot(c, float3(${AdjustmentMath.LUMA_R}, ${AdjustmentMath.LUMA_G}, ${AdjustmentMath.LUMA_B}));
            }

            float lookUp(float v) {
                return float(curve.eval(float2(clamp(v, 0.0, 1.0) * 255.0 + 0.5, 0.5)).r);
            }

            half4 main(float2 position) {
                half4 color = image.eval(position);
                float alpha = float(color.a);
                if (alpha <= 0.0) {
                    return color;
                }
                float3 c = clamp(float3(color.rgb) / alpha, 0.0, 1.0);
                float shift = ${AdjustmentMath.TEMPERATURE_SHIFT} * temperature;
                float magenta = 1.0 + ${AdjustmentMath.TINT_SHIFT} * tint;
                c = float3(
                    toSrgb(toLinear(c.r) * gain * (1.0 + shift) * magenta),
                    toSrgb(toLinear(c.g) * gain * (1.0 - ${AdjustmentMath.TINT_GREEN_SHIFT} * tint)),
                    toSrgb(toLinear(c.b) * gain * (1.0 - shift) * magenta)
                );
                float l = luma(c);
                float tone = ${AdjustmentMath.TONE_SHIFT} * (shadows * (1.0 - smoothstep(0.0, 0.6, l)) + highlights * smoothstep(0.4, 1.0, l));
                c = clamp(c + tone, 0.0, 1.0);
                c = pow(c, float3(gamma));
                c = clamp((c - 0.5) * contrast + 0.5, 0.0, 1.0);
                if (hasCurve > 0.5) {
                    c = float3(lookUp(c.r), lookUp(c.g), lookUp(c.b));
                }
                float g = luma(c);
                c = clamp(g + (c - g) * saturation, 0.0, 1.0);
                return half4(half3(c * alpha), half(alpha));
            }
        """
    }
}

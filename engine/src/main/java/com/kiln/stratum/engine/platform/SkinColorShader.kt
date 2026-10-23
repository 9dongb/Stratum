package com.kiln.stratum.engine.platform

import android.graphics.RuntimeShader
import android.graphics.Shader
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinColorMath
import com.kiln.stratum.engine.skin.SkinTone

/**
 * 피부 색 보정을 GPU에서 픽셀마다 계산하는 셰이더(AGSL, 결정 기록 '피부 보정'의 3단계). 계산은 [SkinColorMath.apply]와 같다.
 *
 * 입력 그림([image], 아래에 합친 그림)의 색을 문서 색 공간의 OKLab으로 바꿔 밝기 L은 그대로 두고 a·b만 바꾼다. 얼굴마다의 기준은
 * 그 픽셀의 문서 자리에서 가까운 얼굴의 것이다(얼굴 무게로 섞음, [SkinColorMath.weight]). 셰이더 자리(그리는 그림의 픽셀)를
 * 문서 좌표로 옮기는 것은 [origin] + 자리 × [unit]이다(축소본·묶음마다 다르다). 이웃 픽셀을 보지 않아 묶음마다 걸어도 같다.
 * 걸 곳(피부 마스크)과 세기(불투명도)는 조정 레이어 셰이더([AdjustLayerShader])가 정한다.
 */
internal object SkinColorShader {

    /**
     * [image]에 [color]를 건 셰이더. 바꾸는 것이 없으면 null(그대로 그린다). [space]는 문서 색 공간이다.
     * 셰이더 자리 (x, y)는 문서 자리 ([originX] + x × [unit], [originY] + y × [unit])다.
     */
    fun of(color: SkinColor, space: DocumentColorSpace, originX: Float, originY: Float, unit: Float, image: Shader): Shader? {
        if (color.isIdentity) return null
        val faces = color.faces.take(SkinColorMath.MAX_FACES)
        val terms = faces.map(SkinColorMath::terms)
        val shader = RuntimeShader(SOURCE)
        shader.setInputShader("image", image)
        fun array(value: (Int) -> Float) = FloatArray(SkinColorMath.MAX_FACES) { if (it < faces.size) value(it) else 0f }
        shader.setFloatUniform("faceCount", faces.size.toFloat())
        shader.setFloatUniform("centerX", array { faces[it].area.centerX })
        shader.setFloatUniform("centerY", array { faces[it].area.centerY })
        shader.setFloatUniform("radius", array { faces[it].area.radius })
        shader.setFloatUniform("lightness", array { terms[it].lightness })
        shader.setFloatUniform("chromaBase", array { terms[it].chromaBase })
        shader.setFloatUniform("chromaSlope", array { terms[it].chromaSlope })
        shader.setFloatUniform("hueSpread", array { terms[it].hueSpread })
        shader.setFloatUniform("chromaSpread", array { terms[it].chromaSpread })
        shader.setFloatUniform("referenceHue", array { terms[it].referenceHue })
        shader.setFloatUniform("chromaRatio", array { terms[it].chromaRatio })
        shader.setFloatUniform("globalHue", array { terms[it].globalHue })
        shader.setFloatUniform("globalChroma", array { terms[it].globalChroma })
        shader.setFloatUniform("sliders", color.local, color.global, color.even)
        val lab = OkLab.Space.of(space)
        shader.setFloatUniform("toLms", columns(lab.toLms))
        shader.setFloatUniform("fromLms", columns(lab.fromLms))
        shader.setFloatUniform("origin", originX, originY)
        shader.setFloatUniform("unit", unit)
        return shader
    }

    /** 줄마다 적은 3×3 행렬을 셰이더 행렬의 차례(열마다)로 */
    private fun columns(rows: FloatArray): FloatArray = FloatArray(9) { rows[(it % 3) * 3 + it / 3] }

    private val SOURCE = """
        uniform shader image;
        uniform float faceCount;
        uniform float centerX[${SkinColorMath.MAX_FACES}];
        uniform float centerY[${SkinColorMath.MAX_FACES}];
        uniform float radius[${SkinColorMath.MAX_FACES}];
        uniform float lightness[${SkinColorMath.MAX_FACES}];
        uniform float chromaBase[${SkinColorMath.MAX_FACES}];
        uniform float chromaSlope[${SkinColorMath.MAX_FACES}];
        uniform float hueSpread[${SkinColorMath.MAX_FACES}];
        uniform float chromaSpread[${SkinColorMath.MAX_FACES}];
        uniform float referenceHue[${SkinColorMath.MAX_FACES}];
        uniform float chromaRatio[${SkinColorMath.MAX_FACES}];
        uniform float globalHue[${SkinColorMath.MAX_FACES}];
        uniform float globalChroma[${SkinColorMath.MAX_FACES}];
        uniform float3 sliders;
        uniform float3x3 toLms;
        uniform float3x3 fromLms;
        uniform float2 origin;
        uniform float unit;

        const float PI = 3.14159265358979;

        float toLinear(float c) {
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
        }

        float toSrgb(float c) {
            c = clamp(c, 0.0, 1.0);
            return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
        }

        float wrapAngle(float a) {
            return a - 2.0 * PI * floor((a + PI) / (2.0 * PI));
        }

        half4 main(float2 position) {
            half4 color = image.eval(position);
            float alpha = float(color.a);
            if (alpha <= 0.0) {
                return color;
            }
            float3 c = clamp(float3(color.rgb) / alpha, 0.0, 1.0);
            float3 lms = toLms * float3(toLinear(c.r), toLinear(c.g), toLinear(c.b));
            lms = pow(max(lms, float3(0.0)), float3(1.0 / 3.0));
            float l = 0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z;
            float a = 1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z;
            float b = 0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z;

            // 이 문서 자리의 얼굴 무게로 섞은 계산 값
            float2 doc = origin + position * unit;
            float total = 0.0;
            float tLightness = 0.0;
            float tBase = 0.0;
            float tSlope = 0.0;
            float tHueSpread = 0.0;
            float tChromaSpread = 0.0;
            float tHue = 0.0;
            float tRatio = 0.0;
            float tGlobalHue = 0.0;
            float tGlobalChroma = 0.0;
            for (int i = 0; i < ${SkinColorMath.MAX_FACES}; i++) {
                if (float(i) >= faceCount) {
                    break;
                }
                float2 d = (doc - float2(centerX[i], centerY[i])) / radius[i];
                float s = dot(d, d);
                float w = 1.0 / (s * s + 0.0001);
                total += w;
                tLightness += w * lightness[i];
                tBase += w * chromaBase[i];
                tSlope += w * chromaSlope[i];
                tHueSpread += w * hueSpread[i];
                tChromaSpread += w * chromaSpread[i];
                tHue += w * referenceHue[i];
                tRatio += w * chromaRatio[i];
                tGlobalHue += w * globalHue[i];
                tGlobalChroma += w * globalChroma[i];
            }
            if (total <= 0.0) {
                return color;
            }
            tLightness /= total;
            tBase /= total;
            tSlope /= total;
            tHueSpread /= total;
            tChromaSpread /= total;
            tHue /= total;
            tRatio /= total;
            tGlobalHue /= total;
            tGlobalChroma /= total;

            float chroma = length(float2(a, b));
            float hue = atan(b, a);
            float expected = max(tBase + tSlope * (l - tLightness), ${SkinTone.MIN_EXPECTED_CHROMA});
            float cref = expected * tRatio;
            float dh = wrapAngle(hue - tHue);
            float dc = chroma - cref;
            float skinLike = 1.0 - smoothstep(${SkinColorMath.FOREIGN_HUE_START}, ${SkinColorMath.FOREIGN_HUE_END}, abs(dh));
            // 부분 붉은기
            float redness = max(-dh, 0.0) / (2.0 * tHueSpread) + max(dc, 0.0) / (2.0 * tChromaSpread);
            float pull = sliders.x * smoothstep(0.5, 1.5, redness) * skinLike;
            float h1 = hue - min(dh, 0.0) * pull;
            float c1 = chroma - max(dc, 0.0) * pull;
            // 톤 균일화
            float offTone = length(float2(wrapAngle(h1 - tHue) / (4.0 * tHueSpread), (c1 - cref) / (4.0 * tChromaSpread)));
            float k = sliders.z * ${SkinColorMath.EVEN_STRENGTH} * (1.0 - smoothstep(1.0, 2.0, offTone)) * skinLike;
            float h2 = h1 + wrapAngle(tHue - h1) * k;
            float c2 = c1 + (cref - c1) * k;
            // 전체 붉은기
            float h3 = min(h2 + sliders.y * tGlobalHue, max(${SkinColorMath.HUE_CAP}, h2));
            float c3 = max(c2 * (1.0 + sliders.y * (tGlobalChroma - 1.0)), min(c2, ${SkinColorMath.CHROMA_FLOOR}));
            float a2 = c3 * cos(h3);
            float b2 = c3 * sin(h3);

            float lp = l + 0.3963377774 * a2 + 0.2158037573 * b2;
            float mp = l - 0.1055613458 * a2 - 0.0638541728 * b2;
            float sp = l - 0.0894841775 * a2 - 1.2914855480 * b2;
            float3 rgb = fromLms * float3(lp * lp * lp, mp * mp * mp, sp * sp * sp);
            float3 result = float3(toSrgb(rgb.r), toSrgb(rgb.g), toSrgb(rgb.b));
            return half4(half3(result * alpha), half(alpha));
        }
    """
}

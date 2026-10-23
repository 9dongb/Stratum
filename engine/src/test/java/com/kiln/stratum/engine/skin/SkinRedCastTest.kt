package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceModel
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사진 전체가 붉은지(결정 기록 '피부 보정'의 3단계): 흰자·채도 낮은 밝은 곳의 가운데 색 */
class SkinRedCastTest {

    private val size = 64

    /** OKLab 색(밝기 [l], 색상 [hueDegrees], 채도 [chroma])의 sRGB 픽셀 */
    private fun pixel(l: Float, hueDegrees: Double, chroma: Float): Int {
        val h = Math.toRadians(hueDegrees).toFloat()
        val rgb = FloatArray(3)
        OkLab.toRgb(l, chroma * cos(h), chroma * sin(h), rgb)
        val r = (rgb[0].coerceIn(0f, 1f) * 255).roundToInt()
        val g = (rgb[1].coerceIn(0f, 1f) * 255).roundToInt()
        val b = (rgb[2].coerceIn(0f, 1f) * 255).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 두 눈(윤곽은 가로 20칸 타원, 눈동자는 가운데 반지름 3칸)이 있는 얼굴 그림의 점들과, 흰자 색이 [white]인 그림 */
    private fun eyes(white: Int): Pair<IntArray, FloatArray> {
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 2)
        fun eye(contour: IntArray, ring: IntArray, cx: Float, cy: Float) {
            contour.forEachIndexed { k, index ->
                val t = 2 * Math.PI * k / contour.size
                points[2 * index] = (cx + 10 * cos(t)).toFloat()
                points[2 * index + 1] = (cy + 5 * sin(t)).toFloat()
            }
            ring.forEachIndexed { k, index ->
                val t = 2 * Math.PI * k / ring.size
                points[2 * index] = (cx + 3 * cos(t)).toFloat()
                points[2 * index + 1] = (cy + 3 * sin(t)).toFloat()
            }
        }
        eye(FaceMeshIndex.RIGHT_EYE, FaceMeshIndex.RIGHT_IRIS_RING, 20f, 30f)
        eye(FaceMeshIndex.LEFT_EYE, FaceMeshIndex.LEFT_IRIS_RING, 44f, 30f)
        val dark = pixel(0.25f, 50.0, 0.02f)
        val crop = IntArray(size * size) { i ->
            val x = i % size + 0.5f
            val y = i / size + 0.5f
            // 눈동자(가운데)는 어둡고, 흰자는 [white]이고, 둘레는 피부
            val inIris = listOf(20f, 44f).any { cx -> (x - cx) * (x - cx) + (y - 30f) * (y - 30f) < 9f }
            val inEye = listOf(20f, 44f).any { cx -> ((x - cx) / 10f).let { it * it } + ((y - 30f) / 5f).let { it * it } < 1f }
            when {
                inIris -> dark
                inEye -> white
                else -> pixel(0.7f, 50.0, 0.055f)
            }
        }
        return crop to points
    }

    private fun scleraOf(white: Int): SkinRedCast.Samples {
        val (crop, points) = eyes(white)
        return SkinRedCast.Samples().also { SkinRedCast.addSclera(crop, size, points, OkLab.Space.SRGB, it) }
    }

    private fun neutralOf(color: Int, inside: Boolean = true): SkinRedCast.Samples {
        // 4분의 1은 채도가 낮은 [color], 나머지는 진한 피부
        val pixels = IntArray(size * size) { if (it % 4 == 0) color else pixel(0.7f, 50.0, 0.09f) }
        return SkinRedCast.Samples().also { SkinRedCast.addNeutral(pixels, BooleanArray(pixels.size) { inside }, OkLab.Space.SRGB, it) }
    }

    @Test
    fun whiteEyes_andGrayWalls_areNotRed() {
        val sclera = scleraOf(pixel(0.9f, 90.0, 0.01f))
        val neutral = neutralOf(pixel(0.8f, 250.0, 0.005f))

        assertTrue("sclera ${sclera.size}", sclera.size > 20)
        assertFalse(SkinRedCast.isRedCast(sclera, neutral))
    }

    @Test
    fun pinkEyes_meanARedCast() {
        // 붉은 조명: 흰자가 분홍(맥 시험의 해군 붉은 조명 채도 0.083·색상 21°)
        assertTrue(SkinRedCast.isRedCast(scleraOf(pixel(0.85f, 21.0, 0.083f)), neutralOf(pixel(0.8f, 250.0, 0.005f))))
        // 흰자가 노랗거나(색상 80°) 흐린 분홍(0.04)이면 아니다
        assertFalse(SkinRedCast.isRedCast(scleraOf(pixel(0.85f, 80.0, 0.083f)), SkinRedCast.Samples()))
        assertFalse(SkinRedCast.isRedCast(scleraOf(pixel(0.85f, 21.0, 0.04f)), SkinRedCast.Samples()))
    }

    @Test
    fun pinkishNeutralPlaces_meanARedCast_butOnlyInsideThePicture() {
        val pink = pixel(0.8f, 10.0, 0.045f)

        assertTrue(SkinRedCast.isRedCast(SkinRedCast.Samples(), neutralOf(pink)))
        // 그림 밖(검정 칸)만 있으면 보지 않는다
        val outside = neutralOf(pink, inside = false)
        assertEquals(0, outside.size)
        assertFalse(SkinRedCast.isRedCast(SkinRedCast.Samples(), outside))
    }
}

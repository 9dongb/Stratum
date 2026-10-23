package com.kiln.stratum.engine.skin

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 피부 색 통계와 계산(결정 기록 '피부 보정'의 3단계): 기준은 자기 피부, 붉은 곳만, 회색·연두가 되지 않음, 밝기는 그대로 */
class SkinColorMathTest {

    private fun degrees(value: Double): Float = Math.toRadians(value).toFloat()

    /** 색상 [hueDegrees]·채도 [chroma]의 a·b */
    private fun ab(hueDegrees: Double, chroma: Float): Pair<Float, Float> {
        val h = degrees(hueDegrees)
        return chroma * cos(h) to chroma * sin(h)
    }

    private val area = SkinFaceArea(100f, 100f, 50f, 40f)

    /** 색상 50°, 채도 0.055(밝기와 상관없음), 색상 퍼짐 0.05 라디안, 채도 퍼짐 0.006인 피부 */
    private val tone = SkinTone(0.7f, degrees(50.0), 0.055f, 0.055f, 0f, 0.05f, 0.006f)

    private fun applied(hueDegrees: Double, chroma: Float, face: SkinFaceTone, local: Float = 0f, global: Float = 0f, even: Float = 0f): Pair<Float, Float> {
        val (a, b) = ab(hueDegrees, chroma)
        val out = FloatArray(2)
        SkinColorMath.apply(0.7f, a, b, SkinColorMath.terms(face), local, global, even, out)
        return Math.toDegrees(atan2(out[1], out[0]).toDouble()).toFloat() to hypot(out[0], out[1])
    }

    @Test
    fun measure_findsTheSkinsOwnColor_andIgnoresRedSpotsAndUnmaskedPixels() {
        val count = 4000
        val l = FloatArray(count)
        val a = FloatArray(count)
        val b = FloatArray(count)
        val w = FloatArray(count)
        val random = java.util.Random(7)
        for (i in 0 until count) {
            val lightness = 0.6f + 0.2f * random.nextFloat()
            val red = i % 10 == 0
            val hue = if (red) 20.0 else 50.0 + random.nextGaussian() * 2.0
            // 보통 채도는 밝을수록 진하다(0.05 + 0.1 × (L − 0.7)), 붉은 점은 진하다
            val chroma = if (red) 0.12f else 0.05f + 0.1f * (lightness - 0.7f) + 0.002f * random.nextGaussian().toFloat()
            val (x, y) = ab(hue, chroma)
            l[i] = lightness
            a[i] = x
            b[i] = y
            // 마스크 밖(무게 0.5)의 픽셀은 재지 않는다
            w[i] = if (i % 7 == 3) 0.5f else 1f
        }
        // 마스크 밖 픽셀을 엉뚱한 색으로 바꿔도 같다
        for (i in 0 until count) if (w[i] < 0.6f) {
            a[i] = -0.2f
            b[i] = -0.2f
        }

        val tone = checkNotNull(SkinTone.measure(l, a, b, w))

        assertEquals(0.7f, tone.lightness, 0.01f)
        assertEquals(50.0, Math.toDegrees(tone.hue.toDouble()), 1.0)
        assertEquals(0.05f, tone.chromaBase, 0.002f)
        assertEquals(0.1f, tone.chromaSlope, 0.02f)
        assertEquals(0.05f, tone.expectedChroma(0.7f), 0.002f)
        // 퍼짐은 보통 피부의 것(붉은 점에 끌려 크게 늘지 않음)
        assertTrue("hue spread ${tone.hueSpread}", tone.hueSpread in degrees(1.0)..degrees(4.0))
        assertTrue("chroma spread ${tone.chromaSpread}", tone.chromaSpread in 0.001f..0.004f)
    }

    @Test
    fun measure_needsEnoughSkin() {
        val l = FloatArray(63) { 0.7f }
        val a = FloatArray(63) { 0.035f }
        val b = FloatArray(63) { 0.04f }

        assertNull(SkinTone.measure(l, a, b, FloatArray(63) { 1f }))
        assertNotNull(SkinTone.measure(l + 0.7f, a + 0.035f, b + 0.04f, FloatArray(64) { 1f }))
    }

    @Test
    fun allZero_leavesTheColor_andTheColorIsIdentity() {
        val face = SkinFaceTone(area, tone)
        for (hue in listOf(10.0, 50.0, 120.0, -100.0)) {
            val (h, c) = applied(hue, 0.08f, face)
            assertEquals(hue, h.toDouble(), 1e-3)
            assertEquals(0.08f, c, 1e-5f)
        }
        assertTrue(SkinColor(faces = listOf(face)).isIdentity)
        assertTrue(SkinColor(local = 1f).isIdentity)
    }

    @Test
    fun local_pullsOnlyRedderPixels_towardTheOwnSkin() {
        val face = SkinFaceTone(area, tone)

        // 붉은 곳(색상 30°, 채도 0.08)은 색상이 피부 쪽으로 오르고 채도가 준다
        val (h, c) = applied(30.0, 0.08f, face, local = 1f)
        assertEquals(50.0, h.toDouble(), 1.0)
        assertEquals(0.055f, c, 0.002f)
        // 반만 올리면 반쯤
        val (halfH, _) = applied(30.0, 0.08f, face, local = 0.5f)
        assertEquals(40.0, halfH.toDouble(), 1.0)
        // 보통 피부와 노란 쪽(색상이 큼)은 그대로다
        val (same, sameC) = applied(50.0, 0.055f, face, local = 1f)
        assertEquals(50.0, same.toDouble(), 1e-3)
        assertEquals(0.055f, sameC, 1e-5f)
        val (yellow, _) = applied(56.0, 0.055f, face, local = 1f)
        assertEquals(56.0, yellow.toDouble(), 1e-3)
    }

    @Test
    fun foreignColors_areLeftAlone() {
        val face = SkinFaceTone(area, tone)

        // 파란 화장·옷 끝처럼 피부와 아주 먼 색은 붉은기·균일화를 걸지 않는다
        val (h, c) = applied(-100.0, 0.1f, face, local = 1f, even = 1f)
        assertEquals(-100.0, h.toDouble(), 1e-3)
        assertEquals(0.1f, c, 1e-5f)
    }

    @Test
    fun even_movesNearbyBlotchesTowardTheReference_butNotFarColors() {
        val face = SkinFaceTone(area, tone)

        // 조금 노란 얼룩(색상 54°, 채도 0.05)은 기준 쪽으로 70%
        val (h, c) = applied(54.0, 0.05f, face, even = 1f)
        assertEquals(54.0 - 4.0 * 0.7, h.toDouble(), 0.2)
        assertEquals(0.05f + 0.005f * 0.7f, c, 0.0005f)
        // 기준에서 멀면(퍼짐의 8배 넘게) 피부가 아니라 보고 그대로
        val (far, _) = applied(50.0 + Math.toDegrees(0.05 * 9), 0.055f, face, even = 1f)
        assertEquals(50.0 + Math.toDegrees(0.05 * 9), far.toDouble(), 1e-3)
    }

    @Test
    fun global_movesARedFaceTowardNaturalSkin_withinTheCapAndFloor() {
        // 달아오른 얼굴: 색상 35°, 채도 0.09
        val red = SkinFaceTone(area, tone.copy(hue = degrees(35.0), chroma = 0.09f, chromaBase = 0.09f))

        val (h, c) = applied(35.0, 0.09f, red, global = 1f)
        assertEquals(47.0, h.toDouble(), 0.1)
        assertEquals(SkinColorMath.HIGH_CHROMA, c, 1e-4f)
        // 흐린 픽셀은 채도 바닥보다 흐려지지 않는다
        val (_, faint) = applied(35.0, 0.03f, red, global = 1f)
        assertEquals(0.03f, faint, 1e-5f)
        // 이미 노란 얼굴은 더 노랗게 옮기지 않는다
        val yellow = SkinFaceTone(area, tone.copy(hue = degrees(55.0)))
        val (same, _) = applied(55.0, 0.055f, yellow, global = 1f)
        assertEquals(55.0, same.toDouble(), 1e-3)
    }

    @Test
    fun aPickedReference_isTheTarget_butNeverPastTheHueCap() {
        val face = SkinFaceTone(area, tone, SkinReference(degrees(45.0), 0.06f))

        val (h, c) = applied(50.0, 0.055f, face, global = 1f)
        assertEquals(45.0, h.toDouble(), 0.1)
        assertEquals(0.06f, c, 1e-4f)
        // 아주 노란 기준이라도 색상 상한(62°)을 넘기지 않는다
        val olive = SkinFaceTone(area, tone, SkinReference(degrees(75.0), 0.055f))
        val (capped, _) = applied(50.0, 0.055f, olive, global = 1f)
        assertEquals(62.0, capped.toDouble(), 0.1)
    }

    @Test
    fun faceWeights_pickTheNearFace_andBlendBetween() {
        val left = SkinFaceTone(SkinFaceArea(100f, 100f, 50f, 40f), tone)
        val right = SkinFaceTone(SkinFaceArea(400f, 100f, 50f, 40f), tone.copy(hue = degrees(30.0)))
        val terms = listOf(SkinColorMath.terms(left), SkinColorMath.terms(right))

        fun hueAt(x: Float): Double {
            val weights = floatArrayOf(SkinColorMath.weight(left.area, x, 100f), SkinColorMath.weight(right.area, x, 100f))
            return Math.toDegrees(SkinColorMath.blend(terms, weights).referenceHue.toDouble())
        }

        assertEquals(50.0, hueAt(110f), 0.01)
        assertEquals(30.0, hueAt(390f), 0.01)
        assertEquals(40.0, hueAt(250f), 0.01)
    }

    @Test
    fun theEyedropperReference_goesToTheNearFace_onlyForSkinColors() {
        // 결정 기록 '피부 보정'의 3b(6단계 검토에서 엔진으로 옮김): 고른 색이 피부 색이면 가까운 얼굴의 기준, 아니면 null
        val far = SkinFaceArea(400f, 100f, 50f, 40f)
        val color = SkinColor(local = 0.5f, faces = listOf(SkinFaceTone(area, tone), SkinFaceTone(far, tone)))
        val skinColor = (0xFF shl 24) or (222 shl 16) or (172 shl 8) or 142
        val blue = (0xFF shl 24) or (40 shl 16) or (90 shl 8) or 220

        val picked = checkNotNull(color.withReferenceFrom(390f, 110f, skinColor))

        assertEquals(null, picked.faces[0].reference)
        val reference = checkNotNull(picked.faces[1].reference)
        assertEquals(0.5f, picked.local, 0f)
        assertTrue("hue ${Math.toDegrees(reference.hue.toDouble())}", Math.toDegrees(reference.hue.toDouble()) in 40.0..75.0)
        assertEquals(null, color.withReferenceFrom(390f, 110f, blue))
        assertEquals(null, SkinColor().withReferenceFrom(0f, 0f, skinColor))
        // 보이는 기준색: 고른 얼굴은 고른 색상, 다른 얼굴은 자기 피부의 가운데 색
        assertEquals(0, color.nearestFace(110f, 90f))
        val shown = checkNotNull(color.referenceColorAt(110f, 90f))
        assertEquals(0xFF, shown ushr 24)
        assertTrue("red ${(shown shr 16) and 0xFF} > blue ${shown and 0xFF}", ((shown shr 16) and 0xFF) > (shown and 0xFF))
    }

    @Test
    fun okLab_roundTripsBothColorSpaces_andGrayHasNoColor() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for (space in OkLab.Space.entries) {
            for (color in listOf(floatArrayOf(0.8f, 0.6f, 0.5f), floatArrayOf(0.2f, 0.7f, 0.3f), floatArrayOf(0.5f, 0.5f, 0.5f))) {
                OkLab.fromRgb(color[0], color[1], color[2], lab, space)
                OkLab.toRgb(lab[0], lab[1], lab[2], rgb, space)
                for (k in 0..2) assertEquals("$space ${color.toList()}", color[k], rgb[k], 1e-4f)
            }
            OkLab.fromRgb(0.5f, 0.5f, 0.5f, lab, space)
            assertEquals(0f, lab[1], 1e-4f)
            assertEquals(0f, lab[2], 1e-4f)
        }
        // 같은 값이면 P3가 더 진한 색이다
        OkLab.fromRgb(1f, 0f, 0f, lab, OkLab.Space.SRGB)
        val srgbChroma = hypot(lab[1], lab[2])
        OkLab.fromRgb(1f, 0f, 0f, lab, OkLab.Space.DISPLAY_P3)
        assertTrue(hypot(lab[1], lab[2]) > srgbChroma)
    }
}

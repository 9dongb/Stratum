package com.kiln.stratum.engine.adjust

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {

    private fun apply(adjustments: Adjustments, r: Float, g: Float, b: Float): FloatArray =
        AdjustmentMath.apply(adjustments, r, g, b, adjustments.curve.takeUnless { it.isIdentity }?.table())

    @Test
    fun none_changesNothing() {
        for (color in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.2f, 0.5f, 0.9f), floatArrayOf(0.03f, 0.7f, 0.31f))) {
            assertArrayEquals(color, apply(Adjustments.NONE, color[0], color[1], color[2]), 1e-5f)
        }
        assertTrue(Adjustments.NONE.isIdentity)
    }

    @Test
    fun exposure_multipliesLinearLight() {
        val brighter = apply(Adjustments(exposure = 1f), 0.5f, 0.5f, 0.5f)
        // sRGB 0.5는 선형 약 0.214, 두 배인 0.428은 sRGB 약 0.686이다
        assertEquals(0.686f, brighter[0], 0.002f)
        assertEquals(AdjustmentMath.toSrgb(2 * AdjustmentMath.toLinear(0.5f)), brighter[1], 1e-5f)
        // 흰색은 넘치지 않는다
        assertEquals(1f, apply(Adjustments(exposure = 2f), 1f, 1f, 1f)[2], 1e-5f)
    }

    @Test
    fun saturation_minusOne_isGray() {
        val gray = apply(Adjustments(saturation = -1f), 0.9f, 0.2f, 0.4f)

        assertEquals(gray[0], gray[1], 1e-5f)
        assertEquals(gray[1], gray[2], 1e-5f)
        assertEquals(AdjustmentMath.luma(0.9f, 0.2f, 0.4f), gray[0], 1e-5f)
    }

    @Test
    fun contrast_spreadsOrSqueezesAroundTheMiddle() {
        assertEquals(0.7f, apply(Adjustments(contrast = 1f), 0.6f, 0.6f, 0.6f)[0], 1e-5f)
        assertEquals(0.5f, apply(Adjustments(contrast = -1f), 0.9f, 0.9f, 0.9f)[0], 1e-5f)
    }

    @Test
    fun brightness_movesMiddleTones_andKeepsBlackAndWhite() {
        assertTrue(apply(Adjustments(brightness = 1f), 0.5f, 0.5f, 0.5f)[0] > 0.7f)
        assertTrue(apply(Adjustments(brightness = -1f), 0.5f, 0.5f, 0.5f)[0] < 0.3f)
        assertEquals(0f, apply(Adjustments(brightness = 1f), 0f, 0f, 0f)[0], 1e-5f)
        assertEquals(1f, apply(Adjustments(brightness = -1f), 1f, 1f, 1f)[0], 1e-5f)
    }

    @Test
    fun shadowsAndHighlights_touchOnlyTheirOwnTones() {
        // 그림자를 올리면 어두운 곳이 밝아지고 흰색은 그대로다
        assertTrue(apply(Adjustments(shadows = 1f), 0.1f, 0.1f, 0.1f)[0] > 0.3f)
        assertEquals(1f, apply(Adjustments(shadows = 1f), 1f, 1f, 1f)[0], 1e-5f)
        // 하이라이트를 내리면 밝은 곳이 어두워지고 검정은 그대로다
        assertTrue(apply(Adjustments(highlights = -1f), 0.9f, 0.9f, 0.9f)[0] < 0.7f)
        assertEquals(0f, apply(Adjustments(highlights = -1f), 0f, 0f, 0f)[0], 1e-5f)
    }

    @Test
    fun temperature_warmsRedAndCoolsBlue() {
        val warm = apply(Adjustments(temperature = 1f), 0.5f, 0.5f, 0.5f)
        val cool = apply(Adjustments(temperature = -1f), 0.5f, 0.5f, 0.5f)

        assertTrue(warm[0] > 0.5f && warm[2] < 0.5f)
        assertTrue(cool[0] < 0.5f && cool[2] > 0.5f)
        assertEquals(0.5f, warm[1], 1e-5f)
    }

    @Test
    fun tint_movesGreenAgainstRedAndBlue_andKeepsGrayBrightness() {
        val magenta = apply(Adjustments(tint = 1f), 0.5f, 0.5f, 0.5f)
        val green = apply(Adjustments(tint = -1f), 0.5f, 0.5f, 0.5f)

        // +는 자홍(빨강·파랑이 늘고 초록이 줄어듦), -는 초록이다
        assertTrue(magenta[0] > 0.5f && magenta[2] > 0.5f && magenta[1] < 0.5f)
        assertTrue(green[0] < 0.5f && green[2] < 0.5f && green[1] > 0.5f)
        assertEquals(magenta[0], magenta[2], 1e-6f)
        // 회색의 밝기(선형 빛의 휘도)는 그대로다
        val gray = AdjustmentMath.toLinear(0.5f)
        for (color in listOf(magenta, green)) {
            val luminance = AdjustmentMath.luma(
                AdjustmentMath.toLinear(color[0]), AdjustmentMath.toLinear(color[1]), AdjustmentMath.toLinear(color[2])
            )
            assertEquals(gray, luminance, 1e-4f)
        }
        // 색온도와 함께 걸면 따로따로 곱한다(초록은 색온도와 상관없다)
        val both = apply(Adjustments(temperature = 0.5f, tint = 0.5f), 0.5f, 0.5f, 0.5f)
        assertEquals(apply(Adjustments(tint = 0.5f), 0.5f, 0.5f, 0.5f)[1], both[1], 1e-6f)
    }

    @Test
    fun tint_isPartOfIdentityRangeAndNormalizing() {
        assertFalse(Adjustments(tint = 0.1f).isIdentity)
        assertThrows(IllegalArgumentException::class.java) { Adjustments(tint = 1.2f) }
        assertEquals(Adjustments.NONE, Adjustments(tint = -0f).normalized())
    }

    @Test
    fun values_outOfRange_areRejected_andMinusZeroIsNormalized() {
        assertThrows(IllegalArgumentException::class.java) { Adjustments(exposure = 2.5f) }
        assertThrows(IllegalArgumentException::class.java) { Adjustments(contrast = -1.1f) }
        val minusZero = Adjustments(saturation = -0f)
        assertTrue(minusZero.isIdentity)
        assertEquals(Adjustments.NONE, minusZero.normalized())
    }
}

class ToneCurveTest {

    @Test
    fun identity_isTheDiagonal() {
        val table = ToneCurve.IDENTITY.table()

        assertEquals(ToneCurve.TABLE_SIZE, table.size)
        for (index in table.indices) assertEquals(index / 255f, table[index], 1e-5f)
        assertTrue(ToneCurve.IDENTITY.isIdentity)
    }

    @Test
    fun curve_passesThroughItsPoints_withoutOvershooting() {
        val curve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.5f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 1f)))

        for (point in curve.points) assertEquals(point.y, curve.valueAt(point.x), 1e-5f)
        // 오르는 점들 사이에서 내려가거나 넘치지 않는다
        val table = curve.table()
        for (index in 1 until table.size) assertTrue("dips at $index", table[index] >= table[index - 1] - 1e-6f)
        for (x in 64..128) {
            val y = curve.valueAt(x / 255f)
            assertTrue("$x: $y", y in 0.499f..0.551f)
        }
        assertFalse(curve.isIdentity)
    }

    @Test
    fun beyondTheEnds_staysFlat() {
        val curve = ToneCurve(listOf(CurvePoint(0.2f, 0.3f), CurvePoint(0.8f, 0.7f)))

        assertEquals(0.3f, curve.valueAt(0.1f), 1e-6f)
        assertEquals(0.7f, curve.valueAt(0.95f), 1e-6f)
    }

    @Test
    fun badPoints_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(listOf(CurvePoint(0.5f, 0f), CurvePoint(0.5f, 1f))) }
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(listOf(CurvePoint(0f, 0f))) }
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(List(17) { CurvePoint(it / 16f, 0f) }) }
        assertThrows(IllegalArgumentException::class.java) { ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1.2f))) }
    }
}

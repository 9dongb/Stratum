package com.kiln.stratum.engine.skin

import java.util.Random
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 잡티 찾기(결정 기록 '피부 보정'의 5단계): 뾰루지·작은 점은 지울 것, 크고 진한 갈색 점은 남길 것, 고른 피부·그림자·마스크 밖은 찾지 않음 */
class SkinSpotsTest {

    private val width = 240
    private val height = 240

    /** 고른 피부(L 0.7, a 0.03)에 고운 잡음(표준 편차 0.005, 늘 같은 씨앗) */
    private fun skin(): Pair<FloatArray, FloatArray> {
        val random = Random(7)
        val lightness = FloatArray(width * height) { 0.7f + 0.005f * random.nextGaussian().toFloat() }
        val a = FloatArray(width * height) { 0.03f + 0.005f * random.nextGaussian().toFloat() }
        return lightness to a
    }

    /** 가운데 ([cx], [cy]) 반지름 [radius]의 원 안에 [change]를 건다 */
    private fun disc(values: FloatArray, cx: Int, cy: Int, radius: Float, delta: Float) {
        for (y in 0 until height) for (x in 0 until width) {
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= radius) values[y * width + x] += delta
        }
    }

    private val fullMask = FloatArray(width * height) { 1f }

    private fun List<SkinSpots.Spot>.near(x: Int, y: Int) = filter { hypot(it.x - x, it.y - y) < 6f }

    @Test
    fun aRedPimple_isFound_andIsNotAMole() {
        val (lightness, a) = skin()
        disc(a, 80, 80, 3f, 0.04f)
        disc(lightness, 80, 80, 3f, -0.02f)

        val spots = SkinSpots.detect(lightness, a, fullMask, width, height)

        val pimple = spots.near(80, 80).single()
        assertFalse(pimple.isMole)
        assertTrue("redness ${pimple.redness}", pimple.redness > 3f)
    }

    @Test
    fun aBigDarkBrownMole_isFound_andKept_butASmallDarkDotIsNot() {
        val (lightness, a) = skin()
        disc(lightness, 160, 80, 6f, -0.15f)
        disc(lightness, 80, 160, 1.6f, -0.08f)

        val spots = SkinSpots.detect(lightness, a, fullMask, width, height)

        val mole = spots.near(160, 80).single()
        assertTrue("mole $mole", mole.isMole)
        assertTrue(mole.radius > SkinSpots.MOLE_RADIUS)
        val dot = spots.near(80, 160).single()
        assertFalse("dot $dot", dot.isMole)
    }

    @Test
    fun aBigMole_isOneSpot_withoutSmallOnesAtItsEdge() {
        // 잡음 있는 피부의 큰 점: 점 안·가장자리의 작은 결을 따로 찾으면 그 자리를 지워 점이 깎인다
        val (lightness, a) = skin()
        disc(lightness, 120, 120, 9f, -0.2f)

        val spots = SkinSpots.detect(lightness, a, fullMask, width, height)

        val near = spots.filter { hypot(it.x - 120, it.y - 120) < 16f }
        assertEquals("$near", 1, near.size)
        assertTrue(near.single().isMole)
    }

    @Test
    fun aSpotAtTheEdgeOfTheSkin_isLeftAlone() {
        // 마스크가 빠진 곳(눈·입술 등) 바로 옆의 그늘은 지우지 않는다(지우는 붓이 피부 밖에 닿음)
        val (lightness, a) = skin()
        disc(a, 120, 120, 3f, 0.05f)
        disc(a, 60, 60, 3f, 0.05f)
        val mask = FloatArray(width * height) { if (it % width in 123..160 && it / width in 100..140) 0f else 1f }

        val spots = SkinSpots.detect(lightness, a, mask, width, height)

        assertEquals("${spots.near(120, 120)}", 0, spots.near(120, 120).size)
        assertEquals(1, spots.near(60, 60).size)
    }

    @Test
    fun aWrinkle_isALine_notASpot() {
        // 진하고 가는 가로 줄(주름): DoG가 줄을 따라 봉우리를 내지만 둥글지 않아 지우지 않는다
        val (lightness, a) = skin()
        for (x in 60 until 180) for (y in 119..121) lightness[y * width + x] -= 0.06f

        val spots = SkinSpots.detect(lightness, a, fullMask, width, height)

        assertEquals("$spots", 0, spots.count { it.y in 112f..128f && it.x in 70f..170f })
    }

    @Test
    fun evenSkin_aSoftShadow_andTheMaskOutside_giveNoSpots() {
        val (lightness, a) = skin()
        // 왼쪽에서 오른쪽으로 0.2 어두워지는 넓은 그림자
        for (i in lightness.indices) lightness[i] -= 0.2f * (i % width) / width
        // 마스크 밖(아래 줄)의 진한 점
        disc(lightness, 120, 220, 5f, -0.3f)
        val mask = FloatArray(width * height) { if (it / width >= 200) 0f else 1f }

        val spots = SkinSpots.detect(lightness, a, mask, width, height)

        assertEquals(emptyList<SkinSpots.Spot>(), spots)
    }

    @Test
    fun noSkin_givesNoSpots() {
        val (lightness, a) = skin()
        assertEquals(emptyList<SkinSpots.Spot>(), SkinSpots.detect(lightness, a, FloatArray(width * height), width, height))
    }

    @Test
    fun overlappingScales_keepOnlyTheStrongest() {
        val (lightness, a) = skin()
        disc(a, 120, 120, 4f, 0.05f)

        val spots = SkinSpots.detect(lightness, a, fullMask, width, height)

        assertEquals(1, spots.near(120, 120).size)
        for (i in spots.indices) for (j in i + 1 until spots.size) {
            assertTrue(hypot(spots[i].x - spots[j].x, spots[i].y - spots[j].y) > maxOf(spots[i].radius, spots[j].radius))
        }
    }
}

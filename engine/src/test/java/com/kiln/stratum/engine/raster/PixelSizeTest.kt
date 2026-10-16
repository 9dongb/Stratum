package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PixelSizeTest {

    @Test
    fun pixelCount_doesNotOverflowForHugePhotos() {
        // 200MP 사진도 Int 범위를 넘지 않지만, 곱셈 중간값이 넘지 않게 Long으로 센다
        assertEquals(199_756_800L, PixelSize(16320, 12240).pixelCount)
        assertEquals(50_000L * 50_000L, PixelSize(50_000, 50_000).pixelCount)
    }

    @Test
    fun megapixels_roundsToNearestAndIsAtLeastOne() {
        assertEquals(50, PixelSize(8160, 6120).megapixels)
        assertEquals(200, PixelSize(16320, 12240).megapixels)
        // 350만 화소는 반올림해서 4
        assertEquals(4, PixelSize(3500, 1000).megapixels)
        assertEquals(1, PixelSize(100, 100).megapixels)
    }

    @Test
    fun init_rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { PixelSize(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { PixelSize(10, -1) }
    }
}

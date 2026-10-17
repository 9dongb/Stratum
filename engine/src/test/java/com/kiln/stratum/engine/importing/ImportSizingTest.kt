package com.kiln.stratum.engine.importing

import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSizingTest {

    private val fold8Photo = PixelSize(8160, 6120)
    private val photo200MP = PixelSize(16320, 12240)

    /** 무엇이든 열 수 있을 만큼 넉넉한 메모리 */
    private val plenty = 100L * 1024 * 1024 * 1024

    @Test
    fun bytesPerPixel_assumesSixteenBitForPngAndAvif() {
        assertEquals(4, ImportSizing.bytesPerPixel("image/jpeg"))
        assertEquals(4, ImportSizing.bytesPerPixel("image/heic"))
        assertEquals(8, ImportSizing.bytesPerPixel("image/png"))
        assertEquals(8, ImportSizing.bytesPerPixel("IMAGE/PNG"))
        assertEquals(8, ImportSizing.bytesPerPixel("image/avif"))
        // 형식을 모르면 흔한 8비트로 본다
        assertEquals(4, ImportSizing.bytesPerPixel(null))
    }

    @Test
    fun peakBytes_isTwoAndAHalfTimesTheDecodedPhoto() {
        // 50MP 8비트 사진은 해독본 약 200MB, 여는 순간 약 500MB
        assertEquals(499_392_000L, ImportSizing.peakBytes(fold8Photo, bytesPerPixel = 4))
    }

    @Test
    fun sampledSize_halvesAndRoundsUp() {
        assertEquals(PixelSize(4080, 3060), ImportSizing.sampledSize(fold8Photo, 2))
        assertEquals(fold8Photo, ImportSizing.sampledSize(photo200MP, 2))
        assertEquals(PixelSize(129, 128), ImportSizing.sampledSize(PixelSize(257, 255), 2))
        assertEquals(fold8Photo, ImportSizing.sampledSize(fold8Photo, 1))
    }

    @Test
    fun sampledSize_rejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException::class.java) { ImportSizing.sampledSize(fold8Photo, 3) }
        assertThrows(IllegalArgumentException::class.java) { ImportSizing.sampledSize(fold8Photo, 0) }
    }

    @Test
    fun plan_opensFold8PhotoDirectly() {
        val plan = ImportSizing.plan(fold8Photo, "image/jpeg", plenty)

        assertTrue(plan.originalFits)
        assertFalse(plan.needsChoice)
        assertFalse(plan.cannotOpen)
    }

    @Test
    fun plan_asksOnlyAboveFiftyMegapixels() {
        // 딱 5000만 화소는 바로 열고, 하나라도 넘으면 묻는다
        assertFalse(ImportSizing.plan(PixelSize(10_000, 5_000), "image/jpeg", plenty).needsChoice)
        assertTrue(ImportSizing.plan(PixelSize(10_000, 5_001), "image/jpeg", plenty).needsChoice)
    }

    @Test
    fun plan_offersBothSizesForLargePhotoThatFits() {
        val plan = ImportSizing.plan(photo200MP, "image/jpeg", plenty)

        assertTrue(plan.needsChoice)
        assertTrue(plan.originalFits)
        assertEquals(2, plan.reducedSampleSize)
        assertEquals(fold8Photo, plan.reducedSize)
    }

    @Test
    fun plan_reducesByHalvesSoResultCanBeWellUnderFiftyMegapixels() {
        // 108MP는 반으로 줄이면 2700만 화소다
        val plan = ImportSizing.plan(PixelSize(12_000, 9_000), "image/jpeg", plenty)

        assertEquals(2, plan.reducedSampleSize)
        assertEquals(27, plan.reducedSize?.megapixels)
    }

    @Test
    fun plan_asksEvenForSmallPhotoWhenOriginalDoesNotFitMemory() {
        // 50MP 사진을 여는 데 약 500MB가 드는데 300MB만 있다
        val plan = ImportSizing.plan(fold8Photo, "image/jpeg", 300L * 1000 * 1000)

        assertFalse(plan.originalFits)
        assertTrue(plan.needsChoice)
        assertEquals(2, plan.reducedSampleSize)
    }

    @Test
    fun plan_reducesMoreWhenMemoryIsTight() {
        // 1/16은 약 195만 바이트가 들어 넘치고, 1/32는 약 49만 바이트로 들어간다
        val plan = ImportSizing.plan(fold8Photo, "image/jpeg", 1_000_000L)

        assertEquals(32, plan.reducedSampleSize)
    }

    @Test
    fun plan_cannotOpenWithoutMemory() {
        val plan = ImportSizing.plan(fold8Photo, "image/jpeg", 0L)

        assertFalse(plan.originalFits)
        assertNull(plan.reducedSampleSize)
        assertTrue(plan.cannotOpen)
    }

    /** API 37 해독기의 기본 한도(약 0.5GiB) */
    private val decoderLimit = 536_900_000L

    @Test
    fun decodeBytes_doublesForFormatsThatMayBeRotated() {
        // JPEG는 방향 정보로 돌릴 때 임시 버퍼를 하나 더 쓸 수 있어 두 배, PNG는 16비트일 수 있어 8바이트
        assertEquals(399_513_600L, ImportSizing.decodeBytes(fold8Photo, "image/jpeg"))
        assertEquals(399_513_600L, ImportSizing.decodeBytes(fold8Photo, "image/png"))
    }

    @Test
    fun plan_keepsFold8PhotoWithinDecoderLimit() {
        val plan = ImportSizing.plan(fold8Photo, "image/jpeg", plenty, decoderLimit)

        assertTrue(plan.originalFits)
        assertFalse(plan.needsChoice)
    }

    @Test
    fun plan_disablesOriginalAboveDecoderLimitEvenWithPlentyOfMemory() {
        // 200MP를 풀려면 약 1.6GB가 들어 한도를 넘는다. 1/2(50MP, 약 400MB)은 한도 안이다.
        val plan = ImportSizing.plan(photo200MP, "image/jpeg", plenty, decoderLimit)

        assertFalse(plan.originalFits)
        assertTrue(plan.needsChoice)
        assertEquals(2, plan.reducedSampleSize)
    }

    @Test
    fun plan_allowsOriginalJustUnderDecoderLimit() {
        // 64MP JPEG는 약 513MB라 한도(약 537MB) 안이다
        val plan = ImportSizing.plan(PixelSize(9248, 6936), "image/jpeg", plenty, decoderLimit)

        assertTrue(plan.originalFits)
        assertTrue(plan.needsChoice)
    }

    @Test
    fun plan_needsMoreMemoryForPng() {
        // 같은 크기라도 PNG는 16비트일 수 있어 두 배로 잡는다
        val budget = 600L * 1000 * 1000

        assertTrue(ImportSizing.plan(fold8Photo, "image/jpeg", budget).originalFits)
        assertFalse(ImportSizing.plan(fold8Photo, "image/png", budget).originalFits)
    }
}

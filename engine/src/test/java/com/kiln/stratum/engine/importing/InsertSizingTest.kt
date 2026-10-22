package com.kiln.stratum.engine.importing

import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.PixelPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** 사진 넣기(사용자 피드백 12차)의 크기·줄이기 단계·가운데 자리 */
class InsertSizingTest {

    @Test
    fun fit_keepsAPhotoThatFits_andShrinksABiggerOneKeepingItsRatio() {
        val document = PixelSize(4000, 3000)
        // 문서 안에 들어가면 그대로(키우지 않는다)
        assertEquals(PixelSize(800, 600), InsertSizing.fit(PixelSize(800, 600), document))
        assertEquals(document, InsertSizing.fit(document, document))
        // 폴드8 50MP 사진(4:3)은 문서 높이에 맞춘다
        assertEquals(PixelSize(4000, 3000), InsertSizing.fit(PixelSize(8160, 6120), document))
        // 세로 문서에 가로 사진, 가로 문서에 세로 사진
        assertEquals(PixelSize(3000, 2250), InsertSizing.fit(PixelSize(8160, 6120), PixelSize(3000, 4000)))
        assertEquals(PixelSize(2250, 3000), InsertSizing.fit(PixelSize(3000, 4000), document))
        // 한 변만 크면 그 변에 맞춘다
        assertEquals(PixelSize(4000, 800), InsertSizing.fit(PixelSize(5000, 1000), document))
        // 아주 가는 사진도 1px은 남는다
        assertEquals(PixelSize(100, 1), InsertSizing.fit(PixelSize(10000, 1), PixelSize(100, 100)))
    }

    @Test
    fun sampleSize_isTheLargestHalvingThatStaysAtLeastTheFit() {
        // 8160×6120 → 3000×2250: 반(4080×3060)은 크고, 1/4(2040×1530)은 작다
        assertEquals(2, InsertSizing.sampleSize(PixelSize(8160, 6120), PixelSize(3000, 2250)))
        // 줄이지 않는 사진은 1
        assertEquals(1, InsertSizing.sampleSize(PixelSize(800, 600), PixelSize(800, 600)))
        // 정확히 반이면 반으로 읽는다(남은 배율 없음)
        assertEquals(2, InsertSizing.sampleSize(PixelSize(4000, 3000), PixelSize(2000, 1500)))
        // 내림으로 본다: 4001/4 = 1000(내림)은 1001보다 작아 반까지만
        assertEquals(2, InsertSizing.sampleSize(PixelSize(4001, 4001), PixelSize(1001, 1001)))
        // 200MP 사진을 작은 문서에 넣으면 많이 줄여 읽는다
        assertEquals(8, InsertSizing.sampleSize(PixelSize(16320, 12240), PixelSize(1600, 1200)))
    }

    @Test
    fun centered_putsTheImageInTheMiddle_withTheSparePixelRightAndBelow() {
        assertEquals(PixelPoint(1600, 1200), InsertSizing.centered(PixelSize(800, 600), PixelSize(4000, 3000)))
        assertEquals(PixelPoint(0, 875), InsertSizing.centered(PixelSize(3000, 2250), PixelSize(3000, 4000)))
        assertEquals(PixelPoint(25, 0), InsertSizing.centered(PixelSize(50, 100), PixelSize(101, 100)))
        assertEquals(PixelPoint(0, 0), InsertSizing.centered(PixelSize(100, 100), PixelSize(100, 100)))
    }

    @Test
    fun placedTileBytes_countsTheTilesTheCenteredImageTouches() {
        // 800×600을 4000×3000 가운데(1600, 1200)에 놓으면 가로 6~9, 세로 4~7번째 타일 16장
        assertEquals(16L * 256 * 256 * 4, InsertSizing.placedTileBytes(PixelSize(800, 600), PixelSize(4000, 3000)))
        // 타일 한 장 안
        assertEquals(256L * 256 * 4, InsertSizing.placedTileBytes(PixelSize(10, 10), PixelSize(100, 100)))
    }
}

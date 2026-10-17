package com.kiln.stratum.engine.preview

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLevelsTest {

    private val fold8Photo = PixelSize(8160, 6120)

    @Test
    fun levelFor_picksSmallestPreviewNotSmallerThanScreen() {
        // Pixel_Fold 안쪽 화면 정도의 캔버스: 1/2(4080px)는 화면보다 크고, 1/4(2040px)는 작다
        assertEquals(1, PreviewLevels.levelFor(fold8Photo, PixelSize(2208, 1840)))
        // 커버 화면 정도의 캔버스: 1/4(2040px)까지 줄여도 화면(1080px)보다 크다
        assertEquals(2, PreviewLevels.levelFor(fold8Photo, PixelSize(1080, 1700)))
    }

    @Test
    fun levelFor_doesNotShrinkPhotoSmallerThanScreen() {
        assertEquals(0, PreviewLevels.levelFor(PixelSize(1000, 800), PixelSize(2208, 1840)))
    }

    @Test
    fun levelFor_halvesWhenExactlyTwiceTheScreen() {
        assertEquals(1, PreviewLevels.levelFor(PixelSize(4096, 2048), PixelSize(2048, 1024)))
    }

    @Test
    fun levelFor_keepsLongEdgeWithinLimit() {
        // 화면이 아주 커도 축소본의 긴 변은 4096px 이하
        val level = PreviewLevels.levelFor(PixelSize(16320, 12240), PixelSize(10_000, 10_000))

        assertEquals(2, level)
        assertEquals(PixelSize(4080, 3060), PreviewLevels.previewSize(PixelSize(16320, 12240), level))
    }

    @Test
    fun levelFor_handlesVeryLongPanorama() {
        val panorama = PixelSize(30_000, 1_000)

        val level = PreviewLevels.levelFor(panorama, PixelSize(2208, 1840))

        assertEquals(3, level)
        assertEquals(PixelSize(3750, 125), PreviewLevels.previewSize(panorama, level))
    }

    @Test
    fun previewSize_roundsUpRemainder() {
        assertEquals(PixelSize(4080, 3060), PreviewLevels.previewSize(fold8Photo, 1))
        assertEquals(PixelSize(4081, 3061), PreviewLevels.previewSize(PixelSize(8161, 6121), 1))
        assertEquals(fold8Photo, PreviewLevels.previewSize(fold8Photo, 0))
    }

    @Test
    fun blocks_coverFold8PhotoWithTwelveBlocks() {
        val blocks = PreviewLevels.blocks(fold8Photo, level = 1)

        // 32×24 타일을 8×8씩 묶으면 4×3 묶음
        assertEquals(12, blocks.size)
        assertEquals(
            PreviewBlock(
                firstColumn = 0, firstRow = 0, columns = 8, rows = 8,
                left = 0, top = 0, width = 2048, height = 2048,
                previewLeft = 0, previewTop = 0, previewWidth = 1024, previewHeight = 1024
            ),
            blocks.first()
        )
        // 오른쪽 아래 끝 묶음은 캔버스 안쪽만 차지한다
        assertEquals(
            PreviewBlock(
                firstColumn = 24, firstRow = 16, columns = 8, rows = 8,
                left = 6144, top = 4096, width = 2016, height = 2024,
                previewLeft = 3072, previewTop = 2048, previewWidth = 1008, previewHeight = 1012
            ),
            blocks.last()
        )
    }

    @Test
    fun blocks_tileThePreviewWithoutGapsOrOverlap() {
        val document = PixelSize(8161, 6121)
        val level = 2
        val preview = PreviewLevels.previewSize(document, level)

        val blocks = PreviewLevels.blocks(document, level)

        // 한 줄의 묶음 폭을 더하면 축소본 폭과 같고, 한 열의 묶음 높이를 더하면 축소본 높이와 같다
        for (row in blocks.groupBy { it.previewTop }.values) {
            assertEquals(preview.width, row.sumOf { it.previewWidth })
        }
        for (column in blocks.groupBy { it.previewLeft }.values) {
            assertEquals(preview.height, column.sumOf { it.previewHeight })
        }
    }

    @Test
    fun blocks_smallPhotoIsOneBlock() {
        val blocks = PreviewLevels.blocks(PixelSize(300, 200), level = 0)

        assertEquals(
            listOf(
                PreviewBlock(
                    firstColumn = 0, firstRow = 0, columns = 2, rows = 1,
                    left = 0, top = 0, width = 300, height = 200,
                    previewLeft = 0, previewTop = 0, previewWidth = 300, previewHeight = 200
                )
            ),
            blocks
        )
    }

    @Test
    fun rejectsLevelOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { PreviewLevels.previewSize(fold8Photo, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewLevels.blocks(fold8Photo, MAX_PREVIEW_LEVEL + 1)
        }
    }

    @Test
    fun levelForScale_picksCoarsestLevelNotMagnified() {
        assertEquals(2, PreviewLevels.levelForScale(0.25))
        assertEquals(1, PreviewLevels.levelForScale(0.26))
        assertEquals(0, PreviewLevels.levelForScale(1.0))
        assertEquals(0, PreviewLevels.levelForScale(32.0))
        // 1.25배까지 키워 보여도 되면, 0.3배는 1/4 축소본(1.2배로 키워 보임)으로 충분하다
        assertEquals(2, PreviewLevels.levelForScale(0.3, maxMagnification = 1.25))
    }

    @Test
    fun region_snapsToTileGridAndClipsToDocument() {
        val document = PixelSize(8161, 6121)

        val region = checkNotNull(PreviewLevels.region(document, level = 2, rect = PixelRect(300, 700, 1001, 1500)))

        assertEquals(PixelRect(256, 512, 1024, 1536), region.rect)
        assertEquals(64, region.previewLeft)
        assertEquals(128, region.previewTop)
        assertEquals(192, region.previewWidth)
        assertEquals(256, region.previewHeight)
        // 문서 밖으로 나간 부분은 자른다
        assertEquals(
            PixelRect(0, 0, 512, 256),
            PreviewLevels.region(document, level = 0, rect = PixelRect(-100, -100, 300, 200))?.rect
        )
    }

    @Test
    fun region_usesCoarserGridAtHighLevelsAndRoundsUpOddEdge() {
        val document = PixelSize(8161, 6121)

        // 10단계는 픽셀 1개가 1024px라 1024 격자에 맞춘다
        assertEquals(
            PixelRect(0, 0, 1024, 2048),
            PreviewLevels.region(document, level = 10, rect = PixelRect(300, 700, 1001, 1500))?.rect
        )
        // 홀수인 문서 끝은 축소본에서 올림한다
        val corner = checkNotNull(PreviewLevels.region(document, level = 1, rect = PixelRect(8000, 6000, 8161, 6121)))
        assertEquals(PixelRect(7936, 5888, 8161, 6121), corner.rect)
        assertEquals(4081 - 3968, corner.previewWidth)
        assertEquals(3061 - 2944, corner.previewHeight)
    }

    @Test
    fun region_isNullOutsideDocument() {
        assertNull(PreviewLevels.region(PixelSize(1000, 800), level = 0, rect = PixelRect(1200, 0, 1300, 100)))
    }

    @Test
    fun blocks_ofWholeRegionMatchWholeDocumentBlocks() {
        for (level in listOf(0, 1, 3, 11)) {
            val document = PixelSize(8161, 6121)
            assertEquals(
                PreviewLevels.blocks(document, level),
                PreviewLevels.blocks(PreviewLevels.wholeRegion(document, level))
            )
        }
    }

    @Test
    fun blocks_ofRegionStartAtRegionAndTileItsPreview() {
        val document = PixelSize(8161, 6121)
        // 2048px 묶음 경계를 넘는 영역
        val region = checkNotNull(PreviewLevels.region(document, level = 1, rect = PixelRect(1792, 0, 4352, 600)))

        val blocks = PreviewLevels.blocks(region)

        assertEquals(listOf(1792, 3840), blocks.filter { it.top == 0 }.map { it.left })
        assertEquals(7, blocks.first().firstColumn)
        assertEquals(0, blocks.first().previewLeft)
        for (row in blocks.groupBy { it.previewTop }.values) {
            assertEquals(region.previewWidth, row.sumOf { it.previewWidth })
        }
        for (column in blocks.groupBy { it.previewLeft }.values) {
            assertEquals(region.previewHeight, column.sumOf { it.previewHeight })
        }
    }

    @Test
    fun detailRegion_isNullWhenBasePreviewIsEnough() {
        val document = PixelSize(2600, 520)
        val canvas = PixelSize(1200, 800)
        val baseLevel = PreviewLevels.levelFor(document, canvas)
        val fitScale = 1200.0 / 2600

        assertNull(PreviewLevels.detailRegion(document, baseLevel, fitScale, PixelRect.of(document)))
    }

    @Test
    fun detailRegion_coversVisibleAreaWithMargin() {
        val document = PixelSize(2600, 520)

        // 400%로 300×60px만 보인다
        val region = checkNotNull(
            PreviewLevels.detailRegion(document, baseLevel = 1, scale = 4.0, visible = PixelRect(1000, 200, 1300, 260))
        )

        assertEquals(0, region.level)
        assertTrue(PixelRect(1000, 200, 1300, 260) in region.rect)
        // 여유(1/8)를 더해 타일 격자에 맞춘다
        assertEquals(PixelRect(768, 0, 1536, 512), region.rect)
    }

    @Test
    fun detailRegion_stepsCoarserWhenTooLarge() {
        val document = PixelSize(16320, 12240)
        // 원본 단계로 보면 6000px 넓이가 보이지만, 4096px를 넘으니 한 단계 거칠게 그린다
        val visible = PixelRect(0, 0, 6000, 3000)

        val region = checkNotNull(PreviewLevels.detailRegion(document, baseLevel = 3, scale = 1.0, visible = visible))

        assertEquals(1, region.level)
        assertTrue(region.previewWidth <= MAX_PREVIEW_EDGE)
        // 더 거칠게 그려야 하는데 그게 전체 축소본 단계와 같아지면 다시 그리지 않는다
        assertNull(PreviewLevels.detailRegion(document, baseLevel = 1, scale = 1.0, visible = visible))
    }

    @Test
    fun canReuse_keepsDetailThatStillCoversVisibleArea() {
        val document = PixelSize(2600, 520)
        val current = PreviewRegion(0, PixelRect(768, 0, 1536, 512))
        val plannedSameLevel = PreviewRegion(0, PixelRect(512, 0, 1536, 520))

        assertTrue(PreviewLevels.canReuse(current, plannedSameLevel, document, PixelRect(900, 100, 1400, 400)))
        assertFalse(PreviewLevels.canReuse(current, plannedSameLevel, document, PixelRect(600, 100, 1400, 400)))
        assertFalse(
            PreviewLevels.canReuse(current, PreviewRegion(1, current.rect), document, PixelRect(900, 100, 1400, 400))
        )
    }
}

package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropFrameTest {

    private val document = PixelSize(4000, 3000)

    private fun assertFrame(left: Double, top: Double, right: Double, bottom: Double, actual: CropFrame) {
        assertEquals("left of $actual", left, actual.left, 1e-6)
        assertEquals("top of $actual", top, actual.top, 1e-6)
        assertEquals("right of $actual", right, actual.right, 1e-6)
        assertEquals("bottom of $actual", bottom, actual.bottom, 1e-6)
    }

    @Test
    fun whole_coversTheDocument() {
        val frame = CropFrame.whole(document)

        assertFrame(0.0, 0.0, 4000.0, 3000.0, frame)
        assertEquals(PixelRect.of(document), frame.toPixelRect(document))
        assertNull(frame.aspect(document))
    }

    @Test
    fun turned_movesTheFrameWithThePhoto() {
        // 4000×3000 사진의 테두리 (100, 200)~(1100, 700)을 사진과 함께 돌리고 뒤집는다(사용자 피드백 8차 1)
        val frame = CropFrame(100.0, 200.0, 1100.0, 700.0)

        // 시계 방향: (x, y) → (3000 - y, x)
        assertFrame(2300.0, 100.0, 2800.0, 1100.0, frame.turned(Orientation.ROTATE_CLOCKWISE, document))
        // 반시계 방향: (x, y) → (y, 4000 - x)
        assertFrame(200.0, 2900.0, 700.0, 3900.0, frame.turned(Orientation.ROTATE_COUNTERCLOCKWISE, document))
        assertFrame(2900.0, 200.0, 3900.0, 700.0, frame.turned(Orientation.FLIP_HORIZONTAL, document))
        assertFrame(100.0, 2300.0, 1100.0, 2800.0, frame.turned(Orientation.FLIP_VERTICAL, document))
        // 네 번 돌리면 제자리다(돌린 뒤의 사진 크기로 다시 돌린다)
        var turned = frame
        var size = document
        repeat(4) {
            turned = turned.turned(Orientation.ROTATE_CLOCKWISE, size)
            size = Orientation.ROTATE_CLOCKWISE.targetSize(size)
        }
        assertFrame(100.0, 200.0, 1100.0, 700.0, turned)
    }

    @Test
    fun turned_keepsTheRatioMatchingTheFrame() {
        // 고정 비율은 90° 돌리면 가로세로를 바꿔(4:5 → 5:4) 돌린 테두리 모양과 맞춘다
        val portrait = CropFrame.whole(document).withRatio(CropRatio.PORTRAIT_4_5, document)
        val turned = portrait.turned(Orientation.ROTATE_CLOCKWISE, document)
        val shown = Orientation.ROTATE_CLOCKWISE.targetSize(document)
        assertEquals(true, turned.swapped)
        assertEquals(turned.width / turned.height, checkNotNull(turned.aspect(shown)), 1e-9)
        // 뒤집기는 모양이 그대로라 바꾸지 않는다
        assertEquals(false, portrait.turned(Orientation.FLIP_HORIZONTAL, document).swapped)
        // 원본 비율은 돌린 사진의 비율을 따르므로 그대로 두어도 맞는다
        val original = CropFrame.whole(document).withRatio(CropRatio.ORIGINAL, document).turned(Orientation.ROTATE_CLOCKWISE, document)
        assertEquals(false, original.swapped)
        assertEquals(original.width / original.height, checkNotNull(original.aspect(shown)), 1e-9)
        // 자유는 비율이 없다
        assertEquals(false, CropFrame.whole(document).turned(Orientation.ROTATE_CLOCKWISE, document).swapped)
    }

    @Test
    fun withRatio_fitsTheLargestFrameAroundTheCenter() {
        val square = CropFrame.whole(document).withRatio(CropRatio.SQUARE, document)
        assertFrame(500.0, 0.0, 3500.0, 3000.0, square)

        // 세로가 기본(사용자 피드백 10차): 9:16으로 세로 3000에 맞춘다
        val tall = CropFrame.whole(document).withRatio(CropRatio.STORY_9_16, document)
        assertFrame(2000 - 843.75, 0.0, 2000 + 843.75, 3000.0, tall)
    }

    @Test
    fun ratios_arePortraitFirst_fromSquareToTheTallest() {
        // 4:3·16:9·3:2(가로)는 없고, 가로는 가로·세로 바꾸기로 만든다
        val fixed = CropRatio.entries.filter { it.width > 0 }
        assertEquals(listOf("1:1", "4:5", "3:4", "2:3", "9:16"), fixed.map { "${it.width}:${it.height}" })
        assertTrue(fixed.all { it.width <= it.height })
        assertTrue(fixed.zipWithNext().all { (a, b) -> a.width.toDouble() / a.height > b.width.toDouble() / b.height })
    }

    @Test
    fun withRatio_threeToFour_fitsAPortraitFrame() {
        // 3:4는 4:3 사진에서 세로 3000에 맞춘 세로 테두리다
        val portrait = CropFrame.whole(document).withRatio(CropRatio.PORTRAIT_3_4, document)

        assertEquals(3.0 / 4, portrait.aspect(document)!!, 1e-9)
        assertFrame(875.0, 0.0, 3125.0, 3000.0, portrait)
        assertEquals(PixelRect(875, 0, 3125, 3000), portrait.toPixelRect(document))
    }

    @Test
    fun withRatio_original_usesTheDocumentShape() {
        // 자유롭게 줄인 테두리에서 원본 비율을 고르면 4:3으로 사진 안에 가장 크게 맞춘다
        val small = CropFrame(1000.0, 1000.0, 2000.0, 1500.0)

        val original = small.withRatio(CropRatio.ORIGINAL, document)

        assertEquals(4.0 / 3, original.aspect(document)!!, 1e-9)
        assertFrame(0.0, 0.0, 4000.0, 3000.0, original)
    }

    @Test
    fun swapped_flipsTheRatioOrTheFrame() {
        val wide = CropFrame.whole(document).withRatio(CropRatio.STORY_9_16, document).swapped(document)
        // 바꾸면 16:9로 폭 4000에 맞춘다
        assertEquals(16.0 / 9, wide.aspect(document)!!, 1e-9)
        assertFrame(0.0, 375.0, 4000.0, 2625.0, wide)

        // 자유 비율은 테두리 모양을 지금 가운데에서 뒤집는다
        val free = CropFrame(1500.0, 1250.0, 2500.0, 1750.0).swapped(document)
        assertFrame(1750.0, 1000.0, 2250.0, 2000.0, free)
    }

    @Test
    fun freeDrag_movesOnlyTheDraggedEdges() {
        val frame = CropFrame.whole(document)

        assertFrame(100.0, 50.0, 4000.0, 3000.0, frame.dragged(CropHandle.TOP_LEFT, 100.0, 50.0, document, minSide = 10.0))
        assertFrame(0.0, 0.0, 3900.0, 3000.0, frame.dragged(CropHandle.RIGHT, -100.0, 999.0, document, minSide = 10.0))
    }

    @Test
    fun freeDrag_staysInsideAndKeepsTheMinimum() {
        val frame = CropFrame(1000.0, 1000.0, 2000.0, 2000.0)

        // 사진 밖으로는 나가지 않는다
        assertFrame(0.0, 1000.0, 2000.0, 2000.0, frame.dragged(CropHandle.LEFT, -5000.0, 0.0, document, minSide = 100.0))
        // 맞은편 변을 넘어가도 짧은 변은 100 아래로 줄지 않는다
        assertFrame(1000.0, 1000.0, 1100.0, 2000.0, frame.dragged(CropHandle.RIGHT, -5000.0, 0.0, document, minSide = 100.0))
    }

    @Test
    fun freeDrag_ofAFrameAlreadyBelowTheMinimum_doesNotJump() {
        // 더 확대해서 줄여 둔 50px 테두리를, 최소 100px인 배율에서 끈다
        val frame = CropFrame(1000.0, 1000.0, 1050.0, 1050.0)

        // 더 좁게는 못 줄이지만, 끄는 순간 100px로 커지지도 않는다
        assertFrame(1000.0, 1000.0, 1050.0, 1050.0, frame.dragged(CropHandle.LEFT, 10.0, 0.0, document, minSide = 100.0))
        // 넓히는 것은 된다
        assertFrame(990.0, 1000.0, 1050.0, 1050.0, frame.dragged(CropHandle.LEFT, -10.0, 0.0, document, minSide = 100.0))
    }

    @Test
    fun lockedCorner_keepsTheRatioFromTheOppositeCorner() {
        val square = CropFrame.whole(document).withRatio(CropRatio.SQUARE, document)

        // 오른쪽 아래를 안쪽으로 끌면 왼쪽 위를 기준으로, 손가락이 덜 들어온 쪽(세로 2800)을 따라 정사각형이 된다
        val dragged = square.dragged(CropHandle.BOTTOM_RIGHT, -1000.0, -200.0, document, minSide = 10.0)

        assertFrame(500.0, 0.0, 3300.0, 2800.0, dragged)
    }

    @Test
    fun lockedEdge_keepsTheRatioAroundTheCenter() {
        val wide = CropFrame.whole(document).withRatio(CropRatio.STORY_9_16, document).swapped(document)

        val dragged = wide.dragged(CropHandle.RIGHT, -1000.0, 0.0, document, minSide = 10.0)

        // 폭 3000에 맞춰 높이 1687.5, 세로 가운데(1500)는 그대로
        assertFrame(0.0, 656.25, 3000.0, 2343.75, dragged)
    }

    @Test
    fun lockedCorner_cannotGrowOutOfThePhoto() {
        val square = CropFrame(1000.0, 1000.0, 2000.0, 2000.0, CropRatio.SQUARE)

        val dragged = square.dragged(CropHandle.BOTTOM_RIGHT, 5000.0, 5000.0, document, minSide = 10.0)

        // 아래쪽 여유(2000)가 먼저 막힌다
        assertFrame(1000.0, 1000.0, 3000.0, 3000.0, dragged)
    }

    @Test
    fun move_staysInsideThePhoto() {
        val square = CropFrame.whole(document).withRatio(CropRatio.SQUARE, document)

        assertFrame(1000.0, 0.0, 4000.0, 3000.0, square.dragged(CropHandle.MOVE, 2000.0, 500.0, document, minSide = 10.0))
    }

    @Test
    fun toPixelRect_roundsInsideThePhotoAndKeepsTheRatio() {
        val square = CropFrame(0.4, 0.6, 1000.4, 1000.6, CropRatio.SQUARE)

        assertEquals(PixelRect(0, 1, 1000, 1001), square.toPixelRect(document))
        assertEquals(PixelRect(0, 375, 4000, 2625), CropFrame.whole(document).withRatio(CropRatio.STORY_9_16, document).swapped(document).toPixelRect(document))
        // 반올림으로 사진 끝을 넘으면 안쪽으로 옮긴다
        assertEquals(PixelRect(3000, 2000, 4000, 3000), CropFrame(2999.6, 1999.6, 3999.9, 2999.9).toPixelRect(document))
    }

    @Test
    fun saved_comesBackTheSame() {
        val frame = CropFrame(1.5, 2.5, 300.25, 400.75, CropRatio.PORTRAIT_2_3, swapped = true)

        assertEquals(frame, CropFrame.fromSaved(frame.toSaved()))
    }

    @Test
    fun saved_withARatioThatNoLongerExists_comesBackFree() {
        // 앞 판이 저장한 16:9(번호 5)·3:2(6)·4:3(7)은 자유로 되살린다. 남은 비율은 앞 판 번호 그대로다.
        val saved = CropFrame(0.0, 0.0, 100.0, 100.0).toSaved()
        assertEquals(CropRatio.FREE, CropFrame.fromSaved(saved.toMutableList().apply { set(4, 5.0) }).ratio)
        assertEquals(CropRatio.PORTRAIT_3_4, CropFrame.fromSaved(saved.toMutableList().apply { set(4, 8.0) }).ratio)
        assertEquals(CropRatio.STORY_9_16, CropFrame.fromSaved(saved.toMutableList().apply { set(4, 4.0) }).ratio)
    }

    @Test
    fun handleAt_prefersCornersThenEdgesThenMove() {
        fun at(x: Double, y: Double) = cropHandleAt(x, y, 100.0, 100.0, 300.0, 200.0, reach = 24.0)

        assertEquals(CropHandle.TOP_LEFT, at(105.0, 95.0))
        assertEquals(CropHandle.TOP, at(200.0, 110.0))
        assertEquals(CropHandle.RIGHT, at(320.0, 150.0))
        assertEquals(CropHandle.BOTTOM_RIGHT, at(300.0, 200.0))
        assertEquals(CropHandle.MOVE, at(200.0, 150.0))
        assertNull(at(400.0, 400.0))
        // 아주 작은 테두리는 더 가까운 모서리를 잡는다
        assertEquals(CropHandle.BOTTOM_RIGHT, cropHandleAt(118.0, 108.0, 100.0, 100.0, 120.0, 110.0, reach = 24.0))
    }
}

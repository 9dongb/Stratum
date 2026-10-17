package com.kiln.stratum.engine.selection

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionShapeTest {

    private val tile = PixelRect(256, 256, 512, 512)

    @Test
    fun rectangle_coversTilesInsideOutsideAndAcross() {
        val shape = SelectionShape.Rectangle(100f, 100f, 700.5f, 600f)

        assertEquals(Coverage.INSIDE, shape.coverage(tile))
        assertEquals(Coverage.OUTSIDE, shape.coverage(PixelRect(768, 0, 1024, 256)))
        // 오른쪽 경계가 픽셀 가운데를 지난다
        assertEquals(Coverage.PARTIAL, shape.coverage(PixelRect(512, 256, 768, 512)))
        assertEquals(PixelRect(100, 100, 701, 600), shape.bounds)
    }

    @Test
    fun emptyRectangle_hasNoBounds() {
        assertNull(SelectionShape.Rectangle(10f, 10f, 10f, 50f).bounds)
    }

    @Test
    fun ellipse_insideOnlyWhenAllCornersAreIn() {
        // 가운데 (384, 384), 반지름 300인 원
        val shape = SelectionShape.Ellipse(84f, 84f, 684f, 684f)

        assertEquals(Coverage.INSIDE, shape.coverage(tile))
        // 바깥 사각형의 귀퉁이는 원 밖이다
        assertEquals(Coverage.OUTSIDE, shape.coverage(PixelRect(0, 0, 100, 100)))
        assertEquals(Coverage.PARTIAL, shape.coverage(PixelRect(0, 256, 256, 512)))
    }

    @Test
    fun lasso_usesEvenOdd_andFindsCrossingEdges() {
        // 삼각형(빗변 x + y = 990). 변이 타일 경계와 겹치면 걸침으로 보므로 경계에서 조금 비켜 둔다.
        val shape = SelectionShape.Lasso(listOf(SelectionPoint(-10f, -10f), SelectionPoint(1000f, -10f), SelectionPoint(-10f, 1000f)))

        assertEquals(Coverage.INSIDE, shape.coverage(PixelRect(0, 0, 256, 256)))
        assertEquals(Coverage.OUTSIDE, shape.coverage(PixelRect(768, 768, 1024, 1024)))
        assertEquals(Coverage.PARTIAL, shape.coverage(PixelRect(256, 512, 512, 768)))
        assertTrue(shape.contains(100f, 100f))
        assertFalse(shape.contains(900f, 900f))
    }

    @Test
    fun lasso_selfCrossing_leavesTheDoubleWrappedPartOut() {
        // 같은 사각형을 두 번 도는 올가미: 두 번 감싼 곳은 밖(even-odd)
        val square = listOf(SelectionPoint(0f, 0f), SelectionPoint(100f, 0f), SelectionPoint(100f, 100f), SelectionPoint(0f, 100f))
        val shape = SelectionShape.Lasso(square + square)

        assertFalse(shape.contains(50f, 50f))
    }

    @Test
    fun lassoWithTwoPoints_isEmpty() {
        val shape = SelectionShape.Lasso(listOf(SelectionPoint(0f, 0f), SelectionPoint(10f, 10f)))

        assertNull(shape.bounds)
        assertEquals(Coverage.OUTSIDE, shape.coverage(tile))
    }

    @Test
    fun boundsIn_clipsToTheDocument() {
        val shape = SelectionShape.Rectangle(-50f, -50f, 100f, 100f)

        assertEquals(PixelRect(0, 0, 100, 100), shape.boundsIn(PixelSize(400, 300)))
        assertNull(SelectionShape.Rectangle(500f, 500f, 600f, 600f).boundsIn(PixelSize(400, 300)))
    }
}

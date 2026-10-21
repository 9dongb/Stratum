package com.kiln.stratum.engine.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskEdgeTest {

    @Test
    fun blur_keepsFlatAreas_andTheMiddleOfAStraightEdge() {
        val width = 40
        val height = 10
        // 왼쪽 반은 255, 오른쪽 반은 0(경계는 x = 20)
        val mask = ByteArray(width * height) { if (it % width < 20) -1 else 0 }

        val blurred = MaskEdge.blur(mask, width, height)

        fun at(x: Int) = blurred[5 * width + x].toInt() and 0xFF
        assertEquals(255, at(2))
        assertEquals(0, at(37))
        // 경계 양쪽 픽셀(19, 20)은 0.5를 사이에 두고 서로 맞선다
        assertTrue("${at(19)} + ${at(20)}", kotlin.math.abs(at(19) + at(20) - 255) <= 1)
        assertTrue(at(19) > 128 && at(20) < 128)
    }

    @Test
    fun blur_ofAUniformMask_isTheSame() {
        val full = ByteArray(64) { -1 }
        val empty = ByteArray(64)

        assertTrue(MaskEdge.blur(full, 8, 8).all { it.toInt() == -1 })
        assertTrue(MaskEdge.blur(empty, 8, 8).all { it.toInt() == 0 })
    }

    @Test
    fun halfWidth_makesTheEdgeAboutOneAndAHalfDocumentPixelsWide() {
        // σ 1칸으로 흐린 경계는 마스크 1칸당 0.399만큼 바뀐다: 한 칸이 문서 4px면 1px당 0.0997, 1.5px면 0.1496
        assertEquals(0.0748f, MaskEdge.halfWidth(4), 1e-4f)
        assertEquals(0.0374f, MaskEdge.halfWidth(8), 1e-4f)
        assertEquals(0.5f, MaskEdge.halfWidth(1, sigma = 0.1f), 0f)
    }
}

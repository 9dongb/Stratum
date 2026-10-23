package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.raster.Raster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 레이어 그룹의 나무 규칙(결정 기록 '피부 보정'의 1g): 그룹의 자손은 그룹 바로 아래에 이어 놓인다. */
class LayerTreeTest {

    private fun pixels(id: Long, parent: Long? = null, opacity: Float = 1f, visible: Boolean = true) =
        Layer(LayerId(id), "L$id", RasterContent(Raster.EMPTY), opacity = opacity, visible = visible, parent = parent?.let(::LayerId))

    private fun group(id: Long, parent: Long? = null, opacity: Float = 1f, visible: Boolean = true) =
        Layer(LayerId(id), "G$id", GroupContent, opacity = opacity, visible = visible, parent = parent?.let(::LayerId))

    /** 아래부터: 0, [1, [2, 3] 4] 5, 6 — 그룹 5 안에 1과 그룹 4(안에 2, 3) */
    private val nested = listOf(pixels(0), pixels(1, parent = 5), pixels(2, parent = 4), pixels(3, parent = 4), group(4, parent = 5), group(5), pixels(6))

    @Test
    fun problem_acceptsContiguousGroups_andRejectsBrokenOnes() {
        assertNull(LayerTree.problem(nested))
        assertNull(LayerTree.problem(listOf(pixels(0), pixels(1))))
        // 빈 그룹도 된다
        assertNull(LayerTree.problem(listOf(pixels(0), group(1))))
        // 부모가 없거나, 그룹이 아니거나, 아래에 있거나, 다른 레이어가 사이를 가르면 안 된다
        assertNotNull(LayerTree.problem(listOf(pixels(0, parent = 9), pixels(1))))
        assertNotNull(LayerTree.problem(listOf(pixels(0, parent = 1), pixels(1))))
        assertNotNull(LayerTree.problem(listOf(group(0), pixels(1, parent = 0))))
        assertNotNull(LayerTree.problem(listOf(pixels(0, parent = 2), pixels(1), group(2))))
    }

    @Test
    fun blockOf_isTheGroupWithItsDescendants() {
        assertEquals(1..5, LayerTree.blockOf(nested, 5))
        assertEquals(2..4, LayerTree.blockOf(nested, 4))
        assertEquals(3..3, LayerTree.blockOf(nested, 3))
        assertEquals(6..6, LayerTree.blockOf(nested, 6))
        assertEquals(setOf(LayerId(2), LayerId(3), LayerId(4)), LayerTree.idsWith(nested, LayerId(4)))
        assertTrue(LayerTree.isUnder(nested, 2, LayerId(5)))
        assertFalse(LayerTree.isUnder(nested, 0, LayerId(5)))
    }

    @Test
    fun depthVisibilityAndOpacity_comeFromTheEnclosingGroups() {
        val layers = listOf(pixels(0, parent = 1, opacity = 0.5f), group(1, parent = 2, opacity = 0.5f, visible = false), group(2, opacity = 0.8f))

        assertEquals(2, LayerTree.depth(layers, layers[0]))
        assertEquals(0, LayerTree.depth(layers, layers[2]))
        assertEquals(0.2f, LayerTree.opacity(layers, layers[0]), 1e-6f)
        assertFalse(LayerTree.isVisible(layers, layers[0]))
        assertTrue(LayerTree.isVisible(layers, layers[2]))
        // 일부만 넘긴 목록(병합)은 목록에 없는 그룹을 보지 않는다
        assertEquals(0.5f, LayerTree.opacity(listOf(layers[0]), layers[0]), 0f)
    }
}

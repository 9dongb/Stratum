package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 레이어 목록의 줄·옮길 자리(결정 기록 '피부 보정'의 1h). 기대한 옮기기는 엔진의 그룹 규칙(그룹 바로 아래에 자손이 이어 놓임)을 지킨다. */
class LayerRowsTest {

    private fun layer(id: Long, parent: Long? = null, visible: Boolean = true, opacity: Float = 1f) =
        LayerSummary(LayerId(id), "L$id", opacity, BlendMode.NORMAL, Adjustments.NONE, visible = visible, parent = parent?.let(::LayerId))

    private fun group(id: Long, parent: Long? = null, visible: Boolean = true, opacity: Float = 1f) = layer(id, parent, visible, opacity).copy(group = true)

    /** 아래부터: 0, [1, [2, 3] 4] 5, 6 — 그룹 5 안에 1과 그룹 4(안에 2, 3) */
    private val nested = listOf(layer(0), layer(1, parent = 5), layer(2, parent = 4), layer(3, parent = 4), group(4, parent = 5), group(5), layer(6))

    private fun ids(rows: List<LayerRows.Row>) = rows.map { it.layer.id.value }

    private fun move(id: Long, toIndex: Int, parent: Long?) = LayerRows.Move(LayerId(id), toIndex, parent?.let(::LayerId))

    /** [move]를 엔진의 MoveLayer처럼 레이어 요약에 건 결과(아래부터 번호, 감싼 그룹) */
    private fun applied(layers: List<LayerSummary>, move: LayerRows.Move): List<Pair<Long, Long?>> {
        val from = layers.indexOfFirst { it.id == move.id }
        val size = LayerRows.blockSize(layers, from)
        val moved = layers.subList(from - size + 1, from + 1).toMutableList()
        moved[moved.lastIndex] = moved.last().copy(parent = move.parent)
        val rest = layers.toMutableList().apply { subList(from - size + 1, from + 1).clear() }
        rest.addAll(move.toIndex - (size - 1), moved)
        return rest.map { it.id.value to it.parent?.value }
    }

    @Test
    fun rows_showTheTopFirst_indentByDepth_andSkipCollapsedGroups() {
        val rows = LayerRows.of(nested, emptySet())

        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L, 0L), ids(rows))
        assertEquals(listOf(0, 0, 1, 2, 2, 1, 0), rows.map { it.depth })
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), rows.map { it.documentIndex })
        assertEquals(listOf(6L, 5L, 4L, 1L, 0L), ids(LayerRows.of(nested, setOf(LayerId(4)))))
        assertEquals(listOf(6L, 5L, 0L), ids(LayerRows.of(nested, setOf(LayerId(5)))))
        // 숨긴 그룹 안의 줄은 보이지 않는 줄이다
        val hidden = nested.map { if (it.id == LayerId(4)) it.copy(visible = false) else it }
        assertEquals(listOf(true, true, false, false, false, true, true), LayerRows.of(hidden, emptySet()).map { it.shown })
    }

    @Test
    fun units_andBlocks_includeTheDescendants() {
        val rows = LayerRows.of(nested, emptySet())

        assertEquals(1, LayerRows.unitSize(rows, 0))
        assertEquals(5, LayerRows.unitSize(rows, 1))
        assertEquals(3, LayerRows.unitSize(rows, 2))
        // 접은 그룹은 줄 하나지만 덩어리는 자손까지다
        assertEquals(1, LayerRows.unitSize(LayerRows.of(nested, setOf(LayerId(4))), 2))
        assertEquals(5, LayerRows.blockSize(nested, 5))
        assertEquals(3, LayerRows.blockSize(nested, 4))
        assertEquals(1, LayerRows.blockSize(nested, 0))
    }

    @Test
    fun drop_belowAnExpandedGroup_goesInAtTheTop_elseNextToTheRowBelow() {
        val rows = LayerRows.of(nested, emptySet())

        // 맨 위 6을 펼친 그룹 5 바로 아래로: 그룹 5 안 맨 위
        val intoGroup = checkNotNull(LayerRows.drop(nested, rows, 0, 1, emptySet()))
        assertEquals(move(6, 5, 5), intoGroup)
        assertEquals(listOf(0L to null, 1L to 5L, 2L to 4L, 3L to 4L, 4L to 5L, 6L to 5L, 5L to null), applied(nested, intoGroup))
        // 맨 아래 0을 맨 위로: 그룹 밖 맨 위
        assertEquals(move(0, 6, null), LayerRows.drop(nested, rows, 6, 0, emptySet()))
        // 그룹 4를 맨 위로: 자손과 함께, 그룹 밖
        val group = checkNotNull(LayerRows.drop(nested, rows, 2, 0, emptySet()))
        assertEquals(move(4, 6, null), group)
        assertEquals(listOf(0L to null, 1L to 5L, 5L to null, 6L to null, 2L to 4L, 3L to 4L, 4L to null), applied(nested, group))
        // 그룹의 맨 아래 자식 1을 맨 아래로: 그룹 밖
        assertEquals(move(1, 0, null), LayerRows.drop(nested, rows, 5, 6, emptySet()))
        // 제자리는 옮기지 않는다
        assertNull(LayerRows.drop(nested, rows, 2, 2, emptySet()))
    }

    @Test
    fun drop_besideACollapsedGroup_staysOutsideIt() {
        val collapsed = setOf(LayerId(4))
        val rows = LayerRows.of(nested, collapsed)

        // 0을 접은 그룹 4와 1 사이로: 그룹 4 안이 아니라 1과 같은 그룹 5 안, 1 바로 위
        val between = checkNotNull(LayerRows.drop(nested, rows, 4, 3, collapsed))
        assertEquals(move(0, 1, 5), between)
        assertEquals(listOf(1L to 5L, 0L to 5L, 2L to 4L, 3L to 4L, 4L to 5L, 5L to null, 6L to null), applied(nested, between))
    }

    @Test
    fun upAndDown_stepInAndOutOfGroups_oneLevelAtATime() {
        val rows = LayerRows.of(nested, emptySet())

        // 그룹 5의 맨 아래 1의 '아래로': 그룹 밖(그룹 바로 아래), 자리는 그대로
        assertEquals(move(1, 1, null), LayerRows.down(nested, rows, 5, emptySet()))
        // 그룹 바로 아래 0의 '위로': 그룹 5 안 맨 아래
        assertEquals(move(0, 0, 5), LayerRows.up(nested, rows, 6, emptySet()))
        // 1의 '위로': 바로 위 이웃 그룹 4 안 맨 아래(한 단계씩)
        assertEquals(move(1, 1, 4), LayerRows.up(nested, rows, 5, emptySet()))
        // 그룹 5의 첫 줄 4의 '위로': 그룹 5 밖, 그룹 줄 바로 위
        val out = checkNotNull(LayerRows.up(nested, rows, 2, emptySet()))
        assertEquals(move(4, 5, null), out)
        assertEquals(listOf(0L to null, 1L to 5L, 5L to null, 2L to 4L, 3L to 4L, 4L to null, 6L to null), applied(nested, out))
        // 그룹 5의 '아래로': 자손과 함께 0 아래로
        assertEquals(move(5, 4, null), LayerRows.down(nested, rows, 1, emptySet()))
        // 6의 '아래로': 펼친 그룹 5 안 맨 위
        assertEquals(move(6, 5, 5), LayerRows.down(nested, rows, 0, emptySet()))
        // 맨 위·맨 아래(그룹 밖)는 더 갈 곳이 없다
        assertNull(LayerRows.up(nested, rows, 0, emptySet()))
        assertNull(LayerRows.down(nested, rows, 6, emptySet()))
        // 접은 그룹은 줄 하나처럼 건너뛴다: 접은 그룹 4 아래 1의 '위로'는 그룹 4 위(그룹 5 안)
        val collapsed = setOf(LayerId(4))
        assertEquals(move(1, 4, 5), LayerRows.up(nested, LayerRows.of(nested, collapsed), 3, collapsed))
    }

    @Test
    fun mergeVisibleCount_followsTheEnginesMergeVisible() {
        val gray = Adjustments(saturation = -1f)

        assertEquals(2, LayerRows.mergeVisibleCount(listOf(layer(0), layer(1))))
        // 가장 아래 픽셀 레이어보다 아래의 조정 레이어와 바꾸는 것이 없는 조정 레이어는 합치지 않는다
        assertEquals(1, LayerRows.mergeVisibleCount(listOf(layer(0).copy(adjustment = true, adjustments = gray), layer(1))))
        assertEquals(1, LayerRows.mergeVisibleCount(listOf(layer(0), layer(1).copy(adjustment = true))))
        assertEquals(2, LayerRows.mergeVisibleCount(listOf(layer(0), layer(1).copy(adjustment = true, adjustments = gray))))
        // 그룹 줄은 세지 않고, 숨기거나 0%인 그룹 안의 레이어도 세지 않는다
        assertEquals(1, LayerRows.mergeVisibleCount(listOf(layer(0), group(1))))
        assertEquals(1, LayerRows.mergeVisibleCount(listOf(layer(0), layer(1, parent = 2), group(2, visible = false))))
        assertEquals(1, LayerRows.mergeVisibleCount(listOf(layer(0), layer(1, parent = 2), group(2, opacity = 0f))))
        assertEquals(2, LayerRows.mergeVisibleCount(listOf(layer(0), layer(1, parent = 2), group(2, opacity = 0.5f))))
    }

    @Test
    fun isDrawn_looksAtTheEnclosingGroups() {
        val layers = listOf(layer(0, parent = 1), group(1, parent = 2), group(2, opacity = 0.5f), layer(3, opacity = 0f))

        assertEquals(true, LayerRows.isDrawn(layers, layers[0]))
        assertEquals(false, LayerRows.isDrawn(layers, layers[3]))
        assertEquals(false, LayerRows.isDrawn(layers.map { if (it.id == LayerId(2)) it.copy(visible = false) else it }, layers[0]))
        assertEquals(false, LayerRows.isDrawn(layers.map { if (it.id == LayerId(1)) it.copy(opacity = 0f) else it }, layers[0]))
    }
}

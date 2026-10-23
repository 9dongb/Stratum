package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId

/**
 * 레이어 목록에 보일 줄과 옮길 자리(결정 기록 '피부 보정'의 1h). 레이어(아래부터)를 위부터 보이고, 접은 그룹의 자손은 빼고,
 * 그룹 안의 줄은 깊이만큼 들여 쓴다. 줄 하나를 옮기면 그 줄이 맨 위인 덩어리(펼친 그룹이면 보이는 자손까지)가 함께 움직인다.
 * 엔진의 그룹 규칙(그룹의 자손은 그룹 바로 아래에 이어 놓임, `LayerTree`)을 레이어 요약만으로 따른다.
 */
internal object LayerRows {

    /** 보이는 줄 하나: [layer], 문서 자리(0이 맨 아래), 감싼 그룹 수, 감싼 그룹까지 모두 눈을 켰는지 */
    class Row(val layer: LayerSummary, val documentIndex: Int, val depth: Int, val shown: Boolean)

    /** 옮기기: [id] 레이어(그룹이면 자손과 함께)를 문서 [toIndex] 자리, [parent] 그룹 안으로(엔진의 `MoveLayer`와 같은 뜻) */
    data class Move(val id: LayerId, val toIndex: Int, val parent: LayerId?)

    /** [layers](아래부터)를 위부터 보이는 줄. 접은 그룹([collapsed])의 자손은 뺀다. */
    fun of(layers: List<LayerSummary>, collapsed: Set<LayerId>): List<Row> {
        val byId = layers.associateBy { it.id }
        val rows = ArrayList<Row>(layers.size)
        for (index in layers.indices.reversed()) {
            val layer = layers[index]
            var depth = 0
            var hidden = false
            var shown = layer.visible
            var parent = layer.parent?.let(byId::get)
            // 고리가 있어도 끝나게 레이어 수만큼만 따라간다
            while (parent != null && depth < layers.size) {
                if (parent.id in collapsed) hidden = true
                shown = shown && parent.visible
                depth++
                parent = parent.parent?.let(byId::get)
            }
            if (!hidden) rows += Row(layer, index, depth, shown)
        }
        return rows
    }

    /** [rows]의 [index] 줄과 그 아래로 이어진 더 깊은 줄(보이는 자손)의 수. 끌면 함께 움직이는 줄이다. */
    fun unitSize(rows: List<Row>, index: Int): Int {
        val depth = rows[index].depth
        var end = index + 1
        while (end < rows.size && rows[end].depth > depth) end++
        return end - index
    }

    /** [layers]의 [documentIndex] 레이어와, 그룹이면 그 자손(접은 것까지)의 수 */
    fun blockSize(layers: List<LayerSummary>, documentIndex: Int): Int {
        val layer = layers[documentIndex]
        if (!layer.group) return 1
        val byId = layers.associateBy { it.id }
        var start = documentIndex
        while (start > 0 && isUnder(layers[start - 1], layer.id, byId)) start--
        return documentIndex - start + 1
    }

    /**
     * [rows]의 [index] 줄(덩어리)을, 덩어리를 뺀 나머지 줄의 [gap] 자리(0이 맨 위)에 놓는 옮기기(손잡이 끌기). 제자리면 null.
     * 바로 위 줄이 펼친 그룹이면 그 그룹 안 맨 위다. 아니면 바로 아래 줄과 같은 그룹에서 그 줄 바로 위이고, 맨 아래면 그룹 밖 맨 아래다.
     * 그래서 펼친 그룹의 맨 아래 자식 자리(그룹 밖 바로 아래와 같은 틈)는 그룹 밖이 된다.
     */
    fun drop(layers: List<LayerSummary>, rows: List<Row>, index: Int, gap: Int, collapsed: Set<LayerId>): Move? {
        val row = rows[index]
        val size = unitSize(rows, index)
        val rest = rows.subList(0, index) + rows.subList(index + size, rows.size)
        val at = gap.coerceIn(0, rest.size)
        if (at == index) return null
        val block = blockSize(layers, row.documentIndex)
        val first = row.documentIndex - block + 1
        val remaining = layers.subList(0, first) + layers.subList(row.documentIndex + 1, layers.size)
        val above = rest.getOrNull(at - 1)?.layer
        val below = rest.getOrNull(at)?.layer
        return when {
            above != null && above.group && above.id !in collapsed -> {
                // 펼친 그룹 바로 아래: 그 그룹 안 맨 위(그룹 줄 바로 아래)
                Move(row.layer.id, remaining.indexOfFirst { it.id == above.id } + block - 1, above.id)
            }
            below != null -> Move(row.layer.id, remaining.indexOfFirst { it.id == below.id } + block, below.parent)
            else -> Move(row.layer.id, block - 1, null)
        }
    }

    /**
     * 화면 읽기 기능의 '위로': 한 자리 위. 바로 위가 같은 그룹 안의 펼친 그룹(맨 아래 자손 줄, 또는 빈 그룹 줄)이면 그 그룹 안 맨 아래로 들어가고,
     * 그 밖에는 바로 위 줄 바로 위로 간다(바로 위가 그룹 줄이면 그 그룹 밖으로 나옴). 맨 위면 null.
     * 들어가거나 나올 때는 문서 자리는 그대로이고 그룹만 바뀐다(그룹의 맨 아래 자리와 그룹 바로 아래는 같은 자리다).
     */
    fun up(layers: List<LayerSummary>, rows: List<Row>, index: Int, collapsed: Set<LayerId>): Move? {
        if (index == 0) return null
        val row = rows[index]
        val previous = rows[index - 1]
        if (previous.depth > row.depth || (previous.depth == row.depth && previous.layer.group && previous.layer.id !in collapsed)) {
            // 바로 위 이웃 그룹(같은 그룹 안)을 찾는다: 위 줄에서 이 줄과 같은 깊이까지 올라간다
            val byId = layers.associateBy { it.id }
            var group = previous.layer
            repeat(previous.depth - row.depth) { group = group.parent?.let(byId::get) ?: return null }
            return Move(row.layer.id, row.documentIndex, group.id)
        }
        return Move(row.layer.id, previous.documentIndex, previous.layer.parent)
    }

    /**
     * 화면 읽기 기능의 '아래로': 한 자리 아래. 그룹의 맨 아래 덩어리면 그 그룹 밖(그룹 바로 아래)으로 나오고,
     * 바로 아래가 펼친 그룹이면 그 그룹 안 맨 위로 들어가고, 그 밖에는 바로 아래 줄(덩어리) 바로 아래로 간다. 맨 아래면 null.
     */
    fun down(layers: List<LayerSummary>, rows: List<Row>, index: Int, collapsed: Set<LayerId>): Move? {
        val row = rows[index]
        val next = rows.getOrNull(index + unitSize(rows, index))
        val block = blockSize(layers, row.documentIndex)
        return when {
            next == null || next.depth < row.depth -> {
                val parent = row.layer.parent ?: return null
                Move(row.layer.id, row.documentIndex, layers.firstOrNull { it.id == parent }?.parent)
            }
            next.layer.group && next.layer.id !in collapsed -> Move(row.layer.id, next.documentIndex + block - 1, next.layer.id)
            else -> {
                val nextBottom = next.documentIndex - blockSize(layers, next.documentIndex) + 1
                Move(row.layer.id, nextBottom + block - 1, row.layer.parent)
            }
        }
    }

    /**
     * 보이는 레이어 병합이 합칠 레이어 수. 엔진(`MergeVisible.mergedLayers`)과 같은 규칙이다: 합성 계획이 그리는 레이어(감싼 그룹까지 보이고
     * 불투명도가 0보다 큼, 바꾸는 것이 없는 표준 조정 레이어와 그룹은 빠짐) 가운데 가장 아래 픽셀 레이어부터 위로 센다.
     */
    fun mergeVisibleCount(layers: List<LayerSummary>): Int {
        val byId = layers.associateBy { it.id }
        var count = 0
        for (layer in layers) {
            if (layer.group || !isDrawn(layer, byId)) continue
            if (layer.adjustment) {
                if (layer.adjustments.isIdentity && layer.blendMode == BlendMode.NORMAL) continue
                // 가장 아래 픽셀 레이어보다 아래의 조정 레이어는 합치지 않는다
                if (count > 0) count++
            } else {
                count++
            }
        }
        return count
    }

    /**
     * [layer]가 그려지는지: 자기와 감싼 그룹이 모두 눈을 켰고, 감싼 그룹의 불투명도까지 곱한 불투명도가 0보다 크다(엔진의 합성 계획과 같다).
     * 글자 도구가 누른 글자 레이어를 찾을 때도 쓴다(숨긴 그룹 안의 글자는 고르지 않음).
     */
    fun isDrawn(layers: List<LayerSummary>, layer: LayerSummary): Boolean = isDrawn(layer, layers.associateBy { it.id })

    private fun isDrawn(layer: LayerSummary, byId: Map<LayerId, LayerSummary>): Boolean {
        var visible = layer.visible
        var opacity = layer.opacity
        var parent = layer.parent?.let(byId::get)
        var depth = 0
        // 고리가 있어도 끝나게 레이어 수만큼만 따라간다
        while (parent != null && depth++ < byId.size) {
            visible = visible && parent.visible
            opacity *= parent.opacity
            parent = parent.parent?.let(byId::get)
        }
        return visible && opacity > 0f
    }

    private fun isUnder(layer: LayerSummary, groupId: LayerId, byId: Map<LayerId, LayerSummary>): Boolean {
        var parent = layer.parent
        repeat(byId.size) {
            if (parent == null) return false
            if (parent == groupId) return true
            parent = byId[parent]?.parent
        }
        return false
    }
}

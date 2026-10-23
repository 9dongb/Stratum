package com.kiln.stratum.engine.document

/**
 * 레이어 그룹의 나무(결정 기록 '피부 보정'의 1g). 레이어 목록은 그대로 아래부터의 한 줄이고, 그룹 안의 레이어는 [Layer.parent]로
 * 그 그룹(내용 [GroupContent])을 가리킨다. 그룹의 자손은 그룹 바로 아래에 이어 놓인다(포토샵의 레이어 목록과 같다).
 * 그래서 레이어 하나, 또는 그룹 하나와 그 자손은 목록의 이어진 한 덩어리다([blockOf]).
 *
 * 그룹은 통과 그룹이다: 그리지 않고, 안의 레이어에 보이기와 불투명도를 물려준다([opacity], [CompositePlan]).
 */
object LayerTree {

    /**
     * [layers]가 나무 규칙을 지키는지. 부모는 있는 그룹이고 레이어보다 위에 있으며, 레이어와 부모 사이는 모두 그 부모의 자손이어야 한다.
     * 그래서 그룹의 자손은 그룹 바로 아래에 이어 놓인다. 어기면 그 까닭, 지키면 null이다(옮기기·불러오기가 쓴다).
     */
    fun problem(layers: List<Layer>): String? {
        if (layers.none { it.parent != null }) return null
        val indexOf = indexMap(layers)
        for ((i, layer) in layers.withIndex()) {
            val parentId = layer.parent ?: continue
            val p = indexOf[parentId] ?: return "Missing parent ${parentId.value} of ${layer.id.value}"
            if (layers[p].content !is GroupContent) return "Parent ${parentId.value} of ${layer.id.value} is not a group"
            if (p <= i) return "Parent ${parentId.value} must be above ${layer.id.value}"
            for (j in i + 1 until p) {
                if (!isUnder(layers, indexOf, j, parentId)) return "${layers[j].id.value} splits group ${parentId.value}"
            }
        }
        return null
    }

    /** [index] 레이어와, 그룹이면 그 자손까지의 목록 범위(아래부터). 맨 위가 [index]다. */
    fun blockOf(layers: List<Layer>, index: Int): IntRange {
        val layer = layers[index]
        if (layer.content !is GroupContent) return index..index
        val indexOf = indexMap(layers)
        var start = index
        while (start > 0 && isUnder(layers, indexOf, start - 1, layer.id)) start--
        return start..index
    }

    /** [layers]의 [id] 레이어와 그 자손의 번호(그룹을 옮기면 함께 옮겨 보일 레이어). 없는 레이어면 비었다. */
    fun idsWith(layers: List<Layer>, id: LayerId): Set<LayerId> {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return emptySet()
        return blockOf(layers, index).mapTo(HashSet()) { layers[it].id }
    }

    /** [index] 레이어가 [groupId] 그룹 안(자손)인지 */
    fun isUnder(layers: List<Layer>, index: Int, groupId: LayerId): Boolean = isUnder(layers, indexMap(layers), index, groupId)

    /** [layer]를 감싼 그룹들(안쪽부터). [layers]에 없는 그룹(병합처럼 일부만 넘긴 목록)에서 멈춘다. */
    fun ancestors(layers: List<Layer>, layer: Layer): List<Layer> {
        val byId = layers.associateBy { it.id }
        val chain = ArrayList<Layer>()
        var parent = layer.parent?.let(byId::get)
        while (parent != null && chain.size < layers.size) {
            chain += parent
            parent = parent.parent?.let(byId::get)
        }
        return chain
    }

    /**
     * [id] 레이어 아래에 합쳐지는 레이어들(아래부터): 목록에서 그 레이어보다 아래인 것과, 감싼 그룹들. [including]이면 그 레이어도 넣는다.
     * 피부 보정의 기준 스포이드·피부 정돈·피부 색 재기가 '그 레이어 바로 아래까지 합친 그림'을, 리터칭의 '아래 포함' 가져오기와 잡티 자동 제거가
     * '그 레이어와 그 아래를 합친 그림'을 그리는 데 쓴다. 없는 레이어면 비었다.
     *
     * 감싼 그룹들은 보이게, 불투명도 1로 둔다: 그 그림으로 만든 그 레이어의 픽셀은 그려질 때 그 그룹들을 다시 거친다(두 번 걸리지 않게).
     * 그래서 피부 보정 그룹을 숨기거나 흐리게 해도 정돈이 낡지 않고, 숨긴 채 다시 만들어도 잡티 제거가 빠지지 않는다(6단계 검토).
     * 그룹 밖의 아래 레이어는 그대로(숨긴 것은 빠짐)다.
     */
    fun below(layers: List<Layer>, id: LayerId, including: Boolean = false): List<Layer> {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0) return emptyList()
        val ancestors = ancestors(layers, layers[index]).mapTo(HashSet()) { it.id }
        return layers.filterIndexed { i, layer -> i < index || (including && i == index) || layer.id in ancestors }
            .map { layer -> if (layer.id in ancestors && (!layer.visible || layer.opacity != 1f)) layer.copy(visible = true, opacity = 1f) else layer }
    }

    /** 감싼 그룹 수(그룹 밖이면 0). 레이어 목록이 들여 쓰는 깊이다. */
    fun depth(layers: List<Layer>, layer: Layer): Int = ancestors(layers, layer).size

    /** 보이는지: 자기와 감싼 그룹이 모두 눈을 켰다(불투명도는 보지 않음) */
    fun isVisible(layers: List<Layer>, layer: Layer): Boolean = layer.visible && ancestors(layers, layer).all { it.visible }

    /** 얹는 불투명도: 자기 불투명도에 감싼 그룹들의 불투명도를 곱한 값 */
    fun opacity(layers: List<Layer>, layer: Layer): Float = ancestors(layers, layer).fold(layer.opacity) { value, group -> value * group.opacity }

    private fun indexMap(layers: List<Layer>): Map<LayerId, Int> =
        HashMap<LayerId, Int>(layers.size * 2).also { map -> layers.forEachIndexed { i, layer -> map[layer.id] = i } }

    private fun isUnder(layers: List<Layer>, indexOf: Map<LayerId, Int>, index: Int, groupId: LayerId): Boolean {
        var parent = layers[index].parent
        // 고리가 있어도 끝나게 레이어 수만큼만 따라간다
        repeat(layers.size) {
            if (parent == null) return false
            if (parent == groupId) return true
            parent = indexOf[parent]?.let { layers[it].parent }
        }
        return false
    }
}

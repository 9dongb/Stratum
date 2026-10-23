package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.selection.Selection

/**
 * 빈(투명한) 레이어를 [index] 위치에 추가한다. 0이면 맨 아래, 레이어 개수와 같으면 맨 위다.
 * 새 레이어 번호는 문서의 nextLayerId이고, nextLayerId는 1 늘어난다.
 *
 * @property name 화면에 보이는 이름. 앱이 strings.xml에서 만들어 넘긴다.
 * @property parent 새 레이어가 들어갈 그룹(결정 기록 '피부 보정'의 1g). 그 그룹 안 자리여야 한다(앱이 고른 레이어로 정함).
 */
data class AddLayer(val name: String, val index: Int, val parent: LayerId? = null) : EditCommand {
    override fun applyTo(document: Document): Document {
        require(index in 0..document.layers.size) { "Layer index out of range: $index" }
        val layer = Layer(LayerId(document.nextLayerId), name, RasterContent(Raster.EMPTY), parent = parent)
        val layers = document.layers.toMutableList().apply { add(index, layer) }
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
    }
}

/**
 * 조정 레이어를 [index] 자리(0이 맨 아래)에 더한다(결정 기록 '피부 보정'의 1e). 보정은 아직 없고, 앱의 조정 패널이 이 레이어의 보정을 고친다.
 * 마스크는 선택 영역이 있으면 그 모양이고 선택을 푼다. 없으면 다 보이는 마스크다(포토샵 조정 레이어와 같다). 새 레이어 번호는 문서의 nextLayerId다.
 */
data class AddAdjustmentLayer(val name: String, val index: Int, val parent: LayerId? = null) : EditCommand {
    override fun applyTo(document: Document): Document {
        require(index in 0..document.layers.size) { "Layer index out of range: $index" }
        val mask = document.selection ?: Selection.all(PixelSize(document.width, document.height))
        val layer = Layer(LayerId(document.nextLayerId), name, AdjustmentContent, mask = mask, parent = parent)
        val layers = document.layers.toMutableList().apply { add(index, layer) }
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1, selection = null)
    }
}

/**
 * [layerId] 레이어를 바로 위에 복제한다(픽셀·불투명도·혼합 모드·보정이 같다). 새 레이어 번호는 문서의 nextLayerId다.
 * 픽셀은 바꾸지 않는 값이라 원래 레이어와 같은 래스터를 함께 써서 메모리가 늘지 않는다. 없는 레이어면 그대로 둔다.
 * 그룹은 자손과 함께 복제해 원래 그룹 바로 위에 놓는다(자손은 이름 그대로 새 번호, 결정 기록 '피부 보정'의 1g).
 *
 * @property name 새 레이어(그룹)의 이름. 앱이 strings.xml에서 만들어 넘긴다('<이름> 복사').
 */
data class DuplicateLayer(val layerId: LayerId, val name: String) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val block = LayerTree.blockOf(document.layers, index)
        var next = document.nextLayerId
        val ids = HashMap<LayerId, LayerId>()
        for (i in block) ids[document.layers[i].id] = LayerId(next++)
        val copies = block.map { i ->
            val layer = document.layers[i]
            // 자손은 복사한 그룹을 가리키고, 맨 위(복제한 레이어)는 원래 레이어가 있던 그룹 그대로다
            layer.copy(id = ids.getValue(layer.id), name = if (i == index) name else layer.name, parent = layer.parent?.let { ids[it] ?: it })
        }
        val layers = document.layers.toMutableList().apply { addAll(index + 1, copies) }
        return document.copy(layers = layers, nextLayerId = next)
    }
}

/**
 * [layerId] 레이어(그룹이면 자손과 함께)를 새 그룹으로 묶는다(레이어 메뉴 '그룹으로 묶기', 결정 기록 '피부 보정'의 1g).
 * 새 그룹은 통과 그룹이고 그 덩어리 바로 위에 놓이며, 원래 레이어가 있던 그룹 안이다. 새 그룹 번호는 문서의 nextLayerId다.
 * 없는 레이어면 그대로 둔다.
 */
data class GroupLayer(val layerId: LayerId, val name: String) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val group = Layer(LayerId(document.nextLayerId), name, GroupContent, parent = layer.parent)
        val layers = document.layers.toMutableList()
        layers[index] = layer.copy(parent = group.id)
        layers.add(index + 1, group)
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
    }
}

/**
 * [groupId] 그룹을 푼다(레이어 메뉴 '그룹 풀기', 결정 기록 '피부 보정'의 1g). 그룹 줄을 지우고, 바로 안의 레이어는 자리 그대로 그룹이 있던 그룹으로 올라간다.
 * 그룹의 불투명도는 바로 안의 레이어 불투명도에 곱하고, 숨긴 그룹이면 바로 안의 레이어를 숨겨 보이던 모습 그대로다.
 * 그룹이 아니거나 없는 레이어, 문서에 그 그룹 하나뿐이면 그대로 둔다.
 */
data class UngroupLayer(val groupId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(groupId)
        if (index < 0 || document.layers.size == 1) return document
        val group = document.layers[index]
        if (group.content !is GroupContent) return document
        val layers = document.layers.mapNotNull { layer ->
            when {
                layer.id == groupId -> null
                layer.parent == groupId ->
                    layer.copy(parent = group.parent, opacity = layer.opacity * group.opacity, visible = layer.visible && group.visible)
                else -> layer
            }
        }
        return document.copy(layers = layers)
    }
}

/**
 * [layerId] 글자 레이어·유동화 기록이 있는 레이어를 보통 픽셀 레이어로 바꾼다(글자 정보·유동화 기록만 지우고 픽셀은 그대로,
 * 사용자 피드백 8차 1-6, 결정 기록 '얼굴 인식 보정' 3단계). 픽셀 도구로 글자 레이어를 칠하려 할 때 물어본 뒤, 또는 레이어 메뉴의 '픽셀로 바꾸기'로 한다.
 * 둘 다 없거나 없는 레이어면 그대로 둔다.
 */
data class RasterizeLayer(val layerId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        if (layer.text == null && layer.liquify == null) return document
        val layers = document.layers.toMutableList().apply { set(index, layer.copy(text = null, liquify = null)) }
        return document.copy(layers = layers)
    }
}

/**
 * [layerId] 레이어를 지운다. 그룹이면 자손과 함께 지운다(결정 기록 '피부 보정'의 1g).
 * 없는 레이어이거나 지우면 레이어가 하나도 남지 않으면 그대로 둔다(포토샵과 같음).
 */
data class DeleteLayer(val layerId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val block = LayerTree.blockOf(document.layers, index)
        if (block.count() == document.layers.size) return document
        val layers = document.layers.toMutableList().apply { subList(block.first, block.last + 1).clear() }
        return document.copy(layers = layers)
    }
}

/**
 * [layerId] 레이어를 옮긴다. [toIndex]는 옮긴 뒤 목록에서의 위치다(0이 맨 아래). 옮긴 뒤 [parent] 그룹 안(null이면 그룹 밖)이다.
 * 그룹은 자손과 함께 옮긴다(자손은 그룹 바로 아래에 그대로, 결정 기록 '피부 보정'의 1g).
 * 없는 레이어이거나 이미 그 자리·그룹에 있으면, 또는 그룹 규칙을 어기는 자리(다른 그룹 사이를 가름, 자기 안으로)면 그대로 둔다.
 */
data class MoveLayer(val layerId: LayerId, val toIndex: Int, val parent: LayerId? = null) : EditCommand {
    override fun applyTo(document: Document): Document {
        val fromIndex = document.indexOfLayer(layerId)
        if (fromIndex < 0) return document
        require(toIndex in document.layers.indices) { "Layer index out of range: $toIndex" }
        if (fromIndex == toIndex && document.layers[fromIndex].parent == parent) return document
        val block = LayerTree.blockOf(document.layers, fromIndex)
        val moved = document.layers.subList(block.first, block.last + 1).toMutableList()
        moved[moved.lastIndex] = moved.last().copy(parent = parent)
        val layers = document.layers.toMutableList().apply { subList(block.first, block.last + 1).clear() }
        val bottom = toIndex - (moved.size - 1)
        if (bottom !in 0..layers.size) return document
        layers.addAll(bottom, moved)
        if (LayerTree.problem(layers) != null) return document
        return document.copy(layers = layers)
    }
}

/** [layerId] 레이어의 불투명도를 [opacity](0~1)로 바꾼다. 없는 레이어이거나 값이 같으면 그대로 둔다. */
data class SetLayerOpacity(val layerId: LayerId, val opacity: Float) : EditCommand {
    init {
        require(opacity in 0f..1f) { "Opacity must be within 0..1: $opacity" }
    }

    override fun applyTo(document: Document): Document =
        document.updateLayer(layerId) { layer ->
            // -0f를 0f로 맞춘다. 그대로 두면 같은 값인데도 다른 레이어로 비교된다.
            val normalized = opacity + 0f
            if (layer.opacity == normalized) layer else layer.copy(opacity = normalized)
        }
}

/**
 * [layerId] 레이어를 보이거나 숨긴다(레이어 줄의 눈 버튼, 결정 기록 '레이어 보이기'). 불투명도는 그대로다.
 * 없는 레이어이거나 값이 같으면 그대로 둔다.
 */
data class SetLayerVisible(val layerId: LayerId, val visible: Boolean) : EditCommand {
    override fun applyTo(document: Document): Document =
        document.updateLayer(layerId) { layer ->
            if (layer.visible == visible) layer else layer.copy(visible = visible)
        }
}

/**
 * [layerId] 레이어의 이름을 [name]으로 바꾼다(레이어 메뉴의 '이름 바꾸기', 2026-10-22 사용자 요청). 픽셀·글자 정보는 그대로다.
 * 없는 레이어이거나 이름이 같으면 그대로 둔다. 이름 다듬기(공백·길이)는 화면 쪽이 하고, 빈 이름은 받지 않는다.
 */
data class RenameLayer(val layerId: LayerId, val name: String) : EditCommand {
    init {
        require(name.isNotBlank()) { "Layer name must not be blank" }
    }

    override fun applyTo(document: Document): Document =
        document.updateLayer(layerId) { layer ->
            if (layer.name == name) layer else layer.copy(name = name)
        }
}

/** [layerId] 레이어의 혼합 모드를 [blendMode]로 바꾼다. 없는 레이어이거나 값이 같으면 그대로 둔다. */
data class SetLayerBlendMode(val layerId: LayerId, val blendMode: BlendMode) : EditCommand {
    override fun applyTo(document: Document): Document =
        document.updateLayer(layerId) { layer ->
            if (layer.blendMode == blendMode) layer else layer.copy(blendMode = blendMode)
        }
}

/**
 * [layerId] 레이어의 기본 보정 값을 [adjustments]로 바꾼다(비파괴: 픽셀은 그대로 두고 값만 기록한다).
 * 없는 레이어이거나 값이 같으면 그대로 둔다.
 */
data class SetLayerAdjustments(val layerId: LayerId, val adjustments: Adjustments) : EditCommand {
    override fun applyTo(document: Document): Document =
        document.updateLayer(layerId) { layer ->
            val normalized = adjustments.normalized()
            if (layer.adjustments == normalized) layer else layer.copy(adjustments = normalized)
        }
}

/**
 * [layerId] 레이어를 [update]로 바꾼 새 문서를 돌려준다.
 * 레이어가 없거나 [update]가 같은 레이어를 돌려주면 문서를 그대로 돌려준다.
 */
private inline fun Document.updateLayer(layerId: LayerId, update: (Layer) -> Layer): Document {
    val index = indexOfLayer(layerId)
    if (index < 0) return this
    val layer = layers[index]
    val updated = update(layer)
    if (updated === layer) return this
    val newLayers = layers.toMutableList().apply { set(index, updated) }
    return copy(layers = newLayers)
}

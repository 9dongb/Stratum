package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.Raster

/**
 * 빈(투명한) 레이어를 [index] 위치에 추가한다. 0이면 맨 아래, 레이어 개수와 같으면 맨 위다.
 * 새 레이어 번호는 문서의 nextLayerId이고, nextLayerId는 1 늘어난다.
 *
 * @property name 화면에 보이는 이름. 앱이 strings.xml에서 만들어 넘긴다.
 */
data class AddLayer(val name: String, val index: Int) : EditCommand {
    override fun applyTo(document: Document): Document {
        require(index in 0..document.layers.size) { "Layer index out of range: $index" }
        val layer = Layer(LayerId(document.nextLayerId), name, RasterContent(Raster.EMPTY))
        val layers = document.layers.toMutableList().apply { add(index, layer) }
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
    }
}

/**
 * [layerId] 레이어를 바로 위에 복제한다(픽셀·불투명도·혼합 모드·보정이 같다). 새 레이어 번호는 문서의 nextLayerId다.
 * 픽셀은 바꾸지 않는 값이라 원래 레이어와 같은 래스터를 함께 써서 메모리가 늘지 않는다. 없는 레이어면 그대로 둔다.
 *
 * @property name 새 레이어의 이름. 앱이 strings.xml에서 만들어 넘긴다('<이름> 복사').
 */
data class DuplicateLayer(val layerId: LayerId, val name: String) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val copy = document.layers[index].copy(id = LayerId(document.nextLayerId), name = name)
        val layers = document.layers.toMutableList().apply { add(index + 1, copy) }
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
    }
}

/**
 * [layerId] 글자 레이어를 보통 픽셀 레이어로 바꾼다(글자 정보만 지우고 픽셀은 그대로, 사용자 피드백 8차 1-6).
 * 픽셀 도구로 글자 레이어를 칠하려 할 때 물어본 뒤, 또는 레이어 메뉴의 '픽셀로 바꾸기'로 한다. 글자 레이어가 아니거나 없는 레이어면 그대로 둔다.
 */
data class RasterizeLayer(val layerId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0 || document.layers[index].text == null) return document
        val layers = document.layers.toMutableList().apply { set(index, document.layers[index].copy(text = null)) }
        return document.copy(layers = layers)
    }
}

/** [layerId] 레이어를 지운다. 없는 레이어이거나 마지막 남은 레이어면 그대로 둔다(포토샵과 같음). */
data class DeleteLayer(val layerId: LayerId) : EditCommand {
    override fun applyTo(document: Document): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0 || document.layers.size == 1) return document
        val layers = document.layers.toMutableList().apply { removeAt(index) }
        return document.copy(layers = layers)
    }
}

/**
 * [layerId] 레이어를 옮긴다. [toIndex]는 옮긴 뒤 목록에서의 위치다(0이 맨 아래).
 * 없는 레이어이거나 이미 그 자리에 있으면 그대로 둔다.
 */
data class MoveLayer(val layerId: LayerId, val toIndex: Int) : EditCommand {
    override fun applyTo(document: Document): Document {
        val fromIndex = document.indexOfLayer(layerId)
        if (fromIndex < 0) return document
        require(toIndex in document.layers.indices) { "Layer index out of range: $toIndex" }
        if (fromIndex == toIndex) return document
        val layers = document.layers.toMutableList()
        layers.add(toIndex, layers.removeAt(fromIndex))
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

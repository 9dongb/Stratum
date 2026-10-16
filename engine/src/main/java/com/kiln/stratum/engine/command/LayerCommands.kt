package com.kiln.stratum.engine.command

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

/** [layerId] 레이어의 혼합 모드를 [blendMode]로 바꾼다. 없는 레이어이거나 값이 같으면 그대로 둔다. */
data class SetLayerBlendMode(val layerId: LayerId, val blendMode: BlendMode) : EditCommand {
    override fun applyTo(document: Document): Document =
        document.updateLayer(layerId) { layer ->
            if (layer.blendMode == blendMode) layer else layer.copy(blendMode = blendMode)
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

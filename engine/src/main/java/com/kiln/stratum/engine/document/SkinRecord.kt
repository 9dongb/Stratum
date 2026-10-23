package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.skin.SkinFaceArea

/**
 * [피부 보정] 그룹의 기록(결정 기록 '피부 보정'의 2c). 그룹 레이어([Layer.skin])에 붙고 프로젝트에 저장된다.
 * 그룹 안의 세 레이어(색 보정·피부 정돈·잡티 제거)는 보통 레이어라, 사용자가 따로 끄고 불투명도를 바꾸고 마스크를 칠할 수 있다.
 *
 * @property source 원본 사진 레이어(그룹 바로 아래, 바꾸지 않음). 피부 정돈·잡티 제거가 읽는다.
 * @property color 색 보정 조정 레이어(피부 색 종류), [smooth] 피부 정돈 레이어, [spots] 잡티 제거 레이어. 사용자가 지우면 그 기능은 쉰다.
 * @property faces 찾은 얼굴의 자리(문서 좌표, 왼쪽부터, 3단계). 피부 정돈의 반지름·잡티 찾기·다시 재기가 얼굴마다 쓰고,
 *   자르기·회전·크기 변경·옮기기를 하면 함께 옮긴다([mapped]). 특징점은 남기지 않는다(마스크를 만든 뒤에는 자리와 크기만 쓴다).
 * @property segmented 분할 모델로 피부를 골랐는지. false면 얼굴 윤곽만으로 골랐다(목·귀·이마 위가 빠짐).
 * @property texture 질감 유지(0..1, 4단계): 피부 정돈이 고운 결을 얼마나 남기는지.
 * @property redCast 만들 때 사진 전체가 붉었는지(흰자·채도 낮은 곳이 붉음, 3단계). 화면이 만든 뒤 한 번 알리는 데만 쓰고 저장하지 않는다.
 * @property smoothBasis 피부 정돈을 만든 바탕(정돈 레이어 아래를 합친 그림)의 지문(4단계). 지금 바탕과 다르면 정돈이 낡았다([isStale]).
 *   저장하지 않고, 프로젝트를 열 때 그때의 바탕으로 다시 정한다(열기 전에 낡았던 것은 알 수 없다).
 */
data class SkinRecord(
    val source: LayerId,
    val color: LayerId,
    val smooth: LayerId,
    val spots: LayerId,
    val faces: List<SkinFaceArea>,
    val segmented: Boolean,
    val texture: Float = DEFAULT_TEXTURE,
    val redCast: Boolean = false,
    val smoothBasis: CompositeKey? = null
) {
    init {
        require(texture in 0f..1f) { "Texture must be within 0..1: $texture" }
    }

    /**
     * 피부 정돈이 낡았는지: 정돈 레이어 아래를 합친 그림(원본·잡티 제거와 그 아래)이 만들 때와 다르다(원본을 칠하거나 보정을 바꿈, 손으로 잡티를 지움 등).
     * 화면은 [다시 만들기]를 앞세운다. 바탕을 모르면(열기 전) 낡지 않은 것으로 본다.
     */
    fun isStale(document: Document): Boolean {
        val basis = smoothBasis ?: return false
        if (document.indexOfLayer(smooth) < 0) return false
        return !basis.matches(document.copy(layers = LayerTree.below(document.layers, smooth)))
    }

    /** 얼굴 자리를 옮긴 기록(자르기·회전·크기 변경·옮기기, [SkinFaceArea.mapped]) */
    fun mapped(scale: Float, map: (Float, Float) -> Pair<Float, Float>): SkinRecord = copy(faces = faces.map { it.mapped(scale, map) })

    /** 그룹을 복제할 때: 안의 레이어 번호를 [ids](원래 → 복사본)대로 바꾼 기록. 원본 레이어는 그대로다. */
    fun remapped(ids: Map<LayerId, LayerId>): SkinRecord =
        copy(color = ids[color] ?: color, smooth = ids[smooth] ?: smooth, spots = ids[spots] ?: spots)

    companion object {
        /** 질감 유지의 처음 값(0단계 맥 시험: 0은 매끈해 플라스틱 느낌, 50%가 알맞음) */
        const val DEFAULT_TEXTURE = 0.5f

        /**
         * [id] 레이어를 고른 채 피부 도구가 고칠 [피부 보정] 그룹(결정 기록 '피부 보정'의 2c 화면): 그 레이어가 그 그룹이거나,
         * 그 그룹 안의 레이어(가까운 그룹부터)이거나, 그 그룹의 원본이면 그 그룹이다. 원본이 같은 그룹이 여럿이면(복제) 맨 위의 것이다.
         * 없으면 null이다.
         */
        fun groupOf(layers: List<Layer>, id: LayerId): Layer? {
            val layer = layers.firstOrNull { it.id == id } ?: return null
            if (layer.skin != null) return layer
            LayerTree.ancestors(layers, layer).firstOrNull { it.skin != null }?.let { return it }
            return layers.lastOrNull { it.skin?.source == id }
        }

        /**
         * 프로젝트를 연 문서: 피부 보정 그룹들의 정돈 바탕을 지금 그림으로 정한 것(지문은 저장하지 않아, 연 뒤의 편집부터 낡음을 안다).
         * 정돈 레이어가 없는 그룹은 그대로다.
         */
        fun withBases(document: Document): Document {
            if (document.layers.none { it.skin != null }) return document
            return document.copy(layers = document.layers.map { layer ->
                val record = layer.skin ?: return@map layer
                if (document.indexOfLayer(record.smooth) < 0) return@map layer
                layer.copy(skin = record.copy(smoothBasis = CompositeKey.of(document.copy(layers = LayerTree.below(document.layers, record.smooth)))))
            })
        }

        /**
         * 문서 전체를 같이 바꾼 뒤(자르기·회전·뒤집기·크기 변경, 피부 정돈 레이어도 함께 바뀜) [after]의 피부 보정 그룹들의 정돈 바탕을 새 그림으로
         * 다시 정한 문서. [before]에서 낡지 않았던 정돈만 그렇게 한다(낡았던 것은 그대로 낡음).
         */
        fun rebased(before: Document, after: Document): Document {
            if (after.layers.none { it.skin?.smoothBasis != null }) return after
            val fresh = before.layers.mapNotNullTo(HashSet()) { layer -> layer.id.takeIf { layer.skin?.let { !it.isStale(before) } == true } }
            return after.copy(layers = after.layers.map { layer ->
                val record = layer.skin ?: return@map layer
                if (layer.id !in fresh || record.smoothBasis == null || after.indexOfLayer(record.smooth) < 0) return@map layer
                val basis = CompositeKey.of(after.copy(layers = LayerTree.below(after.layers, record.smooth)))
                layer.copy(skin = record.copy(smoothBasis = basis))
            })
        }

        /**
         * [layer]가 피부 보정 그룹의 색 보정·피부 정돈 레이어이고 짝 레이어가 지금 같은 마스크를 함께 쓰면(만든 그대로) 그 짝 레이어.
         * 마스크를 고치는 명령은 짝의 마스크도 같이 바꿔, 사용자에게는 피부 마스크가 하나다. 짝을 따로 칠했으면(마스크가 다름) null이다.
         */
        fun maskPartnerOf(layers: List<Layer>, layer: Layer): Layer? {
            val mask = layer.mask ?: return null
            val record = layers.firstNotNullOfOrNull { group -> group.skin?.takeIf { layer.id == it.color || layer.id == it.smooth } } ?: return null
            val partner = if (layer.id == record.color) record.smooth else record.color
            return layers.firstOrNull { it.id == partner }?.takeIf { it.mask === mask }
        }
    }
}

package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.CompositeKey
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.NoFaceException
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinFaceTone

/** [피부 보정] 그룹과 그 안 레이어들의 이름. 엔진은 문구를 만들지 않으므로 앱이 strings.xml에서 만들어 넘긴다. */
data class SkinLayerNames(val group: String, val color: String, val smooth: String, val spots: String)

/**
 * 피부 보정 만들기(결정 기록 '피부 보정'의 2c): 사진 레이어 [source] 바로 위에 [피부 보정] 그룹(통과)과 그 안의 세 레이어를 만든다.
 * 원본은 바꾸지 않는다. 실행 취소 한 단계다.
 *
 * ```
 * [피부 보정] 그룹(통과, 기록 SkinRecord)
 *  ├ 색 보정   : 조정 레이어(피부 색) + 피부 마스크
 *  ├ 피부 정돈 : 결과 레이어(처음은 비어 있음, 4단계) + 피부 마스크, 불투명도 0 = 강도
 *  └ 잡티 제거 : 빈 레이어(5단계)
 * 원본 사진
 * ```
 * - 얼굴은 원본 레이어 하나(불투명도·혼합 모드·마스크 없이, 보정은 건 채)에서 찾는다([FaceFinder]). 찾지 못하면 [NoFaceException]이고 문서는 그대로다.
 * - 피부 마스크는 [SkinMasker]가 얼굴마다 만들어 합친다. 두 마스크는 같은 타일을 함께 써서 메모리가 늘지 않는다(함께 칠한다, MaskCommands).
 * - 색 보정은 얼굴마다 그 사람 피부의 색 통계를 지닌다(3단계, 슬라이더는 처음에 0이라 아직 바꾸는 것이 없다).
 * - 피부 정돈도 만든다(4단계, [SkinSmoother], 질감 유지 처음 값). 불투명도가 0이라 처음에는 보이지 않고, 정돈 슬라이더가 처음부터 실시간이다.
 * - 그룹은 원본이 있던 그룹 안이고, 새 레이어 번호는 문서의 nextLayerId부터다.
 *
 * [finder]·[masker]는 오래 사는 서비스 참조다. 실행 취소 기록은 명령을 보관만 하므로 서비스를 붙잡지 않는다(SelectObject와 같다).
 */
data class CreateSkinRetouch(
    val source: LayerId,
    val names: SkinLayerNames,
    val finder: FaceFinder,
    val masker: SkinMasker
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(source)
        require(index >= 0) { "No layer $source" }
        val layer = document.layers[index]
        require(layer.content is RasterContent) { "Skin retouch needs a pixel layer" }
        val faces: List<FaceLandmarks>
        val masks: SkinMasker.FaceMasks
        val mask = try {
            val picture = finder.render(document, layer, checkCanceled)
            try {
                faces = finder.search(picture, checkCanceled)
                if (faces.isEmpty()) throw NoFaceException()
                masks = masker.masks(picture, faces, OkLab.Space.of(document.colorSpace), checkCanceled)
            } finally {
                picture.close()
            }
            Selection.of(masker.tiles(masks.masks, PixelSize(document.width, document.height), checkCanceled))
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        var next = document.nextLayerId
        val groupId = LayerId(next++)
        val colorId = LayerId(next++)
        val smoothId = LayerId(next++)
        val spotsId = LayerId(next++)
        val areas = faces.map(SkinFaceArea::of)
        val tones = masks.masks.indices.mapNotNull { i -> masks.masks[i].tone?.let { SkinFaceTone(areas[i], it) } }
        val record = SkinRecord(source, colorId, smoothId, spotsId, areas, masks.segmented, redCast = masks.redCast)
        // HDR 사진이면 결과 레이어도 원본의 게인맵을 함께 쓴다(얼굴만 HDR이 꺼져 어둡게 보이지 않게, 계획 '보완할 점 11')
        val created = listOf(
            Layer(spotsId, names.spots, RasterContent(Raster.EMPTY), gainmap = layer.gainmap, parent = groupId),
            Layer(smoothId, names.smooth, RasterContent(Raster.EMPTY), opacity = 0f, gainmap = layer.gainmap, mask = mask, parent = groupId),
            Layer(colorId, names.color, AdjustmentContent(skin = SkinColor(faces = tones)), mask = mask, parent = groupId),
            Layer(groupId, names.group, GroupContent, parent = layer.parent, skin = record)
        )
        val layers = document.layers.toMutableList().apply { addAll(index + 1, created) }
        // 피부 정돈은 그 아래(원본·빈 잡티 제거와 그 아래 레이어, 감싼 그룹)를 합친 그림에서 만든다
        val below = document.copy(layers = LayerTree.below(layers, smoothId), nextLayerId = next)
        val smoothIndex = index + 2
        layers[smoothIndex] = layers[smoothIndex].withPixels(SkinSmoother.build(below, mask, areas, SkinRecord.DEFAULT_TEXTURE, checkCanceled))
        val groupIndex = index + 4
        layers[groupIndex] = layers[groupIndex].copy(skin = record.copy(smoothBasis = CompositeKey.of(below)))
        return document.copy(layers = layers, nextLayerId = next)
    }

    /** 피부 마스크 타일과 피부 정돈 타일(어림: 문서 타일의 1/4이 피부, 마스크는 픽셀 하나에 1바이트, 정돈은 4바이트) */
    override fun estimateNewBytes(document: Document): Long =
        TileGrid(document.width, document.height).keys().size.toLong() * TILE_PIXEL_COUNT / 4 * 5

    /** 얼굴 찾기 그림·모델과 얼굴마다의 마스크 계산, 피부 정돈 계산 */
    override fun estimateWorkBytes(document: Document): Long = finder.workBytes(document) + masker.workBytes(MAX_FACES) + SkinSmoother.WORK_BYTES

    /** 기록·로그용. 서비스는 넣지 않는다. */
    override fun toString(): String = "CreateSkinRetouch(source=$source)"

    private companion object {
        /** 얼굴 찾기가 돌려주는 얼굴 수의 한도(FaceSearch와 같다) */
        const val MAX_FACES = 8
    }
}

/**
 * [피부 보정] 그룹([group])을 지금 그림에 맞게 다시 만든다(결정 기록 '피부 보정'의 4단계): 피부 정돈을 질감 유지 [texture]로
 * 정돈 레이어 아래를 합친 지금 그림에서 다시 만들고, [remeasure]면 색 보정의 얼굴마다 피부 색 통계도 색 보정 바로 아래까지 합친 지금 그림에서
 * 다시 잰다(스포이드로 고른 기준은 그대로). 질감 유지 슬라이더는 정돈만, [다시 만들기]는 둘 다다. 실행 취소 한 단계다.
 *
 * - 정돈·색 보정 레이어가 없거나(사용자가 지움) 그 마스크가 없으면 그 일은 하지 않는다. 그룹이 아니거나 기록이 없으면 문서 그대로다.
 * - 정돈 레이어의 불투명도·마스크·이름은 그대로 두고 픽셀만 바꾼다.
 */
data class RebuildSkinRetouch(val group: LayerId, val texture: Float, val remeasure: Boolean) : CancellableCommand {

    init {
        require(texture in 0f..1f) { "Texture must be within 0..1: $texture" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val groupIndex = document.indexOfLayer(group)
        if (groupIndex < 0) return document
        val record = document.layers[groupIndex].skin ?: return document
        val layers = document.layers.toMutableList()
        var basis = record.smoothBasis
        try {
            val smoothIndex = document.indexOfLayer(record.smooth)
            val smoothLayer = layers.getOrNull(smoothIndex)
            val smoothMask = smoothLayer?.mask
            if (smoothLayer != null && smoothLayer.content is RasterContent && smoothMask != null) {
                val below = document.copy(layers = LayerTree.below(document.layers, record.smooth))
                // 게인맵은 원본의 것을 함께 쓴다(만들 때와 같다)
                val gainmap = document.layers.getOrNull(document.indexOfLayer(record.source))?.gainmap
                layers[smoothIndex] = smoothLayer.withPixels(SkinSmoother.build(below, smoothMask, record.faces, texture, checkCanceled), gainmap)
                basis = CompositeKey.of(below)
            }
            val colorIndex = document.indexOfLayer(record.color)
            val colorLayer = layers.getOrNull(colorIndex)
            val color = (colorLayer?.content as? AdjustmentContent)?.skin
            val colorMask = colorLayer?.mask
            if (remeasure && colorLayer != null && color != null && colorMask != null) {
                // 색 보정 바로 아래까지: 다시 만든 정돈을 넣어 잰다(보이는 그대로의 피부)
                val updated = document.copy(layers = layers)
                val below = updated.copy(layers = LayerTree.below(updated.layers, record.color))
                val tones = SkinToneMeter.measure(below, colorMask, color.faces.map { it.area }, checkCanceled)
                layers[colorIndex] = colorLayer.withSkinColor(
                    color.copy(faces = color.faces.mapIndexed { i, face -> tones[i]?.let { face.copy(tone = it) } ?: face })
                )
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        layers[groupIndex] = layers[groupIndex].copy(skin = record.copy(texture = texture, smoothBasis = basis))
        return document.copy(layers = layers)
    }

    /** 다시 만든 피부 정돈 타일(정돈 레이어의 마스크 타일 수만큼, 픽셀 하나에 4바이트) */
    override fun estimateNewBytes(document: Document): Long {
        val record = document.layers.getOrNull(document.indexOfLayer(group))?.skin ?: return 0L
        val mask = document.layers.getOrNull(document.indexOfLayer(record.smooth))?.mask ?: return 0L
        return mask.tiles.size.toLong() * TILE_PIXEL_COUNT * 4
    }

    override fun estimateWorkBytes(document: Document): Long = SkinSmoother.WORK_BYTES + if (remeasure) SkinToneMeter.WORK_BYTES else 0L
}

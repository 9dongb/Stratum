package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.CompositeKey
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.NoFaceException
import com.kiln.stratum.engine.skin.NoSpotsException
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinFaceTone
import com.kiln.stratum.engine.skin.SkinHealReport
import com.kiln.stratum.engine.skin.SkinSpot

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
 * [피부 보정] 그룹([group])을 지금 그림에 맞게 다시 만든다(결정 기록 '피부 보정'의 4·5단계): 피부 정돈을 질감 유지 [texture]로
 * 정돈 레이어 아래를 합친 지금 그림에서 다시 만들고, [remeasure]면 자동으로 지운 잡티도 지금 그림으로 다시 지우고(5단계) 색 보정의 얼굴마다
 * 피부 색 통계도 색 보정 바로 아래까지 합친 지금 그림에서 다시 잰다(스포이드로 고른 기준은 그대로). 질감 유지 슬라이더는 정돈만, [다시 만들기]는
 * 모두다. 실행 취소 한 단계다.
 *
 * - 잡티: 기록한 자리([SkinRecord.healed])를 잡티 제거 레이어에서 비우고 다시 지운다. 사용자가 지우개로 되살린 자리(그 가운데가 비었음)는 기록에서 뺀다.
 *   손으로 지운 것은 기록에 없어 그대로다(기록한 자리와 겹친 곳은 다시 지운 것으로 바뀐다).
 * - 정돈·색 보정·잡티 제거 레이어가 없거나(사용자가 지움) 그 마스크가 없으면 그 일은 하지 않는다. 그룹이 아니거나 기록이 없으면 문서 그대로다.
 * - 정돈 레이어의 불투명도·마스크·이름은 그대로 두고 픽셀만 바꾼다.
 */
data class RebuildSkinRetouch(val group: LayerId, val texture: Float, val remeasure: Boolean) : CancellableCommand {

    init {
        require(texture in 0f..1f) { "Texture must be within 0..1: $texture" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val groupIndex = document.indexOfLayer(group)
        if (groupIndex < 0) return document
        var record = document.layers[groupIndex].skin ?: return document
        try {
            var current = document
            if (remeasure && record.healed.isNotEmpty()) {
                SkinSpotWork.reheal(current, record, checkCanceled)?.let { (healedDocument, spots) ->
                    current = healedDocument
                    record = record.copy(healed = spots)
                }
            }
            val (smoothed, basis) = SkinSpotWork.rebuiltSmoothing(current, record, texture, checkCanceled)
            val layers = smoothed.toMutableList()
            val colorIndex = current.indexOfLayer(record.color)
            val colorLayer = layers.getOrNull(colorIndex)
            val color = (colorLayer?.content as? AdjustmentContent)?.skin
            val colorMask = colorLayer?.mask
            if (remeasure && colorLayer != null && color != null && colorMask != null) {
                // 색 보정 바로 아래까지: 다시 만든 정돈을 넣어 잰다(보이는 그대로의 피부)
                val updated = current.copy(layers = layers)
                val below = updated.copy(layers = LayerTree.below(updated.layers, record.color))
                val tones = SkinToneMeter.measure(below, colorMask, color.faces.map { it.area }, checkCanceled)
                layers[colorIndex] = colorLayer.withSkinColor(
                    color.copy(faces = color.faces.mapIndexed { i, face -> tones[i]?.let { face.copy(tone = it) } ?: face })
                )
            }
            // 지난 잡티 자동 제거의 결과 알림은 남기지 않는다(그 명령을 기록할 때 한 번 알림)
            layers[groupIndex] = layers[groupIndex].copy(skin = record.copy(texture = texture, smoothBasis = basis ?: record.smoothBasis, healReport = null))
            return current.copy(layers = layers)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
    }

    /** 다시 만든 피부 정돈 타일(정돈 레이어의 마스크 타일 수만큼, 픽셀 하나에 4바이트)과, 다시 지우면 잡티 제거 레이어의 새 타일(같은 어림) */
    override fun estimateNewBytes(document: Document): Long {
        val record = document.layers.getOrNull(document.indexOfLayer(group))?.skin ?: return 0L
        val mask = document.layers.getOrNull(document.indexOfLayer(record.smooth))?.mask ?: return 0L
        val smoothing = mask.tiles.size.toLong() * TILE_PIXEL_COUNT * 4
        return if (remeasure && record.healed.isNotEmpty()) 2 * smoothing else smoothing
    }

    override fun estimateWorkBytes(document: Document): Long =
        SkinSmoother.WORK_BYTES + if (remeasure) SkinToneMeter.WORK_BYTES + SkinSpotWork.HEAL_WORK_BYTES else 0L
}

/**
 * 잡티 자동 제거(결정 기록 '피부 보정'의 5단계, 사용자 결정 "바로 지우기, 점은 남김"): [group] 피부 보정 그룹의 얼굴마다 잡티를 찾아([SkinSpotHealer.find])
 * 점(모반)은 남기고 나머지를 잡티 제거 레이어에 스팟 복구로 지운 뒤([SkinSpotHealer.heal]), 피부 정돈을 지운 그림으로 다시 만든다(같은 단계,
 * 계획 '보완할 점 2'). 지운 자리는 기록에 더하고([SkinRecord.healed]), 지운 수·남긴 점 수를 화면에 알린다([SkinRecord.healReport]).
 * 실행 취소 한 단계다. 지울 것이 없으면 [NoSpotsException]이고 문서는 그대로다.
 *
 * - 찾는 그림은 잡티 제거 레이어와 그 아래(원본)다. 이미 지운 자리는 다시 찾지 않아, 다시 누르면 남은 잡티(얼굴마다 한도를 넘었던 것 등)를 지운다.
 * - 피부 마스크는 색 보정 레이어의 것이다(사용자가 고친 피부, 없으면 피부 정돈의 것). 선택 영역이 있으면 그 안만 지운다(리터칭과 같다).
 */
data class HealSkinSpots(val group: LayerId) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val groupIndex = document.indexOfLayer(group)
        if (groupIndex < 0) return document
        val record = document.layers[groupIndex].skin ?: return document
        val spotsIndex = document.indexOfLayer(record.spots)
        val spotsLayer = document.layers.getOrNull(spotsIndex)?.takeIf { it.content is RasterContent } ?: return document
        val mask = SkinSpotWork.skinMaskOf(document, record) ?: return document
        try {
            val below = document.copy(layers = LayerTree.below(document.layers, record.spots, including = true), selection = null)
            val found = SkinSpotHealer.find(below, mask, record.faces, record.healed, checkCanceled)
            if (found.spots.isEmpty()) throw NoSpotsException(found.moles)
            val healed = SkinSpotHealer.heal(document, record.spots, found.spots, checkCanceled) ?: return document
            if (healed.spots.isEmpty()) throw NoSpotsException(found.moles)
            val updated = document.copy(layers = document.layers.toMutableList().apply { set(spotsIndex, spotsLayer.withPixels(healed.raster, healed.gainmap)) })
            // 피부 정돈을 지운 그림으로 다시 만든다(정돈이 지운 잡티를 다시 덮지 않게)
            val (smoothed, basis) = SkinSpotWork.rebuiltSmoothing(updated, record, record.texture, checkCanceled)
            val layers = smoothed.toMutableList()
            layers[groupIndex] = layers[groupIndex].copy(
                skin = record.copy(
                    healed = record.healed + healed.spots,
                    smoothBasis = basis ?: record.smoothBasis,
                    healReport = SkinHealReport(healed.spots.size, found.moles)
                )
            )
            return updated.copy(layers = layers)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
    }

    /** 잡티 제거 레이어의 새 타일과 다시 만든 피부 정돈 타일(피부 마스크 타일 수만큼씩, 픽셀 하나에 4바이트) */
    override fun estimateNewBytes(document: Document): Long {
        val record = document.layers.getOrNull(document.indexOfLayer(group))?.skin ?: return 0L
        val mask = SkinSpotWork.skinMaskOf(document, record) ?: return 0L
        return 2 * mask.tiles.size.toLong() * TILE_PIXEL_COUNT * 4
    }

    override fun estimateWorkBytes(document: Document): Long = SkinSpotWork.HEAL_WORK_BYTES + SkinSmoother.WORK_BYTES
}

/** 잡티 자동 제거와 다시 만들기가 함께 쓰는 일(결정 기록 '피부 보정'의 4·5단계) */
internal object SkinSpotWork {

    /** 잡티를 찾고 지우는 동안의 메모리 어림: 얼굴 틀 그림·배열, 가져올 그림(1024px 묶음 몇 개) */
    const val HEAL_WORK_BYTES = 96L * 1024 * 1024

    /** 피부 마스크: 색 보정 레이어의 것(사용자가 고친 피부), 없으면 피부 정돈의 것 */
    fun skinMaskOf(document: Document, record: SkinRecord): Selection? =
        document.layers.getOrNull(document.indexOfLayer(record.color))?.mask
            ?: document.layers.getOrNull(document.indexOfLayer(record.smooth))?.mask

    /**
     * 피부 정돈을 [document]의 지금 그림(정돈 레이어 아래)에서 질감 유지 [texture]로 다시 만든 레이어 목록과 그 바탕의 지문.
     * 정돈 레이어나 그 마스크가 없으면 그대로이고 지문은 null이다. 게인맵은 원본의 것을 함께 쓴다(만들 때와 같다).
     */
    fun rebuiltSmoothing(document: Document, record: SkinRecord, texture: Float, checkCanceled: () -> Unit): Pair<List<Layer>, CompositeKey?> {
        val layers = document.layers.toMutableList()
        val smoothIndex = document.indexOfLayer(record.smooth)
        val smoothLayer = layers.getOrNull(smoothIndex)
        val smoothMask = smoothLayer?.mask
        if (smoothLayer == null || smoothLayer.content !is RasterContent || smoothMask == null) return layers to null
        val below = document.copy(layers = LayerTree.below(document.layers, record.smooth))
        val gainmap = document.layers.getOrNull(document.indexOfLayer(record.source))?.gainmap
        layers[smoothIndex] = smoothLayer.withPixels(SkinSmoother.build(below, smoothMask, record.faces, texture, checkCanceled), gainmap)
        return layers to CompositeKey.of(below)
    }

    /**
     * 기록한 잡티([SkinRecord.healed])를 지금 그림으로 다시 지운 문서와 다시 지운 자리. 사용자가 지우개로 되살린 자리(가운데가 반 넘게 비었음)는 뺀다.
     * 기록한 자리를 잡티 제거 레이어에서 먼저 비운다(지운 붓보다 조금 넓게, 단단히). 잡티 제거 레이어가 없으면 null이다.
     */
    fun reheal(document: Document, record: SkinRecord, checkCanceled: () -> Unit): Pair<Document, List<SkinSpot>>? {
        val layer = document.layers.getOrNull(document.indexOfLayer(record.spots)) ?: return null
        val raster = (layer.content as? RasterContent)?.raster ?: return null
        val kept = record.healed.filter { spot -> alphaAt(raster, spot.x.toInt(), spot.y.toInt(), document) >= 128 }
        var cleared = document
        for (spot in record.healed) {
            checkCanceled()
            val settings = BrushSettings(size = SkinSpotHealer.brushOf(spot).size + 2f, hardness = 1f, opacity = 1f)
            cleared = PaintStroke(record.spots, listOf(StrokePoint(spot.x, spot.y)), settings, erase = true).applyTo(cleared, checkCanceled)
        }
        val healed = SkinSpotHealer.heal(cleared, record.spots, kept, checkCanceled) ?: return null
        val index = cleared.indexOfLayer(record.spots)
        val layers = cleared.layers.toMutableList().apply { set(index, cleared.layers[index].withPixels(healed.raster, healed.gainmap)) }
        return cleared.copy(layers = layers) to healed.spots
    }

    /** [raster]의 ([x], [y]) 알파(문서 밖·빈 타일은 0) */
    private fun alphaAt(raster: Raster, x: Int, y: Int, document: Document): Int {
        if (x < 0 || y < 0 || x >= document.width || y >= document.height) return 0
        val tile = raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return 0
        return android.graphics.Color.alpha(tile.pixelAt(x % TILE_SIZE, y % TILE_SIZE))
    }
}

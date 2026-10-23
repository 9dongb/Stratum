package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
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
import com.kiln.stratum.engine.skin.SkinColor

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
 * - 피부 마스크는 [SkinMasker]가 얼굴마다 만들어 합친다. 두 마스크는 같은 타일을 함께 써서 메모리가 늘지 않는다(그 뒤에는 따로 칠한다).
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
                masks = masker.masks(picture, faces, checkCanceled)
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
        val record = SkinRecord(source, colorId, smoothId, spotsId, faces, masks.segmented)
        val created = listOf(
            Layer(spotsId, names.spots, RasterContent(Raster.EMPTY), parent = groupId),
            Layer(smoothId, names.smooth, RasterContent(Raster.EMPTY), opacity = 0f, mask = mask, parent = groupId),
            Layer(colorId, names.color, AdjustmentContent(skin = SkinColor()), mask = mask, parent = groupId),
            Layer(groupId, names.group, GroupContent, parent = layer.parent, skin = record)
        )
        val layers = document.layers.toMutableList().apply { addAll(index + 1, created) }
        return document.copy(layers = layers, nextLayerId = next)
    }

    /** 피부 마스크 타일(어림: 문서 타일의 1/4이 경계 타일) */
    override fun estimateNewBytes(document: Document): Long =
        TileGrid(document.width, document.height).keys().size.toLong() * TILE_PIXEL_COUNT / 4

    /** 얼굴 찾기 그림·모델과 얼굴마다의 마스크 계산 */
    override fun estimateWorkBytes(document: Document): Long = finder.workBytes(document) + masker.workBytes(MAX_FACES)

    /** 기록·로그용. 서비스는 넣지 않는다. */
    override fun toString(): String = "CreateSkinRetouch(source=$source)"

    private companion object {
        /** 얼굴 찾기가 돌려주는 얼굴 수의 한도(FaceSearch와 같다) */
        const val MAX_FACES = 8
    }
}

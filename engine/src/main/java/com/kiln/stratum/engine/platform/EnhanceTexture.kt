package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintModel
import com.kiln.stratum.engine.inpaint.InpaintTexture
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster

/**
 * 질감 향상(결정 기록 'AI 제거·AI 선택'의 '질감 향상'). [layerId] 레이어의 [area]를 AI로 채운 바로 뒤에, 같은 채우기를 채우기 전 픽셀
 * [original]에서 다시 하되 잡음 대신 둘레 원본의 고운 결을 붙인다([Inpainter.paint]의 texture). 창·모델·색 맞추기는 [InpaintRegion]과 같고,
 * 결은 배율(모델 1px이 덮는 문서 px)이 [InpaintTexture.MIN_SCALE] 이상인 창에만 붙인다. 그 밖의 창은 채우기와 같이 잡음을 더한다.
 * 게인맵은 지금 문서의 것(채우기 결과)을 그대로 둔다(매끈한 그림이라 결이 없다).
 *
 * 지금 문서는 [original]의 [area]를 AI로 채운 결과여야 한다(앱이 확인한다). 채운 결과 위에 붙이지 않는 까닭은 잡음과 결이 겹치고,
 * 쓰기 몫이 1보다 작은 경계 띠가 두 번 섞이기 때문이다.
 * 더할 결이 없으면(결을 붙일 창이 없거나, 짝을 찾지 못해 한 조각도 붙이지 못함) [NothingToEnhanceException]을 던진다.
 * 없는 레이어, 빈 원본, 선택 영역 없이 고른 곳을 채우라는 경우는 문서를 그대로 돌려준다.
 * 모델을 이 기기에서 쓸 수 없으면 [com.kiln.stratum.engine.inpaint.InpaintUnavailableException]을 그대로 던진다.
 */
class EnhanceTexture(
    val layerId: LayerId,
    val area: InpaintArea,
    val model: InpaintModel,
    val original: Raster
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        if (layer.content !is RasterContent || original.tiles.isEmpty()) return document
        val selection = document.selection
        if (area is InpaintArea.Selected && selection == null) return document
        val size = PixelSize(document.width, document.height)
        // 모델을 부르기 전에, 결을 붙일 만큼 크게 키우는 창이 있는지 본다
        if (Inpainter.windows(area, selection, size, model.size).none { InpaintTexture.applies(it.scale(model.size)) }) {
            throw NothingToEnhanceException()
        }
        var textured = false
        val painted = try {
            Inpainter.paint(original, size, area, selection, Inpainter.FillSource.Model(model), checkCanceled, texture = true) { textured = true }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (!textured) throw NothingToEnhanceException()
        val layers = document.layers.toMutableList().apply { set(index, layer.withPixels(painted, layer.gainmap)) }
        return document.copy(layers = layers)
    }

    /** 새로 남을 타일은 채우기와 같은 범위다(게인맵 몫까지 넉넉히 센다) */
    override fun estimateNewBytes(document: Document): Long = InpaintRegion(layerId, area, model).estimateNewBytes(document)

    override fun estimateWorkBytes(document: Document): Long = Inpainter.WORK_BYTES + TextureTransfer.WORK_BYTES + model.pendingLoadBytes

    /** 기록·로그용. 자국 목록·모델·원본은 길어서 넣지 않는다. */
    override fun toString(): String = "EnhanceTexture(layer=${layerId.value}, area=${area::class.simpleName})"
}

/** 질감 향상에서 더할 결이 없음(원본 해상도와 거의 같게 채운 작은 곳, 또는 둘레에 가져올 결이 없음). 문서는 그대로다. */
class NothingToEnhanceException : RuntimeException("Nothing to enhance")

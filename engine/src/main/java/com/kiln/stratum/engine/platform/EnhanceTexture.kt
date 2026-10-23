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
 * 더할 결이 없으면 [NothingToEnhanceException]을 던진다: 결을 붙일 창이 없으면 모델을 부르기 전에([NothingToEnhanceException.Reason.SMALL]),
 * 짝을 찾지 못해 한 조각도 붙이지 못하면 채운 뒤에([NothingToEnhanceException.Reason.NO_SOURCE]).
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
        if (!canRun(document)) return document
        val layer = document.layers[index]
        val size = PixelSize(document.width, document.height)
        // 모델을 부르기 전에, 결을 붙일 만큼 크게 키우는 창이 있는지 본다
        if (!hasTextureWindow(document)) throw NothingToEnhanceException(NothingToEnhanceException.Reason.SMALL)
        var textured = false
        val painted = try {
            Inpainter.paint(original, size, area, document.selection, Inpainter.FillSource.Model(model), checkCanceled, texture = true) { textured = true }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (!textured) throw NothingToEnhanceException(NothingToEnhanceException.Reason.NO_SOURCE)
        val layers = document.layers.toMutableList().apply { set(index, layer.withPixels(painted, layer.gainmap)) }
        return document.copy(layers = layers)
    }

    /** 무엇을 해 볼 만한지: 레이어가 픽셀 레이어로 있고, 원본이 비지 않았고, 고른 곳을 채우라면 선택 영역이 있다 */
    private fun canRun(document: Document): Boolean {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return false
        if (layer.content !is RasterContent || original.tiles.isEmpty()) return false
        return !(area is InpaintArea.Selected && document.selection == null)
    }

    /** 결을 붙일 만큼 크게 키우는 창([InpaintTexture.MIN_SCALE] 이상)이 하나라도 있는지. 계획 단계(1024px 이하)만 본다. */
    private fun hasTextureWindow(document: Document): Boolean =
        Inpainter.windows(area, document.selection, PixelSize(document.width, document.height), model.size)
            .any { InpaintTexture.applies(it.scale(model.size)) }

    /**
     * 새로 남을 타일은 채우기와 같은 범위다(게인맵 몫까지 넉넉히 센다). 해 볼 것이 없거나 결을 붙일 창이 없으면 0이다
     * (모델을 부르지 않고 알리기만 하는데 실행 취소 단계를 버리지 않게).
     */
    override fun estimateNewBytes(document: Document): Long =
        if (canRun(document) && hasTextureWindow(document)) InpaintRegion(layerId, area, model).estimateNewBytes(document) else 0L

    /** 채우기의 계산 메모리와 조각 판·그 GPU 사본, 처음이면 모델 불러오기. [estimateNewBytes]처럼 할 일이 없으면 0이다. */
    override fun estimateWorkBytes(document: Document): Long =
        if (canRun(document) && hasTextureWindow(document)) Inpainter.WORK_BYTES + TextureTransfer.WORK_BYTES + model.pendingLoadBytes else 0L

    /** 기록·로그용. 자국 목록·모델·원본은 길어서 넣지 않는다. */
    override fun toString(): String = "EnhanceTexture(layer=${layerId.value}, area=${area::class.simpleName})"
}

/** 질감 향상에서 더할 결이 없음. 문서는 그대로다. */
class NothingToEnhanceException(val reason: Reason) : RuntimeException("Nothing to enhance: $reason") {

    enum class Reason {
        /** 모든 창이 원본 해상도와 거의 같게(배율 [InpaintTexture.MIN_SCALE] 아래로) 채워졌다. 모델은 부르지 않았다. */
        SMALL,

        /** 결을 붙일 창은 있지만 둘레에 가져올 조각(다 알고 불투명한 곳)이 없어 한 조각도 붙이지 못했다 */
        NO_SOURCE
    }
}

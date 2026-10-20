package com.kiln.stratum.engine.platform

import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintModel
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import kotlin.math.ceil
import kotlin.math.floor

/**
 * [layerId] 레이어의 [area]를 AI 모델([model])로 채운다(AI 제거, 결정 기록 'AI 제거·AI 선택'). 원본 해상도에 바로 적용한다.
 *
 * 채울 곳 둘레를 잘라 모델 크기로 채우고, 원본 타일에는 채울 곳 안만 바꿔 넣는다([Inpainter]). 밖의 픽셀은 값이 그대로다.
 * 채울 곳은 칠한 자국, 선택 영역([InpaintArea.Selected]이면 선택 영역 자체), 톡 눌러 고른 물체 모양([InpaintArea.Masked])이다.
 * 선택 영역이 있으면 그 안에만 칠한다. 게인맵은 둘레 값으로 매끈하게 잇는다.
 * 없는 레이어, 빈 레이어, 문서에 닿지 않는 곳, 선택 영역 없이 고른 곳을 채우라는 경우는 문서를 그대로 돌려준다.
 * 모델을 이 기기에서 쓸 수 없으면 [com.kiln.stratum.engine.inpaint.InpaintUnavailableException]을 그대로 던진다.
 *
 * [model]은 오래 사는 서비스 참조다. 실행 취소 기록은 명령을 보관만 하고 다시 실행할 때는 저장한 문서를 쓰므로, 명령이 모델을 붙잡지 않는다.
 */
data class InpaintRegion(
    val layerId: LayerId,
    val area: InpaintArea,
    val model: InpaintModel
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        // 빈 레이어에는 채울 둘레가 없다
        if (raster.tiles.isEmpty()) return document
        val selection = document.selection
        if (area is InpaintArea.Selected && selection == null) return document
        val size = PixelSize(document.width, document.height)
        val painted = try {
            Inpainter.paint(raster, size, area, selection, Inpainter.FillSource.Model(model), checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (painted === raster) return document
        val gainmap = try {
            layer.gainmap?.let { GainmapEdits.fill(it, size, area, selection, checkCanceled) }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        val layers = document.layers.toMutableList().apply { set(index, layer.copy(content = RasterContent(painted), gainmap = gainmap)) }
        return document.copy(layers = layers)
    }

    /** 채울 곳 둘레(넓히기·번짐 몫 [REACH]) 안 타일마다 새 타일 한 장, 게인맵이 있으면 그 타일도. 마스크를 그리지 않는 값싼 위쪽 어림이다. */
    override fun estimateNewBytes(document: Document): Long {
        val size = PixelSize(document.width, document.height)
        val bounds = Inpainter.bounds(area, document.selection, size) ?: return 0L
        val reach = PixelRect(bounds.left - REACH, bounds.top - REACH, bounds.right + REACH, bounds.bottom + REACH).intersect(PixelRect.of(size)) ?: return 0L
        val tileBytes = TILE_SIZE.toLong() * TILE_SIZE * Int.SIZE_BYTES
        var bytes = BrushTiles.keysIn(reach).size * tileBytes
        val index = document.indexOfLayer(layerId)
        val gainmap = if (index >= 0) document.layers[index].gainmap else null
        if (gainmap != null) {
            val scaleX = gainmap.width.toFloat() / size.width
            val scaleY = gainmap.height.toFloat() / size.height
            val scaled = PixelRect(
                floor(reach.left * scaleX).toInt(), floor(reach.top * scaleY).toInt(),
                ceil(reach.right * scaleX).toInt().coerceAtLeast(1), ceil(reach.bottom * scaleY).toInt().coerceAtLeast(1)
            )
            bytes += BrushTiles.keysIn(scaled).size * tileBytes
        }
        return bytes
    }

    /** 창 하나의 계산 메모리와, 모델을 아직 불러오지 않았으면 불러올 때 쓰는 메모리 */
    override fun estimateWorkBytes(document: Document): Long = Inpainter.WORK_BYTES + model.pendingLoadBytes

    /** 기록·로그용. 자국 목록과 모델은 길어서 넣지 않는다. */
    override fun toString(): String = "InpaintRegion(layer=${layerId.value}, area=${area::class.simpleName})"

    private companion object {
        /** 채울 곳 범위 밖으로 바꿀 수 있는 거리(문서 px). 넓히기와 쌍선형 번짐을 넉넉히 담는다. */
        const val REACH = 64
    }
}

package com.kiln.stratum.engine.platform

import android.graphics.ImageDecoder
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportSizing
import com.kiln.stratum.engine.importing.InsertSizing
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.engine.transform.RasterTransform
import com.kiln.stratum.engine.transform.ResizePlan
import com.kiln.stratum.engine.transform.TransformPlan

/** 넣을 사진을 읽지 못했다(사진이 아니거나 파일을 열 수 없음). 문서는 그대로다. */
class PhotoUnreadableException(cause: Throwable? = null) : RuntimeException("Could not read the photo", cause)

/**
 * 사진([source])을 [index] 자리(0이 맨 아래)에 새 레이어 [name]으로 넣는다(사용자 피드백 12차: 레이어 패널의 사진 넣기, 끌어 놓기).
 * 문서보다 크면 비율을 지키며 문서 안에 들어가게 줄이고([InsertSizing]: 해독하면서 반씩 줄인 뒤 남은 배율은 GPU로, [ImageResizer]),
 * 작으면 그대로 둔다. 자리는 문서 가운데이고 문서 크기는 그대로다. 사진은 문서 색 공간(sRGB·P3) 값으로 풀고 게인맵(HDR)은 넣지 않는다.
 * 새 레이어 번호는 문서의 nextLayerId다. 실행 취소 한 단계다.
 * 사진을 읽지 못하면 [PhotoUnreadableException], 메모리가 모자라면 [NotEnoughMemoryException]을 던진다.
 *
 * @property info 넣기 전에 읽은 사진의 머리 정보(크기·형식). 해독하며 줄일 단계와 메모리 어림에 쓴다.
 */
class InsertImageLayer(
    private val source: ImageDecoder.Source,
    val info: PhotoInfo,
    val name: String,
    val index: Int
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        require(index in 0..document.layers.size) { "Layer index out of range: $index" }
        val documentSize = PixelSize(document.width, document.height)
        val sample = InsertSizing.sampleSize(info.size, InsertSizing.fit(info.size, documentSize))
        val decoded = PhotoImporter.decodeLayer(source, document.colorSpace, sample, checkCanceled)
        // 해독기가 줄인 크기는 어림과 1px쯤 다를 수 있어 푼 크기로 다시 맞춘다
        val size = InsertSizing.fit(decoded.size, documentSize)
        return try {
            val image = if (size == decoded.size) decoded.raster else ImageResizer.resize(decoded.raster, ResizePlan(decoded.size, size), checkCanceled)
            val origin = InsertSizing.centered(size, documentSize)
            val plan = TransformPlan.place(size, PixelRect.of(size), Orientation.IDENTITY, documentSize, origin.x, origin.y)
            val placed = RasterTransform.transform(image, plan, CanvasTileComposer, checkCanceled)
            val layer = Layer(LayerId(document.nextLayerId), name, RasterContent(placed))
            val layers = document.layers.toMutableList().apply { add(index, layer) }
            document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
    }

    /** 새 레이어가 걸치는 타일 */
    override fun estimateNewBytes(document: Document): Long {
        val documentSize = PixelSize(document.width, document.height)
        return InsertSizing.placedTileBytes(InsertSizing.fit(info.size, documentSize), documentSize)
    }

    /** 해독하는 동안의 비트맵, 푼 타일, (줄이면) 줄인 타일. 다 쓰면 버린다. */
    override fun estimateWorkBytes(document: Document): Long {
        val documentSize = PixelSize(document.width, document.height)
        val fit = InsertSizing.fit(info.size, documentSize)
        val sampled = ImportSizing.sampledSize(info.size, InsertSizing.sampleSize(info.size, fit))
        val resized = if (sampled == fit) 0L else fit.pixelCount * Int.SIZE_BYTES
        return ImportSizing.decodeBytes(sampled, info.mimeType) + sampled.pixelCount * Int.SIZE_BYTES + resized
    }

    override fun toString(): String = "InsertImageLayer(${info.size.width}x${info.size.height}, index=$index)"
}

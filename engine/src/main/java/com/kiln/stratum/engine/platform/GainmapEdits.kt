package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Paint
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.HdrInfo
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.effect.EffectFill
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.effect.EffectMath
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 레이어 픽셀을 고치는 명령이 그 레이어의 게인맵도 같이 고친다(결정 기록 'HDR').
 *
 * 게인맵은 레이어 전체를 늘여 덮으므로, 문서 좌표의 획·간격·선택 영역을 게인맵 배율로 줄여
 * 같은 명령을 게인맵만 담은 임시 문서에 건다. 게인맵은 보통 원본의 1/4이라 계산이 작다.
 */
internal object GainmapEdits {

    /**
     * 브러시 획 자리의 게인맵을 '밝히지 않음'으로 칠한다(같은 크기·단단함·불투명도). 사진 위에 칠한 색이 HDR로 밝아지지 않게 한다.
     * [selection]이 있으면 그 안에만 칠한다(게인맵 크기로 줄인 것).
     */
    fun neutralize(
        gainmap: HdrGainmap,
        size: PixelSize,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        info: HdrInfo,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): HdrGainmap {
        val scaleX = gainmap.width.toFloat() / size.width
        val scaleY = gainmap.height.toFloat() / size.height
        val brush = settings.copy(size = scaledSize(settings.size, scaleX), color = Gainmaps.neutralColor(info))
        val document = documentOf(gainmap).copy(selection = selection?.let { scaledSelection(it, size, gainmap) })
        val painted = PaintStroke(LayerId(0), scaled(dabs, scaleX, scaleY), brush, erase = false).applyTo(document, checkCanceled)
        return gainmap.copy(raster = rasterOf(painted))
    }

    /**
     * 리터칭 획 자리에 같은 간격([offset], 문서 픽셀)만큼 떨어진 게인맵을 베낀다(복제 도장 방식, 밝기 맞추기 없음).
     * 복구·스팟 복구도 무늬를 가져온 자리의 게인맵을 그대로 가져온다. 간격이 게인맵 한 칸보다 작으면 그대로다.
     */
    fun clone(
        gainmap: HdrGainmap,
        size: PixelSize,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        offset: SourceOffset,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): HdrGainmap {
        val scaleX = gainmap.width.toFloat() / size.width
        val scaleY = gainmap.height.toFloat() / size.height
        val moved = SourceOffset((offset.x * scaleX).roundToInt(), (offset.y * scaleY).roundToInt())
        if (moved.x == 0 && moved.y == 0) return gainmap
        val brush = settings.copy(size = scaledSize(settings.size, scaleX))
        val document = documentOf(gainmap).copy(selection = selection?.let { scaledSelection(it, size, gainmap) })
        val cloned = RetouchStroke(LayerId(0), scaled(dabs, scaleX, scaleY), brush, RetouchMode.CLONE, moved).applyTo(document, checkCanceled)
        return gainmap.copy(raster = rasterOf(cloned))
    }

    /**
     * 흐림·모자이크 획 자리의 게인맵에 같은 효과를 건다(같은 크기·단단함·강도, 모자이크 칸은 게인맵 배율로 줄임).
     * 모자이크는 Ultra HDR JPEG에 가린 곳의 밝기 정보(얼굴 윤곽 등)가 남지 않게 한다. 줄인 칸이 [EffectMath.MIN_CELL]보다 작으면
     * 그 크기로 키운다(더 거칠게 가려 안전하다). [selection]이 있으면 그 안에만 건다(게인맵 크기로 줄인 것).
     */
    fun effect(
        gainmap: HdrGainmap,
        size: PixelSize,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        fill: EffectFill,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): HdrGainmap {
        val scaleX = gainmap.width.toFloat() / size.width
        val scaleY = gainmap.height.toFloat() / size.height
        val brush = settings.copy(size = scaledSize(settings.size, scaleX))
        val scaledFill = if (fill.kind == EffectKind.MOSAIC) {
            fill.copy(cell = (fill.cell * scaleX).roundToInt().coerceIn(EffectMath.MIN_CELL, EffectMath.MAX_CELL))
        } else {
            fill
        }
        val document = documentOf(gainmap).copy(selection = selection?.let { scaledSelection(it, size, gainmap) })
        val done = EffectStroke(LayerId(0), scaled(dabs, scaleX, scaleY), brush, scaledFill).applyTo(document, checkCanceled)
        return gainmap.copy(raster = rasterOf(done))
    }

    /**
     * 레이어들 [layers](아래부터, 각자의 불투명도·혼합 모드로)를 합친 레이어의 게인맵. 내보내기와 같은 겹치기다([GainmapComposer]).
     * 게인맵이 있는 레이어가 없거나 HDR 문서가 아니면 null. 크기는 가장 큰 게인맵과 같다.
     */
    fun merge(document: Document, layers: List<Layer>, checkCanceled: () -> Unit): HdrGainmap? {
        val info = document.hdr ?: return null
        val largest = layers.mapNotNull { it.gainmap }.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val merging = Document(document.width, document.height, layers, document.nextLayerId, hdr = info)
        val composed = GainmapComposer.compose(merging, largest.width, largest.height, checkCanceled) ?: throw NotEnoughMemoryException()
        try {
            val raster = Gainmaps.split(composed, checkCanceled) ?: throw NotEnoughMemoryException()
            return HdrGainmap(largest.width, largest.height, raster)
        } finally {
            composed.recycle()
        }
    }

    /** 게인맵만 담은 임시 문서(불투명도 1, 표준, sRGB) */
    /**
     * AI 제거한 자리의 게인맵을 둘레의 게인 값으로 매끈하게 채운다(모델 없이 [Inpainter]의 같은 순서, 결정 기록 'AI 제거·AI 선택').
     * '밝히지 않음'으로 칠하면 HDR 화면에서 지운 자리만 어둡게 보이므로 둘레 밝기를 잇는다.
     * [selection]이 있으면 그 안에만 채운다(게인맵 크기로 줄인 것).
     */
    fun fill(gainmap: HdrGainmap, size: PixelSize, area: InpaintArea, selection: Selection?, checkCanceled: () -> Unit): HdrGainmap {
        val scaleX = gainmap.width.toFloat() / size.width
        val scaleY = gainmap.height.toFloat() / size.height
        val scaledArea = when (area) {
            is InpaintArea.Painted -> InpaintArea.Painted(scaled(area.dabs, scaleX, scaleY), maxOf(area.radius * minOf(scaleX, scaleY), 0.5f))
            InpaintArea.Selected -> area
            is InpaintArea.Masked -> InpaintArea.Masked(area.alphas, area.width, area.height, area.cellX * scaleX, area.cellY * scaleY)
        }
        val scaledSelection = selection?.let { scaledSelection(it, size, gainmap) }
        val filled = Inpainter.paint(
            gainmap.raster, PixelSize(gainmap.width, gainmap.height), scaledArea, scaledSelection, Inpainter.FillSource.Smooth, checkCanceled
        )
        return if (filled === gainmap.raster) gainmap else gainmap.copy(raster = filled)
    }

    private fun documentOf(gainmap: HdrGainmap): Document = Document.create(gainmap.width, gainmap.height, "", gainmap.raster)

    private fun rasterOf(document: Document): Raster = (document.layers[0].content as RasterContent).raster

    private fun scaled(dabs: List<StrokePoint>, scaleX: Float, scaleY: Float): List<StrokePoint> =
        dabs.map { StrokePoint(it.x * scaleX, it.y * scaleY) }

    private fun scaledSize(size: Float, scale: Float): Float = (size * scale).coerceIn(BrushSettings.MIN_SIZE, BrushSettings.MAX_SIZE)

    /**
     * 문서 크기([size])의 선택 영역을 게인맵 크기로 줄인 선택 영역(쌍선형으로 줄여 부드러운 경계는 그만큼 옅다).
     * 게인맵 타일마다 그 자리에 닿는 선택 타일만 줄여 그린다. 다 비면 아무 데도 고르지 않은 선택 영역이다.
     * AI 제거의 게인맵 채우기도 쓴다(결정 기록 'AI 제거·AI 선택').
     */
    internal fun scaledSelection(selection: Selection, size: PixelSize, gainmap: HdrGainmap): Selection {
        val scaleX = gainmap.width.toFloat() / size.width
        val scaleY = gainmap.height.toFloat() / size.height
        val grid = TileGrid(gainmap.width, gainmap.height)
        val tiles = HashMap<TileKey, MaskTile>()
        val temporary = ArrayList<Bitmap>()
        val paint = Paint().apply { isFilterBitmap = true }
        try {
            for (key in grid.keys()) {
                val bounds = grid.bounds(key)
                // 이 게인맵 타일이 덮는 문서 자리(둘레 한 칸 여유)
                val area = PixelRect(
                    floor(bounds.left / scaleX).toInt() - 1,
                    floor(bounds.top / scaleY).toInt() - 1,
                    ceil((bounds.left + bounds.width) / scaleX).toInt() + 1,
                    ceil((bounds.top + bounds.height) / scaleY).toInt() + 1
                )
                val tile = MaskTiles.draw(bounds.width, bounds.height) { canvas ->
                    canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                    canvas.scale(scaleX, scaleY)
                    for ((selected, mask) in selection.tiles) {
                        val left = selected.column * TILE_SIZE
                        val top = selected.row * TILE_SIZE
                        if (PixelRect(left, top, left + TILE_SIZE, top + TILE_SIZE).intersect(area) == null) continue
                        val bitmap = MaskTiles.bitmapOf(mask, temporary)
                        if (bitmap == null) {
                            canvas.drawRect(left.toFloat(), top.toFloat(), (left + TILE_SIZE).toFloat(), (top + TILE_SIZE).toFloat(), paint)
                        } else {
                            canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), paint)
                        }
                    }
                } ?: continue
                tiles[key] = tile
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return Selection.of(tiles)
    }
}

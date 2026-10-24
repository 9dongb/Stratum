package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Shader
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.CompositePlan
import com.kiln.stratum.engine.document.CompositeStep
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.effect.EffectFill
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.tone.ToneFill
import com.kiln.stratum.engine.transform.MaskTransform
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * 문서 전체 축소본을 레이어별로 나눠 그린다. 레이어마다 픽셀만 그린 축소본(보정·불투명도 없이)을 기억해 두고,
 * 합칠 때 보정·불투명도·혼합 모드를 건다. 레이어 마스크도 마스크 객체마다 같은 단계의 알파 그림으로 기억해 두고 합칠 때 곱한다.
 *
 * 보정 슬라이더를 끄는 동안처럼 픽셀은 그대로이고 레이어 속성만 바뀌면, 레이어 축소본을 다시 그리지 않고
 * 화면 크기의 그림 몇 장만 합쳐서 빠르다(50MP 사진도 타일 800장을 다시 그리지 않는다).
 * 픽셀이 바뀐 레이어(래스터 객체가 다른 것)만 다시 그린다. 확대해서 볼 때와 내보낼 때는 타일에서 바로 그린다(PreviewRenderer).
 *
 * 레이어마다 줄인 뒤 합치므로, 반투명이 겹치는 가장자리는 타일에서 바로 합친 축소본과 아주 조금 다를 수 있다.
 * GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서, 한 번에 하나만 부른다(부르는 쪽의 잠금).
 */
class LayerPreviewCache {

    private class Entry(val level: Int, val bitmap: Bitmap)

    /** 작은 그림 하나와, 그것을 만든 레이어 축소본·보정 값. 둘 다 같으면 다시 만들지 않는다. */
    private class Thumbnail(val source: Bitmap, val adjustments: Adjustments, val bitmap: Bitmap)

    /** 래스터 객체마다의 축소본. 실행 취소 뒤 레이어 번호가 다시 쓰이므로 번호가 아니라 래스터로 찾는다(설계 메모 '캐시'). */
    private val entries = IdentityHashMap<Raster, Entry>()

    /** 래스터 객체마다의 작은 그림(레이어 패널) */
    private val thumbnails = IdentityHashMap<Raster, Thumbnail>()

    /** 마스크 객체마다의 알파 그림(축소본과 같은 단계, 결정 기록 '피부 보정'의 1c) */
    private val masks = IdentityHashMap<Selection, Entry>()

    /** 마스크 객체마다의 작은 그림(레이어 패널, 결정 기록 '피부 보정'의 1d) */
    private val maskThumbnails = IdentityHashMap<Selection, Bitmap>()

    /** 레이어 축소본을 새로 그린 횟수. 속성만 바뀌면 늘지 않는 것을 테스트로 확인한다. */
    var layerRenders = 0
        private set

    /** 레이어 축소본을 바뀐 곳만 고쳐 그린 횟수(브러시 획을 기록한 뒤 등). 테스트가 확인한다. */
    var layerPatches = 0
        private set

    /** 마지막으로 그린 문서의 레이어마다의 래스터. 다음에 픽셀이 조금만 바뀌면 바뀐 곳만 고쳐 그리는 데 쓴다. */
    private val lastRasters = HashMap<LayerId, Raster>()

    /**
     * [document]를 [level] 단계(1/2^level)로 그린 하드웨어 비트맵. 크기는 문서 전체 축소본과 같다.
     * 보이는 레이어(불투명도가 0보다 큼)만 아래부터 합친다. GPU가 그리지 못하면 null.
     * [checkCanceled]는 레이어 사이마다 부른다.
     *
     * [strokes]가 있으면 끄는 동안의(또는 기록을 기다리는) 브러시 획을 그 레이어 축소본 위에 차례로 그려 합친다(기록 전 미리 보기).
     * 레이어의 픽셀이 조금만 바뀌었으면(브러시 획을 기록한 뒤) 앞 축소본에서 바뀐 타일 자리만 다시 그린다.
     * [shift]가 있으면 이동 도구로 끄는 중인 그 레이어의 축소본을 옮겨 합친다(타일을 다시 그리지 않음, 캔버스 밖은 보이지 않음).
     */
    fun render(
        document: Document,
        level: Int,
        strokes: List<LiveStroke> = emptyList(),
        shift: LayerShift? = null,
        checkCanceled: () -> Unit = {}
    ): Bitmap? {
        val size = PixelSize(document.width, document.height)
        val region = PreviewLevels.wholeRegion(size, level)
        // 합성 계획의 차례대로 합친다(보이는 레이어만, 결정 기록 '피부 보정'의 1단계)
        val steps = CompositePlan.of(document)
        // 앞 축소본을 고쳐 쓸 수 있게, 놓아 주기 전에 붙잡아 둔다
        val previous = HashMap<LayerId, Bitmap>()
        for (layer in document.layers) {
            val last = lastRasters[layer.id] ?: continue
            if (last !== rasterOf(layer)) entries[last]?.takeIf { it.level == level }?.let { previous[layer.id] = it.bitmap }
        }
        // 지금 문서에 없는 래스터와 다른 단계의 축소본은 놓아 준다(화면이 아직 그리고 있을 수 있어 recycle하지 않는다).
        // 숨긴(눈을 끈·불투명도 0) 레이어의 축소본은 작은 그림에 쓰므로 남긴다.
        val lastByLayer = HashMap(lastRasters)
        forget(document, level)
        lastRasters.clear()
        for (layer in document.layers) lastRasters[layer.id] = rasterOf(layer)

        val previews = IdentityHashMap<CompositeStep.Draw, Bitmap>()
        for (step in steps) {
            if (step !is CompositeStep.Draw) continue
            checkCanceled()
            val layer = step.layer
            val raster = step.raster
            // 마스크 획은 픽셀이 아니라 마스크 그림에 얹는다(아래 합칠 때)
            val layerStrokes = strokes.filter { it.layerId == layer.id && !it.mask }
            val stroking = layerStrokes.isNotEmpty()
            // 빈 레이어는 그릴 것이 없다(끄는 획이 있으면 투명한 바탕에 그린다)
            if (raster.tiles.isEmpty() && !stroking) continue
            val base = if (raster.tiles.isEmpty()) {
                null
            } else {
                entries[raster]?.bitmap
                    ?: patchFrom(size, lastByLayer[layer.id], previous[layer.id], raster, level)?.also { entries[raster] = Entry(level, it) }
                    ?: renderLayer(size, raster, level)?.also { entries[raster] = Entry(level, it) }
                    ?: return null
            }
            val bitmap = if (stroking) strokeOver(base, region, layerStrokes, document.colorSpace, size) ?: return null else checkNotNull(base)
            previews[step] = bitmap
        }
        val moving = shift?.takeIf { it.dx != 0 || it.dy != 0 }
        // 옮기는 레이어들: 그룹이면 안의 레이어 모두(결정 기록 '피부 보정'의 1g)
        val movingIds = moving?.let { LayerTree.idsWith(document.layers, it.layerId) } ?: emptySet()
        // 그대로 보이는 레이어 하나면 합칠 필요가 없다(투명한 바탕 위에서는 혼합 모드와 상관없이 그 레이어 그대로다)
        (steps.singleOrNull() as? CompositeStep.Draw)?.let { step ->
            val bitmap = previews[step]
            if (bitmap != null && step.isPlain && step.layer.id !in movingIds) return bitmap
        }
        val width = region.previewWidth
        val height = region.previewHeight
        var picture = Picture()
        var canvas = picture.beginRecording(width, height)
        // 조정 레이어가 읽은 아래 그림. 다음 조정 레이어나 끝에서 다 그린 뒤 돌려준다.
        var below: Bitmap? = null
        // 옮기는 동안 빈 자리를 채워 다시 그린 마스크 그림. 다 그린 뒤(그리지 못해도) 돌려준다.
        val movedMasks = ArrayList<Bitmap>()
        try {
            for (step in steps) {
                // 옮기는 레이어: 축소본을 축소 단계만큼 줄인 거리로 옮겨 놓고, 축소본 밖은 투명이다(DECAL). 마스크도 같이 옮긴다.
                val offset = if (moving != null && step.layer.id in movingIds) {
                    val scale = 1f / (1 shl level)
                    Matrix().apply { setTranslate(moving.dx * scale, moving.dy * scale) }
                } else {
                    null
                }
                val maskStrokes = strokes.filter { it.layerId == step.layer.id && it.mask }
                when (step) {
                    is CompositeStep.Draw -> {
                        val bitmap = previews[step] ?: continue
                        val shader = shaderOf(bitmap, offset)
                        // 보정을 건 레이어에 마스크 알파를 곱해(결정 기록 '피부 보정'의 1c) 불투명도·혼합 모드로 아래에 얹는다
                        val adjusted = AdjustShader.of(step.adjustments)?.wrap(shader) ?: shader
                        val mask = step.mask?.let { selection ->
                            val alpha = liveMask(selection, region, maskStrokes) ?: return null.also { below?.recycle() }
                            maskShaderOf(selection, alpha, offset, size, movedMasks) ?: return null.also { below?.recycle() }
                        }
                        val paint = layerPaint(step).apply {
                            this.shader = if (mask == null) adjusted else ComposeShader(adjusted, mask, BlendMode.DST_IN)
                        }
                        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                    }
                    is CompositeStep.Adjust -> {
                        // 조정 레이어: 지금까지 합친 아래를 그림으로 만들고, 그것을 보정한 색을 몫만큼 바꿔 넣은 그림에서 이어 그린다(결정 기록 '피부 보정'의 1e)
                        picture.endRecording()
                        val drawn = PreviewRenderer.drawOnGpu(picture, width, height) ?: return null.also { below?.recycle() }
                        below?.recycle()
                        below = drawn
                        picture = Picture()
                        canvas = picture.beginRecording(width, height)
                        val mask = step.mask?.let { selection ->
                            val alpha = liveMask(selection, region, maskStrokes) ?: return null.also { below?.recycle() }
                            maskShaderOf(selection, alpha, offset, size, movedMasks) ?: return null.also { below?.recycle() }
                        }
                        // 축소본 자리 → 문서 자리(피부 색 보정의 얼굴 무게). 옮기는 중이면 얼굴도 그만큼 옮겨 보인다.
                        val moved = moving?.takeIf { offset != null }
                        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
                            shader = AdjustLayerShader.of(
                                step, BitmapShader(drawn, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), mask,
                                document.colorSpace, -(moved?.dx ?: 0).toFloat(), -(moved?.dy ?: 0).toFloat(), (1 shl level).toFloat()
                            )
                            blendMode = BlendMode.SRC
                        })
                    }
                }
            }
            picture.endRecording()
            val result = PreviewRenderer.drawOnGpu(picture, width, height)
            below?.recycle()
            return result
        } finally {
            for (bitmap in movedMasks) bitmap.recycle()
        }
    }

    /**
     * 레이어 마스크 [selection]의 알파 그림 [alpha]를 그리는 셰이더. 옮기는 중([offset])이면 그만큼 옮기고, 옮겨서 빈 자리는 기록하는 옮기기와 같이
     * 마스크의 바깥([MaskTransform.revealsOutside])으로 채운다(다 보이는 마스크면 보이고 아니면 가림, 결정 기록 '피부 보정'의 1i).
     * 빈 자리를 채워 다시 그린 그림은 [made]에 넣는다(다 그린 뒤 돌려줌). 그리지 못하면 null.
     */
    private fun maskShaderOf(selection: Selection, alpha: Bitmap, offset: Matrix?, size: PixelSize, made: MutableList<Bitmap>): Shader? {
        if (offset == null || !MaskTransform.revealsOutside(selection, size)) return shaderOf(alpha, offset)
        val picture = Picture()
        picture.beginRecording(alpha.width, alpha.height).apply {
            // 다 보이는 바탕에 옮긴 마스크를 그대로 놓는다(마스크 밖은 바탕이 남음)
            drawColor(Color.BLACK)
            drawBitmap(alpha, offset, Paint().apply { blendMode = BlendMode.SRC })
        }
        picture.endRecording()
        val moved = PreviewRenderer.drawOnGpu(picture, alpha.width, alpha.height) ?: return null
        made += moved
        return BitmapShader(moved, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    /** 축소본 [bitmap]을 그리는 셰이더. [offset]이 있으면 그만큼 옮기고 밖은 투명이다(DECAL), 없으면 가장자리를 늘인다. */
    private fun shaderOf(bitmap: Bitmap, offset: Matrix?): Shader =
        if (offset != null) {
            BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply { setLocalMatrix(offset) }
        } else {
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

    /**
     * [mask]의 알파 그림([maskPreview])에 끄는 마스크 획 [strokes]를 얹은 그림(결정 기록 '피부 보정'의 1d). 획이 없으면 기억한 그림 그대로다.
     * 보이게 칠하면 알파를 더하고 가리게 칠하면 덜어 낸다(기록하는 [PaintLayerMask]와 같은 붓). 그리지 못하면 null.
     */
    private fun liveMask(mask: Selection, region: PreviewRegion, strokes: List<LiveStroke>): Bitmap? {
        val base = maskPreview(mask, region) ?: return null
        if (strokes.isEmpty()) return base
        val width = region.previewWidth.toFloat()
        val height = region.previewHeight.toFloat()
        val picture = Picture()
        val canvas = picture.beginRecording(region.previewWidth, region.previewHeight)
        canvas.drawColor(Color.BLACK)
        MaskTiles.drawAlpha(canvas, base, BlendMode.DST_IN)
        val scale = 1f / (1 shl region.level)
        for (stroke in strokes) {
            val clip = stroke.selection?.let { selectionMask(it, region) }
            BrushPainter.drawStroke(canvas, stroke.dabs, stroke.settings.copy(color = Color.BLACK), stroke.erase, scale, 0f, 0f, width, height, clip = clip)
        }
        picture.endRecording()
        return PreviewRenderer.drawOnGpu(picture, region.previewWidth, region.previewHeight)
    }

    /**
     * [layerId] 레이어의 마스크가 가린 정도(알파 = 1 - 마스크)를 담은 그림(마스크를 칠하는 동안의 빨간 막, 결정 기록 '피부 보정'의 1d).
     * 크기는 [level] 단계의 문서 전체 축소본과 같고, 끄는 마스크 획 [strokes]도 얹는다. 화면이 빨갛게 물들여 캔버스 위에 그린다.
     * 마스크가 없거나 그리지 못하면 null. [render]와 같은 잠금 안에서 부른다.
     */
    fun maskOverlay(document: Document, layerId: LayerId, level: Int, strokes: List<LiveStroke> = emptyList()): Bitmap? {
        val mask = document.layers.getOrNull(document.indexOfLayer(layerId))?.mask ?: return null
        val region = PreviewLevels.wholeRegion(PixelSize(document.width, document.height), level)
        val alpha = liveMask(mask, region, strokes.filter { it.layerId == layerId && it.mask }) ?: return null
        val picture = Picture()
        val canvas = picture.beginRecording(region.previewWidth, region.previewHeight)
        canvas.drawColor(Color.BLACK)
        MaskTiles.drawAlpha(canvas, alpha, BlendMode.DST_OUT)
        picture.endRecording()
        return PreviewRenderer.drawOnGpu(picture, region.previewWidth, region.previewHeight)
    }

    /**
     * 레이어마다의 마스크 작은 그림(레이어 패널, 결정 기록 '피부 보정'의 1d): 흰 곳은 보이고 검은 곳은 가린다. 긴 변 [THUMBNAIL_EDGE] 이하.
     * 마스크 객체마다 기억해 마스크가 그대로면 다시 그리지 않는다. 마스크가 없는 레이어는 빠진다. [render]와 같은 잠금 안에서 부른다.
     */
    fun maskThumbnails(document: Document): Map<LayerId, Bitmap> {
        val wanted = IdentityHashMap<Selection, Unit>()
        val result = HashMap<LayerId, Bitmap>()
        for (layer in document.layers) {
            val mask = layer.mask ?: continue
            wanted[mask] = Unit
            val thumbnail = maskThumbnails[mask] ?: maskThumbnail(mask, PixelSize(document.width, document.height))?.also { maskThumbnails[mask] = it } ?: continue
            result[layer.id] = thumbnail
        }
        maskThumbnails.keys.removeIf { it !in wanted }
        return result
    }

    /** [mask]의 작은 그림: 검은 바탕에 마스크 알파만큼 흰색. 그리지 못하면 null. */
    private fun maskThumbnail(mask: Selection, size: PixelSize): Bitmap? {
        val scale = minOf(1f, THUMBNAIL_EDGE.toFloat() / maxOf(size.width, size.height))
        val width = (size.width * scale).roundToInt().coerceAtLeast(1)
        val height = (size.height * scale).roundToInt().coerceAtLeast(1)
        val alpha = try {
            SelectionPreview.maskAt(mask, 0f, 0f, scale, width, height)
        } catch (e: OutOfMemoryError) {
            return null
        }
        try {
            val picture = Picture()
            val canvas = picture.beginRecording(width, height)
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
                shader = BitmapShader(alpha, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                color = Color.WHITE
            })
            picture.endRecording()
            return PreviewRenderer.drawOnGpu(picture, width, height)
        } finally {
            alpha.recycle()
        }
    }

    /** [mask]를 [region](문서 전체, 축소본 단계)으로 그린 알파 그림. 같은 마스크 객체·단계면 다시 그리지 않는다. 그리지 못하면 null. */
    private fun maskPreview(mask: Selection, region: PreviewRegion): Bitmap? {
        masks[mask]?.takeIf { it.level == region.level }?.let { return it.bitmap }
        val bitmap = try {
            SelectionPreview.maskAt(mask, 0f, 0f, 1f / (1 shl region.level), region.previewWidth, region.previewHeight)
        } catch (e: OutOfMemoryError) {
            return null
        }
        masks[mask] = Entry(region.level, bitmap)
        return bitmap
    }

    /**
     * 레이어마다의 작은 그림: 레이어 픽셀에 보정을 건 것(불투명도·혼합 모드는 빼고), 긴 변 [THUMBNAIL_EDGE] 이하. 레이어 패널 목록에 쓴다.
     * [level] 단계의 레이어 축소본을 반씩 줄여 만들어, 큰 사진도 타일을 다시 읽지 않는다. 숨긴 레이어는 이때 축소본을 그린다.
     * 빈 레이어와 그리지 못한 레이어는 빠진다. [render]와 같은 잠금 안에서 부른다.
     */
    fun thumbnails(document: Document, level: Int, checkCanceled: () -> Unit = {}): Map<LayerId, Bitmap> {
        val size = PixelSize(document.width, document.height)
        forget(document, level)
        val result = HashMap<LayerId, Bitmap>()
        for (layer in document.layers) {
            checkCanceled()
            val raster = rasterOf(layer)
            if (raster.tiles.isEmpty()) continue
            val preview = entries[raster]?.bitmap ?: renderLayer(size, raster, level)?.also { entries[raster] = Entry(level, it) } ?: continue
            val cached = thumbnails[raster]?.takeIf { it.source === preview && it.adjustments == layer.adjustments }
            val thumbnail = cached?.bitmap
                ?: shrink(preview, layer.adjustments)?.also { thumbnails[raster] = Thumbnail(preview, layer.adjustments, it) }
                ?: continue
            result[layer.id] = thumbnail
        }
        return result
    }

    /** 마지막으로 만든 화면용 게인맵과 그것을 만든 레이어들(래스터·게인맵·불투명도·혼합 모드)과 단계 */
    private var lastGainmap: Pair<List<Any>, ScreenGainmap>? = null

    /**
     * 화면 축소본에 붙일 HDR 게인맵(편집 화면 HDR, 결정 기록 'HDR'). HDR 문서가 아니면 null.
     * 내보내기와 같은 겹치기이고([GainmapComposer]), 레이어 알파는 [level] 단계의 레이어 축소본(이미 그린 것)을 써서 타일을 다시 읽지 않는다.
     * 크기는 게인맵 원래 크기와 축소본 크기 중 작은 쪽이다. 레이어의 래스터·게인맵·불투명도·혼합 모드·보이기가 같으면 다시 만들지 않아,
     * 보정 슬라이더를 끄는 동안 느려지지 않는다. [render]와 같은 잠금 안에서 부른다.
     */
    fun screenGainmap(document: Document, level: Int): ScreenGainmap? {
        val info = document.hdr ?: return null
        if (!document.hasHdr) return null
        // 겹치는 것은 합성 계획의 그리는 단계다(숨긴 레이어는 빠짐). 그 래스터·게인맵·얹는 불투명도·혼합 모드가 같으면 다시 만들지 않는다.
        val key: List<Any> = listOf(level, document.width, document.height, info) +
            CompositePlan.of(document).map { step ->
                when (step) {
                    is CompositeStep.Draw ->
                        listOf(IdentityKey(step.raster), IdentityKey(step.layer.gainmap?.raster), IdentityKey(step.mask), step.opacity, step.blendMode)
                    // 조정 레이어는 게인맵을 바꾸지 않는다
                    is CompositeStep.Adjust -> emptyList<Any>()
                }
            }
        lastGainmap?.let { (lastKey, gainmap) -> if (lastKey == key) return gainmap }
        val size = PixelSize(document.width, document.height)
        val region = PreviewLevels.wholeRegion(size, level)
        val largest = document.layers.mapNotNull { it.gainmap }.maxBy { it.width.toLong() * it.height }
        val width = minOf(region.previewWidth, largest.width)
        val height = minOf(region.previewHeight, largest.height)
        val composed = GainmapComposer.compose(document, width, height) { raster ->
            val preview = entries[raster]?.takeIf { it.level == level }?.bitmap
                ?: renderLayer(size, raster, level)?.also { entries[raster] = Entry(level, it) }
            preview?.let { GainmapComposer.Alpha(it, owned = false) }
        } ?: return null
        return ScreenGainmap(composed, info).also { lastGainmap = key to it }
    }

    /** 객체가 같은지로 견주는 열쇠(래스터·게인맵은 바꾸지 않는 값이라 객체가 같으면 내용도 같다) */
    private class IdentityKey(val value: Any?) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

    /** 기억한 축소본을 모두 놓아 준다(문서를 닫을 때) */
    fun clear() {
        entries.clear()
        thumbnails.clear()
        masks.clear()
        maskThumbnails.clear()
        lastRasters.clear()
        lastGainmap = null
        lastEffect = null
    }

    /** [document]에 없는 래스터의 축소본·작은 그림과, [level]이 아닌 단계의 축소본을 놓아 준다. */
    private fun forget(document: Document, level: Int) {
        val wanted = IdentityHashMap<Raster, Unit>()
        for (layer in document.layers) wanted[rasterOf(layer)] = Unit
        entries.entries.removeIf { (raster, entry) -> raster !in wanted || entry.level != level }
        thumbnails.keys.removeIf { it !in wanted }
        val wantedMasks = IdentityHashMap<Selection, Unit>()
        for (layer in document.layers) layer.mask?.let { wantedMasks[it] = Unit }
        masks.entries.removeIf { (mask, entry) -> mask !in wantedMasks || entry.level != level }
    }

    /**
     * [preview]를 긴 변 [THUMBNAIL_EDGE] 이하로 줄이고 [adjustments]를 건 그림. 반씩 줄이다가 마지막에 크기를 맞춘다
     * (한 번에 크게 줄이면 쌍선형 필터가 픽셀을 건너뛰어 거칠다).
     */
    private fun shrink(preview: Bitmap, adjustments: Adjustments): Bitmap? {
        var current = preview
        while (maxOf(current.width, current.height) > THUMBNAIL_EDGE * 2) {
            val half = PreviewRenderer.halve(current)
            if (current !== preview) current.recycle()
            current = half ?: return null
        }
        val scale = minOf(1f, THUMBNAIL_EDGE.toFloat() / maxOf(current.width, current.height))
        val width = (current.width * scale).roundToInt().coerceAtLeast(1)
        val height = (current.height * scale).roundToInt().coerceAtLeast(1)
        val shader = BitmapShader(current, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply { setScale(width.toFloat() / current.width, height.toFloat() / current.height) })
        }
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = AdjustShader.of(adjustments)?.wrap(shader) ?: shader })
        picture.endRecording()
        val thumbnail = PreviewRenderer.drawOnGpu(picture, width, height)
        if (current !== preview) current.recycle()
        return thumbnail
    }

    /**
     * 같은 레이어의 앞 래스터 [last]와 그 축소본 [lastPreview]로 [raster]의 축소본을 고쳐 만든다. 고쳐 쓸 수 없으면 null.
     * 앞 래스터가 비어 있었으면(새 레이어에 처음 그림) 투명한 바탕에서 고친다.
     */
    private fun patchFrom(size: PixelSize, last: Raster?, lastPreview: Bitmap?, raster: Raster, level: Int): Bitmap? =
        when {
            last == null -> null
            lastPreview != null -> patch(size, last, lastPreview, raster, level)
            last.tiles.isEmpty() -> patch(size, last, null, raster, level)
            else -> null
        }

    /**
     * [raster]가 [previous]에서 바뀐 타일 자리만 다시 그려 [previousPreview](없으면 투명)를 고친 축소본. 바뀐 것이 없으면 [previousPreview] 그대로다.
     * 타일의 절반 넘게 바뀌었으면 통째로 다시 그리는 편이 낫다고 보고 null을 돌려준다. GPU가 그리지 못해도 null이다.
     * 영역은 단계 격자에 맞춰 그려 전체를 그린 것과 픽셀이 같다(PreviewRenderer.renderRegion).
     */
    private fun patch(size: PixelSize, previous: Raster, previousPreview: Bitmap?, raster: Raster, level: Int): Bitmap? {
        val grid = TileGrid(size.width, size.height)
        // 축소본은 캔버스 안만 그린다: 캔버스 밖 자리만 바뀌었으면 그대로다(결정 기록 '캔버스 밖 픽셀')
        val dirty = HashSet<TileKey>()
        for ((key, tile) in raster.tiles) if (previous.tiles[key] !== tile && grid.contains(key)) dirty += key
        for (key in previous.tiles.keys) if (key !in raster.tiles && grid.contains(key)) dirty += key
        if (dirty.isEmpty()) return previousPreview
        if (dirty.size * 2 > grid.columns * grid.rows) return null
        val rect = PixelRect(
            dirty.minOf { it.column } * TILE_SIZE,
            dirty.minOf { it.row } * TILE_SIZE,
            minOf((dirty.maxOf { it.column } + 1) * TILE_SIZE, size.width),
            minOf((dirty.maxOf { it.row } + 1) * TILE_SIZE, size.height)
        )
        val area = PreviewLevels.region(size, level, rect) ?: return previousPreview
        val whole = PreviewLevels.wholeRegion(size, level)
        val repaint = PreviewRenderer.renderRegion(Document.create(size.width, size.height, "", raster), area) ?: return null
        try {
            val picture = Picture()
            val canvas = picture.beginRecording(whole.previewWidth, whole.previewHeight)
            if (previousPreview != null) canvas.drawBitmap(previousPreview, 0f, 0f, null)
            canvas.drawBitmap(repaint, area.previewLeft.toFloat(), area.previewTop.toFloat(), Paint().apply { blendMode = BlendMode.SRC })
            picture.endRecording()
            layerPatches++
            return PreviewRenderer.drawOnGpu(picture, whole.previewWidth, whole.previewHeight)
        } finally {
            repaint.recycle()
        }
    }

    /**
     * 그림 [image](문서의 [region] 부분을 그 단계로 그린 것, 없으면 투명) 위에 획들 [strokes]를 차례로 그린 그림(GPU).
     * 레이어 축소본 위에 그리거나(합치기 전), 쉬운 경우에는 확대 그림 위에 바로 그린다(맨 위 표준 레이어에 그릴 때, ViewModel이 고른다).
     * 획에 선택 영역이 있으면 그 영역 안으로 자른다(기록할 때와 같다).
     * 복제 획([LiveStroke.clone])은 [image]에서 옮긴 자리를 베껴 보인다. 그림 밖에서 가져올 곳은 비워 둔다.
     * 명암 획([LiveStroke.tone])은 [image]를 닷지·번으로 바꾼 색을 획 모양으로 보인다.
     * 효과 획([LiveStroke.effect], 흐림·선명·모자이크)은 [image] 전체에 효과를 한 번 건 그림으로 획 자리를 바꿔 보인다(기록과 같은 바꾸기).
     * 효과 그림은 같은 그림·영역·효과면 다시 계산하지 않는다(끄는 동안 자국마다 다시 그리므로). [document]는 문서 크기다(모자이크의 문서 끝 칸).
     * 스머지 획([LiveStroke.smudge])은 [image]의 사본에 새 자국만 이어서 집고 내려놓은 그림을 보인다(그 획의 [SmudgePreview]가 든다).
     * 칠하는 색은 [colorSpace](문서 색 공간) 값으로 바꿔 그린다(기록할 때와 같다).
     */
    fun strokeOver(
        image: Bitmap?,
        region: PreviewRegion,
        strokes: List<LiveStroke>,
        colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB,
        document: PixelSize = PixelSize(region.rect.right, region.rect.bottom)
    ): Bitmap? {
        val width = region.previewWidth
        val height = region.previewHeight
        val scale = 1f / (1 shl region.level)
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        if (image != null) canvas.drawBitmap(image, 0f, 0f, null)
        for (stroke in strokes) {
            val clone = stroke.clone
            val tone = stroke.tone
            val effect = stroke.effect
            val smudge = stroke.smudge
            // 복제가 베낄 그림: '아래 포함'이면 그 레이어와 그 아래를 합친 그림, 아니면 레이어 축소본
            val copied = stroke.source ?: image
            // 읽을 그림이 없으면(빈 레이어) 복제하거나 바꿀 색이 없다
            if ((tone != null || effect != null || smudge != null) && image == null) continue
            if (clone != null && copied == null) continue
            if (smudge != null && image != null) {
                // 스머지는 앞 자국의 결과에 기대므로 미리 보기가 사본에 이어서 그려 둔 것을 그대로 보인다
                val smudged = smudge.render(image, region, stroke.dabs, stroke.selection?.let { selectionMask(it, region) }) ?: continue
                canvas.drawBitmap(smudged, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
                continue
            }
            if (effect != null && image != null) {
                val effected = effectImage(image, region, effect, stroke.settings.size, document) ?: continue
                BrushPainter.drawReplace(
                    canvas, stroke.dabs, stroke.settings,
                    scale = scale,
                    left = region.rect.left.toFloat(),
                    top = region.rect.top.toFloat(),
                    width = width.toFloat(),
                    height = height.toFloat(),
                    fill = BitmapShader(effected, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                    clip = stroke.selection?.let { selectionMask(it, region) }
                )
                continue
            }
            val fill = when {
                // 캔버스 자리 q는 그림의 q - 옮긴 양을 읽는다
                clone != null && copied != null -> BitmapShader(copied, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(clone.x * scale, clone.y * scale) })
                }
                tone != null && image != null -> ToneShader.of(BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), tone)
                else -> null
            }
            val settings = stroke.settings.copy(color = DocumentColors.paintColor(stroke.settings.color, colorSpace))
            BrushPainter.drawStroke(
                canvas, stroke.dabs, settings, stroke.erase,
                scale = scale,
                left = region.rect.left.toFloat(),
                top = region.rect.top.toFloat(),
                width = width.toFloat(),
                height = height.toFloat(),
                clip = stroke.selection?.let { selectionMask(it, region) },
                fill = fill
            )
        }
        picture.endRecording()
        return PreviewRenderer.drawOnGpu(picture, width, height)
    }

    /** 마지막으로 만든 효과 그림과 그 열쇠(그림·영역·효과·붓 크기). 끄는 동안 같은 그림에 같은 효과를 매번 다시 계산하지 않는다. */
    private var lastEffect: Pair<List<Any>, Bitmap>? = null

    /** [image](문서의 [region]을 그 단계로 그린 것) 전체에 [fill]을 건 그림(GPU). 같은 것이면 다시 쓴다. GPU가 그리지 못하면 null. */
    private fun effectImage(image: Bitmap, region: PreviewRegion, fill: EffectFill, brushSize: Float, document: PixelSize): Bitmap? {
        val key: List<Any> = listOf(IdentityKey(image), region, fill, brushSize, document)
        lastEffect?.let { (lastKey, bitmap) -> if (lastKey == key) return bitmap }
        val effected = EffectShader.renderOnGpu(
            image, region.rect.left.toFloat(), region.rect.top.toFloat(), pixel = (1 shl region.level).toFloat(),
            fromX = 0, fromY = 0, width = image.width, height = image.height,
            fill = fill, brushSize = brushSize, document = document
        ) ?: return null
        lastEffect = key to effected
        return effected
    }

    /** 마지막으로 줄여 그린 선택 영역 알파 그림. 끄는 동안 같은 선택 영역·자리를 매번 다시 그리지 않는다. */
    private var lastMask: Triple<Selection, PreviewRegion, Bitmap>? = null

    /** [selection]을 [region]에 줄여 그린 알파 그림(같은 것이면 다시 쓴다) */
    private fun selectionMask(selection: Selection, region: PreviewRegion): Bitmap {
        lastMask?.let { (s, r, bitmap) -> if (s === selection && r == region) return bitmap }
        return SelectionPreview.mask(selection, region).also { lastMask = Triple(selection, region, it) }
    }

    /** [raster] 하나만 담은 문서(불투명도 1, 표준, 보정 없음)의 축소본 */
    private fun renderLayer(size: PixelSize, raster: Raster, level: Int): Bitmap? {
        layerRenders++
        return PreviewRenderer.render(Document.create(size.width, size.height, "", raster), level)
    }

    private fun rasterOf(layer: Layer): Raster =
        when (val content = layer.content) {
            is RasterContent -> content.raster
            // 조정 레이어는 픽셀이 없다(빈 래스터로 보아 축소본·작은 그림을 그리지 않는다)
            is AdjustmentContent, GroupContent -> Raster.EMPTY
        }

    companion object {
        /** 작은 그림의 긴 변(픽셀). 레이어 목록의 40dp 칸에 폴드8(약 2.6배)에서도 선명하다. */
        const val THUMBNAIL_EDGE = 128
    }
}

/**
 * 끄는 동안의 브러시 획(기록 전). [LayerPreviewCache.render]가 [layerId] 레이어 축소본 위에 그려 미리 보인다.
 * [dabs]는 그리는 동안 바뀌지 않는 목록이어야 한다(끄는 쪽이 복사해 넘긴다).
 * [selection]은 획을 시작할 때의 선택 영역이다. 있으면 미리 보기도 그 안으로 자른다.
 * [clone]이 있으면 칠하는 대신 '획 자리 - 그 양'의 레이어 픽셀을 베껴 보인다(복제 도장·복구 브러시를 끄는 동안).
 * [source]가 있으면 레이어 축소본 대신 그 그림(같은 축소본 단계로 그린 문서 전체)에서 베낀다('아래 포함' 가져오기, 결정 기록 '피부 보정'의 5단계).
 * [tone]이 있으면 칠하는 대신 레이어 픽셀을 닷지·번으로 바꾼 색을 보인다(명암 도구를 끄는 동안).
 * [effect]가 있으면 칠하는 대신 레이어 픽셀에 흐림·선명·모자이크를 건 색으로 바꿔 보인다(흐림·모자이크 도구를 끄는 동안).
 * [smudge]가 있으면 칠하는 대신 레이어 픽셀을 문질러 끈 모습을 보인다(스머지를 끄는 동안, 획마다 하나).
 * [mask]면 픽셀이 아니라 그 레이어의 마스크에 얹는다(브러시는 보이게, [erase]면 가리게, 결정 기록 '피부 보정'의 1d).
 */
/**
 * 이동 도구로 끄는 중인 레이어 옮기기(문서 픽셀, 결정 기록 '이동 도구'). [LayerPreviewCache.render]가 그 레이어 축소본을 옮겨 합친다.
 * 손을 떼면 [TranslateLayer]로 기록한다.
 */
data class LayerShift(val layerId: LayerId, val dx: Int, val dy: Int)

class LiveStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val erase: Boolean,
    val selection: Selection? = null,
    val clone: SourceOffset? = null,
    val tone: ToneFill? = null,
    val effect: EffectFill? = null,
    val smudge: SmudgePreview? = null,
    val mask: Boolean = false,
    val source: Bitmap? = null
)

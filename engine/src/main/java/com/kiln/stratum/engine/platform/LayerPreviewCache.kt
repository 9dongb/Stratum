package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Shader
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * 문서 전체 축소본을 레이어별로 나눠 그린다. 레이어마다 픽셀만 그린 축소본(보정·불투명도 없이)을 기억해 두고,
 * 합칠 때 보정·불투명도를 건다.
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

    /** 래스터 객체마다의 축소본. 실행 취소 뒤 레이어 번호가 다시 쓰이므로 번호가 아니라 래스터로 찾는다(설계 메모 '캐시'). */
    private val entries = IdentityHashMap<Raster, Entry>()

    /** 레이어 축소본을 새로 그린 횟수. 속성만 바뀌면 늘지 않는 것을 테스트로 확인한다. */
    var layerRenders = 0
        private set

    /**
     * [document]를 [level] 단계(1/2^level)로 그린 하드웨어 비트맵. 크기는 문서 전체 축소본과 같다.
     * 보이는 레이어(불투명도가 0보다 큼)만 아래부터 합친다. GPU가 그리지 못하면 null.
     * [checkCanceled]는 레이어 사이마다 부른다.
     */
    fun render(document: Document, level: Int, checkCanceled: () -> Unit = {}): Bitmap? {
        val size = PixelSize(document.width, document.height)
        val region = PreviewLevels.wholeRegion(size, level)
        val visible = document.layers.filter { it.opacity > 0f }
        // 지금 문서에 없는 래스터와 다른 단계의 축소본은 놓아 준다(화면이 아직 그리고 있을 수 있어 recycle하지 않는다)
        val wanted = IdentityHashMap<Raster, Unit>()
        for (layer in visible) wanted[rasterOf(layer)] = Unit
        entries.entries.removeIf { (raster, entry) -> raster !in wanted || entry.level != level }

        val previews = ArrayList<Pair<Layer, Bitmap>>()
        for (layer in visible) {
            checkCanceled()
            val raster = rasterOf(layer)
            // 빈 레이어는 그릴 것이 없다
            if (raster.tiles.isEmpty()) continue
            val bitmap = entries[raster]?.bitmap ?: renderLayer(size, raster, level)?.also { entries[raster] = Entry(level, it) }
                ?: return null
            previews += layer to bitmap
        }
        // 그대로 보이는 레이어 하나면 합칠 필요가 없다
        previews.singleOrNull()?.let { (layer, bitmap) ->
            if (layer.opacity == 1f && layer.adjustments.isIdentity) return bitmap
        }
        val picture = Picture()
        val canvas = picture.beginRecording(region.previewWidth, region.previewHeight)
        for ((layer, bitmap) in previews) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // TODO(레이어 합성 작업): 혼합 모드
            val paint = Paint().apply {
                this.shader = AdjustShader.of(layer.adjustments)?.wrap(shader) ?: shader
                alpha = (layer.opacity * 255).roundToInt()
            }
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
        }
        picture.endRecording()
        return PreviewRenderer.drawOnGpu(picture, region.previewWidth, region.previewHeight)
    }

    /** 기억한 축소본을 모두 놓아 준다(문서를 닫을 때) */
    fun clear() {
        entries.clear()
    }

    /** [raster] 하나만 담은 문서(불투명도 1, 표준, 보정 없음)의 축소본 */
    private fun renderLayer(size: PixelSize, raster: Raster, level: Int): Bitmap? {
        layerRenders++
        return PreviewRenderer.render(Document.create(size.width, size.height, "", raster), level)
    }

    private fun rasterOf(layer: Layer): Raster =
        when (val content = layer.content) {
            is RasterContent -> content.raster
        }
}

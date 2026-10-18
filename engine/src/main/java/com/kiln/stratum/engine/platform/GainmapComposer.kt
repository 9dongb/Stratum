package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.Raster
import kotlin.math.roundToInt

/**
 * 문서의 레이어 게인맵들을 겹쳐 게인맵 한 장을 그린다(결정 기록 'HDR'의 겹치기 규칙, GPU).
 *
 * '밝히지 않음' 값으로 채운 뒤, 보이는 표준 레이어를 아래부터 그 레이어의 알파 × 불투명도만큼 그 레이어의 게인맵
 * (없으면 '밝히지 않음')으로 덮는다. 그래서 사진 위에 칠한 그림·글자는 HDR로 밝아지지 않는다.
 * 곱하기·스크린·오버레이 레이어는 아래 사진을 바꾸는 것이라 게인맵을 그대로 둔다.
 * 게인맵 값은 로그 배율의 비례 값이라, 알파로 섞으면 배율을 로그로 섞은 것과 거의 같다.
 * GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
internal object GainmapComposer {

    /**
     * 문서 전체를 덮는 [width]×[height] 게인맵(불투명 ARGB 소프트웨어 비트맵, 1채널은 회색).
     * HDR 문서가 아니거나 GPU가 그리지 못하면 null. [checkCanceled]는 레이어 사이마다 부른다.
     */
    fun compose(document: Document, width: Int, height: Int, checkCanceled: () -> Unit = {}): Bitmap? {
        val level = alphaLevel(document, width, height)
        return compose(document, width, height, checkCanceled) { raster ->
            PreviewRenderer.render(Document.create(document.width, document.height, "", raster), level)?.let { Alpha(it, owned = true) }
        }
    }

    /** 레이어 알파 그림(문서 전체를 덮는 축소본)과, 다 쓰면 돌려줄 것인지(캐시의 그림은 돌려주지 않는다) */
    class Alpha(val bitmap: Bitmap, val owned: Boolean)

    /**
     * [compose]와 같고, 레이어 알파 그림을 [alphaOf]가 준다(화면용 게인맵은 이미 그린 레이어 축소본을 쓴다, [LayerPreviewCache]).
     * [alphaOf]가 null을 주면(그리지 못함) null이다.
     */
    fun compose(document: Document, width: Int, height: Int, checkCanceled: () -> Unit = {}, alphaOf: (Raster) -> Alpha?): Bitmap? {
        val info = document.hdr ?: return null
        if (!document.hasHdr) return null
        val neutral = Gainmaps.neutralColor(info)
        val temporary = ArrayList<Bitmap>()
        try {
            val picture = Picture()
            val canvas = picture.beginRecording(width, height)
            canvas.drawColor(neutral, BlendMode.SRC)
            for (layer in document.layers) {
                if (!layer.isShown || layer.blendMode != com.kiln.stratum.engine.document.BlendMode.NORMAL) continue
                val raster = (layer.content as RasterContent).raster
                if (raster.tiles.isEmpty()) continue
                checkCanceled()
                val alpha = alphaOf(raster)?.also { if (it.owned) temporary += it.bitmap }?.bitmap ?: return null
                val map = mapOf(layer)?.also { temporary += it }
                if (layer.gainmap != null && map == null) return null
                cover(canvas, width, height, layer.opacity, alpha, map, neutral)
            }
            picture.endRecording()
            val drawn = PreviewRenderer.drawOnGpu(picture, width, height) ?: return null
            try {
                return drawn.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                drawn.recycle()
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /** 레이어 하나의 몫: [alpha]의 알파 × [opacity]만큼 [map](없으면 [neutral])으로 덮는다 */
    private fun cover(canvas: Canvas, width: Int, height: Int, opacity: Float, alpha: Bitmap, map: Bitmap?, neutral: Int) {
        val whole = Rect(0, 0, width, height)
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.alpha = (opacity * 255).roundToInt() })
        canvas.drawBitmap(alpha, null, whole, Paint(Paint.FILTER_BITMAP_FLAG))
        // 레이어 알파는 그대로 두고 색만 게인맵으로 바꾼다
        if (map != null) {
            canvas.drawBitmap(map, null, whole, Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC_IN })
        } else {
            canvas.drawColor(neutral, BlendMode.SRC_IN)
        }
        canvas.restoreToCount(saved)
    }

    /** [layer] 게인맵을 원래 크기 그대로 한 장으로 그린 그림(하드웨어). 게인맵이 없으면 null. */
    private fun mapOf(layer: Layer): Bitmap? {
        val gainmap = layer.gainmap ?: return null
        return PreviewRenderer.render(Document.create(gainmap.width, gainmap.height, "", gainmap.raster), 0)
    }

    /** 레이어 알파를 그릴 축소본 단계: [width]×[height]보다 작아지지 않는 가장 작은 단계 */
    private fun alphaLevel(document: Document, width: Int, height: Int): Int {
        var level = 0
        while (document.width shr (level + 1) >= width && document.height shr (level + 1) >= height) level++
        return level
    }
}

package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.kiln.stratum.engine.document.HdrInfo
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.ceil

/**
 * 화면에 그릴 그림에 붙일 HDR 게인맵(편집 화면 HDR, 결정 기록 'HDR'). 레이어 게인맵을 겹친 것이고 문서 전체를 덮는다.
 * 앱은 들고 있다가 [DocumentColors.forScreen]에 넘기기만 한다. 만드는 것은 [LayerPreviewCache.screenGainmap]이다.
 */
class ScreenGainmap internal constructor(internal val contents: Bitmap, internal val info: HdrInfo) {

    /**
     * 문서([document]) 자리 [shown]을 덮는 만큼 잘라 낸 게인맵 그림(소프트웨어). 문서 전체면 그대로이고, 잘라 낼 수 없으면 null.
     * 확대해 보는 부분 그림은 그 자리만큼만 붙여야 HWUI가 그림 전체에 늘여 덮을 때 자리가 맞는다.
     */
    internal fun crop(shown: PixelRect, document: PixelSize): Bitmap? {
        if (shown == PixelRect.of(document)) return contents
        val scaleX = contents.width.toFloat() / document.width
        val scaleY = contents.height.toFloat() / document.height
        val source = RectF(shown.left * scaleX, shown.top * scaleY, shown.right * scaleX, shown.bottom * scaleY)
        val width = ceil(source.width()).toInt().coerceAtLeast(1)
        val height = ceil(source.height()).toInt().coerceAtLeast(1)
        return try {
            val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(cropped)
            canvas.scale(width / source.width(), height / source.height())
            canvas.translate(-source.left, -source.top)
            canvas.drawBitmap(contents, Rect(0, 0, contents.width, contents.height), RectF(0f, 0f, contents.width.toFloat(), contents.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            cropped
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

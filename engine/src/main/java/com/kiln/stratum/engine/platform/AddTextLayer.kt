package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Picture
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.text.TextStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * [text]를 그린 새 레이어를 [index] 자리에 더한다(텍스트 기본, CLAUDE.md 8장). 글자 상자의 왼쪽 위가 문서 좌표 ([x], [y])다.
 *
 * 글자는 픽셀로 그려 넣는다(다시 고칠 수 있는 글자 레이어는 나중). 여러 줄은 줄바꿈 문자대로 나누고 왼쪽에 맞춘다.
 * 글꼴은 기기 기본(한글 포함)이다. 타일은 안드로이드 소프트웨어 그리기로 만든다(브러시와 같은 까닭).
 * 문서 밖으로 나간 부분은 버린다. 글자가 비었거나 문서에 하나도 닿지 않으면 문서를 그대로 돌려준다.
 *
 * @property name 새 레이어 이름. 앱이 정한다(글자의 앞부분 등).
 */
data class AddTextLayer(
    val text: String,
    val x: Float,
    val y: Float,
    val style: TextStyle,
    val name: String,
    val index: Int
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        require(index in 0..document.layers.size) { "Layer index out of range: $index" }
        if (text.isBlank()) return document
        // sRGB로 고른 색을 문서 색 공간 값으로 바꿔 그린다(P3 문서에서도 같은 색으로 보임)
        val layout = layoutOf(text, style.copy(color = DocumentColors.paintColor(style.color, document.colorSpace)))
        val bounds = boundsOf(layout, document) ?: return document
        val raster = try {
            TextTiles.draw(layout, x, y, bounds, TileGrid(document.width, document.height), checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        val layer = Layer(LayerId(document.nextLayerId), name, RasterContent(raster))
        val layers = document.layers.toMutableList().apply { add(index, layer) }
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
    }

    /** 글자가 닿는 타일마다 새 타일 한 장 */
    override fun estimateNewBytes(document: Document): Long {
        if (text.isBlank()) return 0L
        val bounds = boundsOf(layoutOf(text, style), document) ?: return 0L
        return BrushTiles.keysIn(bounds).size.toLong() * TILE_SIZE * TILE_SIZE * Int.SIZE_BYTES
    }

    /** 글자 상자가 닿는 문서 영역(문서 안으로 자름). 닿지 않으면 null. */
    private fun boundsOf(layout: StaticLayout, document: Document): PixelRect? {
        val left = floor(x).toInt().coerceIn(0, document.width)
        val top = floor(y).toInt().coerceIn(0, document.height)
        val right = ceil(x + layout.width).toInt().coerceIn(0, document.width)
        val bottom = ceil(y + layout.height).toInt().coerceIn(0, document.height)
        return PixelRect(left, top, maxOf(left, right), maxOf(top, bottom)).takeIf { it.width > 0 && it.height > 0 }
    }

    companion object {
        /** [text]를 [style]로 줄 나눈 모양. 폭은 가장 긴 줄이다(줄바꿈 문자에서만 나눈다). */
        internal fun layoutOf(text: String, style: TextStyle): StaticLayout {
            val paint = TextPaint().apply {
                isAntiAlias = true
                textSize = style.size
                color = style.color or 0xFF000000.toInt()
                typeface = Typeface.DEFAULT
            }
            val width = ceil(text.lines().maxOf { Layout.getDesiredWidth(it, paint) }).toInt().coerceAtLeast(1)
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .build()
        }
    }
}

/** 글자 모양을 타일에 그린다(소프트웨어). */
private object TextTiles {

    fun draw(layout: StaticLayout, x: Float, y: Float, bounds: PixelRect, grid: TileGrid, checkCanceled: () -> Unit): Raster {
        val tiles = HashMap<TileKey, Tile?>()
        for (key in BrushTiles.keysIn(bounds)) {
            checkCanceled()
            val tile = grid.bounds(key)
            val picture = Picture()
            val canvas = picture.beginRecording(tile.width, tile.height)
            canvas.translate(x - tile.left, y - tile.top)
            layout.draw(canvas)
            picture.endRecording()
            tiles[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, tile.width, tile.height, Bitmap.Config.ARGB_8888)))
        }
        return Raster.EMPTY.withTiles(tiles)
    }
}

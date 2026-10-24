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
import com.kiln.stratum.engine.raster.tileKeysIn
import com.kiln.stratum.engine.raster.tileSquare
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * [text]를 그린 새 글자 레이어를 [index] 자리에 더한다(텍스트 기본, CLAUDE.md 8장). 글자 상자의 왼쪽 위가 문서 좌표 ([x], [y])다.
 *
 * 글자는 픽셀로 그려 넣고, 다시 고칠 수 있게 글자 정보([TextSpec])를 레이어에 함께 둔다(사용자 피드백 8차 1-6). 여러 줄은 줄바꿈 문자대로 나누고 왼쪽에 맞춘다.
 * 글꼴은 기기 기본(한글 포함)이다. 타일은 안드로이드 소프트웨어 그리기로 만든다(브러시와 같은 까닭).
 * 문서 밖으로 나간 글자도 레이어에 담는다(결정 기록 '캔버스 밖 픽셀'). 글자가 비었거나 문서에 하나도 닿지 않으면 문서를 그대로 돌려준다.
 *
 * @property name 새 레이어 이름. 앱이 정한다(글자의 앞부분 등).
 * @property parent 새 레이어가 들어갈 그룹(결정 기록 '피부 보정'의 1g). 그 그룹 안 자리여야 한다.
 */
data class AddTextLayer(
    val text: String,
    val x: Float,
    val y: Float,
    val style: TextStyle,
    val name: String,
    val index: Int,
    val parent: LayerId? = null
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        require(index in 0..document.layers.size) { "Layer index out of range: $index" }
        val drawn = TextRenderer.draw(text, x, y, style, document, checkCanceled) ?: return document
        val grid = TileGrid(document.width, document.height)
        if (drawn.raster.tiles.keys.none { grid.contains(it) }) return document
        val layer = Layer(LayerId(document.nextLayerId), name, RasterContent(drawn.raster), text = drawn.spec, parent = parent)
        val layers = document.layers.toMutableList().apply { add(index, layer) }
        return document.copy(layers = layers, nextLayerId = document.nextLayerId + 1)
    }

    /** 글자가 닿는 타일마다 새 타일 한 장 */
    override fun estimateNewBytes(document: Document): Long = TextRenderer.estimateBytes(text, x, y, style, document)
}

/**
 * [layerId] 글자 레이어의 글자를 [text]·[style]로 고쳐 같은 자리(글자 상자 왼쪽 위)에 다시 그리고, 이름을 [name]으로 바꾼다(사용자 피드백 8차 1-6).
 * 픽셀은 새로 그린 글자로 바꾼다(전 글자가 남지 않는다). 캔버스 밖으로 나간 글자도 그린다(결정 기록 '캔버스 밖 픽셀').
 * 글자 레이어가 아니거나(픽셀로 바꿈) 없는 레이어, 빈 글자면 그대로 둔다.
 */
data class EditTextLayer(val layerId: LayerId, val text: String, val style: TextStyle, val name: String) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val spec = layer.text ?: return document
        val drawn = TextRenderer.draw(text, spec.x, spec.y, style, document, checkCanceled) ?: return document
        val layers = document.layers.toMutableList().apply { set(index, layer.withText(drawn.raster, drawn.spec).copy(name = name)) }
        return document.copy(layers = layers)
    }

    override fun estimateNewBytes(document: Document): Long {
        val spec = document.layers.getOrNull(document.indexOfLayer(layerId))?.text ?: return 0L
        return TextRenderer.estimateBytes(text, spec.x, spec.y, style, document)
    }
}

/** 글자를 타일에 그리는 일. 글자 넣기·고치기·옮기기(이동 도구)가 같이 쓴다. */
internal object TextRenderer {

    /** 그린 타일([raster])과 그 글자 정보([spec]) */
    class Drawn(val raster: Raster, val spec: TextSpec)

    /**
     * [text]를 [style]로 글자 상자 왼쪽 위 ([x], [y])에 그린 타일과 글자 정보. 글자가 비었으면 null이다.
     * 글자 상자 전체를 그린다: 캔버스 밖으로 나간 글자도 타일에 담아, 옮겨 들이거나 픽셀로 바꾸거나 병합해도 남는다(결정 기록 '캔버스 밖 픽셀').
     * sRGB로 고른 색을 문서 색 공간 값으로 바꿔 그린다(P3 문서에서도 같은 색으로 보임).
     * 타일을 만들 메모리가 없으면 [NotEnoughMemoryException]을 던진다.
     */
    fun draw(text: String, x: Float, y: Float, style: TextStyle, document: Document, checkCanceled: () -> Unit): Drawn? {
        if (text.isBlank()) return null
        val layout = layoutOf(text, style.copy(color = DocumentColors.paintColor(style.color, document.colorSpace)))
        val spec = TextSpec(text, x, y, style, layout.width.toFloat(), layout.height.toFloat())
        val bounds = boundsOf(layout, x, y) ?: return Drawn(Raster.EMPTY, spec)
        val raster = try {
            TextTiles.draw(layout, x, y, bounds, TileGrid(document.width, document.height), checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return Drawn(raster, spec)
    }

    /** 글자가 닿는 타일마다 새 타일 한 장 */
    fun estimateBytes(text: String, x: Float, y: Float, style: TextStyle, document: Document): Long {
        if (text.isBlank()) return 0L
        val bounds = boundsOf(layoutOf(text, style), x, y) ?: return 0L
        return tileKeysIn(bounds).size.toLong() * TILE_SIZE * TILE_SIZE * Int.SIZE_BYTES
    }

    /** 글자 상자가 닿는 레이어 영역(캔버스 밖 포함, 결정 기록 '캔버스 밖 픽셀'). 비면 null. */
    private fun boundsOf(layout: StaticLayout, x: Float, y: Float): PixelRect? {
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        val right = ceil(x + layout.width).toInt()
        val bottom = ceil(y + layout.height).toInt()
        return PixelRect(left, top, maxOf(left, right), maxOf(top, bottom)).takeIf { it.width > 0 && it.height > 0 }
    }

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

/** 글자 모양을 타일에 그린다(소프트웨어). */
private object TextTiles {

    /**
     * 글자 상자 [bounds]에 걸친 타일마다 그린다. 타일이 담는 크기는 왼쪽 위부터 글자 상자가 닿는 곳까지이고,
     * 캔버스 안 자리는 캔버스 안쪽보다 작지 않다(결정 기록 '캔버스 밖 픽셀').
     */
    fun draw(layout: StaticLayout, x: Float, y: Float, bounds: PixelRect, grid: TileGrid, checkCanceled: () -> Unit): Raster {
        val tiles = HashMap<TileKey, Tile?>()
        for (key in tileKeysIn(bounds)) {
            checkCanceled()
            val square = tileSquare(key)
            val data = checkNotNull(square.intersect(bounds))
            var width = data.right - square.left
            var height = data.bottom - square.top
            grid.canvasRect(key)?.let { inside ->
                width = maxOf(width, inside.width)
                height = maxOf(height, inside.height)
            }
            val picture = Picture()
            val canvas = picture.beginRecording(width, height)
            canvas.translate(x - square.left, y - square.top)
            layout.draw(canvas)
            picture.endRecording()
            tiles[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)))
        }
        return Raster.EMPTY.withTiles(tiles)
    }
}

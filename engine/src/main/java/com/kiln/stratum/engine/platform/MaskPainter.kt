package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection

/**
 * 레이어 마스크에 브러시 획을 그린다(결정 기록 '피부 보정'의 1c). 브러시는 보이게(255 쪽으로), 지우개는 가리게(0 쪽으로) 칠한다.
 * 픽셀 브러시와 같은 자국·단단함·불투명도([BrushPainter])라, 한 획의 불투명도는 자국이 겹쳐도 넘지 않는다.
 * 선택 영역이 있으면 고른 곳에만 칠한다. 마스크 타일(알파 그림)에 소프트웨어로 그리고, 획이 닿지 않은 알파는 그대로다.
 */
internal object MaskPainter {

    /**
     * [mask]의 [bounds] 안 타일에 획을 그린 새 마스크. 바뀐 것이 없으면 [mask]를 그대로 돌려준다.
     * [reveal]이면 보이게, 아니면 가리게 칠한다. [selection]이 있으면 그 안만 칠한다. [checkCanceled]는 타일 사이마다 부른다.
     */
    fun paint(
        mask: Selection,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        reveal: Boolean,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): Selection {
        val grid = TileGrid(size.width, size.height)
        val reach = settings.radius + 1f
        val changes = HashMap<TileKey, MaskTile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            for (key in BrushTiles.keysIn(bounds)) {
                checkCanceled()
                // 선택 영역이 있는데 이 자리를 하나도 고르지 않았으면 칠할 곳이 없다
                val clip = if (selection == null) null else (selection.tiles[key] ?: continue)
                val old = mask.tiles[key]
                // 다 보이는 곳을 더 보이게, 다 가린 곳을 더 가리게 칠해도 그대로다
                if (reveal && old?.isFull == true) continue
                if (!reveal && old == null) continue
                val tile = grid.bounds(key)
                val near = dabs.filter {
                    it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                        it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                }
                if (near.isEmpty()) continue
                changes[key] = MaskTiles.draw(tile.width, tile.height) { canvas ->
                    when {
                        old == null -> Unit
                        old.isFull -> canvas.drawColor(Color.BLACK)
                        else -> MaskTiles.drawAlpha(canvas, checkNotNull(MaskTiles.bitmapOf(old, temporary)), BlendMode.SRC)
                    }
                    // 보이게 칠하면 알파를 더하고(SRC_OVER), 가리게 칠하면 덜어 낸다(지우개와 같은 DST_OUT)
                    BrushPainter.drawStroke(
                        canvas, near, settings.copy(color = Color.BLACK), erase = !reveal,
                        scale = 1f,
                        left = tile.left.toFloat(),
                        top = tile.top.toFloat(),
                        width = tile.width.toFloat(),
                        height = tile.height.toFloat(),
                        clip = clip?.let { MaskTiles.bitmapOf(it, temporary) }
                    )
                }
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return if (changes.isEmpty()) mask else mask.withTiles(changes)
    }
}

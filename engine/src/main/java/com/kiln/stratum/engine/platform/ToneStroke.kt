package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.tone.ToneFill
import com.kiln.stratum.engine.tone.ToneMath

/**
 * 닷지(밝게)·번(어둡게) 획 하나. [layerId] 레이어의 획이 닿는 곳을 [fill]의 밝기 범위 중심으로 노출만큼 바꾼다([ToneMath]).
 * 원본 해상도에 바로 그린다(결정 기록 '원본 픽셀').
 *
 * 한 획 안에서는 겹쳐 칠해도 한 번만 걸리고(브러시의 불투명도와 같다), 여러 획은 쌓인다. 부드러운 브러시 가장자리는 그만큼 덜 걸린다.
 * 선택 영역이 있으면 그 안에만 건다. 투명한 곳(빈 타일)은 바꿀 색이 없어 건너뛴다.
 * 바꾼 색은 GPU로 계산하고([ToneShader]), 획 모양으로 얹기는 소프트웨어로 해서 칠하지 않은 픽셀은 값이 그대로다.
 * 없는 레이어거나 노출이 0이거나 문서에 닿지 않는 획이면 문서를 그대로 돌려준다.
 */
data class ToneStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val fill: ToneFill
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0 || fill.exposure == 0f) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: return document
        val toned = try {
            paint(raster, size, bounds, document.selection, checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (toned === raster) return document
        val layers = document.layers.toMutableList().apply { set(index, layer.withPixels(toned)) }
        return document.copy(layers = layers)
    }

    /** 획이 닿는 (있는) 타일마다 새 타일과 계산 그림 한 장 */
    override fun estimateNewBytes(document: Document): Long {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return 0L
        val raster = (layer.content as? RasterContent)?.raster ?: return 0L
        val bounds = StrokeDabs.bounds(dabs, settings.radius, PixelSize(document.width, document.height)) ?: return 0L
        return BrushTiles.keysIn(bounds).count { it in raster.tiles }.toLong() * TILE_SIZE * TILE_SIZE * Int.SIZE_BYTES * 2
    }

    private fun paint(raster: Raster, size: PixelSize, bounds: PixelRect, selection: Selection?, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val reach = settings.radius + 1f
        val changes = HashMap<TileKey, Tile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            for (key in BrushTiles.keysIn(bounds)) {
                checkCanceled()
                // 선택 영역이 있는데 이 자리를 하나도 고르지 않았으면 바꿀 것이 없다
                val mask = if (selection == null) null else (selection.tiles[key] ?: continue)
                // 빈 자리(투명)는 바꿀 색이 없다
                val old = raster.tiles[key] ?: continue
                val tile = grid.bounds(key)
                val near = dabs.filter {
                    it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                        it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                }
                if (near.isEmpty()) continue
                val source = CanvasTileComposer.bitmapOf(old, temporary)
                val toned = ToneShader.render(source, tile.width, tile.height, fill) ?: throw NotEnoughMemoryException()
                try {
                    // 세기는 노출이 정하므로 브러시 불투명도는 쓰지 않는다. 캔버스 안쪽만 바꾼다(결정 기록 '캔버스 밖 픽셀').
                    changes[key] = TileRebuild.pixels(key, old, grid, temporary) { canvas ->
                        BrushPainter.drawStroke(
                            canvas, near, settings.copy(opacity = 1f), erase = false,
                            scale = 1f,
                            left = tile.left.toFloat(),
                            top = tile.top.toFloat(),
                            width = tile.width.toFloat(),
                            height = tile.height.toFloat(),
                            clip = mask?.let { MaskTiles.bitmapOf(it, temporary) },
                            fill = BitmapShader(toned, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        )
                    }
                } finally {
                    toned.recycle()
                }
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return if (changes.isEmpty()) raster else raster.withTiles(changes)
    }
}

/** 닷지·번 색 계산(GPU 셰이더, AGSL). [ToneMath]와 같은 식이다. */
object ToneShader {

    /** [image] 셰이더의 색을 [fill]대로 바꾸는 셰이더(끄는 동안의 미리 보기도 쓴다) */
    fun of(image: Shader, fill: ToneFill): RuntimeShader =
        RuntimeShader(TONE).apply {
            setInputShader("image", image)
            setFloatUniform("exposure", fill.exposure)
            setFloatUniform("range", fill.range.ordinal.toFloat())
            setFloatUniform("dodge", if (fill.dodge) 1f else 0f)
        }

    /** [source] 전체([width]×[height])를 [fill]대로 바꾼 소프트웨어 비트맵. GPU가 그리지 못하면 null. */
    internal fun render(source: Bitmap, width: Int, height: Int, fill: ToneFill): Bitmap? {
        val shader = of(BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }, fill)
        val drawn = Retouch.drawOnGpu(shader, width, height) ?: return null
        try {
            return drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        }
    }

    /** 범위는 0 어두운 영역, 1 중간 영역, 2 밝은 영역([com.kiln.stratum.engine.tone.ToneRange] 순서). 알파를 곱하지 않은 색으로 계산한다. */
    private const val TONE = """
        uniform shader image;
        uniform float exposure;
        uniform float range;
        uniform float dodge;

        half4 main(float2 position) {
            float4 c = float4(image.eval(position));
            if (c.a <= 0.0) return half4(c);
            float3 rgb = c.rgb / c.a;
            float l = clamp(dot(rgb, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            float w = range < 0.5 ? (1.0 - l) * (1.0 - l) : (range < 1.5 ? 4.0 * l * (1.0 - l) : l * l);
            float k = exposure * w * ${ToneMath.STRENGTH};
            rgb = dodge > 0.5 ? rgb + (1.0 - rgb) * k : rgb * (1.0 - k);
            return half4(half3(clamp(rgb, 0.0, 1.0) * c.a), half(c.a));
        }
    """
}

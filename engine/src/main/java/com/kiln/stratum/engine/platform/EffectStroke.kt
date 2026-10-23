package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
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
import com.kiln.stratum.engine.effect.EffectFill
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.effect.EffectMath
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Selection

/**
 * 흐림·선명·모자이크 획 하나([fill]). [layerId] 레이어의 획이 닿는 곳을 효과를 건 픽셀로 바꾼다. 원본 해상도에 바로 그린다(결정 기록 '원본 픽셀').
 *
 * 한 획 안에서는 겹쳐 칠해도 한 번만 걸리고(명암과 같다), 여러 획은 쌓인다. 부드러운 브러시 가장자리는 그만큼 섞인다
 * (결과 = 원래 × (1 - 몫) + 효과 × 몫, [BrushPainter.drawReplace]). 칠하지 않은 픽셀은 값이 그대로다.
 * 효과는 문서 자리로만 정해져(흐림은 둘레를 더 읽고, 모자이크 칸은 문서 (0, 0)에 맞춤) 타일·묶음 사이에 이음매가 없다.
 * 흐림·모자이크는 투명한 곳으로도 번지고(포토샵과 같다), 선명은 알파를 그대로 둔다. 문서 밖은 가장자리 픽셀을 늘인 것으로 본다.
 * 선택 영역이 있으면 그 안에만 건다. 게인맵(HDR)은 흐림·모자이크만 같은 일을 하고 선명은 그대로 둔다(명암과 같은 절충).
 * 효과 계산은 GPU(AGSL, [EffectShader]) 두 번이고, 얹기는 소프트웨어다.
 * 없는 레이어·빈 레이어거나 문서에 닿지 않는 획이면 문서를 그대로 돌려준다.
 */
data class EffectStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val fill: EffectFill
) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        // 빈 레이어에는 바꿀 색이 없고, 강도 0인 흐림·선명은 바뀌는 것이 없다
        if (raster.tiles.isEmpty() || (fill.kind != EffectKind.MOSAIC && fill.strength == 0f)) return document
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: return document
        val painted = try {
            Effects.paint(raster, size, bounds, dabs, settings, fill, document.selection, checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (painted === raster) return document
        val gainmap = if (fill.kind == EffectKind.SHARPEN) {
            layer.gainmap
        } else {
            try {
                layer.gainmap?.let { GainmapEdits.effect(it, size, dabs, settings, fill, document.selection, checkCanceled) }
            } catch (e: OutOfMemoryError) {
                throw NotEnoughMemoryException(e)
            }
        }
        val layers = document.layers.toMutableList().apply { set(index, layer.withPixels(painted, gainmap)) }
        return document.copy(layers = layers)
    }

    /** 획이 닿는 타일마다 새 타일 한 장과, 묶음 하나를 계산하는 동안의 그림들 */
    override fun estimateNewBytes(document: Document): Long {
        val bounds = StrokeDabs.bounds(dabs, settings.radius, PixelSize(document.width, document.height)) ?: return 0L
        val tileBytes = TILE_SIZE.toLong() * TILE_SIZE * Int.SIZE_BYTES
        return BrushTiles.keysIn(bounds).size * tileBytes + Effects.workBytes(fill, settings.size)
    }
}

/** 흐림·선명·모자이크 획 그리기 */
internal object Effects {

    /** 효과를 한 번에 계산하는 묶음의 한 변(타일 수). 1024px이고, 묶음마다 GPU를 두 번 쓴다(리터칭과 같은 묶음). */
    private const val BLOCK_TILES = 4

    /** 묶음 하나를 계산하는 동안 잠깐 쓰는 메모리의 어림(바이트): 읽을 곳 그림 두 장(소프트웨어·GPU)과 결과 두 장(GPU·소프트웨어) */
    fun workBytes(fill: EffectFill, brushSize: Float): Long {
        val block = (BLOCK_TILES * TILE_SIZE).toLong()
        val reach = if (fill.kind == EffectKind.MOSAIC) fill.cell else EffectMath.margin(fill, brushSize)
        val read = block + 2L * reach
        return (2 * read * read + 2 * block * block) * Int.SIZE_BYTES
    }

    /**
     * [raster]의 [bounds] 안 타일에 획을 그린 새 래스터. 바뀐 것이 없으면 [raster]를 그대로 돌려준다.
     * 획이 닿는 타일을 묶음([BLOCK_TILES]×[BLOCK_TILES] 타일)으로 모아, 묶음마다 읽을 곳([readArea])을 한 장으로 베껴
     * 효과를 건 뒤([EffectShader.render]) 타일마다 획 모양으로 바꾼다([BrushPainter.drawReplace]).
     * 빈 자리라도 둘레에 픽셀이 있으면(흐림·모자이크가 번져 들어옴) 그린다. 둘레까지 비었으면 바뀌는 것이 없어 건너뛴다.
     */
    fun paint(
        raster: Raster,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        fill: EffectFill,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): Raster {
        val grid = TileGrid(size.width, size.height)
        val reach = settings.radius + 1f
        val margin = EffectMath.margin(fill, settings.size)
        // 그릴 타일: 선택 영역이 있으면 고른 타일만, 자국이 닿고 읽을 곳에 픽셀이 있는 것만
        val keys = BrushTiles.keysIn(bounds).filter { key ->
            if (selection != null && key !in selection.tiles) return@filter false
            val tile = grid.bounds(key)
            val rect = PixelRect(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)
            raster.hasTilesIn(readArea(rect, fill, margin, size)) && dabs.any {
                it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                    it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
            }
        }
        val changes = HashMap<TileKey, Tile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            for (block in keys.groupBy { TileKey(it.column / BLOCK_TILES, it.row / BLOCK_TILES) }.values) {
                checkCanceled()
                val area = block.map { key -> grid.bounds(key).let { PixelRect(it.left, it.top, it.left + it.width, it.top + it.height) } }
                    .reduce { a, b -> PixelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
                val read = readArea(area, fill, margin, size)
                val source = Retouch.compose(raster, size, read)
                val effect = try {
                    EffectShader.render(source, read, area, fill, settings.size, size) ?: throw NotEnoughMemoryException()
                } finally {
                    source.recycle()
                }
                try {
                    for (key in block) {
                        checkCanceled()
                        val tile = grid.bounds(key)
                        val near = dabs.filter {
                            it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                                it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                        }
                        val picture = Picture()
                        val canvas = picture.beginRecording(tile.width, tile.height)
                        raster.tiles[key]?.let { canvas.drawBitmap(CanvasTileComposer.bitmapOf(it, temporary), 0f, 0f, CanvasTileComposer.CopyPaint) }
                        // 타일 자리 q의 효과 색은 묶음 그림의 (q + 타일 - 묶음) 자리다
                        val shader = BitmapShader(effect, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                            setLocalMatrix(Matrix().apply { setTranslate((area.left - tile.left).toFloat(), (area.top - tile.top).toFloat()) })
                        }
                        BrushPainter.drawReplace(
                            canvas, near, settings,
                            scale = 1f,
                            left = tile.left.toFloat(),
                            top = tile.top.toFloat(),
                            width = tile.width.toFloat(),
                            height = tile.height.toFloat(),
                            fill = shader,
                            clip = selection?.tiles?.get(key)?.let { MaskTiles.bitmapOf(it, temporary) }
                        )
                        picture.endRecording()
                        // 브러시 타일과 같이 소프트웨어로 그리고 바꿀 수 없는 비트맵 하나로 돌려준다
                        changes[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, tile.width, tile.height, Bitmap.Config.ARGB_8888)))
                    }
                } finally {
                    effect.recycle()
                }
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
        return if (changes.isEmpty()) raster else raster.withTiles(changes)
    }

    /**
     * [area]를 계산하려고 읽을 곳: 흐림·선명은 둘레 [margin]까지, 모자이크는 걸친 칸 전체(칸 경계에 맞춤).
     * 문서 밖은 자른다(셰이더가 그림 끝 픽셀을 늘여 읽으므로 문서 가장자리를 늘인 것이 된다).
     */
    fun readArea(area: PixelRect, fill: EffectFill, margin: Int, size: PixelSize): PixelRect =
        if (fill.kind == EffectKind.MOSAIC) {
            val (left, right) = EffectMath.cellAligned(area.left, area.right, fill.cell, size.width)
            val (top, bottom) = EffectMath.cellAligned(area.top, area.bottom, fill.cell, size.height)
            PixelRect(left, top, right, bottom)
        } else {
            PixelRect(
                (area.left - margin).coerceAtLeast(0),
                (area.top - margin).coerceAtLeast(0),
                (area.right + margin).coerceAtMost(size.width),
                (area.bottom + margin).coerceAtMost(size.height)
            )
        }
}

/**
 * 흐림·선명·모자이크 계산(GPU 셰이더, AGSL). [EffectMath]와 같은 값·식을 쓴다.
 * 흐림은 가로·세로로 나눠 두 번 흐리고, 선명은 가로로 흐린 뒤 세로로 흐리며 원래 색과의 차이를 더하고,
 * 모자이크는 칸의 가로 평균을 낸 뒤 세로 평균을 낸다. 알파를 곱한 값으로 섞는다(선명은 알파를 빼고 색만 바꾼다).
 */
object EffectShader {

    /**
     * [source](문서의 [read] 자리를 베낀 같은 크기의 그림)에 [fill]을 건 [area] 자리 그림(소프트웨어, 바꿀 수 없음). [area]는 [read] 안이다.
     * GPU가 그리지 못하면 null.
     */
    internal fun render(source: Bitmap, read: PixelRect, area: PixelRect, fill: EffectFill, brushSize: Float, document: PixelSize): Bitmap? {
        val drawn = renderOnGpu(
            source, read.left.toFloat(), read.top.toFloat(), pixel = 1f,
            fromX = area.left - read.left, fromY = area.top - read.top, width = area.width, height = area.height,
            fill = fill, brushSize = brushSize, document = document
        ) ?: return null
        try {
            return drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        }
    }

    /**
     * [image]에 [fill]을 건 그림(GPU 하드웨어 비트맵). [image]의 (0, 0)은 문서 ([docLeft], [docTop])이고, 그림 픽셀 하나는 문서 [pixel]픽셀이다
     * (원본은 1, 축소본은 2^단계). 결과는 [image]의 ([fromX], [fromY])부터 [width]×[height]다. 그림 밖은 끝 픽셀을 늘여 읽는다.
     * 기록(원본 묶음)과 끄는 동안의 미리 보기(축소본, [com.kiln.stratum.engine.platform.LayerPreviewCache])가 같이 쓴다. GPU가 그리지 못하면 null.
     */
    fun renderOnGpu(
        image: Bitmap,
        docLeft: Float,
        docTop: Float,
        pixel: Float,
        fromX: Int,
        fromY: Int,
        width: Int,
        height: Int,
        fill: EffectFill,
        brushSize: Float,
        document: PixelSize
    ): Bitmap? =
        when (fill.kind) {
            EffectKind.BLUR, EffectKind.SHARPEN -> {
                val step = EffectMath.tapStep(EffectMath.sigma(fill, brushSize)) / pixel
                // 먼저 그림 전체를 가로로 흐린다(세로로 흐릴 때 위아래 줄도 읽으므로)
                val across = Retouch.drawOnGpu(blur(read(image, linear = true), 0f, 0f, axisX = 1f, axisY = 0f, step), image.width, image.height)
                across?.let {
                    try {
                        val shader = if (fill.kind == EffectKind.BLUR) {
                            blur(read(it, linear = true), fromX.toFloat(), fromY.toFloat(), axisX = 0f, axisY = 1f, step)
                        } else {
                            sharpen(read(it, linear = true), read(image, linear = false), fromX.toFloat(), fromY.toFloat(), step, EffectMath.sharpenAmount(fill.strength))
                        }
                        Retouch.drawOnGpu(shader, width, height)
                    } finally {
                        it.recycle()
                    }
                }
            }
            EffectKind.MOSAIC -> {
                // 칸마다 가로 평균(그림 전체), 그다음 세로 평균
                val across = Retouch.drawOnGpu(
                    mosaic(read(image, linear = true), 0f, 0f, docLeft, docTop, pixel, axisX = 1f, axisY = 0f, fill.cell, document.width),
                    image.width, image.height
                )
                across?.let {
                    try {
                        Retouch.drawOnGpu(
                            mosaic(read(it, linear = true), fromX.toFloat(), fromY.toFloat(), docLeft, docTop, pixel, axisX = 0f, axisY = 1f, fill.cell, document.height),
                            width, height
                        )
                    } finally {
                        it.recycle()
                    }
                }
            }
        }

    /**
     * [image]를 가우스 σ [sigma](그림 픽셀)로 흐린 그림(GPU 하드웨어 비트맵, 알파를 곱한 값으로 섞음). 결과는 [image]의 ([fromX], [fromY])부터
     * [width]×[height]다. 그림 밖은 끝 픽셀을 늘여 읽는다. 흐림 도구와 같은 두 번 흐리기다(피부 정돈의 고운 결, 결정 기록 '피부 보정'의 4단계).
     * GPU가 그리지 못하면 null.
     */
    internal fun gaussian(image: Bitmap, sigma: Float, fromX: Int, fromY: Int, width: Int, height: Int): Bitmap? {
        val step = EffectMath.tapStep(sigma)
        val across = Retouch.drawOnGpu(blur(read(image, linear = true), 0f, 0f, axisX = 1f, axisY = 0f, step), image.width, image.height) ?: return null
        try {
            return Retouch.drawOnGpu(blur(read(across, linear = true), fromX.toFloat(), fromY.toFloat(), axisX = 0f, axisY = 1f, step), width, height)
        } finally {
            across.recycle()
        }
    }

    /** 그림을 읽는 셰이더. 그림 밖은 끝 픽셀을 늘인다. [linear]면 픽셀 사이를 쌍선형으로 읽는다. */
    private fun read(bitmap: Bitmap, linear: Boolean): Shader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = if (linear) BitmapShader.FILTER_MODE_LINEAR else BitmapShader.FILTER_MODE_NEAREST
        }

    private fun blur(image: Shader, fromX: Float, fromY: Float, axisX: Float, axisY: Float, step: Float): RuntimeShader =
        RuntimeShader(BLUR).apply {
            setInputShader("image", image)
            setFloatUniform("from", fromX, fromY)
            setFloatUniform("axis", axisX, axisY)
            setFloatUniform("stepLength", step)
        }

    private fun sharpen(across: Shader, original: Shader, fromX: Float, fromY: Float, step: Float, amount: Float): RuntimeShader =
        RuntimeShader(SHARPEN).apply {
            setInputShader("image", across)
            setInputShader("original", original)
            setFloatUniform("from", fromX, fromY)
            setFloatUniform("stepLength", step)
            setFloatUniform("amount", amount)
        }

    private fun mosaic(
        image: Shader,
        fromX: Float,
        fromY: Float,
        docLeft: Float,
        docTop: Float,
        pixel: Float,
        axisX: Float,
        axisY: Float,
        cell: Int,
        limit: Int
    ): RuntimeShader =
        RuntimeShader(MOSAIC).apply {
            setInputShader("image", image)
            setFloatUniform("from", fromX, fromY)
            setFloatUniform("docOrigin", docLeft, docTop)
            setFloatUniform("pixel", pixel)
            setFloatUniform("axis", axisX, axisY)
            setFloatUniform("cell", cell.toFloat())
            setFloatUniform("limit", limit.toFloat())
        }

    /** 한 축 가우스 흐림: 가운데에서 양쪽으로 [EffectMath.TAPS]곳씩 3σ까지(가중치 [EffectMath.tapWeight]) */
    private const val BLUR = """
        uniform shader image;
        uniform float2 from;
        uniform float2 axis;
        uniform float stepLength;

        half4 main(float2 position) {
            float2 at = position + from;
            float4 sum = float4(0.0);
            float total = 0.0;
            for (int i = -${EffectMath.TAPS}; i <= ${EffectMath.TAPS}; i++) {
                float t = 3.0 * float(i) / ${EffectMath.TAPS}.0;
                float w = exp(-0.5 * t * t);
                sum += w * float4(image.eval(at + axis * (float(i) * stepLength)));
                total += w;
            }
            return half4(sum / total);
        }
    """

    /** 선명: 가로로 흐린 그림을 세로로 흐려 원래 색과 견주고, 차이를 [amount]배 더한다. 알파는 원래 그대로, 색은 알파를 뺀 값에서 계산한다. */
    private const val SHARPEN = """
        uniform shader image;
        uniform shader original;
        uniform float2 from;
        uniform float stepLength;
        uniform float amount;

        half4 main(float2 position) {
            float2 at = position + from;
            float4 sum = float4(0.0);
            float total = 0.0;
            for (int i = -${EffectMath.TAPS}; i <= ${EffectMath.TAPS}; i++) {
                float t = 3.0 * float(i) / ${EffectMath.TAPS}.0;
                float w = exp(-0.5 * t * t);
                sum += w * float4(image.eval(at + float2(0.0, float(i) * stepLength)));
                total += w;
            }
            float4 blurred = sum / total;
            float4 o = float4(original.eval(at));
            if (o.a <= 0.0) return half4(o);
            float3 color = o.rgb / o.a;
            float3 soft = blurred.a > 0.0 ? blurred.rgb / blurred.a : color;
            float3 sharp = clamp(color + amount * (color - soft), 0.0, 1.0);
            return half4(half3(sharp * o.a), half(o.a));
        }
    """

    /**
     * 모자이크 한 축: 이 픽셀이 든 칸(문서 (0, 0)에서 시작하는 격자, 문서 밖은 뺌)의 그 축 평균([EffectMath.cellSamples]와 같은 곳을 읽는다).
     * 칸이 그림 픽셀 [EffectMath.CELL_SAMPLES]개 이하면 픽셀 가운데마다 읽어 정확한 평균이다.
     */
    private const val MOSAIC = """
        uniform shader image;
        uniform float2 from;
        uniform float2 docOrigin;
        uniform float pixel;
        uniform float2 axis;
        uniform float cell;
        uniform float limit;

        half4 main(float2 position) {
            float2 at = position + from;
            float origin = dot(docOrigin, axis);
            float here = dot(at, axis);
            float start = floor((origin + here * pixel) / cell) * cell;
            float low = max(start, 0.0);
            float high = min(start + cell, limit);
            float lowImage = (low - origin) / pixel;
            float span = (high - low) / pixel;
            float count = clamp(ceil(span), 1.0, ${EffectMath.CELL_SAMPLES}.0);
            float4 sum = float4(0.0);
            for (int i = 0; i < ${EffectMath.CELL_SAMPLES}; i++) {
                if (float(i) < count) {
                    float pick = lowImage + (float(i) + 0.5) * span / count;
                    sum += float4(image.eval(at + axis * (pick - here)));
                }
            }
            return half4(sum / count);
        }
    """
}

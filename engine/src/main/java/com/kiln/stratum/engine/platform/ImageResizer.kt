package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.transform.ResizePlan

/**
 * 문서 전체(모든 레이어)의 크기를 [width]×[height]로 바꾼다. 픽셀을 다시 계산하는 일이라 GPU로 한다([ImageResizer]).
 * 크기는 CanvasLimits.checkResize 안이어야 한다(크기 변경 창이 검사하고 넘긴다). 같은 크기면 그대로 둔다.
 */
data class ResizeImage(val width: Int, val height: Int) : CancellableCommand {

    init {
        require(width > 0 && height > 0) { "Size must be positive: ${width}x$height" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val source = PixelSize(document.width, document.height)
        val target = PixelSize(width, height)
        if (source == target) return document
        val plan = ResizePlan(source, target)
        val layers = document.layers.map { layer ->
            when (val content = layer.content) {
                is RasterContent -> layer.copy(content = RasterContent(ImageResizer.resize(content.raster, plan, checkCanceled)))
            }
        }
        // 선택 영역은 크기를 바꾸지 않고 푼다(결정 기록 '선택 도구')
        return document.copy(width = width, height = height, layers = layers, selection = null)
    }

    /** 빈 레이어와 한 가지 색 레이어는 새 메모리를 거의 쓰지 않고, 나머지는 레이어마다 새 크기만큼 쓴다. */
    override fun estimateNewBytes(document: Document): Long {
        val source = PixelSize(document.width, document.height)
        val layerBytes = width.toLong() * height * Int.SIZE_BYTES
        return document.layers.count { layer ->
            val raster = (layer.content as? RasterContent)?.raster
            raster != null && raster.tiles.isNotEmpty() && ImageResizer.solidTile(raster, source) == null
        } * layerBytes
    }
}

/**
 * 레이어 픽셀의 크기를 GPU로 바꾼다.
 *
 * 1. 줄일 때는 먼저 축소본과 같은 방법으로 정확히 반씩 줄인다(2×2 평균, [PreviewRenderer.renderRegion]).
 *    가로·세로 배율이 다르면 한 축만 더 반으로 줄인다([PreviewRenderer.halveAxis]).
 * 2. 남은 배율(0.5보다 크고 1 이하, 키울 때는 1보다 큼)은 바이큐빅(Catmull-Rom) 셰이더로 한 번 보간한다.
 * 3. 대상은 1024px 묶음씩 그리고, 묶음마다 보간에 필요한 원본 둘레까지 읽어 이음매가 없다. 캔버스 끝은 가장자리 픽셀을 늘인다.
 * 4. 결과를 GPU에서 읽어 와 타일로 나눈다(바꿀 수 없는 비트맵).
 *
 * 레이어 하나만 담은 문서(불투명도 1, 표준)로 그려, 레이어의 불투명도·혼합 모드가 픽셀에 섞이지 않고 숨긴 레이어도 그려진다.
 * 한 가지 색 레이어는 계산하지 않고 그 타일 한 장으로 둔다. GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
internal object ImageResizer {

    /** [raster]를 [plan]대로 바꾼 새 래스터. 메모리가 모자라면 [NotEnoughMemoryException]을 던진다. */
    fun resize(raster: Raster, plan: ResizePlan, checkCanceled: () -> Unit): Raster {
        if (raster.tiles.isEmpty()) return Raster.EMPTY
        solidTile(raster, plan.source)?.let { return Raster.filled(TileGrid(plan.target.width, plan.target.height), it) }
        val single = Document.create(plan.source.width, plan.source.height, "", raster)
        val targetGrid = TileGrid(plan.target.width, plan.target.height)
        val tiles = HashMap<TileKey, Tile?>()
        try {
            for (block in plan.blocks()) {
                checkCanceled()
                val sourceRect = plan.sourceRect(block)
                // 원본이 모두 투명(타일 없음)이면 대상도 비워 둔다
                if (!raster.hasTilesIn(sourceRect)) continue
                val pixels = renderBlock(single, plan, block, sourceRect) ?: throw NotEnoughMemoryException()
                try {
                    splitIntoTiles(pixels, block, targetGrid, tiles)
                } finally {
                    pixels.recycle()
                }
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 모든 자리가 같은 한 가지 색 타일이면 그 타일. 크기를 바꿔도 같은 색이라 그대로 쓴다. */
    fun solidTile(raster: Raster, size: PixelSize): Tile? {
        val grid = TileGrid(size.width, size.height)
        val first = raster.tiles.values.firstOrNull() ?: return null
        if (first.solidColor == null || raster.tiles.size != grid.columns * grid.rows) return null
        return first.takeIf { tile -> raster.tiles.values.all { it === tile } }
    }

    /**
     * 대상 묶음 [block]을 그린 소프트웨어 비트맵(대상 픽셀 크기). GPU가 그리지 못하면 null.
     * [document]의 보이는 레이어를 모두 합성해 그린다(크기 변경은 레이어 하나만 담은 문서, 내보내기는 문서 전체).
     * [background]가 있으면 그 색 바탕 위에 얹는다(JPEG로 내보낼 때의 흰 바탕).
     */
    internal fun renderBlock(
        document: Document,
        plan: ResizePlan,
        block: PixelRect,
        sourceRect: PixelRect,
        background: Int? = null
    ): Bitmap? {
        val fine = minOf(plan.levelX, plan.levelY)
        val coarse = maxOf(plan.levelX, plan.levelY)
        // 더 거친 단계의 격자에 맞춰 두어야, 한 축만 더 반으로 줄일 때도 두 픽셀 짝이 문서 전체와 같다
        val snapped = checkNotNull(PreviewLevels.region(plan.source, coarse, sourceRect)) { "Empty source for $block" }
        val region = PreviewRegion(fine, snapped.rect)
        var level = PreviewRenderer.renderRegion(document, region) ?: return null
        try {
            repeat(plan.levelX - fine) { level = halve(level, horizontal = true) ?: return null }
            repeat(plan.levelY - fine) { level = halve(level, horizontal = false) ?: return null }
            return resample(level, plan, block, region.rect, background)
        } finally {
            level.recycle()
        }
    }

    /** [bitmap]의 한 축을 반으로 줄이고, 앞 비트맵은 돌려준다. */
    private fun halve(bitmap: Bitmap, horizontal: Boolean): Bitmap? {
        val halved = PreviewRenderer.halveAxis(bitmap, horizontal)
        bitmap.recycle()
        return halved
    }

    /**
     * 반씩 줄인 원본 [level](문서 [sourceRect]를 그린 것)을 바이큐빅으로 보간해 대상 묶음 [block]을 그리고 GPU에서 읽어 온다.
     * 대상 픽셀 가운데 (x + 0.5)는 원본 x / 배율이고, 반씩 줄인 단계에서는 그것을 2^단계로 나눈 자리다.
     * [background]가 있으면 먼저 칠하고 그 위에 얹는다. 없으면 보간한 값을 그대로 쓴다.
     */
    private fun resample(level: Bitmap, plan: ResizePlan, block: PixelRect, sourceRect: PixelRect, background: Int?): Bitmap? {
        val perPixelX = 1.0 / (plan.scaleX * (1 shl plan.levelX))
        val perPixelY = 1.0 / (plan.scaleY * (1 shl plan.levelY))
        val shader = RuntimeShader(BICUBIC).apply {
            setInputShader(
                "image",
                BitmapShader(level, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_NEAREST
                }
            )
            setFloatUniform("scale", perPixelX.toFloat(), perPixelY.toFloat())
            setFloatUniform(
                "offset",
                (block.left * perPixelX - (sourceRect.left shr plan.levelX)).toFloat(),
                (block.top * perPixelY - (sourceRect.top shr plan.levelY)).toFloat()
            )
        }
        val picture = Picture()
        val canvas = picture.beginRecording(block.width, block.height)
        if (background != null) canvas.drawColor(background)
        canvas.drawRect(0f, 0f, block.width.toFloat(), block.height.toFloat(), Paint().apply {
            this.shader = shader
            blendMode = if (background != null) BlendMode.SRC_OVER else BlendMode.SRC
        })
        picture.endRecording()
        // GPU 전용 내용이라 하드웨어 비트맵으로 그린 뒤 직접 읽어 온다. 둘 다 실패하면 null이다(SDK 소스로 확인).
        val drawn = PreviewRenderer.drawOnGpu(picture, block.width, block.height) ?: return null
        try {
            return drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        }
    }

    /** 대상 묶음 비트맵 [pixels]를 타일로 나눠 [into]에 넣는다. 끝 타일은 캔버스 안쪽 크기만 담는다. */
    private fun splitIntoTiles(pixels: Bitmap, block: PixelRect, grid: TileGrid, into: MutableMap<TileKey, Tile?>) {
        for (row in block.top / TILE_SIZE until (block.bottom + TILE_SIZE - 1) / TILE_SIZE) {
            for (column in block.left / TILE_SIZE until (block.right + TILE_SIZE - 1) / TILE_SIZE) {
                val key = TileKey(column, row)
                val bounds = grid.bounds(key)
                val picture = Picture()
                val canvas = picture.beginRecording(bounds.width, bounds.height)
                canvas.drawBitmap(pixels, (block.left - bounds.left).toFloat(), (block.top - bounds.top).toFloat(), CopyPaint)
                picture.endRecording()
                // 소프트웨어로 그려 바꿀 수 없는 비트맵 하나가 나온다(값은 그대로 베낀다)
                into[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)))
            }
        }
    }

    /** 그대로 베끼는 붓(필터·안티에일리어싱 끔). `Paint()`도 비트맵 필터가 켜져 있어 직접 끈다. */
    private val CopyPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
        blendMode = BlendMode.SRC
    }

    /**
     * 바이큐빅(Catmull-Rom) 보간 셰이더(AGSL). 이웃 4×4 픽셀을 가중 평균한다.
     * Catmull-Rom은 픽셀 가운데에서는 그 픽셀 값 그대로라, 남은 배율이 1이면(정확히 반씩 줄인 경우) 값이 바뀌지 않는다.
     * 알파를 미리 곱한 값이라, 가중치가 음수인 곳에서 생기는 넘침을 0 ≤ 색 ≤ 알파 ≤ 1로 잘라 낸다.
     */
    private const val BICUBIC = """
        uniform shader image;
        uniform float2 scale;
        uniform float2 offset;

        float weight(float x) {
            x = abs(x);
            if (x < 1.0) {
                return (1.5 * x - 2.5) * x * x + 1.0;
            }
            if (x < 2.0) {
                return ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0;
            }
            return 0.0;
        }

        half4 main(float2 position) {
            float2 source = offset + position * scale;
            float2 base = floor(source - 0.5);
            float4 sum = float4(0.0);
            float total = 0.0;
            for (int j = -1; j <= 2; j++) {
                for (int i = -1; i <= 2; i++) {
                    float2 center = base + float2(float(i), float(j)) + 0.5;
                    float w = weight(source.x - center.x) * weight(source.y - center.y);
                    sum += float4(image.eval(center)) * w;
                    total += w;
                }
            }
            float4 color = sum / total;
            color.a = clamp(color.a, 0.0, 1.0);
            color.rgb = clamp(color.rgb, float3(0.0), float3(color.a));
            return half4(color);
        }
    """
}

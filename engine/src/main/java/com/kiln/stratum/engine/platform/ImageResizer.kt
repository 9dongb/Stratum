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
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileKeysIn
import com.kiln.stratum.engine.raster.tileSquare
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.transform.MaskTransform
import com.kiln.stratum.engine.transform.ResizePlan
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * 문서 전체(모든 레이어)의 크기를 [width]×[height]로 바꾼다. 픽셀을 다시 계산하는 일이라 GPU로 한다([ImageResizer]).
 * 레이어 마스크도 픽셀과 같은 방법으로 바꾼다([MaskResizer], 결정 기록 '피부 보정'의 1c).
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
        // 피부 보정의 얼굴 자리는 같은 비율로 옮기고 길이는 두 배율의 가운데만큼(결정 기록 '피부 보정'의 3단계)
        val scaleX = width / document.width.toFloat()
        val scaleY = height / document.height.toFloat()
        val scaled = { x: Float, y: Float -> x * scaleX to y * scaleY }
        // 함께 쓰던 마스크는 한 번 바꿔 계속 함께 쓴다(피부 보정의 색 보정·피부 정돈, 결정 기록 '피부 보정'의 2c).
        // 캔버스 밖 픽셀이 있는 레이어의 마스크는 그 픽셀 자리까지 함께 바꾸므로 따로 바꾼다(결정 기록 '캔버스 밖 픽셀').
        // 조정 레이어의 바깥이 보이는 마스크는 끝이 없어(캔버스 밖 빈 곳이 보임) 따로 바꾼다(검토에서 고침).
        val masks = IdentityHashMap<Selection, Selection>()
        val endlessMasks = IdentityHashMap<Selection, Selection>()
        val grid = TileGrid(document.width, document.height)
        fun resized(mask: Selection?, pixels: Raster? = null, adjustment: Boolean = false) = mask?.let {
            when {
                adjustment && MaskTransform.revealsOutside(it, source) -> endlessMasks.getOrPut(it) { MaskResizer.resize(it, plan, checkCanceled, endless = true) }
                pixels != null && pixels.hasOffCanvas(grid) -> MaskResizer.resize(it, plan, checkCanceled, pixels)
                else -> masks.getOrPut(it) { MaskResizer.resize(it, plan, checkCanceled) }
            }
        }
        val layers = document.layers.map { layer ->
            when (val content = layer.content) {
                is RasterContent -> layer.withPixels(ImageResizer.resize(content.raster, plan, checkCanceled)).copy(mask = resized(layer.mask, content.raster))
                // 조정 레이어는 마스크만 바꾼다(결정 기록 '피부 보정'의 1e)
                is AdjustmentContent -> layer.copy(mask = resized(layer.mask, adjustment = true)).withSkinMapped((scaleX + scaleY) / 2f, scaled)
                // 그룹은 그리는 것이 없다(1g)
                GroupContent -> layer.withSkinMapped((scaleX + scaleY) / 2f, scaled)
            }
        }
        // 선택 영역은 크기를 바꾸지 않고 푼다(결정 기록 '선택 도구'). 피부 정돈도 함께 바뀌어 낡지 않는다(결정 기록 '피부 보정'의 4단계).
        return SkinRecord.rebased(document, document.copy(width = width, height = height, layers = layers, selection = null))
    }

    /**
     * 빈 레이어와 한 가지 색 레이어는 새 메모리를 거의 쓰지 않고, 나머지는 레이어마다 새 크기만큼 쓴다(캔버스 밖 픽셀이 있으면 원본 타일을 바꾼 곳에
     * 걸친 대상 타일만큼, 결정 기록 '캔버스 밖 픽셀'). 경계가 있는 마스크는 픽셀 하나에 1바이트를 더한다.
     */
    override fun estimateNewBytes(document: Document): Long {
        val source = PixelSize(document.width, document.height)
        val target = PixelSize(width, height)
        val grid = TileGrid(document.width, document.height)
        val layerBytes = width.toLong() * height * Int.SIZE_BYTES
        var pixels = 0L
        for (layer in document.layers) {
            val raster = (layer.content as? RasterContent)?.raster ?: continue
            if (raster.tiles.isEmpty() || ImageResizer.solidColor(raster, source) != null) continue
            pixels += if (raster.hasOffCanvas(grid)) {
                ImageResizer.wholeTargetTiles(raster, ResizePlan(source, target)).toLong() * TILE_PIXEL_COUNT * Int.SIZE_BYTES
            } else {
                layerBytes
            }
        }
        val masks = document.layers.count { layer -> layer.mask?.tiles?.values?.any { !it.isFull } == true } * width.toLong() * height
        return pixels + masks
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
        solidColor(raster, plan.source)?.let { color ->
            // 한 가지 색 캔버스: 꽉 찬 타일이 있으면 그것을, 없으면(캔버스가 타일 하나보다 작음) 새로 만들어 새 크기의 자리마다 놓는다
            val full = raster.tiles.values.firstOrNull { it.width == TILE_SIZE && it.height == TILE_SIZE } ?: BlankCanvas.solidTile(color)
            try {
                return Raster.filled(TileGrid(plan.target.width, plan.target.height), full ?: throw NotEnoughMemoryException())
            } catch (e: OutOfMemoryError) {
                throw NotEnoughMemoryException(e)
            }
        }
        // 캔버스 밖 픽셀이 있으면 레이어 내용 전체를 바꾼다(결정 기록 '캔버스 밖 픽셀')
        if (raster.hasOffCanvas(TileGrid(plan.source.width, plan.source.height))) return resizeWhole(raster, plan, checkCanceled)
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

    /**
     * 캔버스 밖 픽셀이 있는 레이어의 크기 변경(결정 기록 '캔버스 밖 픽셀'): 레이어 내용과 캔버스를 담는 사각형 전체를 같은 배율로 바꾼다.
     * 대상 묶음은 캔버스 안과 같은 격자이고, 레이어 공간으로 그려(캔버스 밖 원본도 읽음) 캔버스 끝에 이음매가 없다.
     * 대상 타일은 바꾼 내용이 닿는 곳까지만 담고(캔버스 안 자리는 캔버스 안쪽보다 작지 않음), 그 자리로 오는 원본에 타일이 없으면 비워 둔다.
     */
    private fun resizeWhole(raster: Raster, plan: ResizePlan, checkCanceled: () -> Unit): Raster {
        val extent = checkNotNull(raster.extent())
        val within = extent.union(PixelRect.of(plan.source))
        val area = plan.targetArea(within)
        // 바이큐빅은 내용 끝 너머로 대상 픽셀 한둘(키우면 배율만큼)까지 번지므로 그만큼 더 담는다(그리는 사각형 안에서만)
        val spill = ceil(maxOf(plan.scaleX, plan.scaleY)).toInt() + 1
        val content = checkNotNull(plan.targetArea(extent).expanded(spill).intersect(area))
        val single = Document.create(plan.source.width, plan.source.height, "", raster)
        val targetGrid = TileGrid(plan.target.width, plan.target.height)
        val tiles = HashMap<TileKey, Tile?>()
        try {
            for (block in plan.blocks(area)) {
                checkCanceled()
                val sourceRect = plan.sourceRect(block, within)
                if (!raster.hasTilesIn(sourceRect)) continue
                val pixels = renderBlock(single, plan, block, sourceRect, within = within) ?: throw NotEnoughMemoryException()
                try {
                    for (key in tileKeysIn(block)) {
                        val square = tileSquare(key)
                        // 이 타일 자리로 오는 원본이 모두 투명(타일 없음)이면 비워 둔다
                        val covered = square.intersect(block) ?: continue
                        if (!raster.hasTilesIn(plan.sourceRect(covered, within))) continue
                        val size = wholeTileSize(key, square, content, targetGrid) ?: continue
                        tiles[key] = copyTile(pixels, block, square, size)
                    }
                } finally {
                    pixels.recycle()
                }
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /**
     * 캔버스 밖 픽셀이 있는 [raster]를 [plan]대로 바꿀 때 생길 수 있는 대상 타일 수(메모리 어림에 씀): 원본 타일마다 바꾼 사각형(바이큐빅이 번지는 만큼 넓힘)이
     * 걸친 대상 자리. 내용과 캔버스를 담는 사각형 전체로 어림하면 캔버스에서 멀리 떨어진 내용이 그 사이 빈 곳까지 세어 크기 변경을 막았다(검토에서 고침).
     */
    fun wholeTargetTiles(raster: Raster, plan: ResizePlan): Int {
        val spill = ceil(maxOf(plan.scaleX, plan.scaleY)).toInt() + 1
        val keys = HashSet<TileKey>()
        for (key in raster.tiles.keys) keys += tileKeysIn(plan.targetArea(checkNotNull(raster.tileRect(key))).expanded(spill))
        return keys.size
    }

    /** [key] 대상 타일이 담을 크기: 왼쪽 위부터 바꾼 내용([content])이 닿는 곳까지, 캔버스 안 자리는 캔버스 안쪽보다 작지 않게. 담을 것이 없으면 null. */
    private fun wholeTileSize(key: TileKey, square: PixelRect, content: PixelRect, grid: TileGrid): PixelSize? {
        val data = square.intersect(content)
        var width = if (data == null) 0 else data.right - square.left
        var height = if (data == null) 0 else data.bottom - square.top
        grid.canvasRect(key)?.let { inside ->
            width = maxOf(width, inside.width)
            height = maxOf(height, inside.height)
        }
        return if (width > 0 && height > 0) PixelSize(width, height) else null
    }

    /**
     * 캔버스([size])의 모든 자리를 같은 한 가지 색 타일이 캔버스 안쪽 크기로 덮으면(한 가지 색 캔버스) 그 색. 크기를 바꿔도 같은 색이라 계산하지 않는다.
     * 캔버스 밖 자리나 캔버스 밖으로 더 담은 타일이 있으면 null이다(결정 기록 '캔버스 밖 픽셀').
     */
    fun solidColor(raster: Raster, size: PixelSize): Int? {
        val grid = TileGrid(size.width, size.height)
        val color = raster.tiles.values.firstOrNull()?.solidColor ?: return null
        if (raster.tiles.size != grid.columns * grid.rows) return null
        val honest = raster.tiles.all { (key, tile) ->
            tile.solidColor == color && grid.contains(key) && grid.solidShape(key) == PixelSize(tile.width, tile.height)
        }
        return if (honest) color else null
    }

    /**
     * 대상 묶음 [block]을 그린 소프트웨어 비트맵(대상 픽셀 크기). GPU가 그리지 못하면 null.
     * [document]의 보이는 레이어를 모두 합성해 그린다(크기 변경은 레이어 하나만 담은 문서, 내보내기는 문서 전체).
     * [background]가 있으면 그 색 바탕 위에 얹는다(JPEG로 내보낼 때의 흰 바탕). [within]이 있으면 레이어 공간으로 그린다(캔버스 밖 픽셀이 있는 레이어의 크기 변경).
     */
    internal fun renderBlock(
        document: Document,
        plan: ResizePlan,
        block: PixelRect,
        sourceRect: PixelRect,
        background: Int? = null,
        within: PixelRect? = null
    ): Bitmap? {
        val fine = minOf(plan.levelX, plan.levelY)
        val coarse = maxOf(plan.levelX, plan.levelY)
        // 더 거친 단계의 격자에 맞춰 두어야, 한 축만 더 반으로 줄일 때도 두 픽셀 짝이 문서 전체와 같다
        val snapped = if (within == null) {
            checkNotNull(PreviewLevels.region(plan.source, coarse, sourceRect)) { "Empty source for $block" }.rect
        } else {
            // 레이어 공간: 오른쪽·아래는 레이어 내용과 캔버스를 담는 사각형([within]) 끝에서 자른다(그 끝은 캔버스 끝처럼 가장자리를 늘인다)
            val aligned = PreviewLevels.area(coarse, sourceRect).rect
            PixelRect(aligned.left, aligned.top, minOf(aligned.right, within.right), minOf(aligned.bottom, within.bottom))
        }
        val region = PreviewRegion(fine, snapped)
        var level = (if (within == null) PreviewRenderer.renderRegion(document, region) else PreviewRenderer.renderLayerSpace(document, region)) ?: return null
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
        for (key in tileKeysIn(block)) {
            val inside = grid.bounds(key)
            into[key] = copyTile(pixels, block, tileSquare(key), PixelSize(inside.width, inside.height))
        }
    }

    /** 대상 묶음 [block]을 그린 [pixels]에서 [square] 자리의 [size] 타일을 베껴 낸다(값 그대로) */
    private fun copyTile(pixels: Bitmap, block: PixelRect, square: PixelRect, size: PixelSize): Tile {
        val picture = Picture()
        val canvas = picture.beginRecording(size.width, size.height)
        canvas.drawBitmap(pixels, (block.left - square.left).toFloat(), (block.top - square.top).toFloat(), CopyPaint)
        picture.endRecording()
        // 소프트웨어로 그려 바꿀 수 없는 비트맵 하나가 나온다(값은 그대로 베낀다)
        return Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, size.width, size.height, Bitmap.Config.ARGB_8888)))
    }

    /** 그대로 베끼는 붓(필터·안티에일리어싱 끔). `Paint()`도 비트맵 필터가 켜져 있어 직접 끈다. */
    private val CopyPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
        blendMode = BlendMode.SRC
    }

    /**
     * [BICUBIC]의 읽기 부분: 입력 `image`, 그림 픽셀마다 읽을 자리 `offset + position × scale`, 함수 `bicubic`.
     * `bicubicAt`은 입력 그림의 자리(픽셀 가운데가 i + 0.5)를 바로 받는다.
     * AI 제거의 잡음 섞인 키우기와 질감 향상(`Inpainter`, `TextureTransfer`)도 같은 읽기를 쓴다. Kotlin으로 같은 계산을 하는 곳: `InpaintNoise.upscaledLuma`.
     */
    internal const val BICUBIC_SAMPLE = """
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

        float4 bicubicAt(float2 source) {
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
            return color;
        }

        float4 bicubic(float2 position) {
            return bicubicAt(offset + position * scale);
        }
    """

    /**
     * 바이큐빅(Catmull-Rom) 보간 셰이더(AGSL). 이웃 4×4 픽셀을 가중 평균한다.
     * Catmull-Rom은 픽셀 가운데에서는 그 픽셀 값 그대로라, 남은 배율이 1이면(정확히 반씩 줄인 경우) 값이 바뀌지 않는다.
     * 알파를 미리 곱한 값이라, 가중치가 음수인 곳에서 생기는 넘침을 0 ≤ 색 ≤ 알파 ≤ 1로 잘라 낸다.
     * AI 제거도 모델이 채운 그림을 원본 해상도로 키울 때 쓴다(결정 기록 'AI 제거·AI 선택').
     */
    internal const val BICUBIC = BICUBIC_SAMPLE + """
        half4 main(float2 position) {
            return half4(bicubic(position));
        }
    """
}

package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.inpaint.CropWindow
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintMask
import com.kiln.stratum.engine.inpaint.InpaintModel
import com.kiln.stratum.engine.inpaint.InpaintNoise
import com.kiln.stratum.engine.inpaint.InpaintPlan
import com.kiln.stratum.engine.inpaint.InpaintTensor
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.retouch.HoleFill
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * AI 제거의 처리 순서(결정 기록 'AI 제거·AI 선택'). [InpaintRegion]과 게인맵 채우기([GainmapEdits.fill])가 쓴다.
 *
 * 1. 계획: 채울 곳을 긴 변 1024칸 이하로 줄여 그리고(안드로이드 그리기), 조금이라도 덮인 칸을 구멍으로 보고,
 *    칠한 경우 고리 안쪽까지 메워 덩어리로 나눈다([InpaintMask]). 덩어리마다 창을 정한다([InpaintPlan.windows]).
 * 2. 창마다 차례로(앞 창의 결과를 다음 창이 읽는다): 창을 모델 크기로 읽고(줄인 단계는 GPU 2×2 평균, 남은 맞추기는 쌍선형),
 *    구멍을 그 크기로 다시 그려 넓힌 뒤, 모델(또는 매끈한 잇기)로 채우고 둘레 밝기·색에 맞춘 그림을 만든다([InpaintTensor]).
 * 3. 채울 그림을 원본 해상도로 키워(GPU 바이큐빅, 1024px 묶음마다 한 번) 쓰기 몫 ∩ 선택 영역만큼 타일에 바꿔 넣는다(소프트웨어).
 *    AI 모델로 채운 곳에는 둘레만큼 모자란 고운 잡음을 키우면서 더한다([InpaintNoise], 원본 해상도의 작은 조각 몇 개에서 잼).
 *
 * 몫이 0인 픽셀과 닿지 않은 타일은 그대로다. Kotlin 반복은 계획(1024칸 이하)과 모델 크기(512²)에서만 한다.
 * 보정은 걸지 않은 레이어의 원래 픽셀을 읽는다(리터칭과 같다). P3 문서도 값을 바꾸지 않고 그대로 넣는다.
 */
internal object Inpainter {

    /** 구멍을 채울 색을 만드는 곳 */
    sealed interface FillSource {
        /** 한 번에 채우는 그림의 한 변(px) */
        val size: Int

        /** AI 모델 */
        class Model(val model: InpaintModel) : FillSource {
            override val size: Int
                get() = model.size
        }

        /** 둘레 값을 매끈하게 이음(pull-push). 게인맵처럼 매끈한 그림에 쓴다. */
        data object Smooth : FillSource {
            override val size: Int
                get() = HoleFill.MAX_EDGE
        }
    }

    /** 잠깐 쓰는 메모리의 어림(바이트): 창 하나의 배열(약 25MB)과 묶음 그림 몇 장 */
    const val WORK_BYTES = 64L * 1024 * 1024

    /** 채울 그림을 한 번에 키우는 묶음의 한 변(타일 수). 1024px이고, 묶음마다 GPU를 한 번만 쓴다. */
    private const val BLOCK_TILES = 4

    /** 아주 작은 자국도 한 칸 가까이 덮게 그리는 최소 반지름(그림 px) */
    private const val MIN_DAB_RADIUS = 0.75f

    /** 고른 곳을 채울 때 모델이 채울 곳을 고른 곳보다 넓히는 폭(모델 px). 부드러운 경계까지 다 담는다. */
    private const val SELECTION_DILATION = 1f

    /**
     * [ImageResizer.BICUBIC]에 고운 잡음을 더한 셰이더(AGSL, 결정 기록의 '10단계 잡음 맞추기').
     * 잡음은 문서 픽셀 자리(`origin + position`의 정수 자리)로 정해지는 표준 정규 값(Box-Muller)에 `noise`(밝기 표준편차, 0..1 눈금)를 곱한 것이고,
     * 알파를 곱한 R·G·B에 같이 더한 뒤 0 ≤ 색 ≤ 알파로 자른다. 해시는 사인을 쓰지 않는 Dave Hoskins의 hash22다(큰 좌표에서도 고르다).
     */
    private const val NOISY_BICUBIC = ImageResizer.BICUBIC_SAMPLE + """
        uniform float noise;
        uniform float2 origin;

        float2 hash2(float2 p) {
            float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.xx + p3.yz) * p3.zy);
        }

        half4 main(float2 position) {
            float4 color = bicubic(position);
            float2 u = hash2(floor(origin + position));
            float gauss = sqrt(-2.0 * log(max(u.x, 1.0e-7))) * cos(6.2831853 * u.y);
            color.rgb = clamp(color.rgb + gauss * noise * color.a, float3(0.0), float3(color.a));
            return half4(color);
        }
    """

    /** [area]가 닿는 문서 범위(문서 안). 닿지 않으면 null */
    fun bounds(area: InpaintArea, selection: Selection?, size: PixelSize): PixelRect? = when (area) {
        is InpaintArea.Painted -> StrokeDabs.bounds(area.dabs, area.radius, size)
        InpaintArea.Selected -> selection?.tileBounds(size)
        is InpaintArea.Masked -> maskBounds(area, size)
    }

    /** 물체 모양에서 덮인 칸들의 범위(문서 안). 1024칸 이하 마스크라 Kotlin으로 돈다. 덮인 칸이 없으면 null */
    private fun maskBounds(area: InpaintArea.Masked, size: PixelSize): PixelRect? {
        var minX = area.width
        var minY = area.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until area.height) {
            for (x in 0 until area.width) {
                if (area.alphas[y * area.width + x].toInt() == 0) continue
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        if (maxX < 0) return null
        return PixelRect(
            floor(minX * area.cellX).toInt(),
            floor(minY * area.cellY).toInt(),
            ceil((maxX + 1) * area.cellX).toInt(),
            ceil((maxY + 1) * area.cellY).toInt()
        ).intersect(PixelRect.of(size))
    }

    /** [raster]의 [area]를 [source]로 채운 새 래스터. 바뀐 것이 없으면 [raster]를 그대로 돌려준다. */
    fun paint(
        raster: Raster,
        size: PixelSize,
        area: InpaintArea,
        selection: Selection?,
        source: FillSource,
        checkCanceled: () -> Unit
    ): Raster {
        val bounds = bounds(area, selection, size) ?: return raster
        val level = InpaintPlan.planLevel(bounds.width, bounds.height)
        val cell = 1 shl level
        val left = bounds.left / cell * cell
        val top = bounds.top / cell * cell
        val width = ceilDiv(bounds.right - left, cell)
        val height = ceilDiv(bounds.bottom - top, cell)
        val drawn = InpaintMask.holes(coverage(area, selection, left.toFloat(), top.toFloat(), 1f / cell, width, height))
        val holes = if (area is InpaintArea.Painted) InpaintMask.fillEnclosed(drawn, width, height) else drawn
        val regions = InpaintMask.group(InpaintMask.components(holes, width, height), ceilDiv(InpaintPlan.MIN_MARGIN, cell))
        if (regions.isEmpty()) return raster
        // 고리 안쪽(칠하지 않았지만 메운 칸). 창마다 구멍을 다시 그릴 때 함께 넣는다.
        val interior = Interior.of(BooleanArray(holes.size) { holes[it] && !drawn[it] }, width, height, left, top, cell)
        try {
            var current = raster
            for (region in regions) {
                val hole = PixelRect(
                    left + region.bounds.left * cell,
                    top + region.bounds.top * cell,
                    min(left + region.bounds.right * cell, size.width),
                    min(top + region.bounds.bottom * cell, size.height)
                )
                val holeArea = region.cells.toLong() * cell * cell
                for (window in InpaintPlan.windows(hole, holeArea, size, source.size)) {
                    checkCanceled()
                    current = paintWindow(current, size, window, area, selection, interior, source, checkCanceled)
                }
            }
            return current
        } finally {
            interior?.recycle()
        }
    }

    /** 창 하나를 채운 새 래스터 */
    private fun paintWindow(
        raster: Raster,
        size: PixelSize,
        window: CropWindow,
        area: InpaintArea,
        selection: Selection?,
        interior: Interior?,
        source: FillSource,
        checkCanceled: () -> Unit
    ): Raster {
        val modelSize = source.size
        val plane = modelSize * modelSize
        val scale = modelSize.toFloat() / window.size
        val left = window.left.toFloat()
        val top = window.top.toFloat()

        // 구멍: 창 배율로 다시 그린 자국(또는 고른 곳)과 고리 안쪽
        val drawn = InpaintMask.holes(coverage(area, selection, left, top, scale, modelSize, modelSize))
        val hole = if (area is InpaintArea.Painted) {
            interior?.addTo(drawn, left, top, scale, modelSize)
            InpaintMask.fillEnclosed(drawn, modelSize, modelSize)
        } else {
            drawn
        }
        val distance = InpaintMask.distanceToHole(hole, modelSize, modelSize)
        // 칠한 자국·물체 모양은 넓혀 가장자리의 번진 색까지 채우고, 고른 곳은 고른 모양 그대로(1 모델 px만) 채운다
        val radius = if (area is InpaintArea.Selected) SELECTION_DILATION else InpaintPlan.dilation(window.scale(modelSize))
        val chosen = selection?.let { alphaBytes(SelectionPreview.maskAt(it, left, top, scale, modelSize, modelSize)) }
        // 모델이 채울 곳: 넓힌 구멍. 선택 영역이 있으면 그 안만 채우고 밖은 모델에게 보여 준다.
        val modelHole = BooleanArray(plane) { distance[it] <= radius && (chosen == null || chosen[it].toInt() != 0) }
        if (modelHole.none { it }) return raster
        val holeDistance = InpaintMask.distanceToHole(modelHole, modelSize, modelSize)
        checkCanceled()

        val rgba = readWindow(raster, size, window, modelSize, checkCanceled) ?: return raster
        val straight = InpaintTensor.unpremultiply(rgba)
        val filled = when (source) {
            is FillSource.Model -> {
                val known = InpaintTensor.known(straight, holeDistance, 0f)
                InpaintTensor.output(source.model.fill(InpaintTensor.input(straight, known, modelSize)), modelSize)
            }
            FillSource.Smooth -> smooth(straight, holeDistance, modelSize)
        }
        checkCanceled()
        val fill = InpaintTensor.fillImage(rgba, straight, holeDistance, 0f, filled, modelSize)
        // 쓰기 몫: 칠했거나 물체 모양이면 넓힌 띠 안에서 0으로 줄고, 고른 곳이면 넓힌 곳 전체다(타일에서 선택 알파로 다시 자른다)
        val weights = if (area is InpaintArea.Selected) FloatArray(plane) { 1f } else InpaintTensor.writeWeights(distance, radius)
        for (p in 0 until plane) if (!modelHole[p]) weights[p] = 0f
        // 고운 잡음: AI가 채운 곳만(게인맵처럼 매끈한 그림은 더하지 않는다)
        val noise = if (source is FillSource.Model) {
            noiseLevel(raster, size, window, fill, weights, holeDistance, straight, modelSize, checkCanceled)
        } else {
            0f
        }
        checkCanceled()
        return composite(raster, size, window, fill, weights, selection, modelSize, noise, checkCanceled)
    }

    /**
     * 채운 곳에 더할 밝기 잡음의 세기(0..1 눈금, 결정 기록의 '10단계 잡음 맞추기'). 원본 해상도에서 넓힌 구멍 둘레의 원래 픽셀과
     * 키운 채울 그림의 잡음을 [InpaintNoise]로 재어 모자란 만큼이다. 어느 쪽이든 잴 픽셀이 모자라면 0이다.
     * 조각([InpaintNoise.PATCH]px)은 둘레 띠와 몫이 1인 곳에서 [InpaintNoise.PATCHES]개씩 고른다.
     */
    private fun noiseLevel(
        raster: Raster,
        size: PixelSize,
        window: CropWindow,
        fill: FloatArray,
        weights: FloatArray,
        holeDistance: FloatArray,
        straight: InpaintTensor.Straight,
        modelSize: Int,
        checkCanceled: () -> Unit
    ): Float {
        val scale = modelSize.toFloat() / window.size
        // 문서 픽셀 가운데가 놓이는 모델 칸
        fun modelIndex(x: Int, y: Int): Int {
            val mx = floor((x + 0.5f - window.left) * scale).toInt().coerceIn(0, modelSize - 1)
            val my = floor((y + 0.5f - window.top) * scale).toInt().coerceIn(0, modelSize - 1)
            return my * modelSize + mx
        }
        // 모델 칸 가운데를 가운데로 하는 조각(문서 안으로 자름)
        fun patchAt(p: Int): PixelRect? {
            val centerX = window.left + ((p % modelSize) + 0.5f) / scale
            val centerY = window.top + ((p / modelSize) + 0.5f) / scale
            val half = InpaintNoise.PATCH / 2
            val x = centerX.toInt() - half
            val y = centerY.toInt() - half
            return PixelRect(x, y, x + InpaintNoise.PATCH, y + InpaintNoise.PATCH).intersect(PixelRect.of(size))
        }

        val ring = ArrayList<Float>()
        val ringCells = (0 until modelSize * modelSize).filter {
            holeDistance[it] >= InpaintNoise.EDGE_GAP && holeDistance[it] <= InpaintNoise.RING && straight.alpha[it] >= 255f
        }.toIntArray()
        for (p in InpaintNoise.pick(ringCells, InpaintNoise.PATCHES)) {
            checkCanceled()
            val rect = patchAt(p) ?: continue
            val view = LevelView.at(raster, size, rect, 0, checkCanceled) ?: continue
            try {
                val bytes = view.bytes
                val luma = FloatArray(view.width * view.height)
                val valid = BooleanArray(view.width * view.height)
                for (y in 0 until view.height) {
                    for (x in 0 until view.width) {
                        val b = view.byteIndex(x, y)
                        val alpha = bytes[b + 3].toInt() and 0xFF
                        val i = y * view.width + x
                        luma[i] = InpaintNoise.luma(
                            (bytes[b].toInt() and 0xFF).toFloat(),
                            (bytes[b + 1].toInt() and 0xFF).toFloat(),
                            (bytes[b + 2].toInt() and 0xFF).toFloat(),
                            alpha / 255f
                        )
                        valid[i] = alpha == 255 && holeDistance[modelIndex(view.left + x, view.top + y)] >= InpaintNoise.EDGE_GAP
                    }
                }
                InpaintNoise.collect(luma, valid, view.width, view.height, ring)
            } finally {
                view.recycle()
            }
        }
        val known = InpaintNoise.sigma(ring) ?: return 0f

        val inside = ArrayList<Float>()
        val fillCells = (0 until modelSize * modelSize).filter { weights[it] >= 1f }.toIntArray()
        for (p in InpaintNoise.pick(fillCells, InpaintNoise.PATCHES)) {
            checkCanceled()
            val rect = patchAt(p) ?: continue
            val opaque = BooleanArray(rect.width * rect.height)
            val luma = InpaintNoise.upscaledLuma(fill, modelSize, window.left, window.top, scale, rect.left, rect.top, rect.width, rect.height, opaque)
            val valid = BooleanArray(opaque.size) { opaque[it] && weights[modelIndex(rect.left + it % rect.width, rect.top + it / rect.width)] >= 1f }
            InpaintNoise.collect(luma, valid, rect.width, rect.height, inside)
        }
        val filled = InpaintNoise.sigma(inside) ?: return 0f
        return InpaintNoise.added(known, filled) / 255f
    }

    /** 모델 없이 둘레 값을 매끈하게 이어 채운 색(0..255, 픽셀마다 R·G·B) */
    private fun smooth(straight: InpaintTensor.Straight, holeDistance: FloatArray, size: Int): FloatArray {
        val plane = size * size
        val values = FloatArray(plane * 3) { straight.rgb[it] / 255f }
        val weights = FloatArray(plane) { if (holeDistance[it] > 0f && straight.alpha[it] >= InpaintPlan.ALPHA_KNOWN) 1f else 0f }
        val filled = HoleFill.fill(values, weights, size, size, 3)
        return FloatArray(plane * 3) { (filled[it] * 255f).coerceIn(0f, 255f) }
    }

    /**
     * 창을 모델 크기 그림으로 읽은 바이트(알파를 곱한 RGBA). 창 단계로는 [LevelView]가 읽고(줄이면 GPU),
     * 남은 맞추기(1~2배, 작은 문서는 키움)는 쌍선형 그리기다. 창이 문서 끝을 넘은 곳은 끝 픽셀을 늘인다.
     */
    private fun readWindow(raster: Raster, size: PixelSize, window: CropWindow, modelSize: Int, checkCanceled: () -> Unit): ByteArray? {
        val view = LevelView.at(raster, size, window.bounds, window.level, checkCanceled) ?: return null
        try {
            val image = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
            try {
                val factor = modelSize.toFloat() / window.levelSize
                // 그림 픽셀 = (view 그림 픽셀 - 쓸 곳 시작) × factor
                val matrix = Matrix().apply {
                    setScale(factor, factor)
                    preTranslate(-view.offsetX.toFloat(), -view.offsetY.toFloat())
                }
                val shader = BitmapShader(view.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.filterMode = BitmapShader.FILTER_MODE_LINEAR
                shader.setLocalMatrix(matrix)
                val paint = Paint()
                paint.shader = shader
                paint.blendMode = BlendMode.SRC
                Canvas(image).drawRect(0f, 0f, modelSize.toFloat(), modelSize.toFloat(), paint)
                val buffer = ByteBuffer.allocate(image.rowBytes * image.height)
                image.copyPixelsToBuffer(buffer)
                return buffer.array()
            } finally {
                image.recycle()
            }
        } finally {
            view.recycle()
        }
    }

    /**
     * 채울 그림 [fill](알파를 곱한 RGBA 0..1, 모델 크기)을 원본 해상도로 키워, 몫 [weights] ∩ 선택 영역만큼 타일에 바꿔 넣는다.
     * 결과 = 원래 × (1 - 몫) + 채울 색 × 몫(효과 도구의 '바꾸기'와 같다). 몫이 0인 픽셀은 값이 그대로다.
     */
    private fun composite(
        raster: Raster,
        size: PixelSize,
        window: CropWindow,
        fill: FloatArray,
        weights: FloatArray,
        selection: Selection?,
        modelSize: Int,
        noise: Float,
        checkCanceled: () -> Unit
    ): Raster {
        val scale = modelSize.toFloat() / window.size
        // 쓸 범위: 몫이 있는 칸의 범위를 문서로 바꾸고, 쌍선형으로 번지는 만큼 넓힌다
        var minX = modelSize
        var minY = modelSize
        var maxX = -1
        var maxY = -1
        for (p in weights.indices) {
            if (weights[p] <= 0f) continue
            val x = p % modelSize
            val y = p / modelSize
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        if (maxX < 0) return raster
        val reach = PixelRect(
            floor(window.left + (minX - 1) / scale).toInt().coerceAtLeast(0),
            floor(window.top + (minY - 1) / scale).toInt().coerceAtLeast(0),
            ceil(window.left + (maxX + 2) / scale).toInt().coerceIn(1, size.width),
            ceil(window.top + (maxY + 2) / scale).toInt().coerceIn(1, size.height)
        ).intersect(PixelRect.of(size)) ?: return raster
        val keys = BrushTiles.keysIn(reach).filter { selection == null || it in selection.tiles }
        if (keys.isEmpty()) return raster

        val grid = TileGrid(size.width, size.height)
        val colors = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.RGBA_F16)
        val shares = alphaBitmap(weights, modelSize)
        val changes = HashMap<TileKey, Tile?>()
        val temporary = ArrayList<Bitmap>()
        try {
            colors.copyPixelsFromBuffer(ShortBuffer.wrap(ShortArray(fill.size) { Half.toHalf(fill[it]) }))
            for (block in keys.groupBy { TileKey(it.column / BLOCK_TILES, it.row / BLOCK_TILES) }.values) {
                checkCanceled()
                val area = block.map { tileRect(grid, it) }
                    .reduce { a, b -> PixelRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
                val upscaled = upscale(colors, window, scale, area, noise) ?: throw NotEnoughMemoryException()
                try {
                    for (key in block) {
                        checkCanceled()
                        changes[key] = blendTile(raster.tiles[key], tileRect(grid, key), area, upscaled, shares, window, scale, selection?.tiles?.get(key), temporary)
                    }
                } finally {
                    upscaled.recycle()
                }
            }
        } finally {
            colors.recycle()
            shares.recycle()
            for (bitmap in temporary) bitmap.recycle()
        }
        return raster.withTiles(changes)
    }

    /**
     * 채울 그림을 문서 [area] 크기로 키운 소프트웨어 그림(GPU 바이큐빅 한 번).
     * 그림 픽셀 가운데 (x + 0.5)는 채울 그림에서 ((area.left + x + 0.5 - 창 왼쪽) × [scale]) 자리다.
     * [noise](밝기 표준편차, 0..1 눈금)가 0보다 크면 문서 픽셀 자리로 정해지는 잡음을 R·G·B에 같이 더한다(묶음이 바뀌어도 이어진다).
     */
    private fun upscale(colors: Bitmap, window: CropWindow, scale: Float, area: PixelRect, noise: Float): Bitmap? {
        val image = BitmapShader(colors, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        image.filterMode = BitmapShader.FILTER_MODE_NEAREST
        val shader = RuntimeShader(if (noise > 0f) NOISY_BICUBIC else ImageResizer.BICUBIC)
        shader.setInputShader("image", image)
        shader.setFloatUniform("scale", scale, scale)
        shader.setFloatUniform("offset", (area.left - window.left) * scale, (area.top - window.top) * scale)
        if (noise > 0f) {
            shader.setFloatUniform("noise", noise)
            shader.setFloatUniform("origin", area.left.toFloat(), area.top.toFloat())
        }
        val drawn = Retouch.drawOnGpu(shader, area.width, area.height) ?: return null
        try {
            return drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        }
    }

    /** 타일 하나: 원래 타일에서 몫만큼 덜어 내고, 채울 색 × 몫을 더한다. 선택 영역이 있으면 몫을 그 알파로 자른다. */
    private fun blendTile(
        old: Tile?,
        tile: PixelRect,
        area: PixelRect,
        upscaled: Bitmap,
        shares: Bitmap,
        window: CropWindow,
        scale: Float,
        chosen: MaskTile?,
        temporary: MutableList<Bitmap>
    ): Tile {
        val width = tile.width.toFloat()
        val height = tile.height.toFloat()
        val picture = Picture()
        val canvas = picture.beginRecording(tile.width, tile.height)
        old?.let { canvas.drawBitmap(CanvasTileComposer.bitmapOf(it, temporary), 0f, 0f, CanvasTileComposer.CopyPaint) }
        // 타일 픽셀의 몫은 몫 그림의 ((타일 자리 - 창 왼쪽) × scale) 자리다(쌍선형). 창 밖으로는 가장자리 몫을 늘인다(문서 끝에 붙은 구멍).
        val shareShader = BitmapShader(shares, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shareShader.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shareShader.setLocalMatrix(Matrix().apply {
            setTranslate((window.left - tile.left).toFloat(), (window.top - tile.top).toFloat())
            preScale(1f / scale, 1f / scale)
        })
        val clip = chosen?.let { MaskTiles.bitmapOf(it, temporary) }
        val drawShares = {
            val paint = Paint()
            paint.shader = shareShader
            canvas.drawRect(0f, 0f, width, height, paint)
            if (clip != null) MaskTiles.drawAlpha(canvas, clip, BlendMode.DST_IN)
        }
        // 몫만큼 아래를 덜어 낸다
        val cut = canvas.saveLayer(0f, 0f, width, height, Paint().apply { blendMode = BlendMode.DST_OUT })
        drawShares()
        canvas.restoreToCount(cut)
        // 채울 색 × 몫을 더한다(두 몫의 합이 1을 넘지 않아 잘리지 않는다)
        val add = canvas.saveLayer(0f, 0f, width, height, Paint().apply { blendMode = BlendMode.PLUS })
        drawShares()
        val colorShader = BitmapShader(upscaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        colorShader.setLocalMatrix(Matrix().apply { setTranslate((area.left - tile.left).toFloat(), (area.top - tile.top).toFloat()) })
        val colorPaint = Paint()
        colorPaint.shader = colorShader
        colorPaint.blendMode = BlendMode.SRC_IN
        canvas.drawRect(0f, 0f, width, height, colorPaint)
        canvas.restoreToCount(add)
        picture.endRecording()
        // 브러시 타일과 같이 소프트웨어로 그리고 바꿀 수 없는 비트맵 하나로 돌려준다
        return Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, tile.width, tile.height, Bitmap.Config.ARGB_8888)))
    }

    /** [area]를 문서 ([left], [top])부터 [scale]배로 그린 덮인 정도(0..255, 줄마다 이어 붙인 [width]×[height]) */
    private fun coverage(area: InpaintArea, selection: Selection?, left: Float, top: Float, scale: Float, width: Int, height: Int): ByteArray {
        val mask = when (area) {
            is InpaintArea.Painted -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val canvas = Canvas(bitmap)
                val paint = Paint()
                paint.color = Color.WHITE
                paint.isAntiAlias = true
                val radius = max(area.radius * scale, MIN_DAB_RADIUS)
                for (dab in area.dabs) canvas.drawCircle((dab.x - left) * scale, (dab.y - top) * scale, radius, paint)
                bitmap
            }
            InpaintArea.Selected -> SelectionPreview.maskAt(checkNotNull(selection) { "Filling the selection needs one" }, left, top, scale, width, height)
            is InpaintArea.Masked -> {
                // 물체 모양 그림을 이 배율로 그린다(쌍선형, 조금이라도 덮인 칸이 구멍이 된다)
                val shape = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ALPHA_8)
                val rowBytes = shape.rowBytes
                val bytes = ByteArray(rowBytes * area.height)
                for (y in 0 until area.height) area.alphas.copyInto(bytes, y * rowBytes, y * area.width, (y + 1) * area.width)
                shape.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(-left, -top)
                canvas.scale(area.cellX, area.cellY)
                canvas.drawBitmap(shape, 0f, 0f, Paint().apply { isFilterBitmap = true })
                shape.recycle()
                bitmap
            }
        }
        return alphaBytes(mask)
    }

    /** 알파 그림의 값(줄마다 이어 붙임, 줄 끝 빈 바이트는 뺌). 그림은 돌려준다. */
    private fun alphaBytes(bitmap: Bitmap): ByteArray {
        try {
            val width = bitmap.width
            val rowBytes = bitmap.rowBytes
            val buffer = ByteBuffer.allocate(rowBytes * bitmap.height)
            bitmap.copyPixelsToBuffer(buffer)
            val raw = buffer.array()
            return if (rowBytes == width) raw else ByteArray(width * bitmap.height) { raw[(it / width) * rowBytes + it % width] }
        } finally {
            bitmap.recycle()
        }
    }

    /** 몫(0..1, [size]²)을 알파 그림으로 */
    private fun alphaBitmap(values: FloatArray, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val rowBytes = bitmap.rowBytes
        val bytes = ByteArray(rowBytes * size)
        for (y in 0 until size) {
            for (x in 0 until size) bytes[y * rowBytes + x] = (values[y * size + x] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bitmap
    }

    private fun tileRect(grid: TileGrid, key: TileKey): PixelRect =
        grid.bounds(key).let { PixelRect(it.left, it.top, it.left + it.width, it.top + it.height) }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    /**
     * 계획 단계에서 메운 고리 안쪽(문서 ([left], [top])부터 한 칸 [cell] px). 한 칸 넓혀 알파 그림으로 두고,
     * 창마다 그 배율로 그려(쌍선형) 구멍에 더한다. 칸이 거칠어도 칠한 자국과 이어지게 한 칸 넓힌다.
     */
    private class Interior(private val mask: Bitmap, private val left: Int, private val top: Int, private val cell: Int) {

        /** 창 배율로 그려 [hole](모델 크기 [size]²)에 더한다 */
        fun addTo(hole: BooleanArray, windowLeft: Float, windowTop: Float, scale: Float, size: Int) {
            val target = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(target)
            canvas.scale(scale, scale)
            canvas.translate(left - windowLeft, top - windowTop)
            canvas.scale(cell.toFloat(), cell.toFloat())
            canvas.drawBitmap(mask, 0f, 0f, Paint().apply { isFilterBitmap = true })
            val drawn = alphaBytes(target)
            for (p in hole.indices) if (drawn[p].toInt() != 0) hole[p] = true
        }

        fun recycle() = mask.recycle()

        companion object {
            /** 안쪽 칸이 없으면 null */
            fun of(cells: BooleanArray, width: Int, height: Int, left: Int, top: Int, cell: Int): Interior? {
                if (cells.none { it }) return null
                // 한 칸 넓힌다(8방향)
                val grown = BooleanArray(cells.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        if (!cells[y * width + x]) continue
                        for (dy in -1..1) {
                            val ny = y + dy
                            if (ny < 0 || ny >= height) continue
                            for (dx in -1..1) {
                                val nx = x + dx
                                if (nx in 0 until width) grown[ny * width + nx] = true
                            }
                        }
                    }
                }
                val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val rowBytes = mask.rowBytes
                val bytes = ByteArray(rowBytes * height)
                for (y in 0 until height) for (x in 0 until width) if (grown[y * width + x]) bytes[y * rowBytes + x] = 0xFF.toByte()
                mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                return Interior(mask, left, top, cell)
            }
        }
    }
}

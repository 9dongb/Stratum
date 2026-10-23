package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.kiln.stratum.engine.inpaint.CropWindow
import com.kiln.stratum.engine.inpaint.InpaintTensor
import com.kiln.stratum.engine.inpaint.InpaintTexture
import com.kiln.stratum.engine.inpaint.TextureMatch
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 질감 향상의 결 붙이기(결정 기록 'AI 제거·AI 선택'의 '질감 향상'). [InpaintTexture.plan]이 고른 짝으로, 원본 해상도에서 채운 곳에
 * 둘레의 결(원본 − 모델 크기에서 바이큐빅으로 키운 원본)을 옮겨 붙인다. [Inpainter]가 채울 그림을 키울 때 [TEXTURED_BICUBIC]으로 함께 그린다.
 *
 * GPU에 넘기는 것은 짝 표(그물 칸마다 가져올 칸과 조각 번호, 8888)와 조각 판(쓴 조각의 원본 픽셀, 8888)이다.
 * AGSL은 배열을 상수·반복 번호로만 읽을 수 있어 짝을 그림으로 넘기고, F16은 2048보다 큰 정수가 정확하지 않아 8888 채널에 나눠 담는다.
 * 조각 판은 타일을 그대로 베낀 소프트웨어 그림(안드로이드 그리기)이라 Kotlin 픽셀 반복이 없다.
 */
internal object TextureTransfer {

    /** 잠깐 쓰는 메모리의 어림(바이트): 조각 판과 GPU에 올린 사본 */
    const val WORK_BYTES = 2 * InpaintTexture.ATLAS_BYTES

    /**
     * [ImageResizer.BICUBIC]에 결을 더한 셰이더(AGSL). 그림 픽셀(문서 `origin + position`)을 덮는 조각 넷(가운데가 모델 px [InpaintTexture.STRIDE]의
     * 배수)마다 짝 표에서 가져올 칸을 읽고, 같은 자리 관계의 원본 픽셀(조각 판)에서 그 자리를 키운 값(`bicubicAt`)을 뺀 결을 cos² 창 무게로 더한 뒤
     * √Σw²로 나눈다(분산이 줄지 않게). 결은 알파를 곱해 더하고 0 ≤ 색 ≤ 알파로 자른다.
     * 가져올 픽셀과 조각 판의 자리는 [InpaintTexture.blockStart]와 같은 식이다.
     */
    val TEXTURED_BICUBIC = ImageResizer.BICUBIC_SAMPLE + """
        uniform shader table;
        uniform shader atlas;
        uniform float2 origin;
        uniform float2 corner;
        uniform float pixels;
        uniform float grid;
        uniform float block;
        uniform float columns;

        half4 main(float2 position) {
            float4 color = bicubic(position);
            float2 doc = origin + position;
            float2 model = (doc - corner) / pixels;
            float2 first = floor(model / ${InpaintTexture.STRIDE}.0) * ${InpaintTexture.STRIDE}.0;
            float3 sum = float3(0.0);
            float weights = 0.0;
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) {
                    float2 center = first + float2(float(i), float(j)) * ${InpaintTexture.STRIDE}.0;
                    float2 cell = center / ${InpaintTexture.STRIDE}.0 - 1.0;
                    if (cell.x >= 0.0 && cell.y >= 0.0 && cell.x < grid && cell.y < grid) {
                        float4 pair = float4(table.eval(cell + 0.5));
                        if (pair.a > 0.5) {
                            // 정수 나눗셈은 몫을 (값 + 0.5) / 나눌 수로 구하고 나머지는 빼서 구한다. GPU의 나눗셈은 몇 ULP 틀릴 수 있어
                            // floor(값 / 나눌 수)가 딱 나누어떨어지는 값에서 하나 작게 나올 수 있다(mod도 같은 나눗셈을 쓴다).
                            float red = floor(pair.r * 255.0 + 0.5);
                            float high = floor((red + 0.5) / 128.0);
                            float2 source = (float2(red - high * 128.0, floor(pair.g * 255.0 + 0.5)) + 1.0) * ${InpaintTexture.STRIDE}.0;
                            float slot = high * 256.0 + floor(pair.b * 255.0 + 0.5);
                            float row = floor((slot + 0.5) / columns);
                            float2 wave = cos((model - center) * (3.14159265 / ${InpaintTexture.CELL}.0));
                            float w = wave.x * wave.x * wave.y * wave.y;
                            float2 from = floor(doc + (source - center) * pixels);
                            float2 start = floor(corner + source * pixels - ${InpaintTexture.CELL / 2}.0 * pixels) - ${InpaintTexture.BLOCK_MARGIN}.0;
                            float2 at = float2(slot - row * columns, row) * block + (from - start) + 0.5;
                            float4 original = float4(atlas.eval(at));
                            float4 low = bicubicAt((from + 0.5 - corner) / pixels);
                            sum += w * (original.rgb - low.rgb);
                            weights += w * w;
                        }
                    }
                }
            }
            if (weights > 0.0) {
                color.rgb = clamp(color.rgb + sum / sqrt(weights) * color.a, float3(0.0), float3(color.a));
            }
            return half4(color);
        }
    """

    /** 셰이더에 넘길 짝 표·조각 판과 그 값. 다 쓰면 [recycle]한다. */
    class Inputs(
        private val table: Bitmap,
        private val atlas: Bitmap,
        private val window: CropWindow,
        private val modelSize: Int,
        private val grid: Int,
        private val block: Int,
        private val columns: Int
    ) {
        /** [TEXTURED_BICUBIC] 셰이더에 묶음 [area](문서)를 그릴 값을 건다 */
        fun applyTo(shader: RuntimeShader, area: PixelRect) {
            shader.setInputShader("table", nearest(table))
            shader.setInputShader("atlas", nearest(atlas))
            shader.setFloatUniform("origin", area.left.toFloat(), area.top.toFloat())
            shader.setFloatUniform("corner", window.left.toFloat(), window.top.toFloat())
            shader.setFloatUniform("pixels", (window.size.toDouble() / modelSize).toFloat())
            shader.setFloatUniform("grid", grid.toFloat())
            shader.setFloatUniform("block", block.toFloat())
            shader.setFloatUniform("columns", columns.toFloat())
        }

        fun recycle() {
            table.recycle()
            atlas.recycle()
        }

        private fun nearest(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_NEAREST
        }
    }

    /**
     * 창 하나의 짝을 찾고 짝 표·조각 판을 만든다. [straight]는 창을 모델 크기로 읽은 원래 그림(알파를 푼 값), [fill]은 채울 그림
     * (알파를 곱한 RGBA 0..1), [hole]은 넓힌 구멍, [holeDistance]는 그 구멍까지 거리다.
     * 배율이 [InpaintTexture.MIN_SCALE]보다 작거나 짝을 찾지 못하면 null.
     */
    fun prepare(
        raster: Raster,
        size: PixelSize,
        window: CropWindow,
        straight: InpaintTensor.Straight,
        fill: FloatArray,
        holeDistance: FloatArray,
        hole: BooleanArray,
        modelSize: Int,
        checkCanceled: () -> Unit
    ): Inputs? {
        val scale = window.scale(modelSize)
        if (!InpaintTexture.applies(scale)) return null
        val inside = insideRect(window, size, modelSize) ?: return null
        val match = InpaintTexture.plan(
            straight.rgb, straightRgb(fill, modelSize), straight.alpha, holeDistance, hole, modelSize, inside, InpaintTexture.maxCandidates(scale)
        ) ?: return null
        checkCanceled()
        val block = InpaintTexture.blockSize(scale)
        val columns = InpaintTexture.columns(match.sources.size)
        val atlas = atlas(raster, window, match, scale, block, columns)
        val table = try {
            table(match)
        } catch (e: Throwable) {
            // 짝 표를 만들지 못하면(메모리 등) 이미 만든 조각 판을 놓는다
            atlas.recycle()
            throw e
        }
        return Inputs(table, atlas, window, modelSize, match.grid, block, columns)
    }

    /**
     * 후보 조각이 들어갈 수 있는 모델 자리: 문서 안쪽 ([InpaintTexture.BLOCK_MARGIN] + 1)px까지. 그 안의 조각은 조각 판에 담을 픽셀
     * ([InpaintTexture.blockStart]부터 [InpaintTexture.blockSize])이 모두 문서 안이다. 자리가 없으면 null.
     */
    private fun insideRect(window: CropWindow, size: PixelSize, modelSize: Int): PixelRect? {
        val pixels = window.size.toDouble() / modelSize
        val inset = InpaintTexture.BLOCK_MARGIN + 1
        val left = max(0, ceil((inset - window.left) / pixels).toInt())
        val top = max(0, ceil((inset - window.top) / pixels).toInt())
        val right = min(modelSize, floor((size.width - inset - window.left) / pixels).toInt())
        val bottom = min(modelSize, floor((size.height - inset - window.top) / pixels).toInt())
        return if (right > left && bottom > top) PixelRect(left, top, right, bottom) else null
    }

    /** 채울 그림(알파를 곱한 RGBA 0..1)의 알파를 푼 R·G·B(0..255). 알파가 0이면 0이다. */
    private fun straightRgb(fill: FloatArray, size: Int): FloatArray {
        val plane = size * size
        val rgb = FloatArray(plane * 3)
        for (p in 0 until plane) {
            val alpha = fill[p * 4 + 3]
            if (alpha <= 0f) continue
            for (c in 0..2) rgb[p * 3 + c] = (fill[p * 4 + c] / alpha * 255f).coerceIn(0f, 255f)
        }
        return rgb
    }

    /** 짝 표: 그물 칸마다 [InpaintTexture.pack]한 색, 짝이 없으면 투명 */
    private fun table(match: TextureMatch): Bitmap {
        val grid = match.grid
        val colors = IntArray(grid * grid) { cell ->
            val slot = match.slots[cell]
            if (slot < 0) 0 else match.sources[slot].let { InpaintTexture.pack(it % grid, it / grid, slot) }
        }
        return Bitmap.createBitmap(colors, grid, grid, Bitmap.Config.ARGB_8888)
    }

    /**
     * 조각 판: 조각 번호 i의 원본 픽셀(문서 [InpaintTexture.blockStart]부터 한 변 [block])을 ((i % [columns]) × block, (i / columns) × block)에 둔다.
     * 타일을 그대로 베낀다(소프트웨어, 값 그대로).
     */
    private fun atlas(raster: Raster, window: CropWindow, match: TextureMatch, scale: Double, block: Int, columns: Int): Bitmap {
        val count = match.sources.size
        val rows = (count + columns - 1) / columns
        val width = columns * block
        val height = rows * block
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        val temporary = ArrayList<Bitmap>()
        try {
            for (slot in 0 until count) {
                val source = match.sources[slot]
                val startX = InpaintTexture.blockStart(window.left, source % match.grid, scale)
                val startY = InpaintTexture.blockStart(window.top, source / match.grid, scale)
                val atX = (slot % columns) * block
                val atY = (slot / columns) * block
                canvas.save()
                canvas.clipRect(atX.toFloat(), atY.toFloat(), (atX + block).toFloat(), (atY + block).toFloat())
                for (row in startY / TILE_SIZE..(startY + block - 1) / TILE_SIZE) {
                    for (column in startX / TILE_SIZE..(startX + block - 1) / TILE_SIZE) {
                        val tile = raster.tiles[TileKey(column, row)] ?: continue
                        val x = (atX + column * TILE_SIZE - startX).toFloat()
                        val y = (atY + row * TILE_SIZE - startY).toFloat()
                        canvas.drawBitmap(CanvasTileComposer.bitmapOf(tile, temporary), x, y, CanvasTileComposer.CopyPaint)
                    }
                }
                canvas.restore()
            }
            picture.endRecording()
            return Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }
}

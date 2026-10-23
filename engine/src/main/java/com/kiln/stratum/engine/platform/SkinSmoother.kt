package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.util.Half
import android.util.Log
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.preview.MAX_PREVIEW_LEVEL
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColorMath
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinSmoothMath
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.min

/**
 * 피부 정돈 레이어의 픽셀을 만든다(결정 기록 '피부 보정'의 4단계, 계산은 [SkinSmoothMath]). 결과 = 큰 띠 + 질감 유지 × 고운 결(OKLab).
 *
 * - 정돈 레이어 아래를 합친 그림([below]: 원본 + 잡티 제거, 그 아래 레이어들)에서 만든다. 마스크 타일 자리만 만들고 나머지는 비운다.
 * - 타일마다 가장 가까운 얼굴([SkinColorMath.weight], 타일 가운데)의 크기(길이 단위 U)로 반지름을 정한다(얼굴마다 따로, 계획 '보완할 점 6').
 * - 큰 띠의 계수는 그 얼굴이 맡은 타일 둘레를 줄인 단계로 그려 순수 Kotlin으로 구한다(작은 배열). 원본 해상도 계산(고운 결의 흐림·합치기)은
 *   1024px 묶음마다 GPU로 한다(CLAUDE.md 2장).
 * - 고운 결의 흐림은 마스크 무게 평균이다([SkinSmoothMath.maskedBlur]와 같은 계산): 알파를 곱한 색에 마스크를 곱해 흐리고, 합칠 때 흐린 알파로 나눈다.
 * - 메인 스레드가 아닌 곳에서 부른다(GPU 작업을 기다린다).
 */
internal object SkinSmoother {

    private const val TAG = "SkinSmoother"

    /** 원본 해상도로 한 번에 합치는 묶음(타일 4×4 = 1024px) */
    private const val BLOCK_TILES = 4

    /** 원본 해상도 계산에 잠깐 쓰는 메모리의 어림: 읽는 그림(둘레 포함 1536²)과 마스크를 곱한 그림·마스크·가로로 흐린 그림·결과·복사본, 줄인 단계의 배열 */
    const val WORK_BYTES = 64L * 1024 * 1024

    /**
     * [below]를 [faces]마다 정돈한 피부 정돈 레이어의 픽셀(문서 크기). [mask] 타일 자리만 만든다. [texture]는 질감 유지(0..1).
     * 메모리가 모자라거나 GPU가 그리지 못하면 [NotEnoughMemoryException]. [checkCanceled]는 묶음 사이마다 부른다.
     */
    fun build(below: Document, mask: Selection, faces: List<SkinFaceArea>, texture: Float, checkCanceled: () -> Unit = {}): Raster {
        if (faces.isEmpty() || mask.tiles.isEmpty()) return Raster.EMPTY
        val size = PixelSize(below.width, below.height)
        val grid = TileGrid(size.width, size.height)
        val inside = grid.keys().toHashSet()
        // 타일마다 가장 가까운 얼굴이 맡는다
        val byFace = HashMap<Int, MutableList<TileKey>>()
        for (key in mask.tiles.keys) {
            if (key !in inside) continue
            val bounds = grid.bounds(key)
            val x = bounds.left + bounds.width / 2f
            val y = bounds.top + bounds.height / 2f
            val face = faces.indices.maxBy { SkinColorMath.weight(faces[it], x, y) }
            byFace.getOrPut(face) { ArrayList() } += key
        }
        val tiles = HashMap<TileKey, Tile?>()
        try {
            for ((face, keys) in byFace) {
                checkCanceled()
                val started = SystemClock.elapsedRealtime()
                smoothFace(below, size, grid, faces[face], keys, mask, texture, tiles, checkCanceled)
                Log.i(TAG, "Smoothed face $face (${keys.size} tiles, unit ${faces[face].unit.toInt()} px) in ${SystemClock.elapsedRealtime() - started} ms")
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 얼굴 [face]가 맡은 [keys] 타일을 정돈해 [into]에 넣는다 */
    private fun smoothFace(
        below: Document,
        size: PixelSize,
        grid: TileGrid,
        face: SkinFaceArea,
        keys: List<TileKey>,
        mask: Selection,
        texture: Float,
        into: MutableMap<TileKey, Tile?>,
        checkCanceled: () -> Unit
    ) {
        val radius = SkinSmoothMath.boxRadius(face.unit)
        val sigma = SkinSmoothMath.fineSigma(face.unit)
        val level = SkinSmoothMath.level(radius).coerceAtMost(MAX_PREVIEW_LEVEL)
        val scale = 1 shl level
        // 맡은 타일을 덮는 사각형, 그리고 계수의 둘레(상자를 두 번 거침)
        var covered: PixelRect? = null
        for (key in keys) covered = covered?.union(tileRect(grid, key)) ?: tileRect(grid, key)
        val area = checkNotNull(covered)
        val margin = ceil(2f * radius).toInt() + 2 * scale
        val context = PreviewLevels.region(size, level, area.expanded(margin)) ?: return
        val coefficients = coefficientsOf(below, context, mask, radius / scale, checkCanceled)
        val slope = halfBitmap(coefficients, 0, context.previewWidth, context.previewHeight)
        try {
            val offset = halfBitmap(coefficients, 3, context.previewWidth, context.previewHeight)
            try {
                val space = OkLab.Space.of(below.colorSpace)
                val wanted = keys.toHashSet()
                val blocks = keys.map { TileKey(it.column / BLOCK_TILES, it.row / BLOCK_TILES) }.toSortedSet(compareBy({ it.row }, { it.column }))
                for (block in blocks) {
                    checkCanceled()
                    val left = block.column * BLOCK_TILES * TILE_SIZE
                    val top = block.row * BLOCK_TILES * TILE_SIZE
                    val rect = PixelRect(left, top, min(left + BLOCK_TILES * TILE_SIZE, size.width), min(top + BLOCK_TILES * TILE_SIZE, size.height))
                    val pixels = smoothBlock(below, size, rect, mask, sigma, slope, offset, context.rect, scale, texture, space)
                    try {
                        split(pixels, rect, grid, wanted, into)
                    } finally {
                        pixels.recycle()
                    }
                }
            } finally {
                offset.recycle()
            }
        } finally {
            slope.recycle()
        }
    }

    /** [context] 영역을 그 단계로 그려 큰 띠의 계수 여섯 배열을 구한다(줄인 단계, 순수 Kotlin) */
    private fun coefficientsOf(
        below: Document,
        context: PreviewRegion,
        mask: Selection,
        radius: Float,
        checkCanceled: () -> Unit
    ): Array<FloatArray> {
        val width = context.previewWidth
        val height = context.previewHeight
        val drawn = PreviewRenderer.renderRegion(below, context, checkCanceled) ?: throw NotEnoughMemoryException()
        val pixels = IntArray(width * height)
        try {
            val software = drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
            software.getPixels(pixels, 0, width, 0, 0, width, height)
            software.recycle()
        } finally {
            drawn.recycle()
        }
        val scale = 1f / (1 shl context.level)
        val maskBitmap = SelectionPreview.maskAt(mask, context.rect.left.toFloat(), context.rect.top.toFloat(), scale, width, height)
        val alphas = ByteArray(width * height)
        try {
            val software = maskBitmap.copy(Bitmap.Config.ALPHA_8, false) ?: throw NotEnoughMemoryException()
            software.copyPixelsToBuffer(ByteBuffer.wrap(alphas))
            software.recycle()
        } finally {
            maskBitmap.recycle()
        }
        checkCanceled()
        val (lightness, a, b) = OkLab.fromPixels(pixels, OkLab.Space.of(below.colorSpace))
        val weights = FloatArray(alphas.size) { (alphas[it].toInt() and 0xFF) / 255f }
        return SkinSmoothMath.coefficients(lightness, a, b, weights, width, height, radius)
    }

    /** 원본 해상도 묶음 [rect]의 정돈 결과(소프트웨어 비트맵) */
    private fun smoothBlock(
        below: Document,
        size: PixelSize,
        rect: PixelRect,
        mask: Selection,
        sigma: Float,
        slope: Bitmap,
        offset: Bitmap,
        coefficientArea: PixelRect,
        scale: Int,
        texture: Float,
        space: OkLab.Space
    ): Bitmap {
        // 흐림이 읽는 둘레까지(3σ와 쌍선형 한 칸) 그린다
        val region = PreviewLevels.region(size, 0, rect.expanded(ceil(3f * sigma).toInt() + 2)) ?: throw NotEnoughMemoryException()
        val input = PreviewRenderer.renderRegion(below, region) ?: throw NotEnoughMemoryException()
        try {
            val fromX = rect.left - region.rect.left
            val fromY = rect.top - region.rect.top
            val weighted = maskedInput(input, mask, region) ?: throw NotEnoughMemoryException()
            val blurred = try {
                EffectShader.gaussian(weighted, sigma, fromX, fromY, rect.width, rect.height) ?: throw NotEnoughMemoryException()
            } finally {
                weighted.recycle()
            }
            try {
                val shader = RuntimeShader(COMBINE).apply {
                    setInputShader("image", BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
                    setInputShader("blurred", BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
                    // 계수는 색이 아니라 값이다(색 공간을 바꾸지 않고 읽는다), 칸 사이는 쌍선형
                    setInputBuffer("slope", BitmapShader(slope, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
                    setInputBuffer("offset", BitmapShader(offset, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
                    setFloatUniform("from", fromX.toFloat(), fromY.toFloat())
                    setFloatUniform("docOrigin", rect.left.toFloat(), rect.top.toFloat())
                    setFloatUniform("coefficientOrigin", coefficientArea.left.toFloat(), coefficientArea.top.toFloat())
                    setFloatUniform("coefficientScale", scale.toFloat())
                    setFloatUniform("texture", texture)
                    setFloatUniform("blurWeight", SkinSmoothMath.BLUR_WEIGHT_LOW, SkinSmoothMath.BLUR_WEIGHT_HIGH)
                    setFloatUniform("toLms", columns(space.toLms))
                    setFloatUniform("fromLms", columns(space.fromLms))
                }
                val drawn = Retouch.drawOnGpu(shader, rect.width, rect.height) ?: throw NotEnoughMemoryException()
                try {
                    return drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
                } finally {
                    drawn.recycle()
                }
            } finally {
                blurred.recycle()
            }
        } finally {
            input.recycle()
        }
    }

    /** 흐림에 넣을 그림: [input]([region] 자리를 그린 그림)의 알파를 곱한 색 × 같은 자리 [mask] 알파(GPU) */
    private fun maskedInput(input: Bitmap, mask: Selection, region: PreviewRegion): Bitmap? {
        val maskBitmap = SelectionPreview.maskAt(mask, region.rect.left.toFloat(), region.rect.top.toFloat(), 1f, region.previewWidth, region.previewHeight)
        try {
            val shader = RuntimeShader(MASKED).apply {
                setInputShader("image", BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
                setInputShader("mask", BitmapShader(maskBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
            }
            return Retouch.drawOnGpu(shader, region.previewWidth, region.previewHeight)
        } finally {
            maskBitmap.recycle()
        }
    }

    /** 묶음 그림 [pixels]([rect] 자리)를 타일로 나눠 [wanted] 타일만 [into]에 넣는다(값 그대로) */
    private fun split(pixels: Bitmap, rect: PixelRect, grid: TileGrid, wanted: Set<TileKey>, into: MutableMap<TileKey, Tile?>) {
        for (row in rect.top / TILE_SIZE until (rect.bottom + TILE_SIZE - 1) / TILE_SIZE) {
            for (column in rect.left / TILE_SIZE until (rect.right + TILE_SIZE - 1) / TILE_SIZE) {
                val key = TileKey(column, row)
                if (key !in wanted) continue
                val bounds = grid.bounds(key)
                val picture = Picture()
                val canvas = picture.beginRecording(bounds.width, bounds.height)
                canvas.drawBitmap(pixels, (rect.left - bounds.left).toFloat(), (rect.top - bounds.top).toFloat(), CopyPaint)
                picture.endRecording()
                into[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)))
            }
        }
    }

    /** 계수 [values]의 [first]·[first]+1·[first]+2번 배열을 RGB에 담은 반정밀도 그림(알파는 1) */
    private fun halfBitmap(values: Array<FloatArray>, first: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
        val one = Half.toHalf(1f)
        val data = ShortArray(width * height * 4)
        for (i in 0 until width * height) {
            data[4 * i] = Half.toHalf(values[first][i])
            data[4 * i + 1] = Half.toHalf(values[first + 1][i])
            data[4 * i + 2] = Half.toHalf(values[first + 2][i])
            data[4 * i + 3] = one
        }
        bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(data))
        return bitmap
    }

    private fun tileRect(grid: TileGrid, key: TileKey): PixelRect {
        val bounds = grid.bounds(key)
        return PixelRect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
    }

    private fun PixelRect.union(other: PixelRect) =
        PixelRect(min(left, other.left), min(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    /** 줄마다 적은 3×3 행렬을 셰이더 행렬의 차례(열마다)로 */
    private fun columns(rows: FloatArray): FloatArray = FloatArray(9) { rows[(it % 3) * 3 + it / 3] }

    /** 그대로 베끼는 붓(필터·안티에일리어싱 끔) */
    private val CopyPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }

    /** 마스크를 곱하기(AGSL): 알파를 곱한 색 × 마스크 알파. 이 그림을 흐려 흐린 알파로 나누면 마스크 무게 평균이다. */
    private const val MASKED = """
        uniform shader image;
        uniform shader mask;

        half4 main(float2 position) {
            return image.eval(position) * mask.eval(position).a;
        }
    """

    /**
     * 합치기(AGSL): 원래 색과 흐린 색을 OKLab으로 바꿔 고운 결(차이)을 구하고, 큰 띠(계수 × 흐린 L)에 질감 유지만큼 더한다.
     * 흐린 색은 마스크 무게 평균(흐린 알파로 나눔)이고, 흐린 알파가 작은 곳(마스크 밖 먼 곳)은 원래 색 쪽으로 섞는다([SkinSmoothMath.blurShare]).
     * 계수는 문서 자리를 계수 그림 자리로 옮겨 쌍선형으로 읽는다. 알파는 원래 그대로다(투명한 곳은 투명).
     */
    private const val COMBINE = """
        uniform shader image;
        uniform shader blurred;
        uniform shader slope;
        uniform shader offset;
        uniform float2 from;
        uniform float2 docOrigin;
        uniform float2 coefficientOrigin;
        uniform float coefficientScale;
        uniform float texture;
        uniform float2 blurWeight;
        uniform float3x3 toLms;
        uniform float3x3 fromLms;

        float toLinear(float c) {
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
        }

        float toSrgb(float c) {
            c = clamp(c, 0.0, 1.0);
            return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
        }

        float3 toLab(float3 c) {
            float3 lms = toLms * float3(toLinear(c.r), toLinear(c.g), toLinear(c.b));
            lms = pow(max(lms, float3(0.0)), float3(1.0 / 3.0));
            return float3(
                0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
                1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
                0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z
            );
        }

        float3 toRgb(float3 lab) {
            float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
            float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
            float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
            float3 rgb = fromLms * float3(l * l * l, m * m * m, s * s * s);
            return float3(toSrgb(rgb.r), toSrgb(rgb.g), toSrgb(rgb.b));
        }

        half4 main(float2 position) {
            half4 color = image.eval(position + from);
            float alpha = float(color.a);
            if (alpha <= 0.0) {
                return half4(0.0);
            }
            half4 soft = blurred.eval(position);
            float weight = float(soft.a);
            float3 lab = toLab(clamp(float3(color.rgb) / alpha, 0.0, 1.0));
            float3 blur = toLab(clamp(float3(soft.rgb) / max(weight, 0.00001), 0.0, 1.0));
            blur = mix(lab, blur, smoothstep(blurWeight.x, blurWeight.y, weight));
            float2 at = (docOrigin + position - coefficientOrigin) / coefficientScale;
            float3 a = float3(slope.eval(at).rgb);
            float3 b = float3(offset.eval(at).rgb);
            // 큰 띠의 길잡이는 흐린 L이다(원래 L이면 결이 큰 띠에도 남아 고운 결을 더할 때 오히려 또렷해진다)
            float3 result = a * blur.x + b + texture * (lab - blur);
            return half4(half3(toRgb(result) * alpha), half(alpha));
        }
    """
}

package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.Shader
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Coverage
import com.kiln.stratum.engine.selection.HeapMaskPixels
import com.kiln.stratum.engine.selection.MaskPixels
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.MaskTileOps
import com.kiln.stratum.engine.selection.SelectionShape
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * 힙 밖 알파 비트맵(ALPHA_8, 바꿀 수 없음) 한 장에 담은 선택 영역 타일. 담은 크기는 비트맵 크기다(문서 끝 타일은 보통 문서 안쪽 크기).
 * 여러 선택 영역 상태가 함께 쓰므로 recycle()하지 않는다.
 */
internal class AlphaMaskPixels(val bitmap: Bitmap) : MaskPixels {

    init {
        require(!bitmap.isMutable) { "Mask bitmap must be immutable" }
        require(bitmap.config == Bitmap.Config.ALPHA_8) { "Mask bitmap must be ALPHA_8: ${bitmap.config}" }
        require(bitmap.width in 1..TILE_SIZE && bitmap.height in 1..TILE_SIZE) { "Mask bitmap too large: ${bitmap.width}x${bitmap.height}" }
    }

    override fun alphaAt(x: Int, y: Int): Int = if (x < bitmap.width && y < bitmap.height) Color.alpha(bitmap.getPixel(x, y)) else 0

    override val width: Int
        get() = bitmap.width

    override val height: Int
        get() = bitmap.height

    override val byteCount: Int
        get() = bitmap.allocationByteCount
}

/**
 * 선택 영역 타일을 알파 비트맵으로 그리고 합친다. 안드로이드 소프트웨어 그리기라 Kotlin으로 픽셀을 하나씩 돌지 않는다.
 * 합치기는 알파 합성 규칙(Porter-Duff)이다: 합 SRC_OVER, 빼기 DST_OUT, 곱 DST_IN.
 */
internal object MaskTiles : MaskTileOps {

    /** 타일의 알파 비트맵. 다 고른 타일은 null. 힙 타일(테스트)은 임시 비트맵을 만들어 [temporary]에 넣는다. */
    fun bitmapOf(tile: MaskTile, temporary: MutableList<Bitmap>): Bitmap? =
        when (val pixels = tile.pixels) {
            null -> null
            is AlphaMaskPixels -> pixels.bitmap
            is HeapMaskPixels -> Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ALPHA_8)
                .apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels.toByteArray())) }
                .also { temporary += it }
            else -> error("Unknown mask storage: ${pixels::class.java.name}")
        }

    /**
     * [width]×[height] 알파 타일을 [draw]로 그린다(처음에는 0). 메모리가 모자라면 [NotEnoughMemoryException].
     * 다 그린 타일이 모두 0이면 null, 모두 255면 [MaskTile.FULL]이다(비교는 안드로이드 안에서 한다, [Bitmap.sameAs]).
     */
    fun draw(width: Int, height: Int, draw: (Canvas) -> Unit): MaskTile? {
        val picture = Picture()
        draw(picture.beginRecording(width, height))
        picture.endRecording()
        // 소프트웨어로 그리고 바꿀 수 없는 비트맵 하나로 돌려준다(CanvasTileComposer와 같다)
        val bitmap = try {
            Bitmap.createBitmap(picture, width, height, Bitmap.Config.ALPHA_8)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        return when {
            bitmap.sameAs(uniform(width, height, full = false)) -> null.also { bitmap.recycle() }
            bitmap.sameAs(uniform(width, height, full = true)) -> MaskTile.FULL.also { bitmap.recycle() }
            else -> MaskTile.of(AlphaMaskPixels(bitmap))
        }
    }

    /**
     * 크기마다 모두 0(또는 255)인 알파 비트맵. 그린 타일이 비었는지(꽉 찼는지) 견주는 데 쓴다. 크기가 옮기는 만큼마다 달라질 수 있어
     * 최근에 쓴 [UNIFORM_LIMIT]개만 남긴다(검토에서 고침). 내보낸 비트맵은 다른 스레드가 견주는 중일 수 있어 recycle()하지 않고 GC에 맡긴다.
     */
    private val uniforms = object : LinkedHashMap<Triple<Int, Int, Boolean>, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int, Boolean>, Bitmap>?): Boolean = size > UNIFORM_LIMIT
    }

    private const val UNIFORM_LIMIT = 32

    @Synchronized
    private fun uniform(width: Int, height: Int, full: Boolean): Bitmap =
        uniforms.getOrPut(Triple(width, height, full)) {
            val drawn = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            if (full) Canvas(drawn).drawColor(Color.BLACK)
            drawn.copy(Bitmap.Config.ALPHA_8, false).also { drawn.recycle() }
        }

    /**
     * 모두 0인 꽉 찬 마스크 타일 한 장([com.kiln.stratum.engine.transform.TileComposer.emptyMask]). 모든 자리·상태가 함께 쓰고 recycle()하지 않는다.
     * 저장하면 보통 마스크 타일 파일(0뿐이라 아주 작음)로 쓰고 그대로 읽힌다.
     */
    val empty: MaskTile by lazy {
        val drawn = try {
            Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ALPHA_8)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        val bitmap = drawn.copy(Bitmap.Config.ALPHA_8, false).also { drawn.recycle() } ?: throw NotEnoughMemoryException()
        MaskTile.of(AlphaMaskPixels(bitmap))
    }

    /**
     * 알파 그림 [alpha]를 (0, 0)에 [mode]로 얹는다. 알파를 셰이더로 그려 소스 알파로 쓴다.
     * 알파 비트맵을 그냥 그리면 소프트웨어 그리기는 그 알파를 덮는 정도로만 써서 DST_IN이 아무것도 바꾸지 않는다(에뮬레이터에서 확인).
     */
    fun drawAlpha(canvas: Canvas, alpha: Bitmap, mode: BlendMode) {
        val paint = Paint().apply {
            shader = BitmapShader(alpha, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
            blendMode = mode
        }
        canvas.drawRect(0f, 0f, alpha.width.toFloat(), alpha.height.toFloat(), paint)
    }

    private fun combine(a: MaskTile, b: MaskTile, mode: BlendMode): MaskTile? {
        val temporary = ArrayList<Bitmap>()
        try {
            val first = checkNotNull(bitmapOf(a, temporary)) { "Full tiles are combined without drawing" }
            val second = checkNotNull(bitmapOf(b, temporary)) { "Full tiles are combined without drawing" }
            return draw(first.width, first.height) { canvas ->
                drawAlpha(canvas, first, BlendMode.SRC)
                drawAlpha(canvas, second, mode)
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    override fun union(a: MaskTile, b: MaskTile): MaskTile? = combine(a, b, BlendMode.SRC_OVER)

    override fun subtract(a: MaskTile, b: MaskTile): MaskTile? = combine(a, b, BlendMode.DST_OUT)

    override fun intersect(a: MaskTile, b: MaskTile): MaskTile? = combine(a, b, BlendMode.DST_IN)

    override fun invert(a: MaskTile): MaskTile? = invert(a, a.width, a.height)

    /**
     * [a]를 뒤집은 [width]×[height] 마스크 타일(담은 크기 이상). [a]가 담지 않은 곳은 0이었으므로 뒤집으면 255다
     * (레이어 마스크를 뒤집으면 끝 자리에서 가렸던 캔버스 밖 픽셀도 보이게, 결정 기록 '캔버스 밖 픽셀').
     */
    fun invert(a: MaskTile, width: Int, height: Int): MaskTile? {
        val temporary = ArrayList<Bitmap>()
        try {
            val source = checkNotNull(bitmapOf(a, temporary)) { "Full tiles are inverted without drawing" }
            return draw(width, height) { canvas ->
                canvas.drawColor(Color.BLACK)
                drawAlpha(canvas, source, BlendMode.DST_OUT)
            }
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /**
     * [shape]를 문서 [size]의 타일마다 그린 알파(없는 자리는 0). [feather](픽셀)가 있으면 경계를 부드럽게 흐린다.
     * 안이거나 밖인 타일은 그리지 않고 정한다([SelectionShape.coverage], 흐림이 닿는 만큼 넓혀 본다).
     * [checkCanceled]는 타일 사이마다 부른다.
     */
    fun shapeTiles(shape: SelectionShape, feather: Float, size: PixelSize, checkCanceled: () -> Unit): Map<TileKey, MaskTile> {
        val margin = featherMargin(feather)
        val bounds = shape.bounds ?: return emptyMap()
        val area = PixelRect(bounds.left - margin, bounds.top - margin, bounds.right + margin, bounds.bottom + margin)
            .intersect(PixelRect.of(size)) ?: return emptyMap()
        val grid = TileGrid(size.width, size.height)
        val path = pathOf(shape)
        val paint = Paint().apply {
            isAntiAlias = true
            if (feather > 0f) maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        }
        val tiles = HashMap<TileKey, MaskTile>()
        for (row in area.top / TILE_SIZE..(area.bottom - 1) / TILE_SIZE) {
            for (column in area.left / TILE_SIZE..(area.right - 1) / TILE_SIZE) {
                checkCanceled()
                val key = TileKey(column, row)
                val tile = grid.bounds(key)
                val reach = PixelRect(tile.left - margin, tile.top - margin, tile.left + tile.width + margin, tile.top + tile.height + margin)
                when (shape.coverage(reach)) {
                    Coverage.OUTSIDE -> Unit
                    Coverage.INSIDE -> tiles[key] = MaskTile.FULL
                    Coverage.PARTIAL -> draw(tile.width, tile.height) { canvas ->
                        canvas.translate(-tile.left.toFloat(), -tile.top.toFloat())
                        canvas.drawPath(path, paint)
                    }?.let { tiles[key] = it }
                }
            }
        }
        return tiles
    }

    /** 흐림이 경계에서 닿는 거리(픽셀). 안드로이드 흐림의 시그마는 반지름 × 0.58 + 0.5이고, 3시그마까지 본다. */
    fun featherMargin(feather: Float): Int = if (feather <= 0f) 0 else ceil(feather * 1.8f + 2f).toInt()

    /** 모양의 안드로이드 경로(문서 좌표) */
    fun pathOf(shape: SelectionShape): Path =
        Path().apply {
            when (shape) {
                is SelectionShape.Rectangle -> addRect(shape.left, shape.top, shape.right, shape.bottom, Path.Direction.CW)
                is SelectionShape.Ellipse -> addOval(shape.left, shape.top, shape.right, shape.bottom, Path.Direction.CW)
                is SelectionShape.Lasso -> {
                    fillType = Path.FillType.EVEN_ODD
                    shape.points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
                    close()
                }
            }
        }

}

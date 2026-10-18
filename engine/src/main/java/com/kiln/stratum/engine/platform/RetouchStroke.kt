package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
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
import com.kiln.stratum.engine.retouch.HoleFill
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.engine.retouch.SpotSearch
import com.kiln.stratum.engine.selection.Selection
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * [layerId] 레이어에 리터칭 획 하나를 그린다. 원본 해상도에 바로 그린다(결정 기록 '원본 픽셀').
 *
 * - 복제 도장([RetouchMode.CLONE]): '획 자리 - [offset]'의 픽셀을 획 모양(크기·단단함·불투명도)으로 그대로 베낀다.
 * - 복구 브러시([RetouchMode.HEAL]): 같은 곳의 무늬를 가져오고, 밝기·색은 칠한 곳 둘레에 맞춘다.
 *   결과 = 가져온 픽셀 + ('그 자리 - 가져온 픽셀'을 칠한 곳 바깥에서 안쪽으로 매끈하게 이은 값, [HoleFill]).
 *   칠한 곳 안의 원래 픽셀(얼룩)은 결과에 들어가지 않는다.
 * - 스팟 복구 브러시([RetouchMode.SPOT_HEAL]): 가져올 곳을 칠한 곳 둘레에서 찾아([SpotSearch]) 복구 브러시처럼 그린다.
 *   [offset]은 쓰지 않는다. 가져올 곳을 찾지 못하면 그대로다.
 *
 * 가져오는 픽셀은 획을 그리기 전의 레이어다. 문서 밖에서 가져와야 하는 곳은 그대로 둔다. 선택 영역이 있으면 그 안에만 그린다.
 * 획이 닿는 타일만 새로 만들고, 칠하지 않은 픽셀은 값이 그대로다. 복제 도장은 가져온 픽셀 값도 그대로다.
 * 얹기는 브러시처럼 소프트웨어 그리기이고, 복구의 합치기는 GPU다. 매끈하게 잇기와 가져올 곳 찾기는 줄인 그림에서 Kotlin으로 한다.
 * 없는 레이어거나 문서에 닿지 않는 획이면 문서를 그대로 돌려준다.
 */
data class RetouchStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val mode: RetouchMode,
    val offset: SourceOffset? = null
) : CancellableCommand {

    init {
        require(mode == RetouchMode.SPOT_HEAL || offset != null) { "$mode needs a source offset" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: return document
        // 빈 레이어에서는 가져올 픽셀이 없다
        if (raster.tiles.isEmpty()) return document
        val painted = try {
            val from = when (mode) {
                RetouchMode.SPOT_HEAL -> Retouch.spotOffset(raster, size, bounds, dabs, settings, checkCanceled) ?: return document
                else -> checkNotNull(offset)
            }
            if (from.x == 0 && from.y == 0) return document
            Retouch.paint(raster, size, bounds, dabs, settings, from, heal = mode != RetouchMode.CLONE, document.selection, checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (painted === raster) return document
        val layers = document.layers.toMutableList().apply { set(index, layer.copy(content = RasterContent(painted))) }
        return document.copy(layers = layers)
    }

    /** 획이 닿는 타일마다 새 타일 한 장과, 타일마다 잠깐 쓰는 그림 몇 장, 복구의 줄인 그림 계산 */
    override fun estimateNewBytes(document: Document): Long {
        val bounds = StrokeDabs.bounds(dabs, settings.radius, PixelSize(document.width, document.height)) ?: return 0L
        val tileBytes = TILE_SIZE.toLong() * TILE_SIZE * Int.SIZE_BYTES
        val tiles = BrushTiles.keysIn(bounds).size * tileBytes
        val work = if (mode == RetouchMode.CLONE) 3 * tileBytes else 4 * tileBytes + Retouch.HEAL_WORK_BYTES
        return tiles + work
    }
}

/** 리터칭 획 그리기 */
internal object Retouch {

    /** 복구의 줄인 그림 계산(그림 읽기·구멍 메우기·반정밀도 그림)이 잠깐 쓰는 메모리의 어림(바이트) */
    const val HEAL_WORK_BYTES = 32L * 1024 * 1024

    /**
     * [raster]의 [bounds] 안 타일에 획을 그린 새 래스터. 바뀐 것이 없으면 [raster]를 그대로 돌려준다.
     * 타일마다 '타일 자리 - [offset]'의 픽셀을 한 장으로 베끼고([compose]), [heal]이면 밝기·색을 맞춘 뒤([Membrane.heal]) 획 모양으로 얹는다.
     */
    fun paint(
        raster: Raster,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        offset: SourceOffset,
        heal: Boolean,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): Raster {
        val membrane = if (heal) Membrane.of(raster, size, bounds, dabs, settings, offset, checkCanceled) ?: return raster else null
        try {
            val grid = TileGrid(size.width, size.height)
            val whole = PixelRect.of(size)
            val reach = settings.radius + 1f
            val changes = HashMap<TileKey, Tile?>()
            val temporary = ArrayList<Bitmap>()
            try {
                for (key in BrushTiles.keysIn(bounds)) {
                    checkCanceled()
                    // 선택 영역이 있는데 이 자리를 하나도 고르지 않았으면 그릴 것이 없다
                    val mask = if (selection == null) null else (selection.tiles[key] ?: continue)
                    val tile = grid.bounds(key)
                    val near = dabs.filter {
                        it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                            it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                    }
                    if (near.isEmpty()) continue
                    val rect = PixelRect(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)
                    // 가져올 곳이 모두 문서 밖이면 그릴 것이 없다
                    val from = rect.offset(-offset.x, -offset.y)
                    if (from.intersect(whole) == null) continue
                    val source = compose(raster, size, from)
                    val fill = membrane?.heal(source, rect) ?: source
                    try {
                        val picture = Picture()
                        val canvas = picture.beginRecording(tile.width, tile.height)
                        raster.tiles[key]?.let { canvas.drawBitmap(CanvasTileComposer.bitmapOf(it, temporary), 0f, 0f, CanvasTileComposer.CopyPaint) }
                        BrushPainter.drawStroke(
                            canvas, near, settings, erase = false,
                            scale = 1f,
                            left = tile.left.toFloat(),
                            top = tile.top.toFloat(),
                            width = tile.width.toFloat(),
                            height = tile.height.toFloat(),
                            clip = mask?.let { MaskTiles.bitmapOf(it, temporary) },
                            fill = BitmapShader(fill, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        )
                        picture.endRecording()
                        // 브러시 타일과 같이 소프트웨어로 그리고 바꿀 수 없는 비트맵 하나로 돌려준다
                        changes[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, tile.width, tile.height, Bitmap.Config.ARGB_8888)))
                    } finally {
                        if (fill !== source) fill.recycle()
                        source.recycle()
                    }
                }
            } finally {
                for (bitmap in temporary) bitmap.recycle()
            }
            return if (changes.isEmpty()) raster else raster.withTiles(changes)
        } finally {
            membrane?.recycle()
        }
    }

    /** [raster]의 [area] 부분(문서 밖은 투명)을 한 장으로 베낀 소프트웨어 비트맵. 값이 그대로다. */
    fun compose(raster: Raster, size: PixelSize, area: PixelRect): Bitmap {
        val picture = Picture()
        val canvas = picture.beginRecording(area.width, area.height)
        val temporary = ArrayList<Bitmap>()
        try {
            val inside = area.intersect(PixelRect.of(size))
            if (inside != null) {
                // 문서 밖(끝 타일의 캔버스 밖 부분)은 그리지 않는다
                canvas.clipRect(
                    (inside.left - area.left).toFloat(), (inside.top - area.top).toFloat(),
                    (inside.right - area.left).toFloat(), (inside.bottom - area.top).toFloat()
                )
                for (row in inside.top / TILE_SIZE..(inside.bottom - 1) / TILE_SIZE) {
                    for (column in inside.left / TILE_SIZE..(inside.right - 1) / TILE_SIZE) {
                        val tile = raster.tiles[TileKey(column, row)] ?: continue
                        val x = (column * TILE_SIZE - area.left).toFloat()
                        val y = (row * TILE_SIZE - area.top).toFloat()
                        canvas.drawBitmap(CanvasTileComposer.bitmapOf(tile, temporary), x, y, CanvasTileComposer.CopyPaint)
                    }
                }
            }
            picture.endRecording()
            return Bitmap.createBitmap(picture, area.width, area.height, Bitmap.Config.ARGB_8888)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /**
     * 스팟 복구 브러시가 가져올 곳(칠한 곳 - 가져올 곳). 칠한 곳과 후보가 닿을 만큼 둘레를 줄인 그림으로 그려
     * [SpotSearch]로 찾는다(긴 변 [SpotSearch.MAX_EDGE] 이하). 찾지 못하면 null.
     */
    fun spotOffset(
        raster: Raster,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        checkCanceled: () -> Unit
    ): SourceOffset? {
        // 후보는 반지름의 5배나 칠한 곳 크기의 1.5배(+ 띠)까지 간다(SpotSearch)
        val far = maxOf(5f * settings.radius, 1.5f * (bounds.width + bounds.height) + settings.radius).let { ceil(it).toInt() + 2 }
        val wanted = PixelRect(bounds.left - far, bounds.top - far, bounds.right + far, bounds.bottom + far)
        val view = LevelView.of(raster, size, wanted, SpotSearch.MAX_EDGE, checkCanceled) ?: return null
        try {
            val pixels = IntArray(view.width * view.height)
            view.bitmap.getPixels(pixels, 0, view.width, view.offsetX, view.offsetY, view.width, view.height)
            val painted = view.footprint(dabs, settings.radius, dilate = 0.5f)
            val cell = 1 shl view.level
            val shift = SpotSearch.find(pixels, painted, view.width, view.height, settings.radius / cell) ?: return null
            return SourceOffset(-shift.dx * cell, -shift.dy * cell)
        } finally {
            view.recycle()
        }
    }

    /** [shader]로 채운 [width]×[height] 하드웨어 비트맵(GPU). GPU가 그리지 못하면 null. */
    fun drawOnGpu(shader: RuntimeShader, width: Int, height: Int): Bitmap? {
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            this.shader = shader
            blendMode = BlendMode.SRC
        })
        picture.endRecording()
        return PreviewRenderer.drawOnGpu(picture, width, height)
    }
}

/**
 * 문서의 한 영역을 어떤 단계(1/2^[level])로 그린 소프트웨어 그림. 가져올 곳 찾기와 밝기·색 맞추기가 읽는다.
 * 그림은 단계 격자에 맞춘 넓은 영역([region])이고, 쓸 곳은 그 안의 ([offsetX], [offsetY])부터 [width]×[height]다.
 * 쓸 곳의 왼쪽 위는 문서에서 ([left], [top])이고, 둘 다 단계 칸(2^[level])에 맞춰져 있다.
 */
private class LevelView(
    val bitmap: Bitmap,
    val region: PreviewRegion,
    val level: Int,
    val left: Int,
    val top: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int
) {
    /** 단계 칸 하나의 문서 픽셀 수 */
    val cell: Int
        get() = 1 shl level

    /**
     * 쓸 곳에서 획이 칠하는 곳(픽셀마다). 자국마다 반지름 [radius]보다 [dilate] 칸 넓은 원을 그려 본다.
     * 안드로이드 그리기(알파 그림)로 그리고 알파를 읽는다.
     */
    fun footprint(dabs: List<StrokePoint>, radius: Float, dilate: Float): BooleanArray {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        try {
            val canvas = Canvas(mask)
            val paint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            val scale = 1f / cell
            for (dab in dabs) canvas.drawCircle((dab.x - left) * scale, (dab.y - top) * scale, radius * scale + dilate, paint)
            val bytes = ByteBuffer.allocate(mask.rowBytes * height)
            mask.copyPixelsToBuffer(bytes)
            val alpha = bytes.array()
            return BooleanArray(width * height) { i -> alpha[(i / width) * mask.rowBytes + i % width].toInt() != 0 }
        } finally {
            mask.recycle()
        }
    }

    fun recycle() = bitmap.recycle()

    companion object {
        /**
         * [raster]의 [wanted] 영역(문서 밖은 자름)을, 긴 변이 [maxEdge] 이하가 되는 가장 덜 줄인 단계로 그린다(GPU).
         * 문서에 닿지 않거나 GPU가 그리지 못하면 null.
         */
        fun of(raster: Raster, size: PixelSize, wanted: PixelRect, maxEdge: Int, checkCanceled: () -> Unit): LevelView? {
            val inside = wanted.intersect(PixelRect.of(size)) ?: return null
            var level = 0
            while (maxOf(inside.width, inside.height) > maxEdge shl level) level++
            val cell = 1 shl level
            // 쓸 곳을 단계 칸에 맞춘다(문서 안에서)
            val left = inside.left / cell * cell
            val top = inside.top / cell * cell
            val region = PreviewLevels.region(size, level, PixelRect(left, top, inside.right, inside.bottom)) ?: return null
            val drawn = PreviewRenderer.renderRegion(Document.create(size.width, size.height, "", raster), region, checkCanceled)
                ?: throw NotEnoughMemoryException()
            val bitmap = try {
                drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
            } finally {
                drawn.recycle()
            }
            val offsetX = (left shr level) - region.previewLeft
            val offsetY = (top shr level) - region.previewTop
            val width = minOf(ceilDiv(inside.right - left, cell), bitmap.width - offsetX)
            val height = minOf(ceilDiv(inside.bottom - top, cell), bitmap.height - offsetY)
            if (width <= 0 || height <= 0) {
                bitmap.recycle()
                return null
            }
            return LevelView(bitmap, region, level, left, top, offsetX, offsetY, width, height)
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }
}

/**
 * 복구 브러시의 밝기·색 맞추기. 칠한 곳 둘레에서 '그 자리 - 가져온 곳'(알파를 곱한 RGBA)을 재고, 칠한 곳 안은 [HoleFill]로 매끈하게 이은 값.
 * 줄인 단계(긴 변 [HoleFill.MAX_EDGE] 이하)의 반정밀도 그림([field])으로 GPU에 올려, 타일마다 쌍선형으로 읽어 더한다([heal]).
 * 줄인 단계에서는 가져올 곳의 옮긴 양을 단계 칸에 맞춰 반올림한다(밝기·색은 매끈해서 차이가 작다).
 */
private class Membrane(
    private val field: Bitmap,
    private val left: Int,
    private val top: Int,
    private val cell: Int,
    private val offset: SourceOffset,
    private val size: PixelSize
) {
    /** 타일 [rect]에 가져온 픽셀 [source](타일 크기) + 맞춘 값. GPU가 그리지 못하면 [NotEnoughMemoryException]. */
    fun heal(source: Bitmap, rect: PixelRect): Bitmap {
        val shader = RuntimeShader(HEAL).apply {
            setInputShader("source", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
            setInputShader("field", BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            setFloatUniform("tileOrigin", rect.left.toFloat(), rect.top.toFloat())
            setFloatUniform("fieldOrigin", left.toFloat(), top.toFloat())
            setFloatUniform("fieldScale", cell.toFloat())
            setFloatUniform("offset", offset.x.toFloat(), offset.y.toFloat())
            setFloatUniform("documentSize", size.width.toFloat(), size.height.toFloat())
        }
        val drawn = Retouch.drawOnGpu(shader, rect.width, rect.height) ?: throw NotEnoughMemoryException()
        try {
            return drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
        } finally {
            drawn.recycle()
        }
    }

    fun recycle() = field.recycle()

    companion object {
        /**
         * [bounds](획이 닿는 곳)와 둘레(반지름만큼)의 맞춘 값. 가져올 곳이 하나도 문서 안에 없으면 null.
         * 칠한 곳은 자국마다 반지름보다 한 칸 넓게 본다(둘레의 값에 얼룩이 섞이지 않게).
         */
        fun of(
            raster: Raster,
            size: PixelSize,
            bounds: PixelRect,
            dabs: List<StrokePoint>,
            settings: BrushSettings,
            offset: SourceOffset,
            checkCanceled: () -> Unit
        ): Membrane? {
            val margin = maxOf(8, ceil(settings.radius).toInt())
            val wanted = PixelRect(bounds.left - margin, bounds.top - margin, bounds.right + margin, bounds.bottom + margin)
            val destination = LevelView.of(raster, size, wanted, HoleFill.MAX_EDGE, checkCanceled) ?: return null
            try {
                val cell = destination.cell
                val level = destination.level
                // 가져올 곳(옮긴 양을 칸에 맞춤)도 같은 단계로 그린다. 쓸 곳이 같은 칸에 맞아 픽셀끼리 짝이 맞는다.
                val shiftX = floor(offset.x / cell.toFloat() + 0.5f).toInt() * cell
                val shiftY = floor(offset.y / cell.toFloat() + 0.5f).toInt() * cell
                val sourceArea = PixelRect(
                    destination.left - shiftX, destination.top - shiftY,
                    destination.left - shiftX + destination.width * cell, destination.top - shiftY + destination.height * cell
                )
                val sourceInside = sourceArea.intersect(PixelRect.of(size)) ?: return null
                val sourceRegion = PreviewLevels.region(size, level, sourceInside) ?: return null
                val sourceDrawn = PreviewRenderer.renderRegion(Document.create(size.width, size.height, "", raster), sourceRegion, checkCanceled)
                    ?: throw NotEnoughMemoryException()
                val sourceBitmap = try {
                    sourceDrawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
                } finally {
                    sourceDrawn.recycle()
                }
                try {
                    checkCanceled()
                    val width = destination.width
                    val height = destination.height
                    val destinationBytes = bytesOf(destination.bitmap)
                    val destinationRow = destination.bitmap.rowBytes
                    val sourceBytes = bytesOf(sourceBitmap)
                    val sourceRow = sourceBitmap.rowBytes
                    val painted = destination.footprint(dabs, settings.radius, dilate = 1f)
                    // 가져올 그림 안에서 쓸 곳의 (0, 0) 자리(문서 밖이면 음수일 수 있다)
                    val sourceX = ((destination.left - shiftX) shr level) - sourceRegion.previewLeft
                    val sourceY = ((destination.top - shiftY) shr level) - sourceRegion.previewTop
                    val values = FloatArray(width * height * 4)
                    val weights = FloatArray(width * height)
                    for (y in 0 until height) {
                        val sy = y + sourceY
                        if (sy < 0 || sy >= sourceBitmap.height) continue
                        for (x in 0 until width) {
                            val p = y * width + x
                            val sx = x + sourceX
                            if (painted[p] || sx < 0 || sx >= sourceBitmap.width) continue
                            val d = (y + destination.offsetY) * destinationRow + (x + destination.offsetX) * 4
                            val s = sy * sourceRow + sx * 4
                            for (c in 0 until 4) {
                                values[p * 4 + c] = ((destinationBytes[d + c].toInt() and 0xFF) - (sourceBytes[s + c].toInt() and 0xFF)) / 255f
                            }
                            weights[p] = 1f
                        }
                    }
                    checkCanceled()
                    val filled = HoleFill.fill(values, weights, width, height, 4)
                    val field = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
                    field.copyPixelsFromBuffer(ShortBuffer.wrap(ShortArray(filled.size) { Half.toHalf(filled[it]) }))
                    return Membrane(field, destination.left, destination.top, cell, offset, size)
                } finally {
                    sourceBitmap.recycle()
                }
            } finally {
                destination.recycle()
            }
        }

        /** 소프트웨어 그림의 픽셀 바이트(줄마다 rowBytes, 픽셀마다 R·G·B·A, 알파를 곱한 값) */
        private fun bytesOf(bitmap: Bitmap): ByteArray {
            val buffer = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buffer)
            return buffer.array()
        }

        /**
         * 복구 합치기(AGSL): 가져온 픽셀 + 맞춘 값(쌍선형). 가져올 곳이 문서 밖이면 투명(그 자리는 그대로 남는다).
         * 알파는 0~1, 색은 0~알파로 자른다(알파를 곱한 값).
         */
        private const val HEAL = """
            uniform shader source;
            uniform shader field;
            uniform float2 tileOrigin;
            uniform float2 fieldOrigin;
            uniform float fieldScale;
            uniform float2 offset;
            uniform float2 documentSize;

            half4 main(float2 position) {
                float2 document = tileOrigin + position;
                float2 from = document - offset;
                if (from.x < 0.0 || from.y < 0.0 || from.x >= documentSize.x || from.y >= documentSize.y) return half4(0.0);
                float4 color = float4(source.eval(position)) + float4(field.eval((document - fieldOrigin) / fieldScale));
                color.a = clamp(color.a, 0.0, 1.0);
                color.rgb = clamp(color.rgb, float3(0.0), float3(color.a));
                return half4(color);
            }
        """
    }
}

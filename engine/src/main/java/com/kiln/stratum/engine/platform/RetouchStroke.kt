package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Matrix
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
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
 * 가져오는 픽셀은 획을 그리기 전의 레이어다. [sampleBelow]면 그 레이어와 그 아래를 합친 그림이다(포토샵 '현재 및 아래 샘플링',
 * 결정 기록 '피부 보정'의 5단계, [BelowSampler]): 빈 레이어(잡티 제거)에 아래 사진의 잡티를 지운 결과만 그린다.
 * 문서 밖에서 가져와야 하는 곳은 그대로 둔다. 선택 영역이 있으면 그 안에만 그린다.
 * 획이 닿는 타일만 새로 만들고, 칠하지 않은 픽셀은 값이 그대로다. 복제 도장은 가져온 픽셀 값도 그대로다.
 * 얹기는 브러시처럼 소프트웨어 그리기이고, 복구의 합치기는 GPU다. 매끈하게 잇기와 가져올 곳 찾기는 줄인 그림에서 Kotlin으로 한다.
 * 없는 레이어거나 문서에 닿지 않는 획이면 문서를 그대로 돌려준다.
 */
data class RetouchStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val mode: RetouchMode,
    val offset: SourceOffset? = null,
    val sampleBelow: Boolean = false
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
        val from: SourceOffset
        val painted = try {
            // 가져올 그림: 그 레이어, 또는 그 레이어와 그 아래를 합친 그림(필요한 곳만 그림)
            val sampler = if (sampleBelow) BelowSampler(document, layerId) else null
            val reach = Retouch.sampleReach(mode, dabs, settings, offset)
            val sample = sampler?.sample(bounds.expanded(reach), checkCanceled) ?: raster
            // 가져올 픽셀이 없다(빈 레이어)
            if (sample.tiles.isEmpty()) return document
            from = when (mode) {
                RetouchMode.SPOT_HEAL -> Retouch.spotOffset(sample, size, bounds, dabs, settings, checkCanceled) ?: return document
                else -> checkNotNull(offset)
            }
            if (from.x == 0 && from.y == 0) return document
            Retouch.paintAll(listOf(raster), sample, size, bounds, dabs, settings, from, heal = mode != RetouchMode.CLONE, document.selection, checkCanceled).single()
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (painted === raster) return document
        // 게인맵은 무늬를 가져온 자리의 것을 그대로 베낀다(결정 기록 'HDR')
        val gainmap = try {
            layer.gainmap?.let { GainmapEdits.clone(it, size, dabs, settings, from, document.selection, checkCanceled) }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        val layers = document.layers.toMutableList().apply { set(index, layer.withPixels(painted, gainmap)) }
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

    /** '아래 포함'이면 가져올 곳까지 합친 그림(묶음마다 그림·복사본·타일) */
    override fun estimateWorkBytes(document: Document): Long {
        if (!sampleBelow) return 0L
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius, size) ?: return 0L
        val area = bounds.expanded(Retouch.sampleReach(mode, dabs, settings, offset)).intersect(PixelRect.of(size)) ?: return 0L
        val blocks = ((area.width + 2047L) / 1024) * ((area.height + 2047L) / 1024)
        return blocks * BelowSampler.BLOCK_WORK_BYTES
    }
}

/** 리터칭 획 그리기 */
internal object Retouch {

    /** 복구의 줄인 그림 계산(그림 읽기·구멍 메우기·반정밀도 그림)이 잠깐 쓰는 메모리의 어림(바이트) */
    const val HEAL_WORK_BYTES = 32L * 1024 * 1024

    /** 가져올 그림을 한 번에 만드는 묶음의 한 변(타일 수). 1024px이고, 묶음마다 GPU를 한 번만 쓴다. */
    private const val BLOCK_TILES = 4

    /**
     * 획 [dabs]을 그리는 데 가져올 그림이 있어야 하는 거리([bounds] 둘레, 문서 픽셀): 스팟 복구는 가져올 곳을 찾는 거리, 복구·복제는 옮긴 양,
     * 그리고 맞추기 둘레·타일 맞춤(타일 하나)과 여유. '아래 포함'([BelowSampler])이 이만큼 그린다.
     */
    fun sampleReach(mode: RetouchMode, dabs: List<StrokePoint>, settings: BrushSettings, offset: SourceOffset?): Int {
        val shift = when (mode) {
            RetouchMode.SPOT_HEAL -> SpotSearch.maxReach(settings.radius, extentOf(dabs, settings.radius)) + settings.radius + 2f
            else -> offset?.let { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y)).toFloat() } ?: 0f
        }
        return ceil(shift).toInt() + maxOf(8, ceil(settings.radius).toInt()) + TILE_SIZE + 16
    }

    /**
     * [raster]의 [bounds] 안 타일에 획을 그린 새 래스터. 바뀐 것이 없으면 [raster]를 그대로 돌려준다.
     * 획이 닿는 타일을 묶음([BLOCK_TILES]×[BLOCK_TILES] 타일)으로 모아, 묶음마다 '자리 - [offset]'의 픽셀을 한 장으로 베끼고([compose]),
     * [heal]이면 밝기·색을 맞춘 뒤([Membrane.heal], GPU 한 번) 타일마다 획 모양으로 얹는다.
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
    ): Raster = paintAll(listOf(raster), raster, size, bounds, dabs, settings, offset, heal, selection, checkCanceled).single()

    /**
     * [targets] 래스터마다 같은 획을 그린 새 래스터들(차례 그대로). 가져오는 픽셀과 밝기·색 맞추기는 [sample]에서 읽는다(그 레이어, 또는 '아래 포함' 그림).
     * 채울 그림은 묶음마다 한 번만 만들고 대상마다 획 모양으로 얹는다(자동 잡티 제거가 잡티 제거 레이어와 가져올 그림을 함께 고친다).
     */
    fun paintAll(
        targets: List<Raster>,
        sample: Raster,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        offset: SourceOffset,
        heal: Boolean,
        selection: Selection?,
        checkCanceled: () -> Unit
    ): List<Raster> {
        val membrane = if (heal) Membrane.of(sample, size, bounds, dabs, settings, offset, checkCanceled) ?: return targets else null
        try {
            val grid = TileGrid(size.width, size.height)
            val whole = PixelRect.of(size)
            val reach = settings.radius + 1f
            // 그릴 타일: 선택 영역이 있으면 고른 타일만, 자국이 닿고 가져올 곳이 문서 안에 있는 것만
            val keys = BrushTiles.keysIn(bounds).filter { key ->
                if (selection != null && key !in selection.tiles) return@filter false
                val tile = grid.bounds(key)
                val rect = PixelRect(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)
                rect.offset(-offset.x, -offset.y).intersect(whole) != null && dabs.any {
                    it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                        it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                }
            }
            val changes = targets.map { HashMap<TileKey, Tile?>() }
            val temporary = ArrayList<Bitmap>()
            try {
                for (block in keys.groupBy { TileKey(it.column / BLOCK_TILES, it.row / BLOCK_TILES) }.values) {
                    checkCanceled()
                    val area = block.map { key -> grid.bounds(key).let { PixelRect(it.left, it.top, it.left + it.width, it.top + it.height) } }
                        .reduce { a, b -> PixelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
                    val source = compose(sample, size, area.offset(-offset.x, -offset.y))
                    val fill = membrane?.heal(source, area) ?: source
                    try {
                        for (key in block) {
                            checkCanceled()
                            val tile = grid.bounds(key)
                            val near = dabs.filter {
                                it.x + reach >= tile.left && it.x - reach <= tile.left + tile.width &&
                                    it.y + reach >= tile.top && it.y - reach <= tile.top + tile.height
                            }
                            for ((index, target) in targets.withIndex()) {
                                val picture = Picture()
                                val canvas = picture.beginRecording(tile.width, tile.height)
                                target.tiles[key]?.let { canvas.drawBitmap(CanvasTileComposer.bitmapOf(it, temporary), 0f, 0f, CanvasTileComposer.CopyPaint) }
                                // 타일 자리 q의 채울 색은 묶음 그림의 (q + 타일 - 묶음) 자리다
                                val shader = BitmapShader(fill, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                                    setLocalMatrix(Matrix().apply { setTranslate((area.left - tile.left).toFloat(), (area.top - tile.top).toFloat()) })
                                }
                                BrushPainter.drawStroke(
                                    canvas, near, settings, erase = false,
                                    scale = 1f,
                                    left = tile.left.toFloat(),
                                    top = tile.top.toFloat(),
                                    width = tile.width.toFloat(),
                                    height = tile.height.toFloat(),
                                    clip = selection?.tiles?.get(key)?.let { MaskTiles.bitmapOf(it, temporary) },
                                    fill = shader
                                )
                                picture.endRecording()
                                // 브러시 타일과 같이 소프트웨어로 그리고 바꿀 수 없는 비트맵 하나로 돌려준다
                                changes[index][key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, tile.width, tile.height, Bitmap.Config.ARGB_8888)))
                            }
                        }
                    } finally {
                        if (fill !== source) fill.recycle()
                        source.recycle()
                    }
                }
            } finally {
                for (bitmap in temporary) bitmap.recycle()
            }
            return targets.mapIndexed { index, target -> if (changes[index].isEmpty()) target else target.withTiles(changes[index]) }
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
     * 스팟 복구 브러시가 가져올 곳(칠한 곳 - 가져올 곳). 칠한 곳과 후보가 닿을 만큼(후보의 가장 먼 거리 + 반지름) 둘레를
     * 줄인 그림으로 그려 [SpotSearch]로 찾는다(긴 변 [SpotSearch.MAX_EDGE] 이하). 찾지 못하면 null.
     */
    fun spotOffset(
        raster: Raster,
        size: PixelSize,
        bounds: PixelRect,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        checkCanceled: () -> Unit
    ): SourceOffset? {
        val reach = SpotSearch.maxReach(settings.radius, extentOf(dabs, settings.radius))
        val far = ceil(reach + settings.radius).toInt() + 2
        val wanted = PixelRect(bounds.left - far, bounds.top - far, bounds.right + far, bounds.bottom + far)
        val view = LevelView.of(raster, size, wanted, SpotSearch.MAX_EDGE, checkCanceled) ?: return null
        try {
            val pixels = IntArray(view.width * view.height)
            view.bitmap.getPixels(pixels, 0, view.width, view.offsetX, view.offsetY, view.width, view.height)
            val painted = view.footprint(dabs, settings.radius, dilate = 0.5f)
            val cell = 1 shl view.level
            val shift = SpotSearch.find(pixels, painted, view.width, view.height, settings.radius / cell, reach / cell) ?: return null
            return SourceOffset(-shift.dx * cell, -shift.dy * cell)
        } finally {
            view.recycle()
        }
    }

    /** 획의 가장 긴 폭(문서 픽셀): 8방향으로 자국 가운데를 비춘 길이 중 가장 긴 것 + 지름 */
    fun extentOf(dabs: List<StrokePoint>, radius: Float): Float {
        var widest = 0f
        for (direction in 0 until 8) {
            val angle = direction * PI / 8
            val cos = cos(angle).toFloat()
            val sin = sin(angle).toFloat()
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            for (dab in dabs) {
                val projected = dab.x * cos + dab.y * sin
                if (projected < low) low = projected
                if (projected > high) high = projected
            }
            widest = maxOf(widest, high - low)
        }
        return widest + 2f * radius
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
    /** 문서의 [rect] 자리에 가져온 픽셀 [source](같은 크기) + 맞춘 값. GPU가 그리지 못하면 [NotEnoughMemoryException]. */
    fun heal(source: Bitmap, rect: PixelRect): Bitmap {
        val shader = RuntimeShader(HEAL).apply {
            setInputShader("source", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST })
            setInputShader("field", BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            setFloatUniform("areaOrigin", rect.left.toFloat(), rect.top.toFloat())
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
                // 가져올 곳도 같은 단계로 그린다. 옮긴 양을 단계 칸에 맞춰 반올림해 픽셀끼리 짝이 맞는다.
                val shiftX = floor(offset.x / cell.toFloat() + 0.5f).toInt() * cell
                val shiftY = floor(offset.y / cell.toFloat() + 0.5f).toInt() * cell
                val sourceArea = PixelRect(
                    destination.left - shiftX, destination.top - shiftY,
                    destination.left - shiftX + destination.width * cell, destination.top - shiftY + destination.height * cell
                )
                val source = LevelView.at(raster, size, sourceArea, destination.level, checkCanceled) ?: return null
                try {
                    checkCanceled()
                    val width = destination.width
                    val height = destination.height
                    val destinationBytes = destination.bytes
                    val sourceBytes = source.bytes
                    val painted = destination.footprint(dabs, settings.radius, dilate = 1f)
                    val values = FloatArray(width * height * 4)
                    val weights = FloatArray(width * height)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val p = y * width + x
                            if (painted[p]) continue
                            // 가져올 곳이 문서 밖이면 모르는 곳이다
                            val s = source.byteIndexAt(destination.left - shiftX + x * cell, destination.top - shiftY + y * cell)
                            if (s < 0) continue
                            val d = destination.byteIndex(x, y)
                            for (c in 0 until 4) {
                                values[p * 4 + c] = ((destinationBytes[d + c].toInt() and 0xFF) - (sourceBytes[s + c].toInt() and 0xFF)) / 255f
                            }
                            weights[p] = 1f
                        }
                    }
                    checkCanceled()
                    // 둘레 차이 중 다른 것들과 크게 다른 곳(어긋난 경계)은 안쪽으로 번지지 않게 몫을 줄인다(그 자리 값은 그대로)
                    val filled = HoleFill.fill(values, weights, width, height, 4, HoleFill.influence(values, weights, 4))
                    val field = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
                    field.copyPixelsFromBuffer(ShortBuffer.wrap(ShortArray(filled.size) { Half.toHalf(filled[it]) }))
                    return Membrane(field, destination.left, destination.top, cell, offset, size)
                } finally {
                    source.recycle()
                }
            } finally {
                destination.recycle()
            }
        }

        /**
         * 복구 합치기(AGSL): 가져온 픽셀 + 맞춘 값(쌍선형). 가져올 곳이 문서 밖이면 투명(그 자리는 그대로 남는다).
         * 알파는 0~1, 색은 0~알파로 자른다(알파를 곱한 값).
         */
        private const val HEAL = """
            uniform shader source;
            uniform shader field;
            uniform float2 areaOrigin;
            uniform float2 fieldOrigin;
            uniform float fieldScale;
            uniform float2 offset;
            uniform float2 documentSize;

            half4 main(float2 position) {
                float2 document = areaOrigin + position;
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

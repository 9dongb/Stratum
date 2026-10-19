package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.effect.SmudgeMath
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.Selection
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 스머지(손가락) 획 하나. 획을 시작한 곳의 색을 집어 자국마다 끌고 가며 섞는다(포토샵의 손가락 도구).
 * 자국마다 집은 색 = 집은 색 × 남김 + 그 자리 색 × (1 - 남김)이고([SmudgeMath.retention]), 그 자리를 집은 색으로 바꾼다
 * (그 자리 × (1 - 붓 몫) + 집은 색 × 붓 몫, [BrushPainter.drawReplace]). 첫 자국은 집기만 한다.
 *
 * 원본 해상도에 그린다(결정 기록 '원본 픽셀'). 획이 닿는 타일만 바꿀 수 있는 사본(작업 타일)으로 두고, 자국마다 안드로이드 그리기로
 * 집고 내려놓는다(Kotlin으로 픽셀을 돌지 않는다). 칠하지 않은 픽셀은 값이 그대로다.
 * 선택 영역이 있으면 내려놓는 곳만 그 안이다(집는 것은 어디서나). 문서 밖은 가장자리 픽셀을 늘인 것으로 집는다(투명이 끌려 들어오지 않게).
 * 레이어의 투명한 곳은 그대로 집어 끈다(포토샵과 같다). 게인맵(HDR)은 그대로 둔다(선명과 같은 절충).
 * 없는 레이어거나 자국이 둘보다 적거나(끌지 않음) 강도가 0이거나 문서에 닿지 않는 획이면 문서를 그대로 돌려준다.
 */
data class SmudgeStroke(
    val layerId: LayerId,
    val dabs: List<StrokePoint>,
    val settings: BrushSettings,
    val strength: Float
) : CancellableCommand {

    init {
        require(strength in 0f..1f) { "Strength out of range: $strength" }
    }

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0 || dabs.size < 2 || strength == 0f) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val size = PixelSize(document.width, document.height)
        StrokeDabs.bounds(dabs, settings.radius, size) ?: return document
        val smudged = try {
            val surface = TileSurface(raster, size, document.selection)
            try {
                val brush = SmudgeBrush(settings, SmudgeMath.retention(strength, settings.spacing, settings.size))
                try {
                    for (dab in dabs) {
                        checkCanceled()
                        brush.dab(surface, dab.x, dab.y)
                    }
                } finally {
                    brush.recycle()
                }
                surface.finish()
            } finally {
                surface.recycle()
            }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (smudged.isEmpty()) return document
        val layers = document.layers.toMutableList().apply { set(index, layer.copy(content = RasterContent(raster.withTiles(smudged)))) }
        return document.copy(layers = layers)
    }

    /** 획이 닿는 타일마다 새 타일과 작업 사본, 붓의 집은 색·그 자리 네모 */
    override fun estimateNewBytes(document: Document): Long {
        val size = PixelSize(document.width, document.height)
        val bounds = StrokeDabs.bounds(dabs, settings.radius + 1f, size) ?: return 0L
        val tileBytes = TILE_SIZE.toLong() * TILE_SIZE * Int.SIZE_BYTES
        val side = (2 * (ceil(settings.radius).toLong() + 2))
        return BrushTiles.keysIn(bounds).size * tileBytes * 2 + 3 * side * side * Int.SIZE_BYTES
    }
}

/**
 * 스머지 붓이 집고 내려놓는 바탕(바탕 픽셀 좌표). 기록은 문서 타일([TileSurface]), 끄는 동안의 미리 보기는 축소본 한 장([BitmapSurface]).
 */
internal interface SmudgeSurface {
    val width: Int
    val height: Int

    /** 바탕의 [rect](바탕 안) 자리를 [canvas]의 (0, 0)부터 그대로 베낀다. 캔버스는 처음에 투명하다. */
    fun copyInto(canvas: Canvas, rect: PixelRect)

    /**
     * 바탕의 [rect]에 걸친 조각마다 [draw]를 부른다. 캔버스는 그 조각 자리로 옮기고 잘라 두었고, 넘기는 값은 조각의 바탕 자리
     * (왼쪽, 위, 폭, 높이)와 선택 영역 알파(없으면 null)와 그 알파의 (0, 0)이 놓일 캔버스 자리다.
     * 선택 영역에 하나도 들지 않은 조각은 건너뛴다(내려놓지 않는다).
     */
    fun paint(rect: PixelRect, draw: (Canvas, Int, Int, Int, Int, Bitmap?, Float, Float) -> Unit)
}

/**
 * 스머지 붓: 붓 둘레 네모(가운데에서 반지름 + 1px) 크기의 집은 색. 자국마다 그 자리 색과 섞고 내려놓는다.
 * 자국 자리는 가장 가까운 픽셀에 맞춘다(집은 색이 픽셀 단위로 옮겨 가 흐려지지 않게).
 */
internal class SmudgeBrush(private val settings: BrushSettings, private val retention: Float) {

    /** 가운데에서 네모 끝까지(바탕 픽셀) */
    private val half = ceil(settings.radius).toInt() + 1
    private val side = half * 2
    private val load = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private val under = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private var picked = false

    /** 섞을 때 집은 색에 곱하는 몫과 그 자리 색에 곱하는 몫(합이 255라 넘치지 않는다) */
    private val keep = (retention.coerceIn(0f, 1f) * 255).roundToInt()
    private val keepPaint = Paint().apply {
        blendMode = BlendMode.DST_IN
        alpha = keep
    }
    private val mixPaint = Paint().apply {
        blendMode = BlendMode.PLUS
        alpha = 255 - keep
        isFilterBitmap = false
    }

    /** ([x], [y]) 자국(바탕 좌표): 처음이면 그 자리 색을 집기만 하고, 아니면 집은 색을 그 자리 색과 섞은 뒤 내려놓는다 */
    fun dab(surface: SmudgeSurface, x: Float, y: Float) {
        val centerX = x.roundToInt()
        val centerY = y.roundToInt()
        val rect = PixelRect(centerX - half, centerY - half, centerX + half, centerY + half)
        read(surface, rect, under)
        if (!picked) {
            Canvas(load).drawBitmap(under, 0f, 0f, CanvasTileComposer.CopyPaint)
            picked = true
            return
        }
        Canvas(load).apply {
            drawPaint(keepPaint)
            drawBitmap(under, 0f, 0f, mixPaint)
        }
        val dab = listOf(StrokePoint(centerX.toFloat(), centerY.toFloat()))
        surface.paint(rect) { canvas, left, top, width, height, clip, clipLeft, clipTop ->
            // 조각 자리 q의 집은 색은 네모의 (q + 조각 - 네모) 자리다
            val shader = BitmapShader(load, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                setLocalMatrix(Matrix().apply { setTranslate((rect.left - left).toFloat(), (rect.top - top).toFloat()) })
            }
            BrushPainter.drawReplace(
                canvas, dab, settings,
                scale = 1f,
                left = left.toFloat(),
                top = top.toFloat(),
                width = width.toFloat(),
                height = height.toFloat(),
                fill = shader,
                clip = clip,
                clipLeft = clipLeft,
                clipTop = clipTop
            )
        }
    }

    /** 바탕의 [rect] 자리를 [target]에 베낀다. 바탕 밖은 가장자리 픽셀을 늘인다. */
    private fun read(surface: SmudgeSurface, rect: PixelRect, target: Bitmap) {
        target.eraseColor(0)
        val inside = rect.intersect(PixelRect(0, 0, surface.width, surface.height)) ?: return
        if (inside == rect) {
            surface.copyInto(Canvas(target), rect)
            return
        }
        val inner = Bitmap.createBitmap(inside.width, inside.height, Bitmap.Config.ARGB_8888)
        try {
            surface.copyInto(Canvas(inner), inside)
            Canvas(target).drawRect(0f, 0f, target.width.toFloat(), target.height.toFloat(), Paint().apply {
                shader = BitmapShader(inner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate((inside.left - rect.left).toFloat(), (inside.top - rect.top).toFloat()) })
                }
                blendMode = BlendMode.SRC
            })
        } finally {
            inner.recycle()
        }
    }

    fun recycle() {
        load.recycle()
        under.recycle()
    }
}

/**
 * 문서 타일 바탕(기록). 내려놓는 타일만 바꿀 수 있는 사본을 만들고, 다 그리면 바꿀 수 없는 타일로 돌려준다([finish]).
 * 집을 때는 사본이 있으면 사본(앞 자국이 바꾼 것), 없으면 원래 타일을 읽는다.
 */
internal class TileSurface(private val raster: Raster, size: PixelSize, private val selection: Selection?) : SmudgeSurface {

    override val width: Int = size.width
    override val height: Int = size.height

    private val grid = TileGrid(size.width, size.height)

    /** 바꿀 수 있는 사본 */
    private val work = HashMap<TileKey, Bitmap>()

    /** 힙에 담긴 원래 타일을 그리려고 만든 비트맵(자리마다 한 번) */
    private val originals = HashMap<TileKey, Bitmap>()
    private val temporary = ArrayList<Bitmap>()

    private fun bitmapAt(key: TileKey): Bitmap? =
        work[key] ?: originals[key] ?: raster.tiles[key]?.let { tile -> CanvasTileComposer.bitmapOf(tile, temporary).also { originals[key] = it } }

    override fun copyInto(canvas: Canvas, rect: PixelRect) {
        for (row in rect.top / TILE_SIZE..(rect.bottom - 1) / TILE_SIZE) {
            for (column in rect.left / TILE_SIZE..(rect.right - 1) / TILE_SIZE) {
                val bitmap = bitmapAt(TileKey(column, row)) ?: continue
                canvas.drawBitmap(bitmap, (column * TILE_SIZE - rect.left).toFloat(), (row * TILE_SIZE - rect.top).toFloat(), CanvasTileComposer.CopyPaint)
            }
        }
    }

    override fun paint(rect: PixelRect, draw: (Canvas, Int, Int, Int, Int, Bitmap?, Float, Float) -> Unit) {
        val inside = rect.intersect(PixelRect(0, 0, width, height)) ?: return
        for (row in inside.top / TILE_SIZE..(inside.bottom - 1) / TILE_SIZE) {
            for (column in inside.left / TILE_SIZE..(inside.right - 1) / TILE_SIZE) {
                val key = TileKey(column, row)
                // 고르지 않은 자리에는 내려놓지 않는다
                if (selection != null && key !in selection.tiles) continue
                val tile = grid.bounds(key)
                val part = inside.intersect(PixelRect(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)) ?: continue
                val target = work.getOrPut(key) {
                    bitmapAt(key)?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
                }
                val canvas = Canvas(target)
                canvas.translate((part.left - tile.left).toFloat(), (part.top - tile.top).toFloat())
                canvas.clipRect(0f, 0f, part.width.toFloat(), part.height.toFloat())
                val clip = selection?.tiles?.get(key)?.let { MaskTiles.bitmapOf(it, temporary) }
                draw(canvas, part.left, part.top, part.width, part.height, clip, (tile.left - part.left).toFloat(), (tile.top - part.top).toFloat())
            }
        }
    }

    /** 바꾼 타일들(바꿀 수 없는 비트맵 타일) */
    fun finish(): Map<TileKey, Tile?> =
        work.mapValues { (_, bitmap) -> Tile.of(BitmapTilePixels(bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException())) }

    fun recycle() {
        for (bitmap in work.values) bitmap.recycle()
        for (bitmap in temporary) bitmap.recycle()
    }
}

/** 축소본 한 장 바탕(끄는 동안의 미리 보기). [clip]은 같은 크기의 선택 영역 알파다(없으면 null). */
internal class BitmapSurface(private val bitmap: Bitmap, private val clip: Bitmap?) : SmudgeSurface {

    override val width: Int
        get() = bitmap.width

    override val height: Int
        get() = bitmap.height

    override fun copyInto(canvas: Canvas, rect: PixelRect) {
        canvas.drawBitmap(bitmap, -rect.left.toFloat(), -rect.top.toFloat(), CanvasTileComposer.CopyPaint)
    }

    override fun paint(rect: PixelRect, draw: (Canvas, Int, Int, Int, Int, Bitmap?, Float, Float) -> Unit) {
        val part = rect.intersect(PixelRect(0, 0, width, height)) ?: return
        val canvas = Canvas(bitmap)
        canvas.translate(part.left.toFloat(), part.top.toFloat())
        canvas.clipRect(0f, 0f, part.width.toFloat(), part.height.toFloat())
        draw(canvas, part.left, part.top, part.width, part.height, clip, -part.left.toFloat(), -part.top.toFloat())
    }
}

/**
 * 끄는 동안의 스머지 미리 보기. 레이어 축소본을 바꿀 수 있는 사본으로 두고, 새로 놓인 자국만 이어서 집고 내려놓는다
 * (자국마다 앞 자국의 결과에 기대므로 매번 처음부터 다시 하지 않는다). 기록과 같은 붓([SmudgeBrush])을 축소본 크기로 쓰고,
 * 남김은 문서 크기의 간격으로 정해 기록과 같은 거리만큼 끈다. 축소본이 바뀌면(앞 획을 기록함) 처음부터 다시 한다.
 * ViewModel이 획마다 하나 만들고, [LayerPreviewCache]가 그리는 잠금 안에서만 부른다.
 */
class SmudgePreview(private val settings: BrushSettings, private val strength: Float) {

    private var image: Bitmap? = null
    private var region: PreviewRegion? = null
    private var surface: Bitmap? = null
    private var brush: SmudgeBrush? = null
    private var done = 0

    /**
     * [base](문서의 [region]을 그 단계로 그린 레이어 축소본)에 [dabs](문서 좌표)를 집고 내려놓은 그림. [clip]은 [region]에 맞춘 선택 영역 알파다.
     * 돌려준 그림은 다음 부름까지만 쓴다(이어서 바뀐다). 메모리가 모자라면 null.
     */
    internal fun render(base: Bitmap, region: PreviewRegion, dabs: List<StrokePoint>, clip: Bitmap?): Bitmap? {
        if (base !== image || region != this.region || dabs.size < done) reset(base, region) ?: return null
        val target = surface ?: return null
        val current = brush ?: return null
        val scale = 1f / (1 shl region.level)
        val bitmapSurface = BitmapSurface(target, clip)
        while (done < dabs.size) {
            val dab = dabs[done++]
            current.dab(bitmapSurface, (dab.x - region.rect.left) * scale, (dab.y - region.rect.top) * scale)
        }
        return target
    }

    private fun reset(base: Bitmap, region: PreviewRegion): Unit? {
        release()
        val copy = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val scale = 1f / (1 shl region.level)
        val size = (settings.size * scale).coerceIn(BrushSettings.MIN_SIZE, BrushSettings.MAX_SIZE)
        image = base
        this.region = region
        surface = copy
        brush = SmudgeBrush(settings.copy(size = size), SmudgeMath.retention(strength, settings.spacing, settings.size))
        done = 0
        return Unit
    }

    /** 사본과 붓을 놓아 준다 */
    fun release() {
        surface?.recycle()
        brush?.recycle()
        surface = null
        brush = null
        image = null
        region = null
        done = 0
    }
}

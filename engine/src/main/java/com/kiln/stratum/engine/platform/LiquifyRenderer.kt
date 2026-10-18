package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.BlendMode as LayerBlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import java.nio.ShortBuffer
import kotlin.math.ceil

/**
 * [layerId] 레이어에 픽셀 유동화를 적용한다(변위 격자 [mesh]). 픽셀을 다시 계산하는 일이라 원본 해상도에서 GPU로 그린다.
 * 움직인 곳([LiquifyMesh.changedBounds])에 닿는 타일만 다시 그리고, 나머지 타일은 앞 문서와 함께 쓴다.
 * 문서 밖을 읽는 곳은 가장자리 픽셀을 늘인다. 선택 영역과 상관없이 레이어에 건다(포토샵과 같다).
 * [mesh]는 이 명령이 가진 복사본이라 바뀌지 않는다(작업 모드가 [LiquifyMesh.copy]로 넘긴다).
 */
class LiquifyLayer(val layerId: LayerId, val mesh: LiquifyMesh) : CancellableCommand {

    override fun applyTo(document: Document, checkCanceled: () -> Unit): Document {
        val index = document.indexOfLayer(layerId)
        if (index < 0) return document
        val layer = document.layers[index]
        val raster = (layer.content as? RasterContent)?.raster ?: return document
        val size = PixelSize(document.width, document.height)
        if (mesh.size != size || raster.tiles.isEmpty()) return document
        val changed = mesh.changedBounds() ?: return document
        val warped = try {
            LiquifyRenderer.warp(raster, size, mesh, changed, checkCanceled)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        if (warped === raster) return document
        // 게인맵도 같은 변위로 비튼다(결정 기록 'HDR')
        val gainmap = try {
            layer.gainmap?.let { LiquifyRenderer.warpGainmap(it, size, mesh) }
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        val layers = document.layers.toMutableList().apply { set(index, layer.copy(content = RasterContent(warped), gainmap = gainmap)) }
        return document.copy(layers = layers)
    }

    /** 움직인 곳에 닿는 타일마다 새 타일 한 장과, 묶음 하나를 그리는 데 드는 그림 */
    override fun estimateNewBytes(document: Document): Long {
        val changed = mesh.changedBounds() ?: return 0L
        val tiles = ((changed.right - 1) / TILE_SIZE - changed.left / TILE_SIZE + 1).toLong() *
            ((changed.bottom - 1) / TILE_SIZE - changed.top / TILE_SIZE + 1)
        val reach = LiquifyMesh.MAX_DISPLACEMENT.toLong() * 2 + LiquifyRenderer.BLOCK
        return tiles * TILE_SIZE * TILE_SIZE * Int.SIZE_BYTES + reach * reach * Int.SIZE_BYTES * 2
    }
}

/**
 * 픽셀 유동화를 GPU로 그린다(셰이더 프로그램, AGSL). 결과 픽셀은 원래 그림의 '그 자리 + 변위' 픽셀을 이어서(쌍선형) 읽는다.
 * 변위 격자는 반정밀도 그림(RGBA_F16, 꼭짓점 하나가 한 픽셀, r = dx, g = dy 문서 픽셀)으로 올리고 이어서 읽는다.
 * 작업 모드의 미리 보기([preview])와 적용([warp])이 같은 셰이더를 쓴다. GPU 작업을 기다리므로 메인 스레드가 아닌 곳에서 부른다.
 */
object LiquifyRenderer {

    /** 적용할 때 한 번에 그리는 묶음의 한 변(픽셀) */
    internal const val BLOCK = 1024

    /** 변위 격자를 담을 그림(반정밀도). 크기는 꼭짓점 수와 같고, 바꿀 수 있어 미리 보기마다 다시 채운다. */
    fun fieldBitmap(mesh: LiquifyMesh): Bitmap = Bitmap.createBitmap(mesh.columns, mesh.rows, Bitmap.Config.RGBA_F16)

    /**
     * 변위들 [displacements]([LiquifyMesh.copyInto]로 받은 것)를 [field](fieldBitmap으로 만든 것)에 채운다.
     * [halves]는 다시 쓰는 버퍼(꼭짓점 수 × 4)다. 격자를 바꾸는 쪽(메인 스레드)과 나눠, 받은 사본만 읽는다.
     */
    fun fill(field: Bitmap, displacements: FloatArray, halves: ShortArray) {
        val count = field.width * field.height
        require(displacements.size >= count * 2 && halves.size >= count * 4) { "Buffers too small for ${field.width}x${field.height}" }
        val one = Half.toHalf(1f)
        for (i in 0 until count) {
            halves[i * 4] = Half.toHalf(displacements[i * 2])
            halves[i * 4 + 1] = Half.toHalf(displacements[i * 2 + 1])
            halves[i * 4 + 2] = 0
            halves[i * 4 + 3] = one
        }
        field.copyPixelsFromBuffer(ShortBuffer.wrap(halves))
    }

    /**
     * 작업 모드 미리 보기: 레이어 축소본 [image](문서를 1/2^[level]로 그린 것)를 [field](칸 [cellSize] 픽셀)대로 비틀어 그린 하드웨어 비트맵.
     * 크기는 [image]와 같다. GPU가 그리지 못하면 null.
     */
    fun preview(image: Bitmap, level: Int, field: Bitmap, cellSize: Int): Bitmap? {
        val scale = (1 shl level).toFloat()
        val shader = shaderOf(image, field, cellSize, outputLeft = 0f, outputTop = 0f, outputScaleX = scale, outputScaleY = scale, imageLeft = 0f, imageTop = 0f, imageScaleX = scale, imageScaleY = scale)
        return draw(shader, image.width, image.height)
    }

    /** [raster]의 [changed]에 닿는 타일을 [mesh]대로 다시 그린 새 래스터. 그릴 것이 없으면 [raster] 그대로다. */
    internal fun warp(raster: Raster, size: PixelSize, mesh: LiquifyMesh, changed: PixelRect, checkCanceled: () -> Unit): Raster {
        val grid = TileGrid(size.width, size.height)
        val field = fieldBitmap(mesh)
        val tiles = HashMap<TileKey, Tile?>()
        try {
            val displacements = FloatArray(mesh.columns * mesh.rows * 2).also { mesh.copyInto(it) }
            fill(field, displacements, ShortArray(mesh.columns * mesh.rows * 4))
            // 묶음은 타일 격자에 맞춘다(타일로 나눌 때 걸치지 않게)
            val left = changed.left / TILE_SIZE * TILE_SIZE
            val top = changed.top / TILE_SIZE * TILE_SIZE
            for (blockTop in top until changed.bottom step BLOCK) {
                for (blockLeft in left until changed.right step BLOCK) {
                    checkCanceled()
                    val block = PixelRect(blockLeft, blockTop, minOf(blockLeft + BLOCK, size.width), minOf(blockTop + BLOCK, size.height))
                    val reach = ceil(mesh.maxDisplacementIn(block)).toInt() + 2
                    val source = checkNotNull(
                        PixelRect(block.left - reach, block.top - reach, block.right + reach, block.bottom + reach).intersect(PixelRect.of(size))
                    )
                    // 읽을 곳이 모두 투명이면 결과도 투명이다(이 묶음에 타일이 없어 그대로 둔다)
                    if (!raster.hasTilesIn(source)) continue
                    val image = compose(raster, source)
                    val drawn = try {
                        val shader = shaderOf(
                            image, field, mesh.cellSize,
                            outputLeft = block.left.toFloat(), outputTop = block.top.toFloat(), outputScaleX = 1f, outputScaleY = 1f,
                            imageLeft = source.left.toFloat(), imageTop = source.top.toFloat(), imageScaleX = 1f, imageScaleY = 1f
                        )
                        draw(shader, block.width, block.height)?.let { hardware ->
                            try {
                                hardware.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hardware.recycle()
                            }
                        } ?: throw NotEnoughMemoryException()
                    } finally {
                        image.recycle()
                    }
                    try {
                        split(drawn, block, grid, tiles)
                    } finally {
                        drawn.recycle()
                    }
                }
            }
        } finally {
            field.recycle()
        }
        return if (tiles.isEmpty()) raster else raster.withTiles(tiles)
    }

    /**
     * [gainmap]을 [mesh](문서 크기 [size])대로 비튼 새 게인맵(결정 기록 'HDR'). 게인맵은 레이어 전체를 늘여 덮으므로,
     * 게인맵 픽셀 가운데의 문서 좌표에서 변위를 읽어 같은 셰이더로 그린다. 게인맵은 작아 통째로 한 번에 다시 그린다.
     */
    internal fun warpGainmap(gainmap: HdrGainmap, size: PixelSize, mesh: LiquifyMesh): HdrGainmap {
        val whole = PixelRect(0, 0, gainmap.width, gainmap.height)
        val field = fieldBitmap(mesh)
        try {
            fill(field, FloatArray(mesh.columns * mesh.rows * 2).also { mesh.copyInto(it) }, ShortArray(mesh.columns * mesh.rows * 4))
            val image = compose(gainmap.raster, whole)
            val drawn = try {
                // 게인맵 한 칸이 덮는 문서 픽셀(가로·세로가 조금 다를 수 있어 따로 둔다)
                val scaleX = size.width.toFloat() / gainmap.width
                val scaleY = size.height.toFloat() / gainmap.height
                val shader = shaderOf(image, field, mesh.cellSize, 0f, 0f, scaleX, scaleY, 0f, 0f, scaleX, scaleY)
                draw(shader, gainmap.width, gainmap.height)?.let { hardware ->
                    try {
                        hardware.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardware.recycle()
                    }
                } ?: throw NotEnoughMemoryException()
            } finally {
                image.recycle()
            }
            val tiles = HashMap<TileKey, Tile?>()
            try {
                split(drawn, whole, TileGrid(gainmap.width, gainmap.height), tiles)
            } finally {
                drawn.recycle()
            }
            return gainmap.copy(raster = gainmap.raster.withTiles(tiles))
        } finally {
            field.recycle()
        }
    }

    /** [raster]의 [area] 부분을 한 장으로 베낀 소프트웨어 비트맵(값 그대로) */
    private fun compose(raster: Raster, area: PixelRect): Bitmap {
        val picture = Picture()
        val canvas = picture.beginRecording(area.width, area.height)
        val temporary = ArrayList<Bitmap>()
        try {
            for (row in area.top / TILE_SIZE..(area.bottom - 1) / TILE_SIZE) {
                for (column in area.left / TILE_SIZE..(area.right - 1) / TILE_SIZE) {
                    val tile = raster.tiles[TileKey(column, row)] ?: continue
                    val bitmap = CanvasTileComposer.bitmapOf(tile, temporary)
                    canvas.drawBitmap(bitmap, (column * TILE_SIZE - area.left).toFloat(), (row * TILE_SIZE - area.top).toFloat(), CanvasTileComposer.CopyPaint)
                }
            }
            picture.endRecording()
            return Bitmap.createBitmap(picture, area.width, area.height, Bitmap.Config.ARGB_8888)
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /** 묶음 비트맵 [pixels]를 타일로 나눠 [into]에 넣는다. 끝 타일은 문서 안쪽 크기만 담는다. */
    private fun split(pixels: Bitmap, block: PixelRect, grid: TileGrid, into: MutableMap<TileKey, Tile?>) {
        for (row in block.top / TILE_SIZE until (block.bottom + TILE_SIZE - 1) / TILE_SIZE) {
            for (column in block.left / TILE_SIZE until (block.right + TILE_SIZE - 1) / TILE_SIZE) {
                val key = TileKey(column, row)
                val bounds = grid.bounds(key)
                val picture = Picture()
                val canvas = picture.beginRecording(bounds.width, bounds.height)
                canvas.drawBitmap(pixels, (block.left - bounds.left).toFloat(), (block.top - bounds.top).toFloat(), CanvasTileComposer.CopyPaint)
                picture.endRecording()
                into[key] = Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)))
            }
        }
    }

    private fun shaderOf(
        image: Bitmap,
        field: Bitmap,
        cellSize: Int,
        outputLeft: Float,
        outputTop: Float,
        outputScaleX: Float,
        outputScaleY: Float,
        imageLeft: Float,
        imageTop: Float,
        imageScaleX: Float,
        imageScaleY: Float
    ): RuntimeShader =
        RuntimeShader(LIQUIFY).apply {
            setInputShader("image", BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            setInputShader("field", BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            setFloatUniform("outputOrigin", outputLeft, outputTop)
            setFloatUniform("outputScale", outputScaleX, outputScaleY)
            setFloatUniform("imageOrigin", imageLeft, imageTop)
            setFloatUniform("imageScale", imageScaleX, imageScaleY)
            setFloatUniform("cellSize", cellSize.toFloat())
        }

    private fun draw(shader: RuntimeShader, width: Int, height: Int): Bitmap? {
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            this.shader = shader
            blendMode = BlendMode.SRC
        })
        picture.endRecording()
        return PreviewRenderer.drawOnGpu(picture, width, height)
    }

    /**
     * 유동화 셰이더. 출력 픽셀 가운데의 문서 좌표에서 변위를 읽고(꼭짓점 i는 텍셀 i + 0.5), 원래 그림의 그 자리를 읽는다.
     * 원래 그림은 알파를 미리 곱한 값이라 쌍선형으로 이어 읽어도 가장자리가 어두워지지 않는다.
     */
    private const val LIQUIFY = """
        uniform shader image;
        uniform shader field;
        uniform float2 outputOrigin;
        uniform float2 outputScale;
        uniform float2 imageOrigin;
        uniform float2 imageScale;
        uniform float cellSize;

        half4 main(float2 position) {
            float2 document = outputOrigin + position * outputScale;
            float2 move = float2(field.eval(document / cellSize + 0.5).rg);
            float2 source = (document + move - imageOrigin) / imageScale;
            return image.eval(source);
        }
    """

    /** 미리 보기용 레이어 축소본: [document]에서 [layerId] 레이어만, 불투명도·혼합 모드 없이(보정은 건 채) [level] 단계로 */
    fun layerPreview(document: Document, layerId: LayerId, level: Int): Bitmap? {
        val layer = document.layers.getOrNull(document.indexOfLayer(layerId)) ?: return null
        val alone = document.copy(layers = listOf(layer.copy(opacity = 1f, blendMode = LayerBlendMode.NORMAL)), selection = null)
        return PreviewRenderer.render(alone, level)
    }
}

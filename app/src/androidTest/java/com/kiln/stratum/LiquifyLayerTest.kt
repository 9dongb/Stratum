package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.LiquifyLayer
import com.kiln.stratum.engine.platform.LiquifyRenderer
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 픽셀 유동화를 원본 해상도와 미리 보기에서 GPU로 그리는지 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class LiquifyLayerTest {

    /** 흰 바탕 1200×800 가운데에 검은 세로 줄(x 595..605) */
    private fun document(): Document {
        val white = checkNotNull(BlankCanvas.create(1200, 800, CanvasFill.Solid(Color.WHITE), "Background"))
        val line = StrokeDabs.place(listOf(StrokePoint(600f, 50f), StrokePoint(600f, 750f)), 1f)
        return PaintStroke(LayerId(0), line, BrushSettings(size = 10f, hardness = 1f, color = Color.BLACK), false).applyTo(white)
    }

    private fun rasterOf(document: Document): Raster = (document.layers[0].content as RasterContent).raster

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun dark(color: Int) = Color.red(color) < 100

    @Test
    fun push_movesTheLine_andLeavesFarTilesUntouched() {
        val document = document()
        val mesh = LiquifyMesh.identity(PixelSize(1200, 800))
        // 줄 가운데를 오른쪽으로 40픽셀 민다(작은 걸음으로)
        repeat(8) { step -> mesh.brush(LiquifyTool.PUSH, 600f + step * 5f, 400f, radius = 120f, strength = 1f, moveX = 5f, moveY = 0f) }

        val warped = LiquifyLayer(LayerId(0), mesh).applyTo(document)

        val before = rasterOf(document)
        val after = rasterOf(warped)
        // 가운데 높이에서 줄이 오른쪽으로 옮겨졌다
        assertTrue(dark(pixel(before, 600, 400)))
        assertTrue("moved line should be right of 620", (620..660).any { dark(pixel(after, it, 400)) })
        assertTrue(!dark(pixel(after, 600, 400)))
        // 브러시에서 먼 곳(위쪽 끝)은 그대로이고, 먼 타일은 같은 객체다
        assertTrue(dark(pixel(after, 600, 60)))
        assertSame(before.tiles[TileKey(0, 0)], after.tiles[TileKey(0, 0)])
    }

    @Test
    fun identityMesh_changesNothing() {
        val document = document()

        assertSame(document, LiquifyLayer(LayerId(0), LiquifyMesh.identity(PixelSize(1200, 800))).applyTo(document))
    }

    @Test
    fun preview_matchesTheMeshAtPreviewScale() {
        val document = document()
        val mesh = LiquifyMesh.identity(PixelSize(1200, 800))
        repeat(8) { step -> mesh.brush(LiquifyTool.PUSH, 600f + step * 5f, 400f, radius = 120f, strength = 1f, moveX = 5f, moveY = 0f) }
        val image = checkNotNull(LiquifyRenderer.layerPreview(document, LayerId(0), level = 1))
        val field = LiquifyRenderer.fieldBitmap(mesh)
        val displacements = FloatArray(mesh.columns * mesh.rows * 2).also { mesh.copyInto(it) }
        LiquifyRenderer.fill(field, displacements, ShortArray(mesh.columns * mesh.rows * 4))

        val preview = checkNotNull(LiquifyRenderer.preview(image, 1, field, mesh.cellSize)).copy(Bitmap.Config.ARGB_8888, false)

        assertEquals(600, preview.width)
        // 1/2 단계에서 옮겨진 줄은 x 310..330 근처
        assertTrue((310..330).any { dark(preview.getPixel(it, 200)) })
        assertTrue(!dark(preview.getPixel(300, 200)))
    }

    @Test
    fun bendAcrossABlockEdge_hasNoSeam() {
        // 가로 줄(y 295..305)을 묶음(1024px) 경계에서 아래로 굽힌다. 경계 양쪽 픽셀 열에서 줄 높이가 같아야 한다.
        val white = checkNotNull(BlankCanvas.create(1200, 800, CanvasFill.Solid(Color.WHITE), "Background"))
        val line = StrokeDabs.place(listOf(StrokePoint(0f, 300f), StrokePoint(1200f, 300f)), 1f)
        val document = PaintStroke(LayerId(0), line, BrushSettings(size = 10f, hardness = 1f, color = Color.BLACK), false).applyTo(white)
        val mesh = LiquifyMesh.identity(PixelSize(1200, 800))
        repeat(6) { step -> mesh.brush(LiquifyTool.PUSH, 1024f, 300f + step * 5f, radius = 200f, strength = 1f, moveX = 0f, moveY = 5f) }

        val after = rasterOf(LiquifyLayer(LayerId(0), mesh).applyTo(document))

        fun lineTop(x: Int): Int = (0 until 800).first { dark(pixel(after, x, it)) }
        assertTrue("bent ${lineTop(1024)}", lineTop(1024) > 300)
        assertTrue(abs(lineTop(1023) - lineTop(1024)) <= 1)
    }
}

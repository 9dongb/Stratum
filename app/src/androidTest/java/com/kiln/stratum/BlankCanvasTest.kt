package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 엔진의 빈 새 캔버스 만들기와, 그것을 화면용 축소본으로 그린 결과를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class BlankCanvasTest {

    private fun create(width: Int, height: Int, fill: CanvasFill): Document =
        checkNotNull(BlankCanvas.create(width, height, fill, "Background"))

    private fun Document.raster(): Raster = (layers.single().content as RasterContent).raster

    /** GPU 비트맵의 모든 픽셀을 읽는다. */
    private fun Bitmap.allPixels(): IntArray {
        val readable = checkNotNull(copy(Bitmap.Config.ARGB_8888, false))
        return IntArray(readable.width * readable.height).also {
            readable.getPixels(it, 0, readable.width, 0, 0, readable.width, readable.height)
        }
    }

    /** [document]를 [level] 단계로 그린 축소본의 모든 픽셀이 [expected]인지. 끝 줄·끝 칸까지 본다. */
    private fun assertPreviewIsAll(document: Document, level: Int, expected: Int) {
        val preview = checkNotNull(PreviewRenderer.render(document, level))
        val size = PreviewLevels.previewSize(PixelSize(document.width, document.height), level)
        assertEquals(size.width, preview.width)
        assertEquals(size.height, preview.height)
        val pixels = preview.allPixels()
        for (index in pixels.indices) {
            if (pixels[index] != expected) {
                val x = index % preview.width
                val y = index / preview.width
                fail(
                    "${document.width}x${document.height} level $level: pixel ($x, $y) of " +
                        "${preview.width}x${preview.height} was ${Integer.toHexString(pixels[index])}, " +
                        "expected ${Integer.toHexString(expected)}"
                )
            }
        }
    }

    @Test
    fun solid_sharesOneTilePerShape_andEdgeTilesHoldOnlyTheCanvas() {
        val document = create(1001, 1001, CanvasFill.Solid(Color.WHITE))

        val tiles = document.raster().tiles
        val grid = TileGrid(1001, 1001)
        assertEquals(grid.keys().toSet(), tiles.keys)
        val first = checkNotNull(tiles[TileKey(0, 0)])
        assertEquals(Color.WHITE, first.pixelAt(0, 0))
        assertEquals(Color.WHITE, first.pixelAt(TILE_SIZE - 1, TILE_SIZE - 1))
        // 안쪽 자리는 같은 타일, 끝 자리는 캔버스 안쪽(233px)만 담은 타일을 크기마다 한 장 함께 쓴다(결정 기록 '캔버스 밖 픽셀')
        for ((key, tile) in tiles) {
            assertEquals(grid.solidShape(key), PixelSize(tile.width, tile.height))
            if (grid.solidShape(key) == PixelSize(TILE_SIZE, TILE_SIZE)) assertSame(first, tile)
        }
        assertEquals(4, tiles.values.toSet().size)
        val corner = checkNotNull(tiles[TileKey(3, 3)])
        assertEquals(Color.WHITE, corner.pixelAt(232, 232))
        assertEquals(Color.TRANSPARENT, corner.pixelAt(233, 0))
        assertEquals("Background", document.layers.single().name)
    }

    @Test
    fun transparent_hasNoTiles() {
        val document = create(1001, 1001, CanvasFill.Transparent)

        assertSame(Raster.EMPTY, document.raster())
    }

    @Test
    fun render_solidCanvasIsOpaqueToTheLastRowAndColumn() {
        // 홀수 크기는 반씩 줄일 때 끝 픽셀이 반만 덮일 수 있다. 2601px는 8×8 묶음 경계(2048px)도 지난다.
        for (color in listOf(Color.WHITE, Color.BLACK)) {
            for ((width, height) in listOf(1001 to 1001, 2601 to 521)) {
                val document = create(width, height, CanvasFill.Solid(color))
                for (level in 0..3) assertPreviewIsAll(document, level, color)
            }
        }
    }

    @Test
    fun render_transparentCanvasIsFullyTransparent() {
        val document = create(1001, 521, CanvasFill.Transparent)

        for (level in 0..2) assertPreviewIsAll(document, level, Color.TRANSPARENT)
    }
}

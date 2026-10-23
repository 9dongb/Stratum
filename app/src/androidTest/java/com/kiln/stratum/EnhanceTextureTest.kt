package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintModel
import com.kiln.stratum.engine.platform.EnhanceTexture
import com.kiln.stratum.engine.inpaint.InpaintTexture
import com.kiln.stratum.engine.platform.InpaintRegion
import com.kiln.stratum.engine.platform.NothingToEnhanceException
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 질감 향상 명령(EnhanceTexture)을 가짜 모델로 에뮬레이터에서 확인한다(결정 기록 'AI 제거·AI 선택'의 '질감 향상'). */
@RunWith(AndroidJUnit4::class)
class EnhanceTextureTest {

    private fun gray(level: Int) = Color.rgb(level, level, level)

    /** [width]×[height] 문서. 픽셀마다 [color]로 칠한 레이어 하나다(힙 타일). */
    private fun documentOf(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Document {
        val grid = TileGrid(width, height)
        val tiles = grid.keys().associateWith { key ->
            Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i -> color(key.column * TILE_SIZE + i % TILE_SIZE, key.row * TILE_SIZE + i / TILE_SIZE) })
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    private fun rasterOf(document: Document): Raster = (document.layers[0].content as RasterContent).raster

    private fun pixel(document: Document, x: Int, y: Int): Int =
        rasterOf(document).tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun painted(x: Float, y: Float, radius: Float) = InpaintArea.Painted(listOf(StrokePoint(x, y)), radius)

    /**
     * 1px 체크 무늬(회색 100·150) 바탕 1600×1200에 빨간 원(반지름 280). 가짜 모델은 한 가지 색으로 채우고,
     * 반지름 300으로 칠하면 창 한 변이 약 900px(모델 1px이 문서 1.76px)라 결을 붙인다.
     */
    private fun checkerWithRedDisc(): Document =
        documentOf(1600, 1200) { x, y ->
            when {
                hypot(x + 0.5f - 800f, y + 0.5f - 600f) <= 280f -> Color.RED
                (x + y) % 2 == 0 -> gray(100)
                else -> gray(150)
            }
        }

    private val bigStroke = painted(800f, 600f, radius = 300f)

    /** ([x0], [y0])부터 [size]² 픽셀에서 가로로 이웃한 두 픽셀의 초록 값 차이의 평균(고운 결의 세기) */
    private fun fineDetail(document: Document, x0: Int, y0: Int, size: Int): Double {
        var sum = 0.0
        for (y in y0 until y0 + size) {
            for (x in x0 until x0 + size) sum += abs(Color.green(pixel(document, x + 1, y)) - Color.green(pixel(document, x, y)))
        }
        return sum / (size * size)
    }

    /** [fineDetail]과 같고 세로로 이웃한 두 픽셀이다 */
    private fun fineDetailDown(document: Document, x0: Int, y0: Int, size: Int): Double {
        var sum = 0.0
        for (y in y0 until y0 + size) {
            for (x in x0 until x0 + size) sum += abs(Color.green(pixel(document, x, y + 1)) - Color.green(pixel(document, x, y)))
        }
        return sum / (size * size)
    }

    /** 창의 왼쪽 반·오른쪽 반의 모르는 곳을 그쪽 아는 곳의 평균색으로 따로 채우는 가짜 모델(입력 1×4×size², 출력 1×3×size²) */
    private class SideFillModel(override val size: Int = 512) : InpaintModel {
        override fun fill(input: FloatArray): FloatArray {
            val plane = size * size
            val sums = Array(2) { DoubleArray(3) }
            val counts = IntArray(2)
            for (p in 0 until plane) {
                if (input[p] <= 0f) continue
                val side = if (p % size < size / 2) 0 else 1
                counts[side]++
                for (c in 0 until 3) sums[side][c] += input[(c + 1) * plane + p]
            }
            return FloatArray(InpaintModel.OUTPUT_CHANNELS * plane) { i ->
                val c = i / plane
                val p = i % plane
                val side = if (p % size < size / 2) 0 else 1
                if (input[p] > 0f) input[(c + 1) * plane + p] else (sums[side][c] / counts[side].coerceAtLeast(1)).toFloat()
            }
        }
    }

    @Test
    fun enhance_bringsTheFineTextureBack_whereTheFillOnlyHasNoise() {
        val document = checkerWithRedDisc()
        val filled = InpaintRegion(LayerId(0), bigStroke, FakeInpaintModel()).applyTo(document)

        val enhanced = EnhanceTexture(LayerId(0), bigStroke, FakeInpaintModel(), rasterOf(document)).applyTo(filled)

        // 채우기는 평평한 색에 잡음(밝기 10 이하)만 있다. 질감 향상은 둘레의 1px 체크 무늬를 옮겨 와 이웃 차이가 크다.
        val plain = fineDetail(filled, 740, 540, 120)
        val textured = fineDetail(enhanced, 740, 540, 120)
        assertTrue("plain $plain", plain < 15.0)
        assertTrue("textured $textured, plain $plain", textured > 18.0 && textured > 2 * plain)
        // 색은 채운 색 그대로다(빨간 물체가 돌아오지 않는다)
        val center = pixel(enhanced, 800, 600)
        assertTrue(Integer.toHexString(center), abs(Color.red(center) - Color.green(center)) < 40)
    }

    @Test
    fun enhance_leavesEveryPixelOutsideTheFillExactly_andIsTheSameEveryTime() {
        val document = checkerWithRedDisc()
        val filled = InpaintRegion(LayerId(0), bigStroke, FakeInpaintModel()).applyTo(document)

        val once = EnhanceTexture(LayerId(0), bigStroke, FakeInpaintModel(), rasterOf(document)).applyTo(filled)
        val twice = EnhanceTexture(LayerId(0), bigStroke, FakeInpaintModel(), rasterOf(document)).applyTo(filled)

        for (y in 0 until 1200 step 5) {
            for (x in 0 until 1600 step 5) {
                if (hypot(x + 0.5f - 800f, y + 0.5f - 600f) > 340f) assertEquals("($x, $y)", pixel(document, x, y), pixel(once, x, y))
                assertEquals("($x, $y)", pixel(once, x, y), pixel(twice, x, y))
            }
        }
        // 닿지 않은 타일은 원래 문서와 함께 쓴다
        assertSame(rasterOf(document).tiles[TileKey(0, 0)], rasterOf(once).tiles[TileKey(0, 0)])
    }

    @Test
    fun enhance_staysInsideTheSelection() {
        // 왼쪽 절반만 고른다
        val selected = SelectShape(SelectionShape.Rectangle(0f, 0f, 800f, 1200f), SelectionMode.REPLACE).applyTo(checkerWithRedDisc())
        val filled = InpaintRegion(LayerId(0), bigStroke, FakeInpaintModel()).applyTo(selected)

        val enhanced = EnhanceTexture(LayerId(0), bigStroke, FakeInpaintModel(), rasterOf(selected)).applyTo(filled)

        assertEquals(Color.RED, pixel(enhanced, 810, 600))
        assertEquals(Color.RED, pixel(enhanced, 900, 700))
        assertTrue(fineDetail(enhanced, 640, 540, 120) > 18.0)
        assertSame(selected.selection, enhanced.selection)
    }

    @Test
    fun aSmallFill_hasNothingToEnhance_andDoesNotRunTheModel() {
        // 800×600의 작은 점: 창이 512px라 채운 그림이 원본 해상도 그대로다
        val document = documentOf(800, 600) { x, y -> if (hypot(x + 0.5f - 400f, y + 0.5f - 300f) <= 20f) Color.RED else gray(128) }
        val area = painted(400f, 300f, radius = 30f)
        val filled = InpaintRegion(LayerId(0), area, FakeInpaintModel()).applyTo(document)
        val model = FakeInpaintModel()
        val enhance = EnhanceTexture(LayerId(0), area, model, rasterOf(document))

        val nothing = assertThrows(NothingToEnhanceException::class.java) { enhance.applyTo(filled) }
        assertEquals(NothingToEnhanceException.Reason.SMALL, nothing.reason)
        assertEquals(0, model.calls)
        // 해 볼 것이 없으니 메모리를 비우라고 하지 않는다(실행 취소 단계를 버리지 않게)
        assertEquals(0L, enhance.estimateNewBytes(filled))
        assertEquals(0L, enhance.estimateWorkBytes(filled))
    }

    @Test
    fun withoutOpaqueGroundAround_thereIsNoTextureToBring() {
        // 반투명한 바탕에서는 가져올 조각이 없다
        val document = documentOf(1600, 1200) { x, y ->
            if (hypot(x + 0.5f - 800f, y + 0.5f - 600f) <= 280f) Color.RED else Color.argb(128, 100, 100, 100)
        }
        val filled = InpaintRegion(LayerId(0), bigStroke, FakeInpaintModel()).applyTo(document)

        val nothing = assertThrows(NothingToEnhanceException::class.java) {
            EnhanceTexture(LayerId(0), bigStroke, FakeInpaintModel(), rasterOf(document)).applyTo(filled)
        }
        assertEquals(NothingToEnhanceException.Reason.NO_SOURCE, nothing.reason)
    }

    /**
     * 왼쪽 반은 세로 줄무늬(가로로 바뀜), 오른쪽 반은 가로 줄무늬(세로로 바뀜)이고 가운데에 빨간 원. 채운 곳의 왼쪽은 왼쪽 결을,
     * 오른쪽은 오른쪽 결을 옮겨 와야 한다(짝 표·조각 판의 자리가 틀리면 다른 쪽이나 엉뚱한 조각의 결이 섞인다).
     * 가짜 모델은 창의 왼쪽·오른쪽 반을 그쪽의 아는 곳 평균색으로 채워, 짝 찾기가 같은 쪽 조각을 고르게 한다.
     */
    @Test
    fun eachSideOfTheFill_bringsTheTextureOfItsOwnSide() {
        val document = documentOf(1600, 1200) { x, y ->
            when {
                hypot(x + 0.5f - 800f, y + 0.5f - 600f) <= 280f -> Color.RED
                x < 800 -> gray(if (x % 2 == 0) 70 else 130)
                else -> gray(if (y % 2 == 0) 150 else 210)
            }
        }
        val model = SideFillModel()
        val filled = InpaintRegion(LayerId(0), bigStroke, model).applyTo(document)

        val enhanced = EnhanceTexture(LayerId(0), bigStroke, model, rasterOf(document)).applyTo(filled)

        // 왼쪽(채운 곳 안, 가운데 줄에서 150px): 가로로 이웃한 픽셀이 크게 다르고 세로로는 거의 같다
        val leftAcross = fineDetail(enhanced, 620, 570, 60)
        val leftDown = fineDetailDown(enhanced, 620, 570, 60)
        assertTrue("left across $leftAcross, down $leftDown", leftAcross > 10.0 && leftAcross > 3 * leftDown)
        // 오른쪽: 그 반대
        val rightAcross = fineDetail(enhanced, 920, 570, 60)
        val rightDown = fineDetailDown(enhanced, 920, 570, 60)
        assertTrue("right across $rightAcross, down $rightDown", rightDown > 10.0 && rightDown > 3 * rightAcross)
    }

    @Test
    fun aSmallFillDoesNotAskForMemory_butABigOneDoes() {
        val document = checkerWithRedDisc()
        val model = FakeInpaintModel()
        val enhance = EnhanceTexture(LayerId(0), bigStroke, model, rasterOf(document))

        assertTrue(enhance.estimateNewBytes(document) > 0L)
        assertTrue(enhance.estimateWorkBytes(document) > 0L)
    }

    @Test
    fun aMissingLayerOrAnEmptyOriginal_isNoChange() {
        val document = checkerWithRedDisc()

        assertSame(document, EnhanceTexture(LayerId(7), bigStroke, FakeInpaintModel(), rasterOf(document)).applyTo(document))
        assertSame(document, EnhanceTexture(LayerId(0), bigStroke, FakeInpaintModel(), Raster.EMPTY).applyTo(document))
    }

    @Test
    fun estimates_countTheFillsTiles_andTheTextureWork() {
        val document = checkerWithRedDisc()
        val model = FakeInpaintModel(pendingLoadBytes = 1000L)
        val enhance = EnhanceTexture(LayerId(0), bigStroke, model, rasterOf(document))

        val fill = InpaintRegion(LayerId(0), bigStroke, model)
        assertEquals(fill.estimateNewBytes(document), enhance.estimateNewBytes(document))
        // 채우기의 계산 메모리(모델 불러오기 포함)에 조각 판과 그 GPU 사본을 더한다
        assertEquals(fill.estimateWorkBytes(document) + 2 * InpaintTexture.ATLAS_BYTES, enhance.estimateWorkBytes(document))
    }
}

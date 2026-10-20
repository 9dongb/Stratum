package com.kiln.stratum

import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintUnavailableException
import com.kiln.stratum.engine.platform.InpaintRegion
import com.kiln.stratum.engine.platform.LiteRtInpaintModel
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진짜 MI-GAN 모델로 AI 제거 명령이 처음부터 끝까지 도는지 본다(결정 기록 'AI 제거·AI 선택').
 * 그 기기에서 모델을 돌리지 못하면 건너뛴다. 걸린 시간은 logcat의 "InpaintProbe"로 본다.
 */
@RunWith(AndroidJUnit4::class)
class InpaintRealModelTest {

    /** 가로로 밝아지는 회색(60 → 200) 800×600에 빨간 원 */
    private fun gradientWithRedSpot(): Document {
        val width = 800
        val height = 600
        val tiles = TileGrid(width, height).keys().associateWith { key ->
            Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i ->
                val x = key.column * TILE_SIZE + i % TILE_SIZE
                val y = key.row * TILE_SIZE + i / TILE_SIZE
                if (hypot(x + 0.5f - 400f, y + 0.5f - 300f) <= 25f) Color.RED else gray(x)
            })
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    private fun gray(x: Int): Int {
        val level = 60 + x * 140 / 800
        return Color.rgb(level, level, level)
    }

    private fun pixel(document: Document, x: Int, y: Int): Int =
        (document.layers[0].content as RasterContent).raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    @Test
    fun theRealModel_removesARedSpotFromAGradient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LiteRtInpaintModel(context.assets, context.cacheDir)
        val document = gradientWithRedSpot()
        try {
            val started = SystemClock.elapsedRealtime()
            val removed = try {
                InpaintRegion(LayerId(0), InpaintArea.Painted(listOf(StrokePoint(400f, 300f)), 35f), model).applyTo(document)
            } catch (e: InpaintUnavailableException) {
                assumeNoException("The model cannot run on this device", e)
                return
            }
            Log.i("InpaintProbe", "InpaintRegion with the real model on ${model.activeBackend}: ${SystemClock.elapsedRealtime() - started} ms")

            // 빨간 원 자리가 그 자리 그러데이션 밝기 근처의 회색이 된다
            for ((x, y) in listOf(400 to 300, 385 to 300, 415 to 300, 400 to 280)) {
                val color = pixel(removed, x, y)
                val expected = Color.red(gray(x))
                assertTrue(
                    "($x, $y): ${Integer.toHexString(color)}, expected about $expected",
                    abs(Color.red(color) - expected) <= 25 && abs(Color.green(color) - expected) <= 25 && abs(Color.blue(color) - expected) <= 25
                )
            }
            // 넓힌 띠 밖은 그대로다
            for ((x, y) in listOf(300 to 300, 500 to 300, 400 to 200, 400 to 400)) assertEquals(pixel(document, x, y), pixel(removed, x, y))
        } finally {
            model.release()
        }
    }
}

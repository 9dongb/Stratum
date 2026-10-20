package com.kiln.stratum

import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.platform.LiteRtSegmentModel
import com.kiln.stratum.engine.platform.ObjectSegmenter
import com.kiln.stratum.engine.platform.SelectObject
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.segment.SegmentUnavailableException
import com.kiln.stratum.engine.selection.SelectionMode
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진짜 SAM 2.1 Tiny로 AI 선택 명령이 처음부터 끝까지 도는지 본다(결정 기록 'AI 제거·AI 선택').
 * 그 기기에서 모델을 돌리지 못하면 건너뛴다. 걸린 시간은 logcat의 "SegmentProbe"(불러오기는 "LiteRtSegment")로 본다.
 */
@RunWith(AndroidJUnit4::class)
class SegmentRealModelTest {

    /** 밝은 그러데이션(170 → 230) 1200×800에 어두운 원((500, 420), 반지름 150) */
    private fun darkDiscOnAGradient(): Document {
        val width = 1200
        val height = 800
        val tiles = TileGrid(width, height).keys().associateWith { key ->
            Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i ->
                val x = key.column * TILE_SIZE + i % TILE_SIZE
                val y = key.row * TILE_SIZE + i / TILE_SIZE
                if (inDisc(x + 0.5f, y + 0.5f)) Color.rgb(50, 45, 40) else gray(170 + x * 60 / width)
            })
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    private fun inDisc(x: Float, y: Float) = hypot(x - 500f, y - 420f) <= 150f

    private fun gray(level: Int) = Color.rgb(level, level, level)

    @Test
    fun theRealModel_selectsADarkDiscOnALightGradient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LiteRtSegmentModel(context.assets, context.cacheDir)
        val segmenter = ObjectSegmenter(model)
        val document = darkDiscOnAGradient()
        try {
            val started = SystemClock.elapsedRealtime()
            val selected = try {
                SelectObject(500f, 420f, SelectionMode.REPLACE, segmenter).applyTo(document)
            } catch (e: SegmentUnavailableException) {
                assumeNoException("The model cannot run on this device", e)
                return
            }
            val first = SystemClock.elapsedRealtime() - started
            // 같은 그림에서 다시 누르면 읽기 없이 찾기만 한다
            val again = SystemClock.elapsedRealtime()
            SelectObject(300f, 420f, SelectionMode.REPLACE, segmenter).applyTo(document)
            val tap = SystemClock.elapsedRealtime() - again
            Log.i("SegmentProbe", "SelectObject with the real model on ${model.activeBackend}: first $first ms (load + read + tap), next tap $tap ms")

            val selection = checkNotNull(selected.selection)
            assertEquals(255, selection.alphaAt(500, 420))
            assertEquals(255, selection.alphaAt(500 + 130, 420))
            assertEquals(0, selection.alphaAt(500 + 175, 420))
            assertEquals(0, selection.alphaAt(1150, 50))
            // 원과 거의 같은 모양이다(8px 간격으로 견줌)
            var both = 0
            var either = 0
            for (y in 4 until 800 step 8) {
                for (x in 4 until 1200 step 8) {
                    val chosen = selection.alphaAt(x, y) >= 128
                    val truth = inDisc(x + 0.5f, y + 0.5f)
                    if (chosen && truth) both++
                    if (chosen || truth) either++
                }
            }
            val overlap = both.toDouble() / either
            Log.i("SegmentProbe", "Disc overlap (IoU) ${"%.3f".format(overlap)}")
            assertTrue("IoU $overlap", overlap > 0.9)
        } finally {
            segmenter.release()
        }
    }
}

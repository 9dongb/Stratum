package com.kiln.stratum

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.inpaint.InpaintModel
import com.kiln.stratum.engine.inpaint.InpaintUnavailableException
import com.kiln.stratum.engine.platform.LiteRtInpaintModel
import com.kiln.stratum.engine.platform.LiteRtInpaintModel.Backend
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MI-GAN 채우기 모델이 이 기기의 GPU·CPU에서 도는지 보고, 걸린 시간과 메모리를 남긴다(AI 제거 1단계 실측).
 * 그 장치로 모델을 돌리지 못하면(에뮬레이터의 GPU 등) 실패가 아니라 건너뛴다.
 * 잰 값은 logcat의 "InpaintProbe" 줄로 본다(불러오기 시간은 "LiteRtInpaint" 줄).
 */
@RunWith(AndroidJUnit4::class)
class InpaintModelProbeTest {

    @Test
    fun gpu_fillsAHoleInAGradient() = probe(Backend.GPU)

    @Test
    fun cpu_fillsAHoleInAGradient() = probe(Backend.CPU)

    private fun probe(backend: Backend) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LiteRtInpaintModel(context.assets, context.cacheDir, listOf(backend))
        try {
            val input = gradientWithHole()
            val before = Memory.now()
            val started = SystemClock.elapsedRealtime()
            val first = try {
                model.fill(input)
            } catch (e: InpaintUnavailableException) {
                Log.i(TAG, "$backend cannot run the model on this device: ${e.cause}")
                assumeNoException("$backend cannot run the model on this device", e)
                return
            }
            val cold = SystemClock.elapsedRealtime() - started
            val loaded = Memory.now()

            assertEquals(InpaintModel.OUTPUT_CHANNELS * SIZE * SIZE, first.size)
            assertTrue("Every value is a number", first.all { it.isFinite() })
            val error = holeError(first)
            assertTrue("The hole follows the gradient around it (error $error)", error < MAX_HOLE_ERROR)

            val times = LongArray(RUNS) {
                val runStarted = SystemClock.elapsedRealtimeNanos()
                model.fill(input)
                (SystemClock.elapsedRealtimeNanos() - runStarted) / 1_000_000
            }
            val after = Memory.now()
            times.sort()
            Log.i(
                TAG,
                "$backend: cold ${cold} ms (load + first fill), median ${times[RUNS / 2]} ms, " +
                    "min ${times.first()} ms, max ${times.last()} ms over $RUNS fills; " +
                    "PSS +${(loaded.pssKb - before.pssKb) / 1024} MB after load, +${(after.pssKb - before.pssKb) / 1024} MB after runs; " +
                    "graphics +${(after.graphicsKb - before.graphicsKb) / 1024} MB; native heap +${(after.nativeKb - before.nativeKb) / 1024} MB; " +
                    "hole error ${"%.3f".format(error)}"
            )
        } finally {
            model.release()
        }
    }

    /** 가로로 -0.6..0.6인 회색 그러데이션, 가운데 [HOLE] 네모가 구멍 */
    private fun gradientWithHole(): FloatArray {
        val plane = SIZE * SIZE
        val input = FloatArray(InpaintModel.INPUT_CHANNELS * plane)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val i = y * SIZE + x
                val keep = if (x in HOLE && y in HOLE) 0f else 1f
                val value = expected(x) * keep
                input[i] = keep - 0.5f
                input[plane + i] = value
                input[2 * plane + i] = value
                input[3 * plane + i] = value
            }
        }
        return input
    }

    /** 구멍 안 출력이 그 자리 그러데이션 값과 평균 얼마나 다른지(-1..1 눈금) */
    private fun holeError(output: FloatArray): Float {
        val plane = SIZE * SIZE
        var sum = 0.0
        var count = 0
        for (y in HOLE) {
            for (x in HOLE) {
                for (c in 0 until InpaintModel.OUTPUT_CHANNELS) {
                    sum += abs(output[c * plane + y * SIZE + x] - expected(x))
                    count++
                }
            }
        }
        return (sum / count).toFloat()
    }

    private fun expected(x: Int) = -0.6f + 1.2f * x / (SIZE - 1)

    /** 앱 프로세스의 메모리(KB) */
    private data class Memory(val pssKb: Int, val graphicsKb: Int, val nativeKb: Int) {
        companion object {
            fun now(): Memory {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                fun stat(name: String) = info.getMemoryStat(name)?.toIntOrNull() ?: 0
                return Memory(info.totalPss, stat("summary.graphics"), stat("summary.native-heap"))
            }
        }
    }

    private companion object {
        const val TAG = "InpaintProbe"
        const val SIZE = LiteRtInpaintModel.MODEL_SIZE
        val HOLE = 192 until 320
        const val RUNS = 20

        /** 매끈한 그러데이션이면 채운 값이 둘레를 따라가야 한다. 결이 조금 생겨도 되게 넉넉히 둔다. */
        const val MAX_HOLE_ERROR = 0.25f
    }
}

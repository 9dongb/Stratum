package com.kiln.stratum

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.platform.LiteRtSkinModel
import com.kiln.stratum.engine.skin.SkinModel
import com.kiln.stratum.engine.skin.SkinUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 피부 분할 모델(미디어파이프 셀피 멀티클래스)이 이 기기의 CPU에서 불러와지고 도는지 본다(결정 기록 '피부 보정'의 2a).
 * 그 기기에서 모델을 돌리지 못하면 건너뛴다. 잰 시간·메모리는 logcat의 "SkinProbe"(불러오기는 "LiteRtSkin")로 본다.
 */
@RunWith(AndroidJUnit4::class)
class SkinModelProbeTest {

    @Test
    fun theModel_runsOnTheCpu_andGivesSixClassesPerCell() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LiteRtSkinModel(context.assets, context.cacheDir, listOf(LiteRtSkinModel.Backend.CPU))
        try {
            val gray = FloatArray(SkinModel.INPUT_VALUES) { 0.5f }
            val before = ProcessMemory.now()
            val started = SystemClock.elapsedRealtime()
            val logits = try {
                model.segment(gray)
            } catch (e: SkinUnavailableException) {
                assumeNoException("The skin model cannot run on this device", e)
                return
            }
            val loaded = SystemClock.elapsedRealtime() - started
            val after = ProcessMemory.now()
            assertEquals(LiteRtSkinModel.Backend.CPU, model.activeBackend)
            assertEquals(SkinModel.OUTPUT_VALUES, logits.size)
            assertTrue(logits.all { it.isFinite() })

            // 불러온 뒤 한 번의 시간(5번 평균)
            val runStarted = SystemClock.elapsedRealtime()
            repeat(5) { model.segment(gray) }
            val runMillis = (SystemClock.elapsedRealtime() - runStarted) / 5.0
            Log.i(
                "SkinProbe",
                "CPU: load + first run $loaded ms, run $runMillis ms, " +
                    "PSS +${(after.pssKb - before.pssKb) / 1024} MB (native +${(after.nativeKb - before.nativeKb) / 1024} MB)"
            )
        } finally {
            model.release()
        }
    }
}

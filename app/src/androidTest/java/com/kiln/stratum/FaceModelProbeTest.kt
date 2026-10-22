package com.kiln.stratum

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.face.FaceDetectionDecoder
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.FaceRoi
import com.kiln.stratum.engine.face.FaceUnavailableException
import com.kiln.stratum.engine.platform.LiteRtFaceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 얼굴 모델 둘(BlazeFace 먼 거리용·FaceMesh V2)이 이 기기의 CPU에서 불러와지고 도는지 본다(결정 기록 '얼굴 인식 보정').
 * 그 기기에서 모델을 돌리지 못하면 건너뛴다. 잰 시간·메모리는 logcat의 "FaceProbe"(불러오기는 "LiteRtFace")로 본다.
 */
@RunWith(AndroidJUnit4::class)
class FaceModelProbeTest {

    @Test
    fun theModels_runOnTheCpu_andFindNoFaceInGray() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LiteRtFaceModel(context.assets, context.cacheDir, listOf(LiteRtFaceModel.Backend.CPU))
        try {
            val gray = FloatArray(FaceModel.DETECTOR_SIZE * FaceModel.DETECTOR_SIZE * 3)
            val before = ProcessMemory.now()
            val started = SystemClock.elapsedRealtime()
            val detected = try {
                model.detect(gray)
            } catch (e: FaceUnavailableException) {
                assumeNoException("The face models cannot run on this device", e)
                return
            }
            val loaded = SystemClock.elapsedRealtime() - started
            val after = ProcessMemory.now()
            assertEquals(LiteRtFaceModel.Backend.CPU, model.activeBackend)
            assertTrue(FaceDetectionDecoder.decode(detected, FaceRoi(96f, 96f, 192f)).isEmpty())

            val landmarkGray = FloatArray(FaceModel.LANDMARK_SIZE * FaceModel.LANDMARK_SIZE * 3) { 0.5f }
            val landmarks = model.landmarks(landmarkGray)
            assertEquals(FaceModel.LANDMARK_COUNT * 3, landmarks.points.size)
            assertTrue(landmarks.points.all { it.isFinite() } && landmarks.presence.isFinite())

            // 불러온 뒤 한 번씩의 시간(10번 평균)
            val detectStarted = SystemClock.elapsedRealtime()
            repeat(10) { model.detect(gray) }
            val detectMillis = (SystemClock.elapsedRealtime() - detectStarted) / 10.0
            val landmarkStarted = SystemClock.elapsedRealtime()
            repeat(10) { model.landmarks(landmarkGray) }
            val landmarkMillis = (SystemClock.elapsedRealtime() - landmarkStarted) / 10.0
            Log.i(
                "FaceProbe",
                "CPU: load + first detect $loaded ms, detect $detectMillis ms, landmarks $landmarkMillis ms, " +
                    "gray presence ${landmarks.presence}, PSS +${(after.pssKb - before.pssKb) / 1024} MB " +
                    "(native +${(after.nativeKb - before.nativeKb) / 1024} MB)"
            )
        } finally {
            model.release()
        }
    }
}

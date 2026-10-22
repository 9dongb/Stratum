package com.kiln.stratum.engine.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceRoiTest {

    @Test
    fun cropAndImage_areInverse_atAnyAngle() {
        for (degrees in listOf(0, 17, 90, -135, 180)) {
            val roi = FaceRoi(320f, 210f, 150f, (degrees * PI / 180).toFloat())
            for ((a, b) in listOf(0f to 0f, 0.5f to 0.5f, 1f to 0.25f, 0.3f to 0.9f)) {
                val x = roi.imageX(a, b)
                val y = roi.imageY(a, b)
                assertEquals("a at $degrees°", a, roi.cropA(x, y), 1e-4f)
                assertEquals("b at $degrees°", b, roi.cropB(x, y), 1e-4f)
            }
        }
    }

    @Test
    fun turnedRoi_runsItsWidthAlongTheAngle_clockwiseOnScreen() {
        // 30° 돌린 정사각형의 오른쪽 변 가운데는 가운데에서 (cos 30°, sin 30°) 쪽(그림에서 오른쪽 아래)이다
        val roi = FaceRoi(100f, 100f, 80f, (PI / 6).toFloat())

        assertEquals(100f + 40f * cos(PI / 6).toFloat(), roi.imageX(1f, 0.5f), 1e-4f)
        assertEquals(100f + 40f * sin(PI / 6).toFloat(), roi.imageY(1f, 0.5f), 1e-4f)
        // 아래 변 가운데는 (-sin, cos) 쪽
        assertEquals(100f - 40f * sin(PI / 6).toFloat(), roi.imageX(0.5f, 1f), 1e-4f)
        assertEquals(100f + 40f * cos(PI / 6).toFloat(), roi.imageY(0.5f, 1f), 1e-4f)
    }

    @Test
    fun letterbox_coversTheWholeImage_withTheLongSide() {
        val roi = FaceRoi.letterbox(400, 200)

        assertEquals(400f, roi.side, 0f)
        // 위쪽 그림 끝은 입력의 1/4 줄, 그 위는 그림 밖
        assertEquals(0f, roi.imageY(0.5f, 0.25f), 1e-4f)
        assertEquals(-100f, roi.imageY(0.5f, 0f), 1e-4f)
        assertEquals(0f, roi.imageX(0f, 0.5f), 1e-4f)
        assertEquals(400f, roi.imageX(1f, 0.5f), 1e-4f)
    }

    @Test
    fun fromDetection_isOneAndAHalfTimesTheLongSide_turnedAlongTheEyes() {
        val eyeAngle = PI / 6
        val keypoints = FloatArray(12)
        keypoints[0] = 100f
        keypoints[1] = 100f
        keypoints[2] = (100 + 50 * cos(eyeAngle)).toFloat()
        keypoints[3] = (100 + 50 * sin(eyeAngle)).toFloat()
        val detection = FaceDetection(0.9f, 80f, 60f, 180f, 200f, keypoints)

        val roi = FaceRoi.fromDetection(detection)

        assertEquals(130f, roi.centerX, 1e-4f)
        assertEquals(130f, roi.centerY, 1e-4f)
        assertEquals(140f * 1.5f, roi.side, 1e-4f)
        assertEquals(eyeAngle.toFloat(), roi.angle, 1e-5f)
    }

    @Test
    fun fromLandmarks_usesAllPointsBounds_andTheOuterEyeCorners() {
        val face = TestFace(eyeX = 500f, eyeY = 400f, eyeDistance = 100f, angle = (-20 * PI / 180).toFloat()).landmarks()
        val bounds = face.bounds()

        val roi = FaceRoi.fromLandmarks(face)

        assertEquals((bounds[0] + bounds[2]) / 2f, roi.centerX, 1e-3f)
        assertEquals((bounds[1] + bounds[3]) / 2f, roi.centerY, 1e-3f)
        assertEquals(maxOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) * 1.5f, roi.side, 1e-3f)
        // 시험 얼굴의 눈꼬리 둘은 눈 선과 나란하다
        assertEquals((-20 * PI / 180).toFloat(), roi.angle, 1e-4f)
    }
}

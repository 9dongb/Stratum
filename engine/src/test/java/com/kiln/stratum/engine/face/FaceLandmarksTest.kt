package com.kiln.stratum.engine.face

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLandmarksTest {

    private val face = TestFace(eyeX = 300f, eyeY = 200f, eyeDistance = 80f, angle = (25 * PI / 180).toFloat()).landmarks()

    @Test
    fun eyes_measureTheIrisCenters() {
        assertEquals(80f, face.eyeDistance, 1e-3f)
        assertEquals(300f, face.eyeCenterX, 1e-3f)
        assertEquals(200f, face.eyeCenterY, 1e-3f)
    }

    @Test
    fun contains_isInsideTheFaceOval() {
        // 코끝은 안, 눈 사이 거리 두 배 옆은 밖
        assertTrue(face.contains(face.x(FaceMeshIndex.NOSE_TIP), face.y(FaceMeshIndex.NOSE_TIP)))
        assertFalse(face.contains(300f + 160f, 200f))
        assertFalse(face.contains(-10f, -10f))
    }

    @Test
    fun scaled_multipliesXAndYSeparately() {
        val scaled = face.scaled(2f, 3f)

        assertEquals(face.x(10) * 2f, scaled.x(10), 1e-3f)
        assertEquals(face.y(10) * 3f, scaled.y(10), 1e-3f)
        assertEquals(face.presence, scaled.presence, 0f)
    }

    @Test
    fun bounds_coverAllPoints() {
        val bounds = face.bounds()

        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            assertTrue(face.x(i) in bounds[0]..bounds[2])
            assertTrue(face.y(i) in bounds[1]..bounds[3])
        }
    }

    @Test
    fun isFinite_isFalseForNotANumber() {
        val points = face.points.copyOf()
        points[7] = Float.NaN

        assertTrue(face.isFinite)
        assertFalse(FaceLandmarks(points, 10f).isFinite)
    }
}

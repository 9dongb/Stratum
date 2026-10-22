package com.kiln.stratum.engine.face

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaceSearchTest {

    @Test
    fun oneFace_isFound_andItsPointsComeBackInImageCoordinates() {
        val face = TestFace(eyeX = 900f, eyeY = 700f, eyeDistance = 200f)
        val scene = FakeFaceScene(2000, 1600, listOf(face))

        val found = FaceSearch(scene).find(scene)

        assertEquals(1, found.size)
        assertEquals(900f, found[0].eyeCenterX, 0.5f)
        assertEquals(700f, found[0].eyeCenterY, 0.5f)
        assertEquals(200f, found[0].eyeDistance, 0.5f)
        // 그림 전체와 조각(9)에서 찾고, 특징점은 두 번(상자로, 점으로) 읽는다. 같은 얼굴은 하나로 합친다.
        assertEquals(1 + 9, scene.detectCalls)
        assertEquals(2, scene.landmarkCalls)
    }

    @Test
    fun secondLandmarkPass_cropsAroundThePoints_turnedAlongTheEyeCorners() {
        val tilt = (30 * PI / 180).toFloat()
        val face = TestFace(eyeX = 500f, eyeY = 500f, eyeDistance = 150f, angle = tilt)
        val scene = FakeFaceScene(1000, 1000, listOf(face))

        val found = FaceSearch(scene).find(scene)

        assertEquals(1, found.size)
        val crops = scene.reads.filter { it.second == FaceModel.LANDMARK_SIZE }.map { it.first }
        assertEquals(2, crops.size)
        val expected = FaceRoi.fromLandmarks(face.landmarks())
        assertEquals(expected.centerX, crops[1].centerX, 0.01f)
        assertEquals(expected.centerY, crops[1].centerY, 0.01f)
        assertEquals(expected.side, crops[1].side, 0.01f)
        assertEquals(tilt, crops[1].angle, 1e-4f)
        // 돌린 얼굴도 점이 제자리로 돌아온다
        assertEquals(face.landmarks().x(FaceMeshIndex.LEFT_IRIS), found[0].x(FaceMeshIndex.LEFT_IRIS), 0.5f)
        assertEquals(face.landmarks().y(FaceMeshIndex.LEFT_IRIS), found[0].y(FaceMeshIndex.LEFT_IRIS), 0.5f)
    }

    @Test
    fun severalFaces_areSortedLeftToRight() {
        val faces = listOf(
            TestFace(1500f, 600f, 160f),
            TestFace(400f, 650f, 170f),
            TestFace(950f, 500f, 150f)
        )
        val scene = FakeFaceScene(2000, 1500, faces)

        val found = FaceSearch(scene).find(scene)

        assertEquals(listOf(400f, 950f, 1500f), found.map { Math.round(it.eyeCenterX).toFloat() })
    }

    @Test
    fun aSmallFace_isFoundByTheTiles() {
        // 그림 전체(2000px → 192px)에서는 상자가 16px보다 작아 못 찾고, 800px 조각에서 찾는다
        val small = TestFace(1210f, 1100f, 40f)
        val scene = FakeFaceScene(2000, 1500, listOf(small))

        val found = FaceSearch(scene).find(scene)

        assertEquals(1, found.size)
        assertEquals(1210f, found[0].eyeCenterX, 0.5f)
    }

    @Test
    fun aDetectionWithoutAFace_isDroppedByThePresenceScore() {
        val face = TestFace(500f, 500f, 150f)
        val phantom = TestFace(1500f, 500f, 150f)
        val scene = FakeFaceScene(2000, 1000, listOf(face), phantoms = listOf(phantom))

        val found = FaceSearch(scene).find(scene)

        assertEquals(1, found.size)
        assertEquals(500f, found[0].eyeCenterX, 0.5f)
    }

    @Test
    fun aSidewaysLayer_isFoundByTurningTheWholeImage() {
        val sideways = TestFace(600f, 400f, 120f, angle = (PI / 2).toFloat())
        val scene = FakeFaceScene(1200, 800, listOf(sideways))

        val found = FaceSearch(scene).find(scene)

        assertEquals(1, found.size)
        assertEquals(600f, found[0].eyeCenterX, 0.5f)
        assertEquals(400f, found[0].eyeCenterY, 0.5f)
        // 바로 선 보기(그림 전체 + 조각)에서 못 찾은 뒤 90°로 돌려 찾았다
        val turned = scene.reads.filter { it.second == FaceModel.DETECTOR_SIZE }.map { it.first.angle }
        assertEquals((PI / 2).toFloat(), turned.last(), 1e-5f)
    }

    @Test
    fun smallFaces_belowTheEyeDistance_areLeftOut() {
        val faces = listOf(TestFace(500f, 500f, 150f), TestFace(1500f, 500f, 60f))
        val scene = FakeFaceScene(2000, 1000, faces, minDetectPixels = 4f)

        val found = FaceSearch(scene).find(scene, minEyeDistance = 100f)

        assertEquals(1, found.size)
        assertEquals(150f, found[0].eyeDistance, 0.5f)
    }

    @Test
    fun atMostEightFaces() {
        val faces = (0 until 10).map { TestFace(150f + it * 300f, 600f, 90f) }
        val scene = FakeFaceScene(3100, 1200, faces, minDetectPixels = 4f)

        val found = FaceSearch(scene).find(scene)

        assertEquals(FaceSearch.MAX_FACES, found.size)
        assertEquals(found.sortedBy { it.eyeCenterX }, found)
    }

    @Test
    fun noFace_isAnEmptyList_afterTryingTheTurnedViews() {
        val scene = FakeFaceScene(1000, 800, emptyList())

        val found = FaceSearch(scene).find(scene)

        assertTrue(found.isEmpty())
        val angles = scene.reads.filter { it.second == FaceModel.DETECTOR_SIZE }.map { it.first.angle }
        assertTrue(angles.any { it > 1.5f && it < 1.6f })
        assertTrue(angles.any { it > 3.1f && it < 3.2f })
        assertTrue(angles.any { it > 4.7f && it < 4.8f })
    }

    @Test
    fun canceling_stopsBeforeTheNextModelRun() {
        val scene = FakeFaceScene(2000, 1600, listOf(TestFace(900f, 700f, 200f)))
        var calls = 0

        try {
            FaceSearch(scene).find(scene) { if (++calls > 3) throw IllegalStateException("canceled") }
            fail("Expected cancellation")
        } catch (e: IllegalStateException) {
            assertEquals(3, scene.detectCalls)
        }
    }

    @Test
    fun tiles_areSquares_thatOverlapAndReachTheImageEnds() {
        val tiles = FaceSearch.tiles(2000, 1500)

        assertEquals(9, tiles.size)
        val side = 800f
        assertTrue(tiles.all { it.side == side && it.angle == 0f })
        val lefts = tiles.map { it.centerX - side / 2 }.distinct().sorted()
        val tops = tiles.map { it.centerY - side / 2 }.distinct().sorted()
        assertEquals(listOf(0f, 600f, 1200f), lefts)
        assertEquals(listOf(0f, 350f, 700f), tops)
    }

    @Test
    fun tiles_areNotUsed_forASmallImage_andRunAlongAPanorama() {
        assertTrue(FaceSearch.tiles(500, 400).isEmpty())
        // 짧은 변(500px)보다 큰 조각은 없다
        val panorama = FaceSearch.tiles(3000, 500)
        assertTrue(panorama.all { it.side == 500f && it.centerY == 250f })
        assertEquals(3000f, panorama.maxOf { it.centerX + 250f }, 1e-3f)
    }

    @Test
    fun cutByTile_isOnlyForInnerEdges() {
        val tile = FaceRoi(1000f, 1000f, 800f)
        fun face(left: Float, top: Float, right: Float, bottom: Float) = FaceDetection(0.9f, left, top, right, bottom, FloatArray(12))

        assertFalse(FaceSearch.cutByTile(face(700f, 700f, 900f, 900f), tile, 3000, 3000))
        assertTrue(FaceSearch.cutByTile(face(550f, 700f, 750f, 900f), tile, 3000, 3000))
        assertTrue(FaceSearch.cutByTile(face(1300f, 1300f, 1450f, 1450f), tile, 3000, 3000))
        // 그림 끝에 닿은 조각은 그 변을 넘어도 잘린 것이 아니다(그림 밖까지 이어진 얼굴)
        val corner = FaceRoi(400f, 400f, 800f)
        assertFalse(FaceSearch.cutByTile(face(-30f, -30f, 100f, 100f), corner, 3000, 3000))
    }
}

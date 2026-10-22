package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceUnavailableException
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.LiteRtFaceModel
import com.kiln.stratum.engine.platform.PhotoImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 진짜 얼굴 모델로 시험 사진(NASA 퍼블릭 도메인 사진 넷)에서 얼굴을 찾는다(결정 기록 '얼굴 인식 보정').
 * 사진은 앱 내부 저장소 files/face/에 넣어야 돌고(없으면 건너뜀, docs/emulator.md '얼굴 시험 사진'), 기대값은 같은 순서를 맥(파이썬, LiteRT)에서
 * 돌린 눈동자 가운데(468·473)와 턱 끝(152)의 문서 좌표다. 그림을 줄이는 방법이 조금 달라 두 눈 사이의 5%까지 다를 수 있다고 본다.
 * 걸린 시간은 logcat의 "FaceFinder"로 본다.
 */
@RunWith(AndroidJUnit4::class)
class FacePhotoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val finder = FaceFinder(LiteRtFaceModel(context.assets, context.cacheDir))

    @After
    fun release() = finder.release()

    @Test
    fun aPortrait_hasOneFace_whereTheMacFoundIt() {
        val faces = find(photo("Neil_Armstrong_pose.jpg"))

        assertEquals(1, faces.size)
        assertNear(faces[0], 1890.4f, 1090.3f, 2122.9f, 1051.7f, 2057.1f, 1482.5f)
    }

    @Test
    fun aGroupPhoto_hasThreeFaces_leftToRight_andNotTheSuitButton() {
        // 맥에서는 옷 단추 하나도 얼굴 찾기가 찾았지만 특징점 모델이 얼굴이 아니라고 했다(얼굴 있음 -8.8)
        val faces = find(photo("Apollo_11_Crew.jpg"))

        assertEquals(3, faces.size)
        assertNear(faces[0], 1003.6f, 1326.1f, 1153.2f, 1326.4f, 1087.9f, 1611.0f)
        assertNear(faces[1], 1953.4f, 728.7f, 2103.6f, 722.4f, 2032.9f, 982.5f)
        assertNear(faces[2], 2940.0f, 1387.3f, 3086.7f, 1372.4f, 3028.0f, 1665.2f)
    }

    @Test
    fun aBigPhoto_openedAtHalfSize_findsTheFaceAtHalfTheCoordinates() {
        // 6498×8123(52.8MP)은 앱도 줄여 열 수 있다. 반으로 열면 찾는 그림은 맥과 같은 1625×2031이다.
        val faces = find(photo("Buzz_Aldrin.jpg", sampleSize = 2))

        assertEquals(1, faces.size)
        assertNear(faces[0], 3528.3f / 2, 2420.0f / 2, 3995.5f / 2, 2370.8f / 2, 3837.8f / 2, 3268.6f / 2)
    }

    @Test
    fun aBuilding_hasNoFace() {
        assertTrue(find(photo("VAB_exterior_and_LCC.jpg")).isEmpty())
    }

    @Test
    fun aTiltedOrSidewaysPortrait_turnsThePointsWithIt() {
        val source = file("Neil_Armstrong_pose.jpg")
        val upright = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        try {
            val reference = find(documentOf(upright)).single()
            for (degrees in listOf(30f, 90f)) {
                val turned = turn(upright, degrees)
                val faces = try {
                    find(documentOf(turned))
                } finally {
                    turned.recycle()
                }
                assertEquals("faces at $degrees°", 1, faces.size)
                val radians = Math.toRadians(degrees.toDouble())
                val newWidth = turnedWidth(upright, radians)
                val newHeight = turnedHeight(upright, radians)
                for (index in listOf(FaceMeshIndex.RIGHT_IRIS, FaceMeshIndex.LEFT_IRIS, FaceMeshIndex.CHIN)) {
                    val dx = reference.x(index) - upright.width / 2.0
                    val dy = reference.y(index) - upright.height / 2.0
                    val x = cos(radians) * dx - sin(radians) * dy + newWidth / 2.0
                    val y = sin(radians) * dx + cos(radians) * dy + newHeight / 2.0
                    val error = hypot(faces[0].x(index) - x, faces[0].y(index) - y)
                    assertTrue("point $index at $degrees°: off by $error px", error < reference.eyeDistance * TOLERANCE)
                }
            }
        } finally {
            upright.recycle()
        }
    }

    private fun file(name: String): File {
        val file = File(File(context.filesDir, "face"), name)
        assumeTrue("The test photo is not in ${file.parent}", file.isFile)
        return file
    }

    private fun photo(name: String, sampleSize: Int = 1): Document {
        val result = PhotoImporter.import(ImageDecoder.createSource(file(name)), "Photo", sampleSize)
        return (result as ImportResult.Success).document
    }

    /** [bitmap]을 JPEG(품질 95)로 눌러 앱과 같은 길로 연 문서 */
    private fun documentOf(bitmap: Bitmap): Document {
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        return (PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), "Photo") as ImportResult.Success).document
    }

    /** [bitmap]을 [degrees]만큼 시계 방향으로 돌린 그림(넘치는 곳까지 넓히고 빈 곳은 회색) */
    private fun turn(bitmap: Bitmap, degrees: Float): Bitmap {
        val radians = Math.toRadians(degrees.toDouble())
        val width = turnedWidth(bitmap, radians)
        val height = turnedHeight(bitmap, radians)
        val turned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(turned).apply {
            drawColor(Color.GRAY)
            translate(width / 2f, height / 2f)
            rotate(degrees)
            translate(-bitmap.width / 2f, -bitmap.height / 2f)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return turned
    }

    private fun turnedWidth(bitmap: Bitmap, radians: Double): Int =
        ceil(abs(bitmap.width * cos(radians)) + abs(bitmap.height * sin(radians)) - 1e-6).toInt()

    private fun turnedHeight(bitmap: Bitmap, radians: Double): Int =
        ceil(abs(bitmap.width * sin(radians)) + abs(bitmap.height * cos(radians)) - 1e-6).toInt()

    private fun find(document: Document): List<FaceLandmarks> {
        val layer = document.layers[0]
        val started = SystemClock.elapsedRealtime()
        val picture = finder.render(document, layer)
        val faces = try {
            finder.search(picture)
        } catch (e: FaceUnavailableException) {
            assumeNoException("The face models cannot run on this device", e)
            emptyList()
        } finally {
            picture.close()
        }
        Log.i("FacePhoto", "${document.width}x${document.height}: ${faces.size} faces in ${SystemClock.elapsedRealtime() - started} ms (render + search)")
        return faces
    }

    /** 오른쪽·왼쪽 눈동자 가운데와 턱 끝이 기대값에서 두 눈 사이의 [TOLERANCE] 안인지 */
    private fun assertNear(face: FaceLandmarks, rightX: Float, rightY: Float, leftX: Float, leftY: Float, chinX: Float, chinY: Float) {
        val limit = hypot(leftX - rightX, leftY - rightY) * TOLERANCE
        for ((index, expected) in listOf(
            FaceMeshIndex.RIGHT_IRIS to (rightX to rightY),
            FaceMeshIndex.LEFT_IRIS to (leftX to leftY),
            FaceMeshIndex.CHIN to (chinX to chinY)
        )) {
            val error = hypot(face.x(index) - expected.first, face.y(index) - expected.second)
            assertTrue("point $index (${face.x(index)}, ${face.y(index)}) is $error px from $expected", error < limit)
        }
    }

    private companion object {
        /** 두 눈 사이에 대한 허용 오차 */
        const val TOLERANCE = 0.05f
    }
}

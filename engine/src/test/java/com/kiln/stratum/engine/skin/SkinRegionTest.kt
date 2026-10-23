package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceRoi
import com.kiln.stratum.engine.face.TestFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.hypot

/** 얼굴 하나의 피부 마스크(결정 기록 '피부 보정'의 2b). 가짜 분할 확률과 가짜 사진으로 순서마다 본다. */
class SkinRegionTest {

    private val face = TestFace(eyeX = 600f, eyeY = 500f, eyeDistance = 120f, angle = 0.25f)
    private val landmarks = skinFace(face)
    private val frame = SkinRegion.frameOf(landmarks)
    private val size = 256

    private val skinColor = rgb(222, 172, 142)

    /** 얼굴 좌표 (u, v)의 마스크 칸 번호 */
    private fun cell(u: Float, v: Float, n: Int = size, roi: FaceRoi = frame): Int {
        val (x, y) = face.at(u, v)
        val a = floor(roi.cropA(x, y) * n).toInt().coerceIn(0, n - 1)
        val b = floor(roi.cropB(x, y) * n).toInt().coerceIn(0, n - 1)
        return b * n + a
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * [TestFace]의 점에 피부 마스크가 빼는 곳(입술 바깥 윤곽·눈썹 아래 줄·콧구멍)을 사람 얼굴 같은 자리로 더한 것.
     * TestFace는 이 점들을 얼굴 안쪽 작은 타원에 흩어 두어, 그대로 다각형을 만들면 볼까지 덮는다.
     */
    private fun skinFace(face: TestFace): FaceLandmarks {
        val points = face.landmarks().points.copyOf()
        fun put(index: Int, u: Float, v: Float) {
            val (x, y) = face.at(u, v)
            points[2 * index] = x
            points[2 * index + 1] = y
        }
        // 입술 바깥: 오른쪽 입꼬리부터 아래 → 왼쪽 입꼬리 → 위로 한 바퀴(가운데 (0, 1.06), 반폭 0.38, 반높이 0.08)
        FaceMeshIndex.LIPS_OUTER.forEachIndexed { k, index ->
            val theta = kotlin.math.PI - k * kotlin.math.PI / 10
            put(index, (0.38 * kotlin.math.cos(theta)).toFloat(), (1.06 + 0.08 * kotlin.math.sin(theta)).toFloat())
        }
        // 눈썹 윤곽: 위 줄(바깥 → 안쪽)은 TestFace 그대로, 아래 줄(안쪽 → 바깥)은 그 0.08 아래
        val us = floatArrayOf(0.78f, 0.65f, 0.5f, 0.35f, 0.2f)
        val vs = floatArrayOf(-0.28f, -0.33f, -0.36f, -0.35f, -0.3f)
        for ((outline, side) in listOf(FaceMeshIndex.RIGHT_BROW_OUTLINE to -1f, FaceMeshIndex.LEFT_BROW_OUTLINE to 1f)) {
            outline.forEachIndexed { k, index ->
                if (k < 5) put(index, side * us[k], vs[k]) else put(index, side * us[9 - k], vs[9 - k] + 0.08f)
            }
        }
        // 콧구멍: 콧방울 안쪽의 작은 둘레
        for ((nostril, side) in listOf(FaceMeshIndex.RIGHT_NOSTRIL to -1f, FaceMeshIndex.LEFT_NOSTRIL to 1f)) {
            nostril.forEachIndexed { k, index ->
                val theta = 2 * kotlin.math.PI * k / nostril.size
                put(index, side * 0.12f + (0.05 * kotlin.math.cos(theta)).toFloat(), (0.66 + 0.03 * kotlin.math.sin(theta)).toFloat())
            }
        }
        return FaceLandmarks(points, presence = 15f)
    }

    /**
     * 가짜 분할 확률([SkinModel.INPUT_SIZE]²): 넓힌 윤곽 안은 얼굴 피부, 턱 아래 목은 몸 피부,
     * 그리고 목에서 정사각형 오른쪽 아래 끝까지 이어진 몸 피부 띠(팔·어깨처럼 이어졌지만 목 둘레 밖)
     */
    private fun probabilities(): FloatArray {
        val n = SkinModel.INPUT_SIZE
        val points = SkinRegion.cropPoints(landmarks, frame, n)
        val faceSkin = SkinMaps.dilate(SkinMaps.polygon(points, FaceMeshIndex.FACE_OVAL, n, n), n, n, 3f)
        val chinX = points[2 * FaceMeshIndex.CHIN]
        val chinY = points[2 * FaceMeshIndex.CHIN + 1]
        val unit = hypot(points[2 * FaceMeshIndex.LEFT_EYE_OUTER] - points[2 * FaceMeshIndex.RIGHT_EYE_OUTER], points[2 * FaceMeshIndex.LEFT_EYE_OUTER + 1] - points[2 * FaceMeshIndex.RIGHT_EYE_OUTER + 1])
        val out = FloatArray(SkinModel.OUTPUT_VALUES)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val cell = y * n + x
                val neck = y + 0.5f in chinY - 2f..chinY + unit * 0.9f && x + 0.5f in chinX - unit * 0.45f..chinX + unit * 0.45f
                val strip = y + 0.5f > chinY + unit * 0.6f && y + 0.5f < chinY + unit * 0.9f && x + 0.5f >= chinX
                val k = when {
                    faceSkin[cell] -> SkinModel.FACE_SKIN
                    neck || strip -> SkinModel.BODY_SKIN
                    else -> SkinModel.BACKGROUND
                }
                out[cell * SkinModel.CLASS_COUNT + k] = 1f
            }
        }
        return out
    }

    private fun uniformCrop(color: Int = skinColor) = IntArray(size * size) { color }

    @Test
    fun frame_isTurnedWithTheEyes_andHangsBelowTheFace() {
        assertEquals(0.25f, frame.angle, 1e-4f)
        // 정사각형 가운데는 특징점 가운데보다 턱 쪽(얼굴 좌표 v가 큼)에 있다
        val bounds = landmarks.bounds()
        val (meanX, meanY) = (0 until 478).map { landmarks.x(it) }.average().toFloat() to (0 until 478).map { landmarks.y(it) }.average().toFloat()
        val down = (frame.centerX - meanX) * -kotlin.math.sin(0.25f) + (frame.centerY - meanY) * kotlin.math.cos(0.25f)
        assertTrue("$down", down > 0f)
        // 한 변은 얼굴 크기의 두 배 남짓(특징점 상자보다 크다)
        assertTrue("${frame.side}", frame.side > (bounds[3] - bounds[1]) * 1.6f)
        // 이마 위 끝·턱 끝·두 귀 쪽 윤곽이 모두 정사각형 안이다
        for (index in FaceMeshIndex.FACE_OVAL) {
            val a = frame.cropA(landmarks.x(index), landmarks.y(index))
            val b = frame.cropB(landmarks.x(index), landmarks.y(index))
            assertTrue("$index: $a, $b", a in 0.05f..0.95f && b in 0.05f..0.95f)
        }
    }

    @Test
    fun probabilities_areASoftmaxPerCell() {
        val logits = FloatArray(SkinModel.OUTPUT_VALUES) { (it % 7) * 0.5f }
        val probabilities = SkinRegion.probabilities(logits)
        for (cell in 0 until 50) {
            val sum = (0 until SkinModel.CLASS_COUNT).sumOf { probabilities[cell * SkinModel.CLASS_COUNT + it].toDouble() }
            assertEquals(1.0, sum, 1e-5)
        }
        assertTrue(probabilities[1] > probabilities[0])
    }

    @Test
    fun modelInput_isRgbFromZeroToOne() {
        val input = SkinRegion.modelInput(IntArray(SkinModel.INPUT_SIZE * SkinModel.INPUT_SIZE) { rgb(255, 0, 51) })
        assertEquals(1f, input[0], 1e-6f)
        assertEquals(0f, input[1], 1e-6f)
        assertEquals(0.2f, input[2], 1e-6f)
    }

    @Test
    fun mask_keepsTheFaceAndNeck_dropsTheEyesAndAConnectedArm() {
        val mask = SkinRegion.mask(probabilities(), landmarks, frame, uniformCrop(), size)

        // 볼·이마·목은 피부
        assertTrue("cheek ${mask[cell(-0.55f, 0.55f)]}", mask[cell(-0.55f, 0.55f)] > 0.8f)
        assertTrue("forehead ${mask[cell(0f, -0.65f)]}", mask[cell(0f, -0.65f)] > 0.8f)
        assertTrue("neck ${mask[cell(0f, 2.0f)]}", mask[cell(0f, 2.0f)] > 0.5f)
        // 눈동자 둘레·입술은 뺀다
        assertTrue("eye ${mask[cell(-0.5f, 0.02f)]}", mask[cell(-0.5f, 0.02f)] < 0.2f)
        assertTrue("lips ${mask[cell(0f, 1.05f)]}", mask[cell(0f, 1.05f)] < 0.2f)
        // 목에 이어졌지만 목 둘레 밖인 띠(팔·어깨)와 바탕은 뺀다
        assertTrue("arm ${mask[cell(1.9f, 2.7f)]}", mask[cell(1.9f, 2.7f)] < 0.05f)
        assertTrue("background ${mask[cell(-2.0f, -1.5f)]}", mask[cell(-2.0f, -1.5f)] < 0.05f)
        // 정사각형 끝은 0이다(끊겨 보이지 않게)
        assertTrue((0 until size).all { mask[it] == 0f && mask[(size - 1) * size + it] == 0f })
    }

    @Test
    fun mask_withoutTheModel_usesTheFaceOval() {
        val mask = SkinRegion.mask(null, landmarks, frame, uniformCrop(), size)

        assertTrue("cheek ${mask[cell(-0.55f, 0.55f)]}", mask[cell(-0.55f, 0.55f)] > 0.8f)
        assertTrue("eye ${mask[cell(-0.5f, 0.02f)]}", mask[cell(-0.5f, 0.02f)] < 0.2f)
        // 윤곽 밖(목·바탕)은 빠진다
        assertTrue("neck ${mask[cell(0f, 2.0f)]}", mask[cell(0f, 2.0f)] < 0.05f)
        assertTrue("background ${mask[cell(-2.0f, -1.5f)]}", mask[cell(-2.0f, -1.5f)] < 0.05f)
    }

    @Test
    fun refine_dropsADarkBeardAndAFarColor_onlySubtracting() {
        val crop = uniformCrop()
        val beard = rgb(70, 62, 56)
        val glasses = rgb(40, 90, 210)
        val red = rgb(255, 40, 40)
        val n = size
        for (y in 0 until n) {
            for (x in 0 until n) {
                // 얼굴 좌표로 되돌려 칠한다
                val docX = frame.imageX((x + 0.5f) / n, (y + 0.5f) / n)
                val docY = frame.imageY((x + 0.5f) / n, (y + 0.5f) / n)
                val c = kotlin.math.cos(0.25)
                val s = kotlin.math.sin(0.25)
                val du = (docX - 600f) / 120f
                val dv = (docY - 500f) / 120f
                val u = (c * du + s * dv).toFloat()
                val v = (-s * du + c * dv).toFloat()
                when {
                    u in -0.35f..0.35f && v in 1.2f..1.5f -> crop[y * n + x] = beard
                    u in 0.45f..0.7f && v in 0.35f..0.55f -> crop[y * n + x] = glasses
                    u in -2.4f..-1.6f && v in -1.9f..-1.2f -> crop[y * n + x] = red
                }
            }
        }
        val mask = SkinRegion.mask(probabilities(), landmarks, frame, crop, size)

        assertTrue("beard ${mask[cell(0f, 1.35f)]}", mask[cell(0f, 1.35f)] < 0.3f)
        assertTrue("glasses ${mask[cell(0.58f, 0.45f)]}", mask[cell(0.58f, 0.45f)] < 0.3f)
        assertTrue("cheek ${mask[cell(-0.55f, 0.55f)]}", mask[cell(-0.55f, 0.55f)] > 0.8f)
        // 색으로 더하지는 않는다: 바탕의 붉은 곳은 피부색에 가까워도 0 그대로
        assertTrue("red ${mask[cell(-2.0f, -1.5f)]}", mask[cell(-2.0f, -1.5f)] < 0.05f)
    }
}

package com.kiln.stratum.engine.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 시험용 얼굴: 두 눈동자 가운데 ([eyeX], [eyeY]), 눈 사이 거리 [eyeDistance], 기울기 [angle](라디안, 시계 방향 +).
 * [landmarks]는 그 얼굴의 478점을 얼굴 좌표계(눈 가운데가 0, 눈 선이 가로, 단위는 눈 사이 거리)의 틀로 만든다.
 * 이름이 있는 점과 눈 윤곽·콧등·입술은 사람 얼굴과 비슷한 자리, 얼굴 윤곽은 타원, 나머지 점은 얼굴 안쪽 작은 타원에 둔다.
 * [turn]이 0이 아니면 고개를 돌린 얼굴처럼 얼굴 윤곽의 u < 0 쪽(사람의 오른쪽)을 가로로 (1 − [turn])배 좁힌다(먼 쪽이 좁게 찍힘).
 */
data class TestFace(val eyeX: Float, val eyeY: Float, val eyeDistance: Float, val angle: Float = 0f, val turn: Float = 0f) {

    /** 얼굴 좌표 (u, v)(눈 사이 거리 단위, v는 턱 쪽 +)의 그림 x·y */
    fun at(u: Float, v: Float): Pair<Float, Float> {
        val c = cos(angle.toDouble())
        val s = sin(angle.toDouble())
        val x = eyeX + eyeDistance * (c * u - s * v)
        val y = eyeY + eyeDistance * (s * u + c * v)
        return x.toFloat() to y.toFloat()
    }

    /** 얼굴을 덮는 상자(윤곽 타원을 둘러쌈): 왼쪽, 위, 오른쪽, 아래 */
    fun box(): FloatArray {
        val points = landmarks()
        return points.bounds()
    }

    fun landmarks(): FaceLandmarks {
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 2)
        fun put(index: Int, u: Float, v: Float) {
            val (x, y) = at(u, v)
            points[2 * index] = x
            points[2 * index + 1] = y
        }
        // 나머지 점: 얼굴 안쪽 작은 타원
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            val t = 2 * PI * i / FaceModel.LANDMARK_COUNT
            put(i, (0.5 * cos(t)).toFloat(), (0.3 + 0.6 * sin(t)).toFloat())
        }
        // 윤곽: 가운데 (0, 0.3), 반폭 0.95, 반높이 1.35인 타원(이마 위 끝이 맨 위, 시계 방향). 돌린 얼굴은 u < 0 쪽이 좁다.
        FaceMeshIndex.FACE_OVAL.forEachIndexed { k, index ->
            val t = -PI / 2 + 2 * PI * k / FaceMeshIndex.FACE_OVAL.size
            val u = 0.95 * cos(t)
            put(index, (if (u < 0) u * (1 - turn) else u).toFloat(), (0.3 + 1.35 * sin(t)).toFloat())
        }
        // 눈 윤곽 16점: 눈꼬리부터 아래 꺼풀 → 눈머리 → 위 꺼풀(폭 0.47, 높이 0.18인 타원, 가운데 v 0.02)
        for ((contour, side) in listOf(FaceMeshIndex.RIGHT_EYE to -1f, FaceMeshIndex.LEFT_EYE to 1f)) {
            contour.forEachIndexed { k, index ->
                val t = 2 * PI * k / contour.size
                put(index, side * (0.485f + (0.235 * cos(t)).toFloat()), (0.02 + 0.09 * sin(t)).toFloat())
            }
        }
        put(FaceMeshIndex.RIGHT_IRIS, -0.5f, 0f)
        put(FaceMeshIndex.LEFT_IRIS, 0.5f, 0f)
        // 콧등(위 → 아래)과 코끝, 콧방울
        FaceMeshIndex.NOSE_BRIDGE.forEachIndexed { k, index -> put(index, 0f, 0.08f + 0.12f * k) }
        put(FaceMeshIndex.NOSE_TIP, 0f, 0.6f)
        put(FaceMeshIndex.NOSE_WING_RIGHT, -0.3f, 0.68f)
        put(FaceMeshIndex.NOSE_WING_LEFT, 0.3f, 0.68f)
        put(FaceMeshIndex.MOUTH_RIGHT, -0.38f, 1.0f)
        put(FaceMeshIndex.MOUTH_LEFT, 0.38f, 1.0f)
        put(FaceMeshIndex.UPPER_LIP_TOP, 0f, 0.92f)
        put(FaceMeshIndex.UPPER_LIP_INNER, 0f, 0.99f)
        put(FaceMeshIndex.LOWER_LIP_INNER, 0f, 1.01f)
        put(FaceMeshIndex.LOWER_LIP_BOTTOM, 0f, 1.12f)
        return FaceLandmarks(points, presence = 15f)
    }
}

/**
 * 시험용 그림과 모델: [faces]가 있는 그림이다. 가짜 그림은 읽은 곳만 기억하고(픽셀은 회색), 가짜 모델은 그 곳에 보이는 얼굴로 답한다.
 * - 얼굴 찾기: 보기 안에 가운데가 있고, 입력에서 상자 폭이 [minDetectPixels] 이상이고, 보기와의 기울기 차이가 50° 안인 얼굴을 찾는다.
 *   [phantoms]는 얼굴 찾기만 찾는 가짜 얼굴(특징점 모델은 얼굴이 아니라고 답함)이다.
 * - 특징점: 자른 곳 가운데 근처(비율 0.2..0.8)에 눈 가운데가 있고 기울기 차이가 50° 안인 얼굴의 점을 자른 곳 픽셀로 돌려준다(얼굴 있음 15).
 *   없으면 가운데에 모은 점과 얼굴 있음 -5.
 */
class FakeFaceScene(
    override val width: Int,
    override val height: Int,
    private val faces: List<TestFace>,
    private val phantoms: List<TestFace> = emptyList(),
    private val minDetectPixels: Float = 16f
) : FaceImage, FaceModel {

    /** 읽은 곳(차례로) */
    val reads = ArrayList<Pair<FaceRoi, Int>>()

    var detectCalls = 0
        private set
    var landmarkCalls = 0
        private set

    override fun read(roi: FaceRoi, size: Int, outside: FaceImage.Outside): IntArray {
        reads += roi to size
        return IntArray(size * size) { 0xFF808080.toInt() }
    }

    private val lastRoi: FaceRoi get() = reads.last().first

    override fun detect(input: FloatArray): DetectorOutput {
        detectCalls++
        val view = lastRoi
        val boxes = FloatArray(FaceModel.ANCHOR_COUNT * FaceModel.ANCHOR_VALUES)
        val scores = FloatArray(FaceModel.ANCHOR_COUNT) { -10f }
        val size = FaceModel.DETECTOR_SIZE
        for (face in faces + phantoms) {
            if (!sameUp(face.angle, view.angle)) continue
            val box = face.box()
            var a0 = Float.POSITIVE_INFINITY
            var b0 = Float.POSITIVE_INFINITY
            var a1 = Float.NEGATIVE_INFINITY
            var b1 = Float.NEGATIVE_INFINITY
            for (corner in 0 until 4) {
                val x = if (corner % 2 == 0) box[0] else box[2]
                val y = if (corner < 2) box[1] else box[3]
                a0 = minOf(a0, view.cropA(x, y))
                a1 = maxOf(a1, view.cropA(x, y))
                b0 = minOf(b0, view.cropB(x, y))
                b1 = maxOf(b1, view.cropB(x, y))
            }
            val ca = (a0 + a1) / 2f
            val cb = (b0 + b1) / 2f
            if (ca !in 0f..1f || cb !in 0f..1f || (a1 - a0) * size < minDetectPixels) continue
            val column = (ca * 48).toInt().coerceIn(0, 47)
            val row = (cb * 48).toInt().coerceIn(0, 47)
            val anchor = row * 48 + column
            val anchorX = (column + 0.5f) / 48
            val anchorY = (row + 0.5f) / 48
            val base = anchor * FaceModel.ANCHOR_VALUES
            boxes[base] = (ca - anchorX) * size
            boxes[base + 1] = (cb - anchorY) * size
            boxes[base + 2] = (a1 - a0) * size
            boxes[base + 3] = (b1 - b0) * size
            val marks = face.landmarks()
            val keypoints = intArrayOf(FaceMeshIndex.RIGHT_IRIS, FaceMeshIndex.LEFT_IRIS, FaceMeshIndex.NOSE_TIP, FaceMeshIndex.MOUTH_RIGHT, FaceMeshIndex.RIGHT_EYE_OUTER, FaceMeshIndex.LEFT_EYE_OUTER)
            keypoints.forEachIndexed { k, index ->
                boxes[base + 4 + 2 * k] = (view.cropA(marks.x(index), marks.y(index)) - anchorX) * size
                boxes[base + 5 + 2 * k] = (view.cropB(marks.x(index), marks.y(index)) - anchorY) * size
            }
            scores[anchor] = 4f
        }
        return DetectorOutput(boxes, scores)
    }

    override fun landmarks(input: FloatArray): LandmarkOutput {
        landmarkCalls++
        val roi = lastRoi
        val size = FaceModel.LANDMARK_SIZE
        val face = faces.firstOrNull { face ->
            sameUp(face.angle, roi.angle) && roi.cropA(face.eyeX, face.eyeY) in 0.2f..0.8f && roi.cropB(face.eyeX, face.eyeY) in 0.2f..0.8f
        }
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 3)
        if (face == null) {
            for (i in 0 until FaceModel.LANDMARK_COUNT) {
                points[3 * i] = size / 2f
                points[3 * i + 1] = size / 2f
            }
            return LandmarkOutput(points, -5f)
        }
        val marks = face.landmarks()
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            points[3 * i] = roi.cropA(marks.x(i), marks.y(i)) * size
            points[3 * i + 1] = roi.cropB(marks.x(i), marks.y(i)) * size
        }
        return LandmarkOutput(points, 15f)
    }

    /** 얼굴이 보기에서 바로 서 있는지(기울기 차이 50° 안) */
    private fun sameUp(faceAngle: Float, viewAngle: Float): Boolean {
        var difference = (faceAngle - viewAngle) % (2 * PI.toFloat())
        if (difference > PI) difference -= 2 * PI.toFloat()
        if (difference < -PI) difference += 2 * PI.toFloat()
        return kotlin.math.abs(difference) < (50 * PI / 180).toFloat()
    }
}

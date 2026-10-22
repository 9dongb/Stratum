package com.kiln.stratum

import com.kiln.stratum.engine.face.DetectorOutput
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.LandmarkOutput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 시험용 얼굴 모델: 그림의 빨간 덩어리(빨강이 밝고 초록·파랑이 어두운 픽셀이 4방향으로 이어진 곳)를 얼굴로 본다.
 * 진짜 찾는 순서([com.kiln.stratum.engine.face.FaceSearch])와 그림 읽기([com.kiln.stratum.engine.platform.FacePicture])를 그대로 거친다.
 *
 * - 얼굴 찾기: 입력(192²)의 빨간 덩어리마다 둘러싼 상자를 얼굴 상자로 내고(점수 로짓 5), 두 눈은 상자 위쪽 40% 줄의 30%·70% 자리다.
 * - 특징점: 자른 곳(256²)에서 가운데에 가장 가까운 빨간 덩어리의 상자에 맞춘 틀(478점: 윤곽은 상자 안 타원, 두 눈동자는 위쪽 40% 줄의 30%·70%,
 *   턱 끝은 아래 끝 가운데)을 낸다(얼굴 있음 15). 빨간 곳이 없으면 가운데에 모은 점과 얼굴 있음 -5.
 * - [failure]가 있으면 그것을 던진다(쓸 수 없는 기기 흉내). 부른 횟수를 센다.
 */
class FakeFaceModel : FaceModel {

    @Volatile
    var failure: Exception? = null

    @Volatile
    var detectCalls = 0
        private set

    @Volatile
    var landmarkCalls = 0
        private set

    @Volatile
    var releases = 0
        private set

    override fun detect(input: FloatArray): DetectorOutput {
        failure?.let { throw it }
        detectCalls++
        val size = FaceModel.DETECTOR_SIZE
        val boxes = FloatArray(FaceModel.ANCHOR_COUNT * FaceModel.ANCHOR_VALUES)
        val scores = FloatArray(FaceModel.ANCHOR_COUNT) { -10f }
        for (blob in redBlobs(input, size) { it > 0.6f }.filter { it.width >= 3 && it.height >= 3 }) {
            val left = blob.left / size.toFloat()
            val top = blob.top / size.toFloat()
            val width = blob.width / size.toFloat()
            val height = blob.height / size.toFloat()
            val centerX = left + width / 2f
            val centerY = top + height / 2f
            val column = (centerX * 48).toInt().coerceIn(0, 47)
            val row = (centerY * 48).toInt().coerceIn(0, 47)
            val anchor = row * 48 + column
            val anchorX = (column + 0.5f) / 48
            val anchorY = (row + 0.5f) / 48
            val base = anchor * FaceModel.ANCHOR_VALUES
            boxes[base] = (centerX - anchorX) * size
            boxes[base + 1] = (centerY - anchorY) * size
            boxes[base + 2] = width * size
            boxes[base + 3] = height * size
            // 점 여섯: 오른쪽 눈, 왼쪽 눈(위쪽 40% 줄의 30%·70%), 나머지는 가운데
            val eyeY = top + height * 0.4f
            val keypoints = floatArrayOf(left + width * 0.3f, eyeY, left + width * 0.7f, eyeY) + FloatArray(8) { if (it % 2 == 0) centerX else centerY }
            for (k in 0 until 6) {
                boxes[base + 4 + 2 * k] = (keypoints[2 * k] - anchorX) * size
                boxes[base + 5 + 2 * k] = (keypoints[2 * k + 1] - anchorY) * size
            }
            scores[anchor] = 5f
        }
        return DetectorOutput(boxes, scores)
    }

    override fun landmarks(input: FloatArray): LandmarkOutput {
        failure?.let { throw it }
        landmarkCalls++
        val size = FaceModel.LANDMARK_SIZE
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 3)
        val blob = redBlobs(input, size) { it > 0.8f }
            .filter { it.width >= 3 && it.height >= 3 }
            .minByOrNull { abs(it.left + it.width / 2f - size / 2f) + abs(it.top + it.height / 2f - size / 2f) }
        if (blob == null) {
            for (i in 0 until FaceModel.LANDMARK_COUNT) {
                points[3 * i] = size / 2f
                points[3 * i + 1] = size / 2f
            }
            return LandmarkOutput(points, -5f)
        }
        val centerX = blob.left + blob.width / 2f
        val centerY = blob.top + blob.height / 2f
        fun put(index: Int, x: Float, y: Float) {
            points[3 * index] = x
            points[3 * index + 1] = y
        }
        // 나머지 점은 상자 안 작은 타원, 윤곽은 상자에 꼭 맞는 타원(이마 위 끝이 맨 위)
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            val t = 2 * PI * i / FaceModel.LANDMARK_COUNT
            put(i, (centerX + blob.width * 0.25 * cos(t)).toFloat(), (centerY + blob.height * 0.25 * sin(t)).toFloat())
        }
        FaceMeshIndex.FACE_OVAL.forEachIndexed { k, index ->
            val t = -PI / 2 + 2 * PI * k / FaceMeshIndex.FACE_OVAL.size
            put(index, (centerX + blob.width / 2.0 * cos(t)).toFloat(), (centerY + blob.height / 2.0 * sin(t)).toFloat())
        }
        val eyeY = blob.top + blob.height * 0.4f
        put(FaceMeshIndex.RIGHT_IRIS, blob.left + blob.width * 0.3f, eyeY)
        put(FaceMeshIndex.LEFT_IRIS, blob.left + blob.width * 0.7f, eyeY)
        put(FaceMeshIndex.RIGHT_EYE_OUTER, blob.left + blob.width * 0.2f, eyeY)
        put(FaceMeshIndex.LEFT_EYE_OUTER, blob.left + blob.width * 0.8f, eyeY)
        put(FaceMeshIndex.CHIN, centerX, blob.top + blob.height.toFloat())
        return LandmarkOutput(points, 15f)
    }

    override fun release() {
        releases++
    }

    /** 입력 그림의 빨간 덩어리(빨강 값이 [bright]이고 초록·파랑이 어두운 픽셀, 4방향 이어짐)의 상자 */
    private fun redBlobs(input: FloatArray, size: Int, bright: (Float) -> Boolean): List<Blob> {
        // 입력 범위(-1..1 또는 0..1)와 상관없이 초록·파랑은 빨강보다 많이 어둡다
        val red = BooleanArray(size * size) { p -> bright(input[3 * p]) && input[3 * p] - input[3 * p + 1] > 0.5f && input[3 * p] - input[3 * p + 2] > 0.5f }
        val seen = BooleanArray(size * size)
        val blobs = ArrayList<Blob>()
        val stack = IntArray(size * size)
        for (start in red.indices) {
            if (!red[start] || seen[start]) continue
            var left = size
            var top = size
            var right = -1
            var bottom = -1
            var count = 0
            stack[count++] = start
            seen[start] = true
            while (count > 0) {
                val p = stack[--count]
                val x = p % size
                val y = p / size
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
                for (q in intArrayOf(p - 1, p + 1, p - size, p + size)) {
                    if (q < 0 || q >= red.size || seen[q] || !red[q]) continue
                    if ((q == p - 1 || q == p + 1) && q / size != y) continue
                    seen[q] = true
                    stack[count++] = q
                }
            }
            blobs += Blob(left, top, right - left + 1, bottom - top + 1)
        }
        return blobs
    }

    private class Blob(val left: Int, val top: Int, val width: Int, val height: Int)
}

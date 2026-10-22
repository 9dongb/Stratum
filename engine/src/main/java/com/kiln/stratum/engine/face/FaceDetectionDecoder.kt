package com.kiln.stratum.engine.face

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 찾은 얼굴 후보 하나: 점수(0..1), 그림 좌표의 상자([left]·[top]·[right]·[bottom]), 점 여섯의 x·y([keypoints]).
 * 점은 0 사람의 오른쪽 눈(정면 사진에서 왼쪽), 1 왼쪽 눈, 2 코끝, 3 입 가운데, 4 오른쪽 귀, 5 왼쪽 귀다.
 */
class FaceDetection(
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val keypoints: FloatArray
) {
    init {
        require(keypoints.size == KEYPOINTS * 2) { "Keypoints must be $KEYPOINTS x/y pairs: ${keypoints.size}" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /** 상자에 딸린 점 수 */
        const val KEYPOINTS = 6
    }
}

/**
 * 얼굴 찾기 모델(BlazeFace 먼 거리용) 결과 풀기. 미디어파이프 face_detection_full_range 그래프의 값 그대로다
 * (SsdAnchors: 한 층, 칸 4px(48×48), 앵커 크기 고정 / TensorsToDetections: 좌표 순서 x·y·폭·높이, 배율 192, 점수 시그모이드(±100에서 자름) /
 * NonMaxSuppression: 가중 평균, IoU 0.3).
 */
object FaceDetectionDecoder {

    /** 이 점수(0..1)보다 낮은 후보는 버린다(미디어파이프 얼굴 찾기의 기본 최소 신뢰도) */
    const val MIN_SCORE = 0.5f

    /** 이만큼(IoU) 넘게 겹치는 후보는 하나로 합친다 */
    const val SUPPRESS_IOU = 0.3f

    /** 앵커 칸 수(한 변). 입력 192px를 4px 칸으로 나눈다. */
    private const val GRID = 48

    private const val SCORE_CLIP = 100f

    /** 앵커 가운데(입력의 비율 좌표 x·y, 위 줄부터·한 줄 안에서는 왼쪽부터). 앵커 크기는 1(고정)이다. */
    val anchors: FloatArray = FloatArray(FaceModel.ANCHOR_COUNT * 2).also { anchors ->
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val i = row * GRID + column
                anchors[2 * i] = (column + 0.5f) / GRID
                anchors[2 * i + 1] = (row + 0.5f) / GRID
            }
        }
    }

    /**
     * [output]에서 점수가 [minScore] 이상인 후보. 입력은 그림의 [view] 정사각형을 읽은 것이고, 상자·점은 그림 좌표로 되돌린다.
     * 보기를 돌렸으면(90°씩) 상자는 돌린 상자를 둘러싼 상자다. 값이 숫자가 아니면(NaN) 버린다.
     */
    fun decode(output: DetectorOutput, view: FaceRoi, minScore: Float = MIN_SCORE): List<FaceDetection> {
        val size = FaceModel.DETECTOR_SIZE.toFloat()
        val boxes = output.boxes
        val found = ArrayList<FaceDetection>()
        for (i in 0 until FaceModel.ANCHOR_COUNT) {
            val score = sigmoid(output.scores[i].coerceIn(-SCORE_CLIP, SCORE_CLIP))
            // NaN도 여기서 걸러진다
            if (!(score >= minScore)) continue
            val base = i * FaceModel.ANCHOR_VALUES
            val anchorX = anchors[2 * i]
            val anchorY = anchors[2 * i + 1]
            val centerX = boxes[base] / size + anchorX
            val centerY = boxes[base + 1] / size + anchorY
            val halfWidth = boxes[base + 2] / size / 2f
            val halfHeight = boxes[base + 3] / size / 2f
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (corner in 0 until 4) {
                val a = centerX + if (corner % 2 == 0) -halfWidth else halfWidth
                val b = centerY + if (corner < 2) -halfHeight else halfHeight
                val x = view.imageX(a, b)
                val y = view.imageY(a, b)
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
            val keypoints = FloatArray(FaceDetection.KEYPOINTS * 2)
            for (k in 0 until FaceDetection.KEYPOINTS) {
                val a = boxes[base + 4 + 2 * k] / size + anchorX
                val b = boxes[base + 5 + 2 * k] / size + anchorY
                keypoints[2 * k] = view.imageX(a, b)
                keypoints[2 * k + 1] = view.imageY(a, b)
            }
            if (!(right > left && bottom > top) || keypoints.any { !it.isFinite() }) continue
            found += FaceDetection(score, left, top, right, bottom, keypoints)
        }
        return found
    }

    /**
     * 가중 NMS: 점수가 높은 후보부터, 그것과 [threshold] 넘게 겹치는 후보들을 점수 가중 평균(상자·점)으로 하나로 합친다.
     * 합친 후보의 점수는 가장 높은 것이다(미디어파이프의 WEIGHTED와 같다).
     */
    fun suppress(detections: List<FaceDetection>, threshold: Float = SUPPRESS_IOU): List<FaceDetection> {
        val remaining = detections.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<FaceDetection>()
        while (remaining.isNotEmpty()) {
            val top = remaining.removeAt(0)
            val group = arrayListOf(top)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (overlap(top, other) > threshold) {
                    group += other
                    iterator.remove()
                }
            }
            kept += weighted(group)
        }
        return kept
    }

    /** 두 상자가 겹치는 정도(IoU, 0..1) */
    fun overlap(a: FaceDetection, b: FaceDetection): Float = overlap(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)

    /** 두 상자(왼쪽·위·오른쪽·아래)가 겹치는 정도(IoU, 0..1) */
    fun overlap(
        aLeft: Float, aTop: Float, aRight: Float, aBottom: Float,
        bLeft: Float, bTop: Float, bRight: Float, bBottom: Float
    ): Float {
        val width = min(aRight, bRight) - max(aLeft, bLeft)
        val height = min(aBottom, bBottom) - max(aTop, bTop)
        if (width <= 0f || height <= 0f) return 0f
        val inter = width * height
        val union = (aRight - aLeft) * (aBottom - aTop) + (bRight - bLeft) * (bBottom - bTop) - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun weighted(group: List<FaceDetection>): FaceDetection {
        if (group.size == 1) return group[0]
        val total = group.sumOf { it.score.toDouble() }
        fun average(value: (FaceDetection) -> Float): Float = (group.sumOf { it.score.toDouble() * value(it) } / total).toFloat()
        val keypoints = FloatArray(FaceDetection.KEYPOINTS * 2) { k -> average { it.keypoints[k] } }
        return FaceDetection(group[0].score, average { it.left }, average { it.top }, average { it.right }, average { it.bottom }, keypoints)
    }

    private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))
}

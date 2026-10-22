package com.kiln.stratum.engine.face

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDetectionDecoderTest {

    @Test
    fun anchors_areTheCellCentersOfA48Grid_rowByRow() {
        val anchors = FaceDetectionDecoder.anchors

        assertEquals(2304 * 2, anchors.size)
        assertEquals(0.5f / 48, anchors[0], 1e-6f)
        assertEquals(0.5f / 48, anchors[1], 1e-6f)
        // 둘째 칸은 같은 줄 오른쪽, 49번째는 둘째 줄 첫 칸
        assertEquals(1.5f / 48, anchors[2], 1e-6f)
        assertEquals(0.5f / 48, anchors[3], 1e-6f)
        assertEquals(0.5f / 48, anchors[2 * 48], 1e-6f)
        assertEquals(1.5f / 48, anchors[2 * 48 + 1], 1e-6f)
        assertEquals(47.5f / 48, anchors[2 * 2303], 1e-6f)
        assertEquals(47.5f / 48, anchors[2 * 2303 + 1], 1e-6f)
    }

    @Test
    fun decode_offsetsFromTheAnchor_inInputPixels_andMapsToTheImage() {
        // 앵커 (줄 10, 칸 20). 가운데를 오른쪽으로 9.6px(입력), 위로 4.8px 옮기고, 폭 48px·높이 38.4px.
        val anchor = 10 * 48 + 20
        val output = output(anchor, logit = 2f, values = floatArrayOf(9.6f, -4.8f, 48f, 38.4f) + keypoints())
        // 그림 384×384 전체(입력 1px = 그림 2px)
        val view = FaceRoi.letterbox(384, 384)

        val found = FaceDetectionDecoder.decode(output, view)

        assertEquals(1, found.size)
        val face = found[0]
        val centerX = ((20 + 0.5f) / 48 + 9.6f / 192) * 384
        val centerY = ((10 + 0.5f) / 48 - 4.8f / 192) * 384
        assertEquals(1f / (1f + kotlin.math.exp(-2f)), face.score, 1e-5f)
        assertEquals(centerX - 48f, face.left, 1e-3f)
        assertEquals(centerX + 48f, face.right, 1e-3f)
        assertEquals(centerY - 38.4f, face.top, 1e-3f)
        assertEquals(centerY + 38.4f, face.bottom, 1e-3f)
        // 오른쪽 눈 점은 앵커에서 (-12, -6)px 떨어져 있다
        assertEquals(((20 + 0.5f) / 48 - 12f / 192) * 384, face.keypoints[0], 1e-3f)
        assertEquals(((10 + 0.5f) / 48 - 6f / 192) * 384, face.keypoints[1], 1e-3f)
    }

    @Test
    fun decode_aWideImage_removesTheLetterboxBorder() {
        // 400×200 그림은 위아래 100px씩 그림 밖이다. 입력 가운데(줄·칸 24의 왼쪽 위 끝)는 그림 가운데다.
        val anchor = 24 * 48 + 24
        val output = output(anchor, logit = 3f, values = floatArrayOf(-2f, -2f, 19.2f, 19.2f) + keypoints())

        val face = FaceDetectionDecoder.decode(output, FaceRoi.letterbox(400, 200)).single()

        assertEquals(200f, (face.left + face.right) / 2f, 1e-3f)
        assertEquals(100f, (face.top + face.bottom) / 2f, 1e-3f)
        assertEquals(40f, face.width, 1e-3f)
    }

    @Test
    fun decode_aTurnedView_turnsTheBoxAndPointsBack() {
        // 90° 돌려 읽은 보기: 입력의 오른쪽(+a)은 그림의 아래(+y)다
        val view = FaceRoi(100f, 100f, 200f, (PI / 2).toFloat())
        val anchor = 24 * 48 + 24
        // 입력에서 가운데보다 오른쪽 48px(그림 50px), 폭 38.4·높이 19.2
        val output = output(anchor, logit = 3f, values = floatArrayOf(48f - 2f, -2f, 38.4f, 19.2f) + keypoints())

        val face = FaceDetectionDecoder.decode(output, view).single()

        assertEquals(100f, (face.left + face.right) / 2f, 1e-3f)
        assertEquals(150f, (face.top + face.bottom) / 2f, 1e-3f)
        // 돌린 상자라 폭·높이가 바뀐다
        assertEquals(20f, face.width, 1e-3f)
        assertEquals(40f, face.height, 1e-3f)
    }

    @Test
    fun decode_skipsLowScores_andNotANumber() {
        val low = output(5, logit = -1f, values = floatArrayOf(0f, 0f, 10f, 10f) + keypoints())
        val nan = output(6, logit = Float.NaN, values = floatArrayOf(0f, 0f, 10f, 10f) + keypoints())

        assertTrue(FaceDetectionDecoder.decode(low, FaceRoi.letterbox(192, 192)).isEmpty())
        assertTrue(FaceDetectionDecoder.decode(nan, FaceRoi.letterbox(192, 192)).isEmpty())
    }

    @Test
    fun suppress_mergesOverlappingBoxes_byScoreWeightedAverage() {
        val strong = detection(0.9f, 0f, 0f, 100f, 100f)
        val weak = detection(0.6f, 10f, 0f, 110f, 100f)
        val apart = detection(0.7f, 300f, 300f, 340f, 340f)

        val kept = FaceDetectionDecoder.suppress(listOf(weak, apart, strong))

        assertEquals(2, kept.size)
        val merged = kept[0]
        assertEquals(0.9f, merged.score, 0f)
        assertEquals(10f * 0.6f / 1.5f, merged.left, 1e-4f)
        assertEquals(100f + 10f * 0.6f / 1.5f, merged.right, 1e-4f)
        assertEquals(10f * 0.6f / 1.5f, merged.keypoints[0], 1e-4f)
        assertEquals(0.7f, kept[1].score, 0f)
        assertEquals(300f, kept[1].left, 0f)
    }

    @Test
    fun overlap_isIntersectionOverUnion() {
        val a = detection(1f, 0f, 0f, 10f, 10f)
        val b = detection(1f, 5f, 0f, 15f, 10f)

        assertEquals(50f / 150f, FaceDetectionDecoder.overlap(a, b), 1e-6f)
        assertEquals(0f, FaceDetectionDecoder.overlap(a, detection(1f, 20f, 20f, 30f, 30f)), 0f)
    }

    /** 앵커 [anchor] 하나만 점수 [logit]이고 값이 [values]인 결과(나머지는 점수 -20) */
    private fun output(anchor: Int, logit: Float, values: FloatArray): DetectorOutput {
        val boxes = FloatArray(FaceModel.ANCHOR_COUNT * FaceModel.ANCHOR_VALUES)
        values.copyInto(boxes, anchor * FaceModel.ANCHOR_VALUES)
        val scores = FloatArray(FaceModel.ANCHOR_COUNT) { -20f }
        scores[anchor] = logit
        return DetectorOutput(boxes, scores)
    }

    /** 점 여섯(앵커에서 떨어진 입력 픽셀): 오른쪽 눈 (-12, -6), 왼쪽 눈 (12, -6), 나머지 0 */
    private fun keypoints(): FloatArray = floatArrayOf(-12f, -6f, 12f, -6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    private fun detection(score: Float, left: Float, top: Float, right: Float, bottom: Float) =
        FaceDetection(score, left, top, right, bottom, FloatArray(12) { if (it % 2 == 0) left else top })
}

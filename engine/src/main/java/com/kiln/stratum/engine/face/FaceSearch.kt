package com.kiln.stratum.engine.face

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 얼굴을 찾을 그림. 정사각형 한 곳을 모델 입력 크기로 읽어 준다(플랫폼은 안드로이드 그리기, 테스트는 가짜). */
interface FaceImage {
    val width: Int
    val height: Int

    /**
     * [roi]를 [size]² ARGB 픽셀(줄마다 이어 붙임)로 쌍선형으로 읽는다. 투명한 곳은 흰 바탕에 합친다.
     * 그림 밖은 [outside]대로 칠한다.
     */
    fun read(roi: FaceRoi, size: Int, outside: Outside): IntArray

    /** 그림 밖을 읽을 때 */
    enum class Outside {
        /** 검정(얼굴 찾기, 미디어파이프의 빈 테두리) */
        BLACK,

        /** 가장자리 픽셀을 늘인다(특징점) */
        EDGE
    }
}

/**
 * 그림에서 얼굴을 찾는 순서(결정 기록 '얼굴 인식 보정'). 미디어파이프 얼굴 특징점 그래프와 같은 앞뒤 처리를 하고,
 * 단체 사진의 작은 얼굴을 찾으려고 조각으로 한 번 더 찾는다. 모델 부르기와 그림 읽기는 밖에서 받는다.
 *
 * 1. 얼굴 찾기: 그림 전체(비율 유지)와 정사각형 조각([tiles])을 192²로 읽어 찾고, 조각의 안쪽 경계에 걸린 후보는 버린 뒤 가중 NMS로 합친다.
 * 2. 특징점: 후보 상자로 자른 곳(1.5배, 두 눈으로 돌림)에서 한 번, 그 점들로 다시 자른 곳(눈꼬리로 돌림)에서 한 번 더 읽고 두 번째를 쓴다.
 *    얼굴 있음 로짓이 [PRESENCE_MIN]보다 작으면 버린다.
 * 3. 하나도 없으면 그림 전체를 90°·270°·180° 돌려 차례로 다시 찾는다(옆으로·거꾸로 누운 레이어). 찾으면 그만한다.
 * 4. 특징점 상자가 겹치는 얼굴은 하나만(얼굴 있음이 큰 것) 남기고, 두 눈 사이가 작은 얼굴은 빼고, [MAX_FACES]개까지(얼굴 있음 순) 남긴다.
 *    왼쪽부터(두 눈 가운데 x) 차례로 돌려준다.
 */
class FaceSearch(private val model: FaceModel) {

    /**
     * [image]에서 찾은 얼굴(그림 좌표). 두 눈동자 사이가 [minEyeDistance](그림 픽셀)보다 작은 얼굴은 뺀다.
     * [checkCanceled]는 모델을 부르기 전마다 부르며, 취소됐으면 예외를 던지게 한다.
     * 모델을 이 기기에서 쓸 수 없으면 [FaceUnavailableException]을 던진다.
     */
    fun find(image: FaceImage, minEyeDistance: Float = 0f, checkCanceled: () -> Unit = {}): List<FaceLandmarks> {
        val detections = ArrayList<FaceDetection>()
        detections += detect(image, FaceRoi.letterbox(image.width, image.height), checkCanceled)
        for (tile in tiles(image.width, image.height)) {
            detections += detect(image, tile, checkCanceled).filterNot { cutByTile(it, tile, image.width, image.height) }
        }
        var faces = landmarksOf(image, FaceDetectionDecoder.suppress(detections), checkCanceled)
        if (faces.isEmpty()) {
            for (turns in FALLBACK_TURNS) {
                val turned = detect(image, FaceRoi.letterbox(image.width, image.height, turns), checkCanceled)
                faces = landmarksOf(image, FaceDetectionDecoder.suppress(turned), checkCanceled)
                if (faces.isNotEmpty()) break
            }
        }
        return distinct(faces)
            .filter { it.eyeDistance >= minEyeDistance }
            .sortedByDescending { it.presence }
            .take(MAX_FACES)
            .sortedBy { it.eyeCenterX }
    }

    /** [view]를 읽어 찾은 후보(그림 좌표) */
    private fun detect(image: FaceImage, view: FaceRoi, checkCanceled: () -> Unit): List<FaceDetection> {
        checkCanceled()
        val pixels = image.read(view, FaceModel.DETECTOR_SIZE, FaceImage.Outside.BLACK)
        return FaceDetectionDecoder.decode(model.detect(FaceTensor.detectorInput(pixels)), view)
    }

    /** 후보마다 특징점을 두 번 읽어(상자로 한 번, 점으로 한 번) 얼굴이 맞는 것만. 첫 번째에서 얼굴이 아니면 다시 읽지 않는다. */
    private fun landmarksOf(image: FaceImage, detections: List<FaceDetection>, checkCanceled: () -> Unit): List<FaceLandmarks> =
        detections.mapNotNull { detection ->
            val first = landmarksAt(image, FaceRoi.fromDetection(detection), checkCanceled)
            if (!first.isFinite || first.presence < PRESENCE_MIN) return@mapNotNull null
            val roi = FaceRoi.fromLandmarks(first)
            if (!(roi.side >= MIN_CROP_SIDE)) return@mapNotNull null
            val second = landmarksAt(image, roi, checkCanceled)
            second.takeIf { it.isFinite && it.presence >= PRESENCE_MIN }
        }

    /** [roi]를 읽은 특징점(그림 좌표) */
    private fun landmarksAt(image: FaceImage, roi: FaceRoi, checkCanceled: () -> Unit): FaceLandmarks {
        checkCanceled()
        val size = FaceModel.LANDMARK_SIZE
        val output = model.landmarks(FaceTensor.landmarkInput(image.read(roi, size, FaceImage.Outside.EDGE)))
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 2)
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            val a = output.points[3 * i] / size
            val b = output.points[3 * i + 1] / size
            points[2 * i] = roi.imageX(a, b)
            points[2 * i + 1] = roi.imageY(a, b)
        }
        return FaceLandmarks(points, output.presence)
    }

    /** 특징점 상자가 [DUPLICATE_IOU] 넘게 겹치는 얼굴은 얼굴 있음이 큰 하나만 */
    private fun distinct(faces: List<FaceLandmarks>): List<FaceLandmarks> {
        val kept = ArrayList<Pair<FaceLandmarks, FloatArray>>()
        for (face in faces.sortedByDescending { it.presence }) {
            val box = face.bounds()
            val duplicate = kept.any { (_, other) ->
                FaceDetectionDecoder.overlap(box[0], box[1], box[2], box[3], other[0], other[1], other[2], other[3]) > DUPLICATE_IOU
            }
            if (!duplicate) kept += face to box
        }
        return kept.map { it.first }
    }

    companion object {
        /** 이 로짓보다 얼굴 있음이 작으면 얼굴이 아니다. 맥 시험에서 실제 얼굴은 20 안팎, 얼굴이 아닌 곳(옷 단추) -9, 135° 돌린 사진의 잘못 자른 곳 2.3이었다. */
        const val PRESENCE_MIN = 3f

        /** 특징점 상자가 이만큼(IoU) 넘게 겹치면 같은 얼굴이다 */
        const val DUPLICATE_IOU = 0.3f

        /** 돌려 줄 얼굴 수의 최대 */
        const val MAX_FACES = 8

        /** 조각 한 변: 그림 긴 변의 이만큼(짧은 변을 넘지 않음) */
        const val TILE_FRACTION = 0.4f

        /** 이웃 조각이 적어도 이만큼(조각 한 변의 비율) 겹친다 */
        const val TILE_OVERLAP = 0.25f

        /** 조각이 모델 입력의 이만큼보다 크지 않으면 조각으로 찾지 않는다(그림 전체로 충분하다) */
        private const val TILE_MIN_ZOOM = 1.2f

        /** 특징점으로 다시 자를 곳이 이보다(그림 픽셀) 작으면 얼굴이 아니다(점이 한곳에 모임) */
        private const val MIN_CROP_SIDE = 4f

        /** 못 찾았을 때 다시 찾는 돌림(90°씩): 90°, 270°, 180° */
        private val FALLBACK_TURNS = intArrayOf(1, 3, 2)

        /**
         * 작은 얼굴을 찾는 정사각형 조각들: 한 변은 긴 변의 [TILE_FRACTION](짧은 변을 넘지 않음)이고, 가로·세로로 고르게 놓아
         * 이웃끼리 [TILE_OVERLAP] 이상 겹치며 첫 조각은 0, 끝 조각은 그림 끝에 닿는다. 조각이 작으면(입력의 [TILE_MIN_ZOOM]배 이하) 없다.
         */
        fun tiles(width: Int, height: Int): List<FaceRoi> {
            val side = min(max(width, height) * TILE_FRACTION, min(width, height).toFloat())
            if (side <= FaceModel.DETECTOR_SIZE * TILE_MIN_ZOOM) return emptyList()
            fun starts(length: Int): List<Float> {
                if (length <= side + 0.5f) return listOf(0f)
                val count = ceil((length - side) / (side * (1f - TILE_OVERLAP))).toInt() + 1
                return List(count) { it * (length - side) / (count - 1) }
            }
            return starts(height).flatMap { top -> starts(width).map { left -> FaceRoi(left + side / 2f, top + side / 2f, side) } }
        }

        /** 조각 [tile]에서 찾은 [detection]이 조각의 안쪽 경계(그림 끝이 아닌 변)를 넘는지. 넘으면 잘린 얼굴이라 이웃 조각·그림 전체에 맡긴다. */
        internal fun cutByTile(detection: FaceDetection, tile: FaceRoi, width: Int, height: Int): Boolean {
            val half = tile.side / 2f
            val left = tile.centerX - half
            val top = tile.centerY - half
            val right = tile.centerX + half
            val bottom = tile.centerY + half
            return (detection.left < left && left > EDGE_EPSILON) ||
                (detection.top < top && top > EDGE_EPSILON) ||
                (detection.right > right && right < width - EDGE_EPSILON) ||
                (detection.bottom > bottom && bottom < height - EDGE_EPSILON)
        }

        private const val EDGE_EPSILON = 0.5f
    }
}

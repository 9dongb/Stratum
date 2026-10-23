package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceMeshIndex
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.FaceRoi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 얼굴 하나의 피부 마스크(결정 기록 '피부 보정'의 2b). 0단계 맥 시험(skinlib.skin_mask)과 같은 순서다.
 *
 * 1. 얼굴 둘레 정사각형([frameOf], 눈 선으로 세움)을 잘라 분할 모델([SkinModel])에 넣는다.
 * 2. 이 얼굴의 피부: 코끝이 든 (얼굴 피부 ∪ 몸 피부) 덩어리. 그 안의 얼굴 피부는 윤곽과 많이 겹치는 조각만(이어 붙은 옆 사람 얼굴을 빼려고),
 *    몸 피부는 목 둘레와 넓힌 윤곽 안만(옆 조명 사진에서 무릎이 들어왔다).
 * 3. 특징점의 눈·눈썹·입술·콧구멍을 조금 넓혀 부드럽게 뺀다.
 * 4. 색으로 빼기만 하는 다듬기: 피부 가운데 색에서 먼 곳(안경·물건), 피부보다 많이 어둡고 채도가 낮은 곳(수염·머리카락).
 *    색으로 더하지는 않는다(붉은 옷·배경이 피부로 들어오지 않게).
 * 5. 밝기 길잡이 가이드 필터로 경계를 사진의 경계에 맞춘다.
 * 분할 모델을 쓸 수 없으면 2 대신 윤곽 안을 쓴다(목·귀·이마 위는 빠진다).
 *
 * 길이의 단위 U는 두 눈꼬리(33·263) 사이다. 모두 [MASK_SIZE]² 이하 배열에서만 돈다(CLAUDE.md 2장).
 */
object SkinRegion {

    /** 마스크 한 변(잘라 세운 정사각형을 이 크기로 읽음) */
    const val MASK_SIZE = 512

    /** 정사각형 한 변 = 특징점 범위(눈 선 방향으로 잰 폭·높이 중 큰 것)의 이 배 */
    const val FRAME_SCALE = 2.2f

    /** 정사각형 가운데를 특징점 가운데에서 얼굴 높이의 이만큼 턱 쪽으로(목이 들게) */
    const val FRAME_DOWN = 0.18f

    /** 분할 확률이 이것을 넘으면 그 갈래다 */
    private const val CLASS_THRESHOLD = 0.5f

    /** 얼굴 피부 조각이 이 얼굴 것인지: 넓힌 윤곽과 조각 넓이의 이만큼 넘게 겹치면 */
    private const val OWN_OVERLAP = 0.3f

    /** 그 윤곽은 정사각형 한 변의 이만큼 넓힌다 */
    private const val OWN_OVAL_GROW = 0.06f

    /** 몸 피부를 쓰는 곳: 윤곽을 이만큼(U) 넓힌 곳과 목 둘레 */
    private const val BODY_OVAL_GROW_U = 0.25f

    /** 목 둘레: 턱 끝 아래로 얼굴 높이의 이만큼, 얼굴 폭의 이 배 */
    private const val NECK_DEPTH = 0.9f
    private const val NECK_WIDTH = 1.4f

    /** 뺄 곳과 넓히는 정도(U): 눈 0.04, 눈썹 0.03, 입술 0.03, 콧구멍 0.015 */
    private val FEATURES = listOf(
        FaceMeshIndex.RIGHT_EYE to 0.04f,
        FaceMeshIndex.LEFT_EYE to 0.04f,
        FaceMeshIndex.RIGHT_BROW_OUTLINE to 0.03f,
        FaceMeshIndex.LEFT_BROW_OUTLINE to 0.03f,
        FaceMeshIndex.LIPS_OUTER to 0.03f,
        FaceMeshIndex.RIGHT_NOSTRIL to 0.015f,
        FaceMeshIndex.LEFT_NOSTRIL to 0.015f
    )

    /** 뺄 곳의 경계 흐림(가우스 표준 편차, U) */
    private const val FEATURE_BLUR_U = 0.02f

    /** 정사각형 끝에서 마스크를 0으로 줄이는 띠(한 변의 비율). 목이 정사각형 밖으로 이어져도 끝이 끊겨 보이지 않게. */
    private const val BORDER_FADE = 0.04f

    /** 색 다듬기의 기준(피부 가운데 색)을 잴 곳: 마스크가 이것을 넘는 윤곽 안. 그런 칸이 [MIN_CORE]보다 적으면 다듬지 않는다. */
    private const val CORE_LEVEL = 0.9f
    private const val MIN_CORE = 64

    /** 피부 가운데 색에서 a·b 거리가 (가운데값 + 이 배 × 1.4826 × MAD)를 넘으면 빼기 시작한다 */
    private const val FAR_MADS = 3f

    /** 피부보다 L이 이만큼 넘게 어둡고([DARK_RAMP]에 걸쳐 다 뺌), 채도가 피부의 [DARK_CHROMA]배 아래면([DARK_CHROMA_RAMP]에 걸쳐) 뺀다 */
    private const val DARK_STEP = 0.18f
    private const val DARK_RAMP = 0.08f
    private const val DARK_CHROMA = 0.6f
    private const val DARK_CHROMA_RAMP = 0.2f

    /** 가이드 필터: 반지름 U × 이것(2칸 이상), ε */
    private const val GUIDE_RADIUS_U = 0.03f
    private const val GUIDE_EPSILON = 1e-3f

    /**
     * [face](문서 좌표)의 피부를 담을 정사각형(문서 좌표). 두 눈꼬리를 잇는 선이 가로가 되게 돌리고, 한 변은 그 방향으로 잰
     * 특징점 범위의 [FRAME_SCALE]배, 가운데는 특징점 가운데에서 얼굴 높이의 [FRAME_DOWN]만큼 턱 쪽이다.
     */
    fun frameOf(face: FaceLandmarks): FaceRoi {
        val right = FaceMeshIndex.RIGHT_EYE_OUTER
        val left = FaceMeshIndex.LEFT_EYE_OUTER
        val angle = atan2((face.y(left) - face.y(right)).toDouble(), (face.x(left) - face.x(right)).toDouble())
        val cosine = cos(angle)
        val sine = sin(angle)
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            meanX += face.x(i)
            meanY += face.y(i)
        }
        meanX /= FaceModel.LANDMARK_COUNT
        meanY /= FaceModel.LANDMARK_COUNT
        var minU = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            val dx = face.x(i) - meanX
            val dy = face.y(i) - meanY
            val u = dx * cosine + dy * sine
            val v = -dx * sine + dy * cosine
            minU = min(minU, u)
            maxU = max(maxU, u)
            minV = min(minV, v)
            maxV = max(maxV, v)
        }
        val height = maxV - minV
        val side = FRAME_SCALE * max(maxU - minU, height)
        return FaceRoi(
            centerX = (meanX - sine * FRAME_DOWN * height).toFloat(),
            centerY = (meanY + cosine * FRAME_DOWN * height).toFloat(),
            side = side.toFloat(),
            angle = angle.toFloat()
        )
    }

    /** [frame]을 [size]²로 읽었을 때 [face]의 점들(칸 좌표, 점마다 x·y를 이어 붙임) */
    fun cropPoints(face: FaceLandmarks, frame: FaceRoi, size: Int): FloatArray {
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 2)
        for (i in 0 until FaceModel.LANDMARK_COUNT) {
            points[2 * i] = frame.cropA(face.x(i), face.y(i)) * size
            points[2 * i + 1] = frame.cropB(face.x(i), face.y(i)) * size
        }
        return points
    }

    /** 분할 모델 입력: [pixels]([SkinModel.INPUT_SIZE]² ARGB)의 RGB를 0..1로 */
    fun modelInput(pixels: IntArray): FloatArray {
        require(pixels.size == SkinModel.INPUT_SIZE * SkinModel.INPUT_SIZE) { "Expected ${SkinModel.INPUT_SIZE}² pixels: ${pixels.size}" }
        val input = FloatArray(SkinModel.INPUT_VALUES)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            input[3 * i] = ((pixel shr 16) and 0xFF) / 255f
            input[3 * i + 1] = ((pixel shr 8) and 0xFF) / 255f
            input[3 * i + 2] = (pixel and 0xFF) / 255f
        }
        return input
    }

    /** 칸마다 갈래 로짓의 소프트맥스(확률) */
    fun probabilities(logits: FloatArray): FloatArray {
        require(logits.size == SkinModel.OUTPUT_VALUES) { "Expected ${SkinModel.OUTPUT_VALUES} logits: ${logits.size}" }
        val classes = SkinModel.CLASS_COUNT
        val out = FloatArray(logits.size)
        for (cell in 0 until logits.size / classes) {
            val base = cell * classes
            var top = Float.NEGATIVE_INFINITY
            for (k in 0 until classes) top = max(top, logits[base + k])
            var total = 0f
            for (k in 0 until classes) {
                val e = exp(logits[base + k] - top)
                out[base + k] = e
                total += e
            }
            for (k in 0 until classes) out[base + k] /= total
        }
        return out
    }

    /**
     * 이 얼굴의 피부 마스크([size]², 0..1, 잘라 세운 정사각형의 칸).
     * [probabilities]는 같은 정사각형을 [SkinModel.INPUT_SIZE]²로 읽은 분할 확률([probabilities] 함수의 결과)이고, 없으면 윤곽만 쓴다.
     * [crop]은 같은 정사각형을 [size]²로 읽은 사진(ARGB, 색 다듬기의 길잡이)이다.
     */
    fun mask(probabilities: FloatArray?, face: FaceLandmarks, frame: FaceRoi, crop: IntArray, size: Int = MASK_SIZE): FloatArray {
        require(crop.size == size * size) { "Expected $size² pixels: ${crop.size}" }
        val points = cropPoints(face, frame, size)
        val unit = unitOf(points)
        val skin = if (probabilities != null) {
            SkinMaps.resize(segmentedSkin(probabilities, face, frame), SkinModel.INPUT_SIZE, size)
        } else {
            val oval = SkinMaps.polygon(points, FaceMeshIndex.FACE_OVAL, size, size)
            SkinMaps.gaussian(FloatArray(oval.size) { if (oval[it]) 1f else 0f }, size, size, unit * FEATURE_BLUR_U)
        }
        val features = features(points, size, unit)
        val mask = FloatArray(size * size) { skin[it].coerceIn(0f, 1f) * (1f - features[it]) }
        fadeBorder(mask, size)
        // 가이드 필터가 끝 띠에 값을 조금 되살릴 수 있어 한 번 더 줄인다(정사각형 끝에서 마스크가 끊겨 보이지 않게)
        return refine(mask, crop, points, size, unit).also { fadeBorder(it, size) }
    }

    /** 두 눈꼬리 사이(칸) */
    private fun unitOf(points: FloatArray): Float {
        val right = FaceMeshIndex.RIGHT_EYE_OUTER
        val left = FaceMeshIndex.LEFT_EYE_OUTER
        return max(1f, hypot(points[2 * left] - points[2 * right], points[2 * left + 1] - points[2 * right + 1]))
    }

    /** 분할 확률([SkinModel.INPUT_SIZE]²)에서 이 얼굴의 피부 확률(위 2) */
    private fun segmentedSkin(probabilities: FloatArray, face: FaceLandmarks, frame: FaceRoi): FloatArray {
        val n = SkinModel.INPUT_SIZE
        val classes = SkinModel.CLASS_COUNT
        val cells = n * n
        val faceSkin = BooleanArray(cells) { probabilities[it * classes + SkinModel.FACE_SKIN] > CLASS_THRESHOLD }
        val bodySkin = BooleanArray(cells) { probabilities[it * classes + SkinModel.BODY_SKIN] > CLASS_THRESHOLD }
        val (labels, _) = SkinMaps.labels(BooleanArray(cells) { faceSkin[it] || bodySkin[it] }, n, n)
        val points = cropPoints(face, frame, n)
        val noseX = floor(points[2 * FaceMeshIndex.NOSE_TIP]).toInt().coerceIn(0, n - 1)
        val noseY = floor(points[2 * FaceMeshIndex.NOSE_TIP + 1]).toInt().coerceIn(0, n - 1)
        val keep = labels[noseY * n + noseX]
        // 이 얼굴: 코끝이 든 덩어리(코끝이 피부가 아니면 얼굴 피부 전부에서 고른다)
        val region = BooleanArray(cells) { if (keep != 0) labels[it] == keep else faceSkin[it] }
        // 그 안의 얼굴 피부 조각 가운데 넓힌 윤곽과 많이 겹치는 것만(이어 붙은 옆 사람 얼굴을 뺀다)
        val oval = SkinMaps.dilate(SkinMaps.polygon(points, FaceMeshIndex.FACE_OVAL, n, n), n, n, n * OWN_OVAL_GROW)
        val (faceLabels, faceCount) = SkinMaps.labels(BooleanArray(cells) { faceSkin[it] && region[it] }, n, n)
        val area = IntArray(faceCount + 1)
        val overlap = IntArray(faceCount + 1)
        for (i in 0 until cells) {
            val label = faceLabels[i]
            if (label == 0) continue
            area[label]++
            if (oval[i]) overlap[label]++
        }
        val own = BooleanArray(faceCount + 1) { it > 0 && overlap[it] > OWN_OVERLAP * area[it] }
        // 몸 피부는 목 둘레와 넓힌 윤곽 안만
        val allowed = SkinMaps.dilate(SkinMaps.polygon(points, FaceMeshIndex.FACE_OVAL, n, n), n, n, unitOf(points) * BODY_OVAL_GROW_U)
        markNeck(points, n, allowed)
        return FloatArray(cells) {
            when {
                own[faceLabels[it]] -> probabilities[it * classes + SkinModel.FACE_SKIN] + probabilities[it * classes + SkinModel.BODY_SKIN]
                region[it] && !faceSkin[it] && allowed[it] -> probabilities[it * classes + SkinModel.BODY_SKIN]
                else -> 0f
            }
        }
    }

    /** 턱 끝 아래로 얼굴 높이의 [NECK_DEPTH]만큼, 얼굴 폭의 [NECK_WIDTH]배인 목 둘레 상자를 [out]에 더한다(잘라 세운 정사각형이라 얼굴이 곧게 서 있다) */
    private fun markNeck(points: FloatArray, n: Int, out: BooleanArray) {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (i in FaceMeshIndex.FACE_OVAL) {
            left = min(left, points[2 * i])
            right = max(right, points[2 * i])
            top = min(top, points[2 * i + 1])
            bottom = max(bottom, points[2 * i + 1])
        }
        val chinY = points[2 * FaceMeshIndex.CHIN + 1]
        val centerX = (left + right) / 2f
        val halfWidth = (right - left) * NECK_WIDTH / 2f
        val x0 = floor(centerX - halfWidth).toInt().coerceIn(0, n)
        val x1 = floor(centerX + halfWidth).toInt().coerceIn(0, n)
        val y0 = floor(chinY - (bottom - top) * 0.1f).toInt().coerceIn(0, n)
        val y1 = floor(chinY + (bottom - top) * NECK_DEPTH).toInt().coerceIn(0, n)
        for (y in y0 until y1) for (x in x0 until x1) out[y * n + x] = true
    }

    /** 뺄 곳(눈·눈썹·입술·콧구멍)의 정도([size]², 0..1). 다각형마다 둘레 창 안에서만 계산한다. */
    private fun features(points: FloatArray, size: Int, unit: Float): FloatArray {
        val out = FloatArray(size * size)
        val sigma = unit * FEATURE_BLUR_U
        for ((indices, grow) in FEATURES) {
            val radius = unit * grow
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (i in indices) {
                minX = min(minX, points[2 * i])
                maxX = max(maxX, points[2 * i])
                minY = min(minY, points[2 * i + 1])
                maxY = max(maxY, points[2 * i + 1])
            }
            val margin = radius + 3f * sigma + 2f
            val left = floor(minX - margin).toInt().coerceIn(0, size)
            val top = floor(minY - margin).toInt().coerceIn(0, size)
            val right = (floor(maxX + margin).toInt() + 1).coerceIn(0, size)
            val bottom = (floor(maxY + margin).toInt() + 1).coerceIn(0, size)
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) continue
            val inside = BooleanArray(width * height)
            SkinMaps.fill(points, indices, left, top, width, height, inside)
            val grown = SkinMaps.dilate(inside, width, height, radius)
            val soft = SkinMaps.gaussian(FloatArray(grown.size) { if (grown[it]) 1f else 0f }, width, height, sigma)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = (top + y) * size + left + x
                    out[index] = max(out[index], soft[y * width + x])
                }
            }
        }
        return out
    }

    /** 정사각형 끝 [BORDER_FADE] 띠에서 마스크를 0으로 줄인다 */
    private fun fadeBorder(mask: FloatArray, size: Int) {
        val band = max(1f, size * BORDER_FADE)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // 맨 끝 칸은 0이다
                val edge = min(min(x, size - 1 - x), min(y, size - 1 - y)).toFloat()
                if (edge < band) mask[y * size + x] *= smoothstep(0f, band, edge)
            }
        }
    }

    /** 위 4·5: 색으로 빼기만 하는 다듬기와 가이드 필터 */
    private fun refine(mask: FloatArray, crop: IntArray, points: FloatArray, size: Int, unit: Float): FloatArray {
        val (lightness, a, b) = OkLab.fromPixels(crop)
        val oval = SkinMaps.polygon(points, FaceMeshIndex.FACE_OVAL, size, size)
        val core = BooleanArray(mask.size) { mask[it] > CORE_LEVEL && oval[it] }
        if (core.count { it } >= MIN_CORE) {
            val refL = SkinMaps.median(lightness, core)
            val refA = SkinMaps.median(a, core)
            val refB = SkinMaps.median(b, core)
            val distance = FloatArray(mask.size) { hypot(a[it] - refA, b[it] - refB) }
            val middle = SkinMaps.median(distance, core)
            val deviation = SkinMaps.median(FloatArray(mask.size) { kotlin.math.abs(distance[it] - middle) }, core) + 1e-4f
            val spread = middle + FAR_MADS * 1.4826f * deviation
            val refChroma = max(hypot(refA, refB), 1e-4f)
            for (i in mask.indices) {
                val far = ((distance[i] - spread) / (spread + 1e-4f)).coerceIn(0f, 1f)
                val chroma = hypot(a[i], b[i])
                val dark = (((refL - lightness[i]) - DARK_STEP) / DARK_RAMP).coerceIn(0f, 1f) *
                    ((DARK_CHROMA - chroma / refChroma) / DARK_CHROMA_RAMP).coerceIn(0f, 1f)
                mask[i] *= (1f - far) * (1f - dark)
            }
        }
        val radius = max(2, (unit * GUIDE_RADIUS_U).toInt())
        val guided = SkinMaps.guided(lightness, mask, size, size, radius, GUIDE_EPSILON)
        for (i in guided.indices) guided[i] = guided[i].coerceIn(0f, 1f)
        return guided
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

package com.kiln.stratum.engine.face

import com.kiln.stratum.engine.liquify.MeshWarp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** 얼굴 보정 슬라이더(결정 기록 '얼굴 인식 보정'). 값은 −1..1(화면의 −100..+100)이고 + = 크게·넓게다. */
enum class FaceSlider {
    /** 눈 크기: 두 눈을 저마다의 가운데에서 함께 키우거나 줄인다 */
    EYE_SIZE,

    /** 코 너비: 콧방울 사이를 콧방울 선 쪽으로만 넓히거나 좁힌다 */
    NOSE_WIDTH,

    /** 얼굴 너비: 볼·턱 윤곽을 얼굴 가로축(눈 선) 쪽으로만 밖·안으로 옮긴다 */
    FACE_WIDTH
}

/** 얼굴 하나의 슬라이더 값(−1..1). 한 번 만들면 바꾸지 않는다([with]는 새 값). */
class FaceValues private constructor(private val values: FloatArray) {

    operator fun get(slider: FaceSlider): Float = values[slider.ordinal]

    /** [slider]만 [value](−1..1로 묶음)로 바꾼 값 */
    fun with(slider: FaceSlider, value: Float): FaceValues {
        val next = values.copyOf()
        // -0은 0으로(같은 값끼리 같다고 보게)
        next[slider.ordinal] = value.coerceIn(-1f, 1f) + 0f
        return FaceValues(next)
    }

    /** 모두 0인지(고친 것이 없음) */
    val isZero: Boolean
        get() = values.all { it == 0f }

    override fun equals(other: Any?): Boolean = other is FaceValues && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = FaceSlider.entries.joinToString(prefix = "FaceValues(", postfix = ")") { "${it.name}=${this[it]}" }

    companion object {
        /** 고친 것이 없는 값 */
        val ZERO = FaceValues(FloatArray(FaceSlider.entries.size))
    }
}

/**
 * 얼굴 보정 슬라이더 값을 유동화 변형([MeshWarp])으로 바꾼다(계획 '부위 계산'). 변형은 [com.kiln.stratum.engine.liquify.LiquifyMesh]의
 * 격자에 합쳐 지금의 미리 보기·적용으로 그린다(새 변형 엔진 없음). 값은 모두 특징점(문서 좌표)에서 정하므로 얼굴이 기울거나 커도 부위 방향·크기를 따른다.
 *
 * - 눈 크기: 눈마다 윤곽 16점의 가운데에서 늘이고 줄인다. 타원은 눈꼬리–눈머리 축으로 눈꼬리 쪽 눈 폭의 [EYE_OUTER]배, 눈머리 쪽 [EYE_INNER]배
 *   (콧등까지 닿지 않게 좁다), 세로는 [EYE_TALL]배다(세로는 두 눈 폭의 평균보다 작게 잡지 않는다: 고개를 돌려 먼 눈이 좁게 보여도 세로 크기는 그대로라서).
 * - 코 너비: 콧방울(129·358) 가운데에서 콧방울 선 쪽으로만 늘이고 줄인다. 위로는 콧등 반까지, 아래로는 윗입술 위에서 끝난다.
 * - 얼굴 너비: [FaceWidthWarp].
 * - 감쇠: 타원 거리 [PLATEAU]까지는 다 걸리고 경계(1)에서 0이 되게 5차 스무드스텝으로 줄어 부위 상자 밖은 정확히 0이다(배경이 휘지 않음).
 *
 * 늘이고 줄이는 부위는 사진에서 보이는 크기에 비례해 바뀌므로, 고개를 돌려 좁게 보이는 먼 쪽 눈도 같은 비율로 고쳐진다.
 * 옮기는 부위(얼굴 너비)는 먼 쪽을 좁게 보이는 만큼 덜 옮긴다(2026-10-22 사용자 요청: "돌린 얼굴의 먼 쪽을 약하게 고치는 것도 2단계에 넣으세요").
 * 모든 변형은 접히지 않는다(되돌려 찾는 지도의 야코비안이 ±100%에서도 0.4 이상, 단위 테스트).
 */
object FaceWarps {

    /** 슬라이더 +100%에서 눈을 키우는 배율(−100%는 그 역수) */
    const val EYE_MAX_SCALE = 1.22f

    /** 눈 타원의 가로 반지름 = 눈 폭 × 이것: 눈꼬리 쪽(키운 눈꼬리도 다 걸리게 넉넉히), 눈머리 쪽(콧등에 닿기 전에 0) */
    const val EYE_OUTER = 1.2f
    const val EYE_INNER = 0.95f

    /** 눈 타원의 세로 반지름 = 눈 폭(두 눈 평균보다 작지 않게) × 이것 */
    const val EYE_TALL = 0.75f

    /** 슬라이더 +100%에서 코(콧방울 사이)를 넓히는 배율 */
    const val NOSE_MAX_SCALE = 1.22f

    /** 코 타원: 콧방울 선 쪽 반지름, 위(눈 쪽), 아래(입 쪽) = 콧방울 사이 × 이것 */
    const val NOSE_ACROSS = 1.15f
    const val NOSE_UP = 1.0f
    const val NOSE_DOWN = 0.55f

    /** 감쇠가 시작하는 타원 거리(이 안은 다 걸린다) */
    const val PLATEAU = 0.45f

    /** 이보다 작은(문서 픽셀) 부위는 고치지 않는다(점이 한곳에 모인 얼굴) */
    private const val MIN_PART = 1f

    /** 얼굴 [faces]마다 값 [values](같은 순서, 없으면 0)를 건 변형들. 고친 것이 없으면 비었다. 차례: 얼굴마다 얼굴 너비 → 두 눈 → 코. */
    fun of(faces: List<FaceLandmarks>, values: List<FaceValues>): List<MeshWarp> =
        faces.indices.flatMap { of(faces[it], values.getOrElse(it) { FaceValues.ZERO }) }

    /**
     * 얼굴 [face] 하나에 [values]를 건 변형들. 격자에 합칠 때 이 차례로 거꾸로 따라간다: 얼굴 너비를 마지막에(원래 그림에는 눈·코를 먼저) 건 것과 같다.
     * 특징점은 원래 그림의 것이고 부위끼리 거의 겹치지 않아 차례는 결과를 거의 바꾸지 않는다.
     */
    fun of(face: FaceLandmarks, values: FaceValues): List<MeshWarp> {
        if (values.isZero || !face.isFinite) return emptyList()
        val frame = FaceFrame(face)
        if (frame.unit < MIN_PART) return emptyList()
        return buildList {
            values[FaceSlider.FACE_WIDTH].takeIf { it != 0f }?.let { value -> FaceWidthWarp.of(face, frame, value)?.let(::add) }
            values[FaceSlider.EYE_SIZE].takeIf { it != 0f }?.let { value -> addAll(eyes(face, frame, value)) }
            values[FaceSlider.NOSE_WIDTH].takeIf { it != 0f }?.let { value -> nose(face, frame, value)?.let(::add) }
        }
    }

    /** 두 눈을 저마다의 가운데에서 [EYE_MAX_SCALE]^[value]배 */
    private fun eyes(face: FaceLandmarks, frame: FaceFrame, value: Float): List<MeshWarp> {
        val right = EyeShape(face, FaceMeshIndex.RIGHT_EYE, FaceMeshIndex.RIGHT_EYE_OUTER, FaceMeshIndex.RIGHT_EYE_INNER)
        val left = EyeShape(face, FaceMeshIndex.LEFT_EYE, FaceMeshIndex.LEFT_EYE_OUTER, FaceMeshIndex.LEFT_EYE_INNER)
        val mean = (right.width + left.width) / 2f
        val scale = EYE_MAX_SCALE.pow(value)
        return listOf(right, left).mapNotNull { eye ->
            if (eye.width < MIN_PART) return@mapNotNull null
            val tall = max(eye.width, mean) * EYE_TALL
            EllipseScale.along(
                eye.centerX, eye.centerY, eye.axisX, eye.axisY, frame,
                inner = eye.width * EYE_INNER, outer = eye.width * EYE_OUTER, up = tall, down = tall, scale = scale, bothAxes = true
            )
        }
    }

    /** 콧방울 사이를 콧방울 선 쪽으로만 [NOSE_MAX_SCALE]^[value]배 */
    private fun nose(face: FaceLandmarks, frame: FaceFrame, value: Float): MeshWarp? {
        val rx = face.x(FaceMeshIndex.NOSE_WING_RIGHT)
        val ry = face.y(FaceMeshIndex.NOSE_WING_RIGHT)
        val lx = face.x(FaceMeshIndex.NOSE_WING_LEFT)
        val ly = face.y(FaceMeshIndex.NOSE_WING_LEFT)
        val width = hypot(lx - rx, ly - ry)
        if (width < MIN_PART) return null
        return EllipseScale.along(
            (rx + lx) / 2f, (ry + ly) / 2f, (lx - rx) / width, (ly - ry) / width, frame,
            inner = width * NOSE_ACROSS, outer = width * NOSE_ACROSS, up = width * NOSE_UP, down = width * NOSE_DOWN,
            scale = NOSE_MAX_SCALE.pow(value), bothAxes = false
        )
    }

    /** 눈 하나: 윤곽 점들의 가운데, 눈머리 → 눈꼬리 축(단위 벡터), 눈 폭 */
    private class EyeShape(face: FaceLandmarks, contour: IntArray, outer: Int, inner: Int) {
        val centerX = contour.map { face.x(it) }.average().toFloat()
        val centerY = contour.map { face.y(it) }.average().toFloat()
        val width = hypot(face.x(outer) - face.x(inner), face.y(outer) - face.y(inner))
        val axisX = if (width > 0f) (face.x(outer) - face.x(inner)) / width else 1f
        val axisY = if (width > 0f) (face.y(outer) - face.y(inner)) / width else 0f
    }
}

/**
 * 얼굴 좌표계(계획 '얼굴 좌표계'): 원점은 두 눈동자 가운데, 가로축 u는 오른쪽 눈 → 왼쪽 눈(얼굴이 기운 만큼 기움),
 * 세로축 v는 그에 수직인 턱 쪽이고, 단위 [unit] = max(두 눈 사이, 눈 가운데–턱 끝의 반)(문서 픽셀)이다.
 * 고개를 돌려 두 눈 사이가 좁아져도 단위가 줄지 않는다.
 */
internal class FaceFrame(face: FaceLandmarks) {
    val originX = face.eyeCenterX
    val originY = face.eyeCenterY
    val acrossX: Float
    val acrossY: Float
    val downX: Float
    val downY: Float
    val unit: Float

    init {
        val ex = face.x(FaceMeshIndex.LEFT_IRIS) - face.x(FaceMeshIndex.RIGHT_IRIS)
        val ey = face.y(FaceMeshIndex.LEFT_IRIS) - face.y(FaceMeshIndex.RIGHT_IRIS)
        val eyes = hypot(ex, ey)
        acrossX = if (eyes > 0f) ex / eyes else 1f
        acrossY = if (eyes > 0f) ey / eyes else 0f
        val cx = face.x(FaceMeshIndex.CHIN) - originX
        val cy = face.y(FaceMeshIndex.CHIN) - originY
        // 가로축에 수직인 두 쪽 중 턱 끝이 있는 쪽
        val flip = -acrossY * cx + acrossX * cy < 0f
        downX = if (flip) acrossY else -acrossY
        downY = if (flip) -acrossX else acrossX
        unit = max(eyes, hypot(cx, cy) / 2f)
    }

    fun u(x: Float, y: Float): Float = ((x - originX) * acrossX + (y - originY) * acrossY) / unit

    fun v(x: Float, y: Float): Float = ((x - originX) * downX + (y - originY) * downY) / unit

    fun x(u: Float, v: Float): Float = originX + unit * (u * acrossX + v * downX)

    fun y(u: Float, v: Float): Float = originY + unit * (u * acrossY + v * downY)
}

/** 5차 스무드스텝(0..1): 양 끝에서 기울기·굽음이 0이라 이음매가 보이지 않는다. 기울기는 가운데에서 가장 크다([SMOOTH_SLOPE_MAX]). */
internal fun smooth(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

/** [smooth]의 가장 큰 기울기(15/8) */
internal const val SMOOTH_SLOPE_MAX = 1.875f

/** 부위 감쇠: 타원 거리 [t]가 [FaceWarps.PLATEAU]까지는 1, 1 이상은 0, 사이는 [smooth]로 줄어든다 */
internal fun falloff(t: Float): Float = when {
    t <= FaceWarps.PLATEAU -> 1f
    t >= 1f -> 0f
    else -> 1f - smooth((t - FaceWarps.PLATEAU) / (1f - FaceWarps.PLATEAU))
}

/**
 * 타원 안을 가운데 ([centerX], [centerY]) 기준으로 [scale]배 하는 변형(눈 크기·코 너비). 타원은 가로축 ([axisX], [axisY]) 쪽으로 뒤 [inner]·앞 [outer],
 * 그에 수직인 쪽([perpX], [perpY], 턱 쪽이 +)으로 위 [up]·아래 [down]이다(네 쪽 반지름이 달라도 축 위에서 매끈하게 이어진다).
 * [bothAxes]면 두 축 모두(눈), 아니면 가로축 쪽만(코) 늘인다.
 * 거꾸로 찾는 변위 = (1/[scale] − 1)·감쇠(t)·(점 − 가운데)(가로축만이면 그 성분만). 늘이면 가운데 쪽을, 줄이면 바깥쪽을 읽는다.
 */
internal class EllipseScale private constructor(
    private val centerX: Float,
    private val centerY: Float,
    private val axisX: Float,
    private val axisY: Float,
    private val perpX: Float,
    private val perpY: Float,
    private val inner: Float,
    private val outer: Float,
    private val up: Float,
    private val down: Float,
    scale: Float,
    private val bothAxes: Boolean
) : MeshWarp {

    private val gain = 1f / scale - 1f
    private val reachX = abs(axisX) * max(inner, outer) + abs(perpX) * max(up, down)
    private val reachY = abs(axisY) * max(inner, outer) + abs(perpY) * max(up, down)

    override val left: Float = centerX - reachX
    override val top: Float = centerY - reachY
    override val right: Float = centerX + reachX
    override val bottom: Float = centerY + reachY

    override fun displacement(x: Float, y: Float, out: FloatArray) {
        val ox = x - centerX
        val oy = y - centerY
        val a = ox * axisX + oy * axisY
        val b = ox * perpX + oy * perpY
        val ta = a / if (a < 0f) inner else outer
        val tb = b / if (b < 0f) up else down
        val t2 = ta * ta + tb * tb
        if (t2 >= 1f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val k = gain * falloff(sqrt(t2))
        if (bothAxes) {
            out[0] = k * ox
            out[1] = k * oy
        } else {
            out[0] = k * a * axisX
            out[1] = k * a * axisY
        }
    }

    companion object {
        /** 가로축 ([axisX], [axisY])(단위 벡터)에 수직인 쪽을 얼굴의 턱 쪽([frame])에 맞춘 변형 */
        fun along(
            centerX: Float, centerY: Float, axisX: Float, axisY: Float, frame: FaceFrame,
            inner: Float, outer: Float, up: Float, down: Float, scale: Float, bothAxes: Boolean
        ): EllipseScale {
            val chinward = -axisY * frame.downX + axisX * frame.downY >= 0f
            val perpX = if (chinward) -axisY else axisY
            val perpY = if (chinward) axisX else -axisX
            return EllipseScale(centerX, centerY, axisX, axisY, perpX, perpY, inner, outer, up, down, scale, bothAxes)
        }
    }
}

/**
 * 얼굴 너비(계획 '부위 계산'): 볼·턱 윤곽을 얼굴 가로축(눈 선) 쪽으로만 밖·안으로 옮긴다. 세로로는 옮기지 않아, 눈 선과 나란한 배경 선은 곧게 남는다.
 * 얼굴 좌표(u, v, 단위 U)로 계산한다.
 * - 가로: 가운데 줄(콧등~턱 끝을 잇는 선)에서 그 높이의 윤곽까지를 반폭 W라 하면, 가운데 줄에서 W의 [INNER] 안은 그대로, 윤곽까지 1로 오르고,
 *   윤곽 바깥 [BAND]U 띠에서 0으로 내려간다.
 * - 세로: 눈 위 [RISE_START]부터 [RISE_END](코 가운데쯤)까지 오르고, 턱 끝 [FALL_START] 위부터 [FALL_END] 위까지 내려 턱 끝 가까이는 그대로다.
 * - 크기: 한쪽 [MAX_MOVE]U(값 ±1). 고개를 돌려 사진에서 좁게 보이는 쪽(먼 쪽)은 좁은 만큼(그쪽 반폭 / 넓은 쪽 반폭, 세로 무게로 평균) 덜 옮긴다.
 * - 접힘 막기: 비탈의 기울기가 [SLOPE_LIMIT]를 넘지 않게 반폭이 좁은 곳(턱 끝 쪽)은 그만큼 덜 옮긴다(되돌려 찾는 지도의 야코비안 ≥ 1 − [SLOPE_LIMIT]).
 */
internal class FaceWidthWarp private constructor(
    private val frame: FaceFrame,
    private val move: Float,
    private val tableTop: Float,
    private val tableStep: Float,
    private val plusEdge: FloatArray,
    private val minusEdge: FloatArray,
    private val midTop: Float,
    private val midSlope: Float,
    private val chin: Float,
    private val plusFactor: Float,
    private val minusFactor: Float,
    box: FloatArray
) : MeshWarp {

    override val left: Float = box[0]
    override val top: Float = box[1]
    override val right: Float = box[2]
    override val bottom: Float = box[3]

    override fun displacement(x: Float, y: Float, out: FloatArray) {
        out[0] = 0f
        out[1] = 0f
        val u = frame.u(x, y)
        val v = frame.v(x, y)
        val rows = rows(v, chin)
        if (rows <= 0f) return
        val center = midline(v)
        val plusSide = u >= center
        val half = if (plusSide) edge(plusEdge, v) - center else center - edge(minusEdge, v)
        if (half <= MIN_HALF) return
        val from = abs(u - center)
        val inner = INNER * half
        val shape = when {
            from <= inner -> return
            from < half -> smooth((from - inner) / (half - inner))
            from < half + BAND -> 1f - smooth((from - half) / BAND)
            else -> return
        }
        // 비탈 기울기 한도(접힘 막기): 오르는 비탈(폭 half - inner)과 바깥 띠(폭 BAND) 중 좁은 쪽에 맞춘다
        val reach = min(abs(move) * rows * (if (plusSide) plusFactor else minusFactor), SLOPE_LIMIT * min(half - inner, BAND) / SMOOTH_SLOPE_MAX)
        // 넓히면(값 +) 윤곽 둘레의 결과 자리가 가운데 쪽을 읽고(거꾸로 찾음), 좁히면 바깥쪽을 읽는다
        val outward = if (plusSide) 1f else -1f
        val shift = -outward * (if (move > 0f) 1f else -1f) * reach * shape * frame.unit
        out[0] = shift * frame.acrossX
        out[1] = shift * frame.acrossY
    }

    /** 가운데 줄의 u(높이 [v]에서) */
    private fun midline(v: Float): Float = midTop + midSlope * v

    /** 윤곽 표 [table]의 높이 [v] 값(사이는 이어서) */
    private fun edge(table: FloatArray, v: Float): Float {
        val position = ((v - tableTop) / tableStep).coerceIn(0f, (table.size - 1).toFloat())
        val i = min(position.toInt(), table.size - 2)
        val f = position - i
        return table[i] * (1f - f) + table[i + 1] * f
    }

    companion object {
        /** 가운데 줄에서 반폭의 이만큼 안은 옮기지 않는다(눈·코·입은 그대로) */
        const val INNER = 0.55f

        /** 윤곽 바깥에서 0으로 내려가는 띠의 폭(U). 좁을수록 배경이 덜 휜다. */
        const val BAND = 0.28f

        /** 값 ±1에서 한쪽 윤곽을 옮기는 거리(U). 시험 사진에서 0.06은 잘 보이지 않아 계획의 한도(0.08)로 했다. */
        const val MAX_MOVE = 0.08f

        /** 세로 무게가 오르기 시작하는 높이와 다 오른 높이(U, 눈 선이 0, 턱 쪽 +) */
        const val RISE_START = -0.3f
        const val RISE_END = 0.5f

        /** 턱 끝에서 이만큼(U) 위부터 내려 이만큼 위에서 0 */
        const val FALL_START = 0.5f
        const val FALL_END = 0.05f

        /** 비탈 기울기의 한도(접힘 막기) */
        const val SLOPE_LIMIT = 0.6f

        /** 윤곽 표의 줄 수 */
        private const val TABLE_ROWS = 64

        /** 반폭이 이보다(U) 좁으면 옮기지 않는다 */
        private const val MIN_HALF = 0.02f

        /** 높이 [v]의 세로 무게(0..1). [chin]은 턱 끝의 높이다. */
        fun rows(v: Float, chin: Float): Float =
            smooth((v - RISE_START) / (RISE_END - RISE_START)) * (1f - smooth((v - (chin - FALL_START)) / (FALL_START - FALL_END)))

        /** 얼굴 [face]의 얼굴 너비 변형(값 [value]). 윤곽이 깨졌으면 null. */
        fun of(face: FaceLandmarks, frame: FaceFrame, value: Float): FaceWidthWarp? {
            val oval = FaceMeshIndex.FACE_OVAL
            val us = FloatArray(oval.size) { frame.u(face.x(oval[it]), face.y(oval[it])) }
            val vs = FloatArray(oval.size) { frame.v(face.x(oval[it]), face.y(oval[it])) }
            val top = vs.min()
            val chin = vs.max()
            if (!(chin - top > 0.1f)) return null
            // 높이마다 윤곽의 양쪽 끝(가로선과 윤곽 변이 만나는 u 중 가장 큰 것·작은 것)
            val step = (chin - top) / (TABLE_ROWS - 1)
            val plus = FloatArray(TABLE_ROWS)
            val minus = FloatArray(TABLE_ROWS)
            for (k in 0 until TABLE_ROWS) {
                val v = top + step * k
                var low = Float.POSITIVE_INFINITY
                var high = Float.NEGATIVE_INFINITY
                for (e in oval.indices) {
                    val f = (e + 1) % oval.size
                    val v0 = vs[e]
                    val v1 = vs[f]
                    if ((v < v0 && v < v1) || (v > v0 && v > v1)) continue
                    val u = if (v1 == v0) us[e] else us[e] + (v - v0) * (us[f] - us[e]) / (v1 - v0)
                    low = min(low, u)
                    high = max(high, u)
                }
                if (low > high) {
                    low = 0f
                    high = 0f
                }
                plus[k] = high
                minus[k] = low
            }
            // 가운데 줄: 콧등 점들의 가운데와 턱 끝을 잇는다(고개를 돌리거나 숙여도 얼굴 가운데를 따라감)
            val bridge = FaceMeshIndex.NOSE_BRIDGE
            val bridgeU = bridge.map { frame.u(face.x(it), face.y(it)) }.average().toFloat()
            val bridgeV = bridge.map { frame.v(face.x(it), face.y(it)) }.average().toFloat()
            val chinU = frame.u(face.x(FaceMeshIndex.CHIN), face.y(FaceMeshIndex.CHIN))
            val chinV = frame.v(face.x(FaceMeshIndex.CHIN), face.y(FaceMeshIndex.CHIN))
            val slope = if (chinV - bridgeV > 0.1f) (chinU - bridgeU) / (chinV - bridgeV) else 0f
            val midTop = bridgeU - slope * bridgeV
            // 양쪽 반폭(세로 무게로 평균): 좁게 보이는 쪽(먼 쪽)은 그 비율만큼 덜 옮긴다
            var plusHalf = 0f
            var minusHalf = 0f
            for (k in 0 until TABLE_ROWS) {
                val v = top + step * k
                val weight = rows(v, chin)
                if (weight <= 0f) continue
                val center = midTop + slope * v
                plusHalf += weight * max(0f, plus[k] - center)
                minusHalf += weight * max(0f, center - minus[k])
            }
            if (plusHalf <= 0f && minusHalf <= 0f) return null
            val plusFactor = if (minusHalf > 0f) min(1f, plusHalf / minusHalf) else 1f
            val minusFactor = if (plusHalf > 0f) min(1f, minusHalf / plusHalf) else 1f
            // 상자: 윤곽의 가로 끝에서 띠만큼 더, 세로는 무게가 0이 아닌 곳
            val u0 = minus.min() - BAND
            val u1 = plus.max() + BAND
            val v0 = RISE_START
            val v1 = chin
            val xs = floatArrayOf(frame.x(u0, v0), frame.x(u1, v0), frame.x(u0, v1), frame.x(u1, v1))
            val ys = floatArrayOf(frame.y(u0, v0), frame.y(u1, v0), frame.y(u0, v1), frame.y(u1, v1))
            val box = floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            return FaceWidthWarp(frame, MAX_MOVE * value, top, step, plus, minus, midTop, slope, chin, plusFactor, minusFactor, box)
        }
    }
}

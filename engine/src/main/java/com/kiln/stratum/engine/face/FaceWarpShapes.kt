package com.kiln.stratum.engine.face

import com.kiln.stratum.engine.liquify.MeshWarp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 얼굴 보정의 변형 틀(결정 기록 '얼굴 인식 보정', 계획 '부위 계산'): 얼굴 좌표계, 감쇠, 부위 타원과 그 안의 늘이기·옮기기·돌리기,
 * 얼굴 윤곽과 그 띠를 옮기는 변형. 슬라이더 값을 이것들로 바꾸는 것은 [FaceWarps]다. 모두 순수 계산이다.
 *
 * 변형은 거꾸로 찾는 변위다([MeshWarp]): 결과 자리 p가 원래 그림의 어디를 읽을지. 부위를 앞으로 d만큼 옮기려면 p에서 −d를 읽는다.
 * 옮기기·돌리기의 무게는 결과 자리에서 한 번 구한 뒤, 그만큼 되돌린 자리에서 한 번 더 구해(한 번 다듬기) 옮긴 부위가 감쇠 가운데에 오게 한다.
 */

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

    /** 특징점 [index]의 u·v */
    fun u(face: FaceLandmarks, index: Int): Float = u(face.x(index), face.y(index))

    fun v(face: FaceLandmarks, index: Int): Float = v(face.x(index), face.y(index))
}

/** 5차 스무드스텝(0..1): 양 끝에서 기울기·굽음이 0이라 이음매가 보이지 않는다. 기울기는 가운데에서 가장 크다([SMOOTH_SLOPE_MAX]). */
internal fun smooth(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

/** [smooth]의 가장 큰 기울기(15/8) */
internal const val SMOOTH_SLOPE_MAX = 1.875f

/** 부위 감쇠: 타원 거리 [t]가 [plateau]까지는 1, 1 이상은 0, 사이는 [smooth]로 줄어든다(부위 전체를 같이 고칠 때) */
internal fun falloff(t: Float, plateau: Float = FaceWarps.PLATEAU): Float = when {
    t <= plateau -> 1f
    t >= 1f -> 0f
    else -> 1f - smooth((t - plateau) / (1f - plateau))
}

/** 점 감쇠: 가운데(한 점, 입꼬리·눈꼬리·턱 모서리)에서 1, 경계에서 0([smooth]). 옮기는 거리에 비해 넓게 줄어 접히지 않는다. */
internal fun peak(t: Float): Float = 1f - smooth(t)

/**
 * 부위 타원: 가운데 ([centerX], [centerY]), 가로축 ([axisX], [axisY])(단위 벡터) 쪽으로 뒤 [inner]·앞 [outer], 그에 수직인 쪽([perpX], [perpY], 턱 쪽이 +)으로
 * 위 [up]·아래 [down] 반지름(문서 픽셀). 네 쪽 반지름이 달라도 축 위에서 매끈하게 이어진다.
 */
internal class PartEllipse private constructor(
    val centerX: Float,
    val centerY: Float,
    val axisX: Float,
    val axisY: Float,
    val perpX: Float,
    val perpY: Float,
    private val inner: Float,
    private val outer: Float,
    private val up: Float,
    private val down: Float
) {
    /** 가로축 쪽 성분(가운데에서) */
    fun along(x: Float, y: Float): Float = (x - centerX) * axisX + (y - centerY) * axisY

    /** 수직(턱 쪽 +) 성분 */
    fun across(x: Float, y: Float): Float = (x - centerX) * perpX + (y - centerY) * perpY

    /** 타원 거리(가운데 0, 경계 1) */
    fun distance(x: Float, y: Float): Float {
        val a = along(x, y)
        val b = across(x, y)
        val ta = a / if (a < 0f) inner else outer
        val tb = b / if (b < 0f) up else down
        return sqrt(ta * ta + tb * tb)
    }

    /** 타원을 덮는 상자: 왼쪽, 위, 오른쪽, 아래 */
    val box: FloatArray
        get() {
            val reachX = abs(axisX) * max(inner, outer) + abs(perpX) * max(up, down)
            val reachY = abs(axisY) * max(inner, outer) + abs(perpY) * max(up, down)
            return floatArrayOf(centerX - reachX, centerY - reachY, centerX + reachX, centerY + reachY)
        }

    companion object {
        /** 가로축 ([axisX], [axisY])(단위 벡터)에 수직인 쪽을 얼굴의 턱 쪽([frame])에 맞춘 타원 */
        fun of(centerX: Float, centerY: Float, axisX: Float, axisY: Float, frame: FaceFrame, inner: Float, outer: Float, up: Float, down: Float): PartEllipse {
            val chinward = -axisY * frame.downX + axisX * frame.downY >= 0f
            val perpX = if (chinward) -axisY else axisY
            val perpY = if (chinward) axisX else -axisX
            return PartEllipse(centerX, centerY, axisX, axisY, perpX, perpY, inner, outer, up, down)
        }
    }
}

/** 타원 [shape] 안에서만 걸리는 변형(상자는 타원의 상자). 타원 밖은 정확히 0이다. */
internal abstract class EllipseWarp(protected val shape: PartEllipse) : MeshWarp {
    private val box = shape.box
    override val left: Float = box[0]
    override val top: Float = box[1]
    override val right: Float = box[2]
    override val bottom: Float = box[3]
}

/**
 * 타원 안을 가운데 기준으로 가로축 쪽 [scaleAlong]배, 수직 쪽 [scaleAcross]배 하는 변형(눈 크기·높이·너비, 코·콧등·입 너비, 입술).
 * [half]가 −1이면 수직 쪽은 위 반(이마 쪽)만, +1이면 아래 반(턱 쪽)만 늘인다(입술: 입선에서 바깥 입술 쪽 반쪽만). 감쇠는 [falloff]([plateau])다.
 * 거꾸로 찾는 변위 = (1/배율 − 1)·감쇠(t)·성분. 늘이면 가운데 쪽을, 줄이면 바깥쪽을 읽는다.
 * 줄일 때 감쇠 띠가 가장 눌린다(야코비안 ≈ 1 − (1/배율 − 1)·(감쇠 띠의 가장 가파른 곳)). 많이 줄이는 입술은 [plateau]를 낮춰 띠를 넓힌다.
 */
internal class EllipseScale(
    shape: PartEllipse,
    scaleAlong: Float,
    scaleAcross: Float,
    private val half: Int = 0,
    private val plateau: Float = FaceWarps.PLATEAU
) : EllipseWarp(shape) {
    private val gainAlong = 1f / scaleAlong - 1f
    private val gainAcross = 1f / scaleAcross - 1f

    override fun displacement(x: Float, y: Float, out: FloatArray) {
        val t = shape.distance(x, y)
        if (t >= 1f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val k = falloff(t, plateau)
        val a = shape.along(x, y)
        val b = shape.across(x, y)
        val ka = gainAlong * k * a
        val kb = if (half == 0 || (half < 0) == (b < 0f)) gainAcross * k * b else 0f
        out[0] = ka * shape.axisX + kb * shape.perpX
        out[1] = ka * shape.axisY + kb * shape.perpY
    }
}

/**
 * 타원 안을 앞으로 ([moveX], [moveY])(문서 픽셀)만큼 옮기는 변형(눈 간격·코 높이·입 높이는 [plateau] 감쇠로 부위 전체를, 미소·뒷트임·턱선은
 * 점 감쇠 [peak]로 한 점 둘레를). 결과 자리에서 무게를 구한 뒤 그만큼 되돌린 자리에서 다시 구해 읽을 곳을 정한다.
 */
internal class EllipseMove(shape: PartEllipse, private val moveX: Float, private val moveY: Float, private val plateau: Boolean) : EllipseWarp(shape) {

    private fun weight(x: Float, y: Float): Float {
        val t = shape.distance(x, y)
        return if (plateau) falloff(t) else peak(t)
    }

    override fun displacement(x: Float, y: Float, out: FloatArray) {
        val first = weight(x, y)
        if (first == 0f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val w = weight(x - moveX * first, y - moveY * first)
        out[0] = -moveX * w
        out[1] = -moveY * w
    }
}

/**
 * 타원 안을 가운데 기준으로 [angle](라디안, 그림에서 시계 방향 +)만큼 돌리는 변형(눈 기울기). 돌리는 각도가 [falloff]로 줄어 둘레는 그대로다.
 * 돌려도 타원 거리는 거의 그대로라 결과 자리의 무게로 되돌린다.
 */
internal class EllipseRotate(shape: PartEllipse, private val angle: Float) : EllipseWarp(shape) {
    override fun displacement(x: Float, y: Float, out: FloatArray) {
        val t = shape.distance(x, y)
        if (t >= 1f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val turn = -angle * falloff(t)
        val ox = x - shape.centerX
        val oy = y - shape.centerY
        val c = cos(turn)
        val s = sin(turn)
        out[0] = c * ox - s * oy - ox
        out[1] = s * ox + c * oy - oy
    }
}

/**
 * 얼굴 윤곽(계획 '부위 계산'): 높이(v)마다 윤곽의 양쪽 끝 u(표 [TABLE_ROWS]줄), 가운데 줄(콧등 점들의 가운데와 턱 끝을 잇는 선), 턱 끝·이마 끝의 높이,
 * 양쪽 반폭의 비율([plusFactor]·[minusFactor]: 고개를 돌려 사진에서 좁게 보이는 쪽은 1보다 작다). 얼굴 좌표(u, v, 단위 U)다.
 */
internal class FaceOutline private constructor(
    val top: Float,
    val chin: Float,
    private val step: Float,
    private val plus: FloatArray,
    private val minus: FloatArray,
    private val midTop: Float,
    private val midSlope: Float,
    val plusFactor: Float,
    val minusFactor: Float
) {
    /** 가운데 줄의 u(높이 [v]에서) */
    fun mid(v: Float): Float = midTop + midSlope * v

    /** 높이 [v]에서 가운데 줄부터 윤곽까지의 반폭(+ 쪽이면 [plusSide]) */
    fun half(v: Float, plusSide: Boolean): Float = if (plusSide) edge(plus, v) - mid(v) else mid(v) - edge(minus, v)

    /** + 쪽 윤곽 u의 가장 큰 값, − 쪽 가장 작은 값(상자 계산) */
    val widest: Float
        get() = plus.max()
    val narrowest: Float
        get() = minus.min()

    /** 그 쪽(+ 쪽이면 [plusSide])의 비율: 좁게 보이는 쪽(먼 쪽)은 그만큼 덜 옮긴다 */
    fun factor(plusSide: Boolean): Float = if (plusSide) plusFactor else minusFactor

    private fun edge(table: FloatArray, v: Float): Float {
        val position = ((v - top) / step).coerceIn(0f, (table.size - 1).toFloat())
        val i = min(position.toInt(), table.size - 2)
        val f = position - i
        return table[i] * (1f - f) + table[i + 1] * f
    }

    companion object {
        /** 윤곽 표의 줄 수 */
        private const val TABLE_ROWS = 64

        /** 얼굴 [face]의 윤곽. 윤곽이 깨졌으면(높이가 거의 없음) null. */
        fun of(face: FaceLandmarks, frame: FaceFrame): FaceOutline? {
            val oval = FaceMeshIndex.FACE_OVAL
            val us = FloatArray(oval.size) { frame.u(face, oval[it]) }
            val vs = FloatArray(oval.size) { frame.v(face, oval[it]) }
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
            val bridgeU = bridge.map { frame.u(face, it) }.average().toFloat()
            val bridgeV = bridge.map { frame.v(face, it) }.average().toFloat()
            val chinU = frame.u(face, FaceMeshIndex.CHIN)
            val chinV = frame.v(face, FaceMeshIndex.CHIN)
            val slope = if (chinV - bridgeV > 0.1f) (chinU - bridgeU) / (chinV - bridgeV) else 0f
            val midTop = bridgeU - slope * bridgeV
            // 양쪽 반폭(얼굴 너비의 세로 무게로 평균): 좁게 보이는 쪽(먼 쪽)은 그 비율만큼 덜 옮긴다
            var plusHalf = 0f
            var minusHalf = 0f
            for (k in 0 until TABLE_ROWS) {
                val v = top + step * k
                val weight = FaceWidthWarp.rows(v, chin)
                if (weight <= 0f) continue
                val center = midTop + slope * v
                plusHalf += weight * max(0f, plus[k] - center)
                minusHalf += weight * max(0f, center - minus[k])
            }
            if (plusHalf <= 0f && minusHalf <= 0f) return null
            val plusFactor = if (minusHalf > 0f) min(1f, plusHalf / minusHalf) else 1f
            val minusFactor = if (plusHalf > 0f) min(1f, minusHalf / plusHalf) else 1f
            return FaceOutline(top, chin, step, plus, minus, midTop, slope, plusFactor, minusFactor)
        }
    }
}

/**
 * 얼굴 너비(계획 '부위 계산'): 볼·턱 윤곽을 얼굴 가로축(눈 선) 쪽으로만 밖·안으로 옮긴다. 세로로는 옮기지 않아, 눈 선과 나란한 배경 선은 곧게 남는다.
 * 얼굴 좌표(u, v, 단위 U)로 계산한다.
 * - 가로: 가운데 줄(콧등~턱 끝을 잇는 선)에서 그 높이의 윤곽까지를 반폭 W라 하면, 가운데 줄에서 W의 [INNER] 안은 그대로, 윤곽까지 1로 오르고,
 *   윤곽 바깥 [BAND]U 띠에서 0으로 내려간다.
 * - 세로: 눈 위 [RISE_START]부터 [RISE_END](코 가운데쯤)까지 오르고, 턱 끝 [FALL_START] 위부터 [FALL_END] 위까지 내려 턱 끝 가까이는 그대로다.
 * - 크기: 한쪽 [MAX_MOVE]U(값 ±1). 고개를 돌려 사진에서 좁게 보이는 쪽(먼 쪽)은 좁은 만큼([FaceOutline.factor]) 덜 옮긴다.
 * - 접힘 막기: 비탈의 기울기가 [SLOPE_LIMIT]를 넘지 않게 반폭이 좁은 곳(턱 끝 쪽)은 그만큼 덜 옮긴다(되돌려 찾는 지도의 야코비안 ≥ 1 − [SLOPE_LIMIT]).
 */
internal class FaceWidthWarp private constructor(
    private val frame: FaceFrame,
    private val outline: FaceOutline,
    private val move: Float,
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
        val rows = rows(v, outline.chin)
        if (rows <= 0f) return
        val center = outline.mid(v)
        val plusSide = u >= center
        val half = outline.half(v, plusSide)
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
        val reach = min(abs(move) * rows * outline.factor(plusSide), SLOPE_LIMIT * min(half - inner, BAND) / SMOOTH_SLOPE_MAX)
        // 넓히면(값 +) 윤곽 둘레의 결과 자리가 가운데 쪽을 읽고(거꾸로 찾음), 좁히면 바깥쪽을 읽는다
        val outward = if (plusSide) 1f else -1f
        val shift = -outward * (if (move > 0f) 1f else -1f) * reach * shape * frame.unit
        out[0] = shift * frame.acrossX
        out[1] = shift * frame.acrossY
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

        /** 반폭이 이보다(U) 좁으면 옮기지 않는다 */
        private const val MIN_HALF = 0.02f

        /** 높이 [v]의 세로 무게(0..1). [chin]은 턱 끝의 높이다. */
        fun rows(v: Float, chin: Float): Float =
            smooth((v - RISE_START) / (RISE_END - RISE_START)) * (1f - smooth((v - (chin - FALL_START)) / (FALL_START - FALL_END)))

        /** 윤곽 [outline]의 얼굴 너비 변형(값 [value]) */
        fun of(frame: FaceFrame, outline: FaceOutline, value: Float): FaceWidthWarp {
            // 상자: 윤곽의 가로 끝에서 띠만큼 더, 세로는 무게가 0이 아닌 곳
            val u0 = outline.narrowest - BAND
            val u1 = outline.widest + BAND
            val v0 = RISE_START
            val v1 = outline.chin
            val xs = floatArrayOf(frame.x(u0, v0), frame.x(u1, v0), frame.x(u0, v1), frame.x(u1, v1))
            val ys = floatArrayOf(frame.y(u0, v0), frame.y(u1, v0), frame.y(u0, v1), frame.y(u1, v1))
            return FaceWidthWarp(frame, outline, MAX_MOVE * value, floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()))
        }
    }
}

/**
 * 얼굴 세로 띠를 얼굴 세로축 쪽으로만 [move]U 옮기는 변형(이마·턱 높이, + = 턱 쪽). 높이(v)로는 [zeroV]에서 0, [fullV]에서 1([smooth])이고
 * 그 너머 [endV]까지 0으로 내려간다(이마는 눈썹 → 이마 끝 → 그 위, 턱은 아랫입술 → 턱 끝 → 그 아래). 가로로는 가운데 줄에서 반폭 [half]U 안은 1,
 * 그 바깥 [FaceWidthWarp.BAND]U 띠에서 0이다. 가로로는 옮기지 않아 세로선은 곧게 남는다.
 * 반폭은 높이마다 같다: 윤곽을 따라 반폭이 급하게 바뀌면(턱 끝) 그 옆이 세로로 크게 눌려 접힐 수 있다.
 */
internal class BandMove private constructor(
    private val frame: FaceFrame,
    private val outline: FaceOutline,
    private val move: Float,
    private val zeroV: Float,
    private val fullV: Float,
    private val endV: Float,
    private val half: Float,
    box: FloatArray
) : MeshWarp {

    override val left: Float = box[0]
    override val top: Float = box[1]
    override val right: Float = box[2]
    override val bottom: Float = box[3]

    /** 결과 자리 (u, v)의 무게(0..1) */
    private fun weight(u: Float, v: Float): Float {
        val rise = (v - zeroV) / (fullV - zeroV)
        val vertical = when {
            rise <= 0f -> return 0f
            rise < 1f -> smooth(rise)
            else -> {
                val fall = (v - fullV) / (endV - fullV)
                if (fall >= 1f) return 0f
                1f - smooth(fall)
            }
        }
        val from = abs(u - outline.mid(v))
        val horizontal = when {
            from <= half -> 1f
            from < half + FaceWidthWarp.BAND -> 1f - smooth((from - half) / FaceWidthWarp.BAND)
            else -> return 0f
        }
        return vertical * horizontal
    }

    override fun displacement(x: Float, y: Float, out: FloatArray) {
        val u = frame.u(x, y)
        val v = frame.v(x, y)
        val first = weight(u, v)
        if (first == 0f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val shift = -move * weight(u, v - move * first) * frame.unit
        out[0] = shift * frame.downX
        out[1] = shift * frame.downY
    }

    companion object {
        /** 반폭은 [half]U이되 [minHalf]U보다 좁게 잡지 않는다. 상자는 무게가 0이 아닌 곳을 덮는다(가운데 줄은 높이에 따라 곧게 기운다). */
        fun of(frame: FaceFrame, outline: FaceOutline, move: Float, zeroV: Float, fullV: Float, endV: Float, half: Float, minHalf: Float): BandMove {
            val width = max(minHalf, half)
            val reach = width + FaceWidthWarp.BAND
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            var i = 0
            for (v in floatArrayOf(zeroV, endV)) {
                for (side in floatArrayOf(-1f, 1f)) {
                    val u = outline.mid(v) + side * (reach + abs(outline.mid(endV) - outline.mid(zeroV)))
                    xs[i] = frame.x(u, v)
                    ys[i] = frame.y(u, v)
                    i++
                }
            }
            return BandMove(frame, outline, move, zeroV, fullV, endV, width, floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()))
        }
    }
}

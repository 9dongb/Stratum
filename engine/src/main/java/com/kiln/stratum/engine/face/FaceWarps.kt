package com.kiln.stratum.engine.face

import com.kiln.stratum.engine.liquify.MeshWarp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 얼굴 보정 슬라이더의 부위(화면에서 부위를 고르면 그 부위의 슬라이더가 보인다, 계획 '화면') */
enum class FacePart {
    EYES,
    NOSE,
    MOUTH,
    FACE_SHAPE
}

/**
 * 얼굴 보정 슬라이더(결정 기록 '얼굴 인식 보정'). 값은 −1..1(화면의 −100..+100)이고 + = 크게·위로·넓게·길게다.
 * [code]는 저장할 때 쓰는 바뀌지 않는 번호다(차례와 상관없이, 새 슬라이더는 새 번호를 더한다). 차례는 화면에 보이는 차례다(부위 [part]마다).
 */
enum class FaceSlider(val code: Int, val part: FacePart) {
    /** 눈 크기: 두 눈을 저마다의 가운데에서 함께 키우거나 줄인다 */
    EYE_SIZE(1, FacePart.EYES),

    /** 눈 높이: 눈을 세로로만(뜬 정도) 늘이거나 줄인다 */
    EYE_HEIGHT(4, FacePart.EYES),

    /** 눈 너비: 눈을 눈머리–눈꼬리 쪽으로만 늘이거나 줄인다 */
    EYE_WIDTH(5, FacePart.EYES),

    /** 눈 기울기: 눈을 저마다의 가운데에서 돌린다(+면 두 눈꼬리가 올라감, 좌우 대칭) */
    EYE_TILT(6, FacePart.EYES),

    /** 눈 간격: 두 눈을 얼굴 가로축 쪽으로 밖(+)·안으로 옮긴다 */
    EYE_DISTANCE(7, FacePart.EYES),

    /** 뒷트임: 눈꼬리 둘레를 눈꼬리 쪽으로 늘인다(눈 안쪽 반은 그대로) */
    EYE_TAIL(8, FacePart.EYES),

    /** 코 높이: 코를 위(+)·아래로 옮긴다 */
    NOSE_HEIGHT(9, FacePart.NOSE),

    /** 코 너비: 콧방울 사이를 콧방울 선 쪽으로만 넓히거나 좁힌다 */
    NOSE_WIDTH(2, FacePart.NOSE),

    /** 콧등 너비: 콧등을 가로로만 넓히거나 좁힌다(눈머리와 콧방울에는 닿지 않음, 사용자 요청) */
    BRIDGE_WIDTH(10, FacePart.NOSE),

    /** 미소: 입꼬리를 위·조금 바깥으로(−면 아래로) 옮긴다 */
    SMILE(11, FacePart.MOUTH),

    /** 윗입술: 입선에서 윗입술 쪽만 두껍게·얇게 */
    UPPER_LIP(12, FacePart.MOUTH),

    /** 아랫입술: 입선에서 아랫입술 쪽만 두껍게·얇게 */
    LOWER_LIP(13, FacePart.MOUTH),

    /** 입 너비: 입을 입꼬리 쪽으로만 넓히거나 좁힌다 */
    MOUTH_WIDTH(14, FacePart.MOUTH),

    /** 입 높이: 입을 위(+)·아래로 옮긴다 */
    MOUTH_HEIGHT(15, FacePart.MOUTH),

    /** 이마: 눈썹 위부터 이마 끝까지를 위로 늘이거나(+) 줄인다 */
    FOREHEAD(16, FacePart.FACE_SHAPE),

    /** 턱 높이: 아랫입술 아래부터 턱 끝까지를 아래로 늘이거나(+) 줄인다 */
    CHIN_HEIGHT(17, FacePart.FACE_SHAPE),

    /** 턱선: 두 턱 모서리를 밖(+)·안으로 옮긴다 */
    JAWLINE(18, FacePart.FACE_SHAPE),

    /** 얼굴 너비: 볼·턱 윤곽을 얼굴 가로축(눈 선) 쪽으로만 밖·안으로 옮긴다 */
    FACE_WIDTH(3, FacePart.FACE_SHAPE);

    companion object {
        /** 저장 번호 [code]의 슬라이더. 모르는 번호(더 새 앱이 저장함)면 null */
        fun ofCode(code: Int): FaceSlider? = entries.firstOrNull { it.code == code }

        /** [part] 부위의 슬라이더들(화면 차례) */
        fun of(part: FacePart): List<FaceSlider> = entries.filter { it.part == part }
    }
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

    /** 0이 아닌 값들(저장용): 슬라이더 저장 번호와 값 */
    fun nonZero(): List<Pair<Int, Float>> = FaceSlider.entries.filter { this[it] != 0f }.map { it.code to this[it] }

    override fun equals(other: Any?): Boolean = other is FaceValues && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = FaceSlider.entries.joinToString(prefix = "FaceValues(", postfix = ")") { "${it.name}=${this[it]}" }

    companion object {
        /** 고친 것이 없는 값 */
        val ZERO = FaceValues(FloatArray(FaceSlider.entries.size))

        /** 저장한 값([nonZero])에서 되살린다. 모르는 번호(더 새 앱의 슬라이더)는 건너뛴다. */
        fun of(pairs: List<Pair<Int, Float>>): FaceValues =
            pairs.fold(ZERO) { values, (code, value) -> FaceSlider.ofCode(code)?.let { values.with(it, value) } ?: values }
    }
}

/**
 * 얼굴 보정 슬라이더 값을 유동화 변형([MeshWarp], 틀은 FaceWarpShapes.kt)으로 바꾼다(계획 '부위 계산'). 변형은
 * [com.kiln.stratum.engine.liquify.LiquifyMesh]의 격자에 합쳐 지금의 미리 보기·적용으로 그린다(새 변형 엔진 없음).
 * 값은 모두 특징점(문서 좌표)에서 정하므로 얼굴이 기울거나 커도 부위 방향·크기를 따른다. 상수는 폴드8에서 써 보고 고칠 수 있게 여기 한곳에 둔다.
 *
 * - 눈(크기·높이·너비·기울기·간격): 눈마다 윤곽 16점의 가운데를 둘러싼 타원. 눈머리 → 눈꼬리 축으로 눈꼬리 쪽 눈 폭의 [EYE_OUTER]배, 눈머리 쪽
 *   [EYE_INNER]배(콧등까지 닿지 않게 좁다), 세로는 [EYE_TALL]배(두 눈 폭의 평균보다 작게 잡지 않는다: 고개를 돌려 먼 눈이 좁게 보여도 세로 크기는 그대로라서).
 *   크기·높이·너비는 같은 타원으로 따로 차례로 늘인다(한 번에 곱해 늘이면 감쇠 띠가 접힐 만큼 눌림). 뒷트임은 눈꼬리를 가운데로 한 작은 타원이다.
 * - 코: 콧방울(129·358) 가운데의 타원(콧방울 선 쪽 [NOSE_ACROSS], 위 [NOSE_UP], 아래 [NOSE_DOWN] × 콧방울 사이). 콧등 너비는 콧등 가운데 줄의 타원으로,
 *   가로는 눈머리 앞에서, 아래는 콧방울 선 위에서 끝난다.
 * - 입: 입꼬리(61·291)와 입선(13·14)의 가운데. 위로는 콧방울 선까지, 아래로는 턱 끝까지의 거리에 비례한다. 입술은 입선에서 그 입술 쪽 반만 늘인다.
 * - 얼굴형: 얼굴 너비([FaceWidthWarp]), 턱선(턱 모서리 172·397 둘레를 가로로), 이마·턱 높이(얼굴 세로 띠 [BandMove]).
 * - 감쇠: 부위 전체를 고치는 것은 타원 거리 [PLATEAU]까지 다 걸리고(falloff), 한 점을 옮기는 것은 가운데에서만 다 걸린다(peak). 경계에서 정확히 0이라
 *   부위 상자 밖은 그대로다(배경이 휘지 않음).
 *
 * 늘이고 줄이는 부위는 사진에서 보이는 크기에 비례해 바뀌므로, 고개를 돌려 좁게 보이는 먼 쪽도 같은 비율로 고쳐진다.
 * 가로로 옮기는 것(얼굴 너비·턱선·눈 간격·뒷트임·미소의 바깥으로)은 먼 쪽을 좁게 보이는 만큼([FaceOutline.factor]) 덜 옮긴다
 * (2026-10-22 사용자 요청: "돌린 얼굴의 먼 쪽을 약하게 고치는 것도 2단계에 넣으세요").
 * 모든 변형은 접히지 않는다(되돌려 찾는 지도의 야코비안이 ±100%에서도 0.3 넘게, 단위 테스트).
 */
object FaceWarps {

    /** 슬라이더 +100%에서 눈을 키우는 배율(−100%는 그 역수): 크기, 높이(세로), 너비(가로) */
    const val EYE_MAX_SCALE = 1.22f
    const val EYE_HEIGHT_MAX_SCALE = 1.2f
    const val EYE_WIDTH_MAX_SCALE = 1.15f

    /** 눈 타원의 가로 반지름 = 눈 폭 × 이것: 눈꼬리 쪽(키운 눈꼬리도 다 걸리게 넉넉히), 눈머리 쪽(콧등에 닿기 전에 0) */
    const val EYE_OUTER = 1.2f
    const val EYE_INNER = 0.95f

    /** 눈 타원의 세로 반지름 = 눈 폭(두 눈 평균보다 작지 않게) × 이것 */
    const val EYE_TALL = 0.75f

    /** 눈 기울기 ±100%의 각도(도) */
    const val EYE_TILT_MAX_DEGREES = 8f

    /** 눈 간격 ±100%에서 한 눈을 옮기는 거리(U) */
    const val EYE_DISTANCE_MAX = 0.05f

    /**
     * 뒷트임 ±100%에서 눈꼬리를 옮기는 거리(눈 폭 배)와 그 타원(눈 폭 배): 눈 가운데 쪽(눈 가운데는 0.5라 거의 그대로, 눈 안쪽 반은 그대로),
     * 관자놀이 쪽, 세로. 0.5로 좁히면 다른 눈 슬라이더와 함께 ±100%일 때 너무 눌렸다(야코비안 0.26).
     */
    const val EYE_TAIL_MAX = 0.13f
    const val EYE_TAIL_INNER = 0.65f
    const val EYE_TAIL_OUTER = 0.7f
    const val EYE_TAIL_TALL = 0.45f

    /** 슬라이더 +100%에서 코(콧방울 사이)를 넓히는 배율 */
    const val NOSE_MAX_SCALE = 1.22f

    /** 코 타원: 콧방울 선 쪽 반지름, 위(눈 쪽), 아래(입 쪽) = 콧방울 사이 × 이것 */
    const val NOSE_ACROSS = 1.15f
    const val NOSE_UP = 1.0f
    const val NOSE_DOWN = 0.55f

    /** 코 높이 ±100%에서 코를 옮기는 거리(U) */
    const val NOSE_HEIGHT_MAX = 0.05f

    /** 콧등 너비 +100%의 배율. 타원의 가로 반지름은 콧등 줄에서 가까운 눈머리까지의 이만큼, 위는 168 위로 [BRIDGE_ABOVE]U, 아래는 콧방울 선 [BRIDGE_CLEAR]U 위까지 */
    const val BRIDGE_MAX_SCALE = 1.2f
    const val BRIDGE_ACROSS = 0.85f
    const val BRIDGE_ABOVE = 0.08f
    const val BRIDGE_CLEAR = 0.15f

    /** 미소 ±100%에서 입꼬리를 올리는 거리(입 폭 배), 그에 대한 바깥으로 옮기는 비, 입꼬리 타원(입 폭 배: 입 가운데 쪽, 볼 쪽, 세로) */
    const val SMILE_MAX = 0.06f
    const val SMILE_OUT = 0.35f
    const val SMILE_INNER = 0.35f
    const val SMILE_OUTER = 0.45f
    const val SMILE_TALL = 0.3f

    /**
     * 입술 +100%의 배율, 입술 타원: 입선에서 바깥 입술까지 두께의 [LIP_REACH]배(그 입술 쪽), 가로는 입 폭의 [LIP_ACROSS]배.
     * 감쇠는 [LIP_PLATEAU]부터라 띠가 넓다(−100%로 1/1.35배 얇게 해도 접히지 않게, 보통 [PLATEAU]면 야코비안 0.25).
     */
    const val LIP_MAX_SCALE = 1.35f
    const val LIP_REACH = 3.0f
    const val LIP_ACROSS = 0.75f
    const val LIP_PLATEAU = 0.3f

    /** 입 너비 +100%의 배율과 타원(가로: 입 폭 배, 위: 입선–콧방울 선 거리 배, 아래: 입선–턱 끝 거리 배) */
    const val MOUTH_WIDTH_MAX_SCALE = 1.15f
    const val MOUTH_ACROSS = 1.0f
    const val MOUTH_UP = 0.85f
    const val MOUTH_DOWN = 0.7f

    /** 입 높이 ±100%에서 입을 옮기는 거리(U)와 타원(가로: 입 폭 배, 아래: 입선–턱 끝 거리 배, 위는 [MOUTH_UP]) */
    const val MOUTH_HEIGHT_MAX = 0.05f
    const val MOUTH_MOVE_ACROSS = 0.8f
    const val MOUTH_MOVE_DOWN = 0.8f

    /** 이마 ±100%에서 이마 끝을 옮기는 거리(U), 눈썹 위 끝에서 띠가 시작하는 틈(U), 이마 끝 위로 0이 되기까지(U) */
    const val FOREHEAD_MAX = 0.08f
    const val FOREHEAD_GAP = 0.03f
    const val FOREHEAD_BEYOND = 0.35f

    /**
     * 턱 높이 ±100%에서 턱 끝을 옮기는 거리(U), 아랫입술 아래 끝에서 띠가 시작하는 틈(U), 턱 끝 아래로 0이 되기까지(U),
     * 띠의 반폭(아랫입술 높이의 얼굴 반폭 배, 높이마다 같다: 턱 끝에서 급하게 좁아지는 윤곽을 따르면 −100%에서 접혔다), 가장 좁은 반폭(U)
     */
    const val CHIN_MAX = 0.08f
    const val CHIN_GAP = 0.03f
    const val CHIN_BEYOND = 0.35f
    const val CHIN_WIDTH = 0.8f
    const val CHIN_MIN_HALF = 0.25f

    /** 턱선 ±100%에서 턱 모서리를 옮기는 거리(U)와 그 타원(U: 얼굴 안쪽, 바깥쪽, 세로) */
    const val JAW_MAX = 0.06f
    const val JAW_INNER = 0.45f
    const val JAW_OUTER = 0.4f
    const val JAW_TALL = 0.45f

    /** 감쇠가 시작하는 타원 거리(이 안은 다 걸린다) */
    const val PLATEAU = 0.45f

    /** 이보다 작은(문서 픽셀) 부위는 고치지 않는다(점이 한곳에 모인 얼굴) */
    private const val MIN_PART = 1f

    /** 입선에서 콧방울 선·턱 끝까지 거리의 가장 작은 값(U, 점이 깨진 얼굴에서 타원이 0이 되지 않게) */
    private const val MIN_REACH = 0.1f

    /** 얼굴 [faces]마다 값 [values](같은 순서, 없으면 0)를 건 변형들. 고친 것이 없으면 비었다. */
    fun of(faces: List<FaceLandmarks>, values: List<FaceValues>): List<MeshWarp> =
        faces.indices.flatMap { of(faces[it], values.getOrElse(it) { FaceValues.ZERO }) }

    /**
     * 얼굴 [face] 하나에 [values]를 건 변형들. 격자에 합칠 때 이 차례로 거꾸로 따라간다: 얼굴형(얼굴 너비 → 턱선 → 턱 높이 → 이마) → 눈 → 코 → 입이고,
     * 원래 그림에는 그 반대로(입·코·눈을 먼저, 얼굴형을 마지막에) 건 것과 같다. 부위 안에서도 옮기기를 늘이기보다 나중에 건다.
     * 특징점은 원래 그림의 것이고 부위끼리 거의 겹치지 않아 차례는 결과를 거의 바꾸지 않는다.
     */
    fun of(face: FaceLandmarks, values: FaceValues): List<MeshWarp> {
        if (values.isZero || !face.isFinite) return emptyList()
        val frame = FaceFrame(face)
        if (frame.unit < MIN_PART) return emptyList()
        val outline = FaceOutline.of(face, frame)
        return buildList {
            if (outline != null) {
                values[FaceSlider.FACE_WIDTH].takeIf { it != 0f }?.let { add(FaceWidthWarp.of(frame, outline, it)) }
                values[FaceSlider.JAWLINE].takeIf { it != 0f }?.let { addAll(jaw(face, frame, outline, it)) }
                values[FaceSlider.CHIN_HEIGHT].takeIf { it != 0f }?.let { chinHeight(face, frame, outline, it)?.let(::add) }
                values[FaceSlider.FOREHEAD].takeIf { it != 0f }?.let { forehead(face, frame, outline, it)?.let(::add) }
            }
            addAll(eyes(face, frame, outline, values))
            addAll(nose(face, frame, values))
            addAll(mouth(face, frame, outline, values))
        }
    }

    /** 두 눈: 거꾸로 따라갈 차례로 간격(옮기기) → 뒷트임 → 기울기 → 크기·높이·너비(늘이기) */
    private fun eyes(face: FaceLandmarks, frame: FaceFrame, outline: FaceOutline?, values: FaceValues): List<MeshWarp> {
        val size = values[FaceSlider.EYE_SIZE]
        val height = values[FaceSlider.EYE_HEIGHT]
        val width = values[FaceSlider.EYE_WIDTH]
        val tilt = values[FaceSlider.EYE_TILT]
        val distance = values[FaceSlider.EYE_DISTANCE]
        val tail = values[FaceSlider.EYE_TAIL]
        if (size == 0f && height == 0f && width == 0f && tilt == 0f && distance == 0f && tail == 0f) return emptyList()
        val right = EyeShape(face, FaceMeshIndex.RIGHT_EYE, FaceMeshIndex.RIGHT_EYE_OUTER, FaceMeshIndex.RIGHT_EYE_INNER)
        val left = EyeShape(face, FaceMeshIndex.LEFT_EYE, FaceMeshIndex.LEFT_EYE_OUTER, FaceMeshIndex.LEFT_EYE_INNER)
        val mean = (right.width + left.width) / 2f
        val moves = ArrayList<MeshWarp>()
        val tails = ArrayList<MeshWarp>()
        val tilts = ArrayList<MeshWarp>()
        val scales = ArrayList<MeshWarp>()
        // 오른쪽 눈은 얼굴 좌표 − 쪽, 왼쪽 눈은 + 쪽이다
        for ((eye, plusSide) in listOf(right to false, left to true)) {
            if (eye.width < MIN_PART) continue
            val tall = max(eye.width, mean) * EYE_TALL
            val shape = PartEllipse.of(eye.centerX, eye.centerY, eye.axisX, eye.axisY, frame, eye.width * EYE_INNER, eye.width * EYE_OUTER, tall, tall)
            val factor = outline?.factor(plusSide) ?: 1f
            if (distance != 0f) {
                val move = EYE_DISTANCE_MAX * distance * frame.unit * factor * (if (plusSide) 1f else -1f)
                moves += EllipseMove(shape, move * frame.acrossX, move * frame.acrossY, plateau = true)
            }
            if (tail != 0f) {
                val corner = PartEllipse.of(
                    eye.outerX, eye.outerY, eye.axisX, eye.axisY, frame,
                    eye.width * EYE_TAIL_INNER, eye.width * EYE_TAIL_OUTER, eye.width * EYE_TAIL_TALL, eye.width * EYE_TAIL_TALL
                )
                val move = EYE_TAIL_MAX * tail * eye.width * factor
                tails += EllipseMove(corner, move * eye.axisX, move * eye.axisY, plateau = false)
            }
            if (tilt != 0f) {
                // 그림에서 시계 방향으로 돌릴 때 눈꼬리가 턱 쪽으로 가는 눈은 반대로 돌려야 눈꼬리가 올라간다
                val clockwiseLowers = -eye.axisY * frame.downX + eye.axisX * frame.downY > 0f
                val angle = Math.toRadians((EYE_TILT_MAX_DEGREES * tilt).toDouble()).toFloat()
                tilts += EllipseRotate(shape, if (clockwiseLowers) -angle else angle)
            }
            // 크기·높이·너비는 따로 차례로 건다. 한 번에 곱해 늘이면(예: 크기와 높이 모두 −100%면 세로 1/1.46배) 감쇠 띠가 접힐 만큼 눌린다.
            if (width != 0f) scales += EllipseScale(shape, EYE_WIDTH_MAX_SCALE.pow(width), 1f)
            if (height != 0f) scales += EllipseScale(shape, 1f, EYE_HEIGHT_MAX_SCALE.pow(height))
            if (size != 0f) scales += EllipseScale(shape, EYE_MAX_SCALE.pow(size), EYE_MAX_SCALE.pow(size))
        }
        return moves + tails + tilts + scales
    }

    /** 코: 거꾸로 따라갈 차례로 높이(옮기기) → 콧등 너비 → 코 너비 */
    private fun nose(face: FaceLandmarks, frame: FaceFrame, values: FaceValues): List<MeshWarp> {
        val height = values[FaceSlider.NOSE_HEIGHT]
        val width = values[FaceSlider.NOSE_WIDTH]
        val bridge = values[FaceSlider.BRIDGE_WIDTH]
        if (height == 0f && width == 0f && bridge == 0f) return emptyList()
        val rx = face.x(FaceMeshIndex.NOSE_WING_RIGHT)
        val ry = face.y(FaceMeshIndex.NOSE_WING_RIGHT)
        val lx = face.x(FaceMeshIndex.NOSE_WING_LEFT)
        val ly = face.y(FaceMeshIndex.NOSE_WING_LEFT)
        val wings = hypot(lx - rx, ly - ry)
        if (wings < MIN_PART) return emptyList()
        val shape = PartEllipse.of(
            (rx + lx) / 2f, (ry + ly) / 2f, (lx - rx) / wings, (ly - ry) / wings, frame,
            wings * NOSE_ACROSS, wings * NOSE_ACROSS, wings * NOSE_UP, wings * NOSE_DOWN
        )
        return buildList {
            if (height != 0f) {
                val move = NOSE_HEIGHT_MAX * height * frame.unit
                add(EllipseMove(shape, -move * frame.downX, -move * frame.downY, plateau = true))
            }
            if (bridge != 0f) bridgeShape(face, frame)?.let { add(EllipseScale(it, BRIDGE_MAX_SCALE.pow(bridge), 1f)) }
            if (width != 0f) add(EllipseScale(shape, NOSE_MAX_SCALE.pow(width), 1f))
        }
    }

    /**
     * 콧등 타원(사용자 요청 '콧등 너비'): 가운데는 콧등 줄(168·6·197·195·5)의 가운데, 가로 반지름은 그 줄에서 가까운 눈머리(133·362)까지의 [BRIDGE_ACROSS]배라
     * 눈머리에 닿기 전에 0이고, 위는 168보다 [BRIDGE_ABOVE]U 위, 아래는 콧방울 선보다 [BRIDGE_CLEAR]U 위에서 끝나 코 너비와 겹치지 않는다.
     */
    private fun bridgeShape(face: FaceLandmarks, frame: FaceFrame): PartEllipse? {
        val line = FaceMeshIndex.NOSE_BRIDGE
        val midU = line.map { frame.u(face, it) }.average().toFloat()
        val topV = frame.v(face, line.first())
        val bottomV = frame.v(face, line.last())
        val centerV = (topV + bottomV) / 2f
        val wingV = (frame.v(face, FaceMeshIndex.NOSE_WING_RIGHT) + frame.v(face, FaceMeshIndex.NOSE_WING_LEFT)) / 2f
        val clearance = min(abs(frame.u(face, FaceMeshIndex.RIGHT_EYE_INNER) - midU), abs(frame.u(face, FaceMeshIndex.LEFT_EYE_INNER) - midU))
        val across = clearance * BRIDGE_ACROSS * frame.unit
        if (across < MIN_PART) return null
        val up = max(MIN_REACH, centerV - topV + BRIDGE_ABOVE) * frame.unit
        val down = max(MIN_REACH, wingV - BRIDGE_CLEAR - centerV) * frame.unit
        return PartEllipse.of(frame.x(midU, centerV), frame.y(midU, centerV), frame.acrossX, frame.acrossY, frame, across, across, up, down)
    }

    /** 입: 거꾸로 따라갈 차례로 높이(옮기기) → 미소 → 너비 → 윗입술 → 아랫입술 */
    private fun mouth(face: FaceLandmarks, frame: FaceFrame, outline: FaceOutline?, values: FaceValues): List<MeshWarp> {
        val smile = values[FaceSlider.SMILE]
        val upper = values[FaceSlider.UPPER_LIP]
        val lower = values[FaceSlider.LOWER_LIP]
        val width = values[FaceSlider.MOUTH_WIDTH]
        val height = values[FaceSlider.MOUTH_HEIGHT]
        if (smile == 0f && upper == 0f && lower == 0f && width == 0f && height == 0f) return emptyList()
        val rx = face.x(FaceMeshIndex.MOUTH_RIGHT)
        val ry = face.y(FaceMeshIndex.MOUTH_RIGHT)
        val lx = face.x(FaceMeshIndex.MOUTH_LEFT)
        val ly = face.y(FaceMeshIndex.MOUTH_LEFT)
        val mouthWidth = hypot(lx - rx, ly - ry)
        if (mouthWidth < MIN_PART) return emptyList()
        val axisX = (lx - rx) / mouthWidth
        val axisY = (ly - ry) / mouthWidth
        // 입선(입술 사이)과 입 가운데
        val lineX = (face.x(FaceMeshIndex.UPPER_LIP_INNER) + face.x(FaceMeshIndex.LOWER_LIP_INNER)) / 2f
        val lineY = (face.y(FaceMeshIndex.UPPER_LIP_INNER) + face.y(FaceMeshIndex.LOWER_LIP_INNER)) / 2f
        val centerX = ((rx + lx) / 2f + lineX) / 2f
        val centerY = ((ry + ly) / 2f + lineY) / 2f
        val lineV = frame.v(lineX, lineY)
        val wingV = (frame.v(face, FaceMeshIndex.NOSE_WING_RIGHT) + frame.v(face, FaceMeshIndex.NOSE_WING_LEFT)) / 2f
        val above = max(MIN_REACH, lineV - wingV) * frame.unit
        val below = max(MIN_REACH, frame.v(face, FaceMeshIndex.CHIN) - lineV) * frame.unit
        return buildList {
            if (height != 0f) {
                val shape = PartEllipse.of(
                    centerX, centerY, axisX, axisY, frame,
                    mouthWidth * MOUTH_MOVE_ACROSS, mouthWidth * MOUTH_MOVE_ACROSS, above * MOUTH_UP, below * MOUTH_MOVE_DOWN
                )
                val move = MOUTH_HEIGHT_MAX * height * frame.unit
                add(EllipseMove(shape, -move * frame.downX, -move * frame.downY, plateau = true))
            }
            if (smile != 0f) {
                // 입꼬리마다 위로, 조금 바깥으로(먼 쪽은 바깥으로 덜). 오른쪽 입꼬리는 얼굴 좌표 − 쪽이다.
                for ((corner, plusSide) in listOf(FaceMeshIndex.MOUTH_RIGHT to false, FaceMeshIndex.MOUTH_LEFT to true)) {
                    val outX = axisX * (if (plusSide) 1f else -1f)
                    val outY = axisY * (if (plusSide) 1f else -1f)
                    val shape = PartEllipse.of(
                        face.x(corner), face.y(corner), outX, outY, frame,
                        mouthWidth * SMILE_INNER, mouthWidth * SMILE_OUTER, mouthWidth * SMILE_TALL, mouthWidth * SMILE_TALL
                    )
                    val lift = SMILE_MAX * smile * mouthWidth
                    val out = lift * SMILE_OUT * (outline?.factor(plusSide) ?: 1f)
                    add(EllipseMove(shape, out * outX - lift * frame.downX, out * outY - lift * frame.downY, plateau = false))
                }
            }
            if (width != 0f) {
                val shape = PartEllipse.of(centerX, centerY, axisX, axisY, frame, mouthWidth * MOUTH_ACROSS, mouthWidth * MOUTH_ACROSS, above * MOUTH_UP, below * MOUTH_DOWN)
                add(EllipseScale(shape, MOUTH_WIDTH_MAX_SCALE.pow(width), 1f))
            }
            if (upper != 0f) {
                val thickness = max(MIN_PART, (lineV - frame.v(face, FaceMeshIndex.UPPER_LIP_TOP)) * frame.unit)
                val shape = PartEllipse.of(lineX, lineY, axisX, axisY, frame, mouthWidth * LIP_ACROSS, mouthWidth * LIP_ACROSS, thickness * LIP_REACH, thickness)
                add(EllipseScale(shape, 1f, LIP_MAX_SCALE.pow(upper), half = -1, plateau = LIP_PLATEAU))
            }
            if (lower != 0f) {
                val thickness = max(MIN_PART, (frame.v(face, FaceMeshIndex.LOWER_LIP_BOTTOM) - lineV) * frame.unit)
                val shape = PartEllipse.of(lineX, lineY, axisX, axisY, frame, mouthWidth * LIP_ACROSS, mouthWidth * LIP_ACROSS, thickness, thickness * LIP_REACH)
                add(EllipseScale(shape, 1f, LIP_MAX_SCALE.pow(lower), half = 1, plateau = LIP_PLATEAU))
            }
        }
    }

    /** 턱선: 두 턱 모서리(172·397) 둘레를 얼굴 가로축 쪽으로 밖(+)·안으로(먼 쪽은 덜) */
    private fun jaw(face: FaceLandmarks, frame: FaceFrame, outline: FaceOutline, value: Float): List<MeshWarp> =
        listOf(FaceMeshIndex.JAW_RIGHT to false, FaceMeshIndex.JAW_LEFT to true).map { (corner, plusSide) ->
            val outX = frame.acrossX * (if (plusSide) 1f else -1f)
            val outY = frame.acrossY * (if (plusSide) 1f else -1f)
            val shape = PartEllipse.of(
                face.x(corner), face.y(corner), outX, outY, frame,
                JAW_INNER * frame.unit, JAW_OUTER * frame.unit, JAW_TALL * frame.unit, JAW_TALL * frame.unit
            )
            val move = JAW_MAX * value * frame.unit * outline.factor(plusSide)
            EllipseMove(shape, move * outX, move * outY, plateau = false)
        }

    /** 이마: 눈썹 위 끝 위([FOREHEAD_GAP])에서 0, 이마 끝(10)에서 다 옮기고 그 위 [FOREHEAD_BEYOND]U에서 0. 가로는 눈썹 높이의 얼굴 폭이다. */
    private fun forehead(face: FaceLandmarks, frame: FaceFrame, outline: FaceOutline, value: Float): MeshWarp? {
        val browTop = (FaceMeshIndex.RIGHT_BROW + FaceMeshIndex.LEFT_BROW).minOf { frame.v(face, it) }
        val zeroV = browTop - FOREHEAD_GAP
        val fullV = frame.v(face, FaceMeshIndex.FOREHEAD)
        if (zeroV - fullV < MIN_REACH) return null
        val half = (outline.half(zeroV, plusSide = true) + outline.half(zeroV, plusSide = false)) / 2f
        return BandMove.of(frame, outline, -FOREHEAD_MAX * value, zeroV, fullV, fullV - FOREHEAD_BEYOND, half, MIN_REACH)
    }

    /**
     * 턱 높이: 아랫입술 아래 끝(17) 아래([CHIN_GAP])에서 0, 턱 끝(152)에서 다 옮기고 그 아래 [CHIN_BEYOND]U에서 0.
     * 가로는 아랫입술 높이 얼굴 반폭의 [CHIN_WIDTH]배로 높이마다 같다.
     */
    private fun chinHeight(face: FaceLandmarks, frame: FaceFrame, outline: FaceOutline, value: Float): MeshWarp? {
        val zeroV = frame.v(face, FaceMeshIndex.LOWER_LIP_BOTTOM) + CHIN_GAP
        val fullV = frame.v(face, FaceMeshIndex.CHIN)
        if (fullV - zeroV < MIN_REACH) return null
        val half = (outline.half(zeroV, plusSide = true) + outline.half(zeroV, plusSide = false)) / 2f * CHIN_WIDTH
        return BandMove.of(frame, outline, CHIN_MAX * value, zeroV, fullV, fullV + CHIN_BEYOND, half, CHIN_MIN_HALF)
    }

    /** 눈 하나: 윤곽 점들의 가운데, 눈꼬리 자리, 눈머리 → 눈꼬리 축(단위 벡터), 눈 폭 */
    private class EyeShape(face: FaceLandmarks, contour: IntArray, outer: Int, inner: Int) {
        val centerX = contour.map { face.x(it) }.average().toFloat()
        val centerY = contour.map { face.y(it) }.average().toFloat()
        val outerX = face.x(outer)
        val outerY = face.y(outer)
        val width = hypot(face.x(outer) - face.x(inner), face.y(outer) - face.y(inner))
        val axisX = if (width > 0f) (face.x(outer) - face.x(inner)) / width else 1f
        val axisY = if (width > 0f) (face.y(outer) - face.y(inner)) / width else 0f
    }
}

package com.kiln.stratum.engine.face

import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.MeshWarp
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 얼굴 보정 슬라이더 값 → 유동화 변형(계획 '부위 계산'): 방향, 크기, 상자 밖 0, 기울기·크기를 따름, 접히지 않음, 돌린 얼굴의 먼 쪽 */
class FaceWarpsTest {

    private val size = PixelSize(1200, 1200)
    private val mesh = LiquifyMesh.identity(size)
    private val upright = TestFace(eyeX = 600f, eyeY = 450f, eyeDistance = 200f)

    private fun values(eye: Float = 0f, nose: Float = 0f, width: Float = 0f): FaceValues =
        FaceValues.ZERO.with(FaceSlider.EYE_SIZE, eye).with(FaceSlider.NOSE_WIDTH, nose).with(FaceSlider.FACE_WIDTH, width)

    /** 얼굴 좌표 ([u], [v])에 있던 원래 그림의 점이 고친 결과에서 보이는 자리(얼굴 좌표) */
    private fun shown(face: TestFace, warps: List<MeshWarp>, u: Float, v: Float): Pair<Float, Float> {
        val (x, y) = face.at(u, v)
        val out = FloatArray(2)
        mesh.forward(x, y, out, warps, iterations = 40)
        return toFace(face, out[0], out[1])
    }

    private fun toFace(face: TestFace, x: Float, y: Float): Pair<Float, Float> {
        val c = cos(face.angle.toDouble())
        val s = sin(face.angle.toDouble())
        val dx = (x - face.eyeX) / face.eyeDistance
        val dy = (y - face.eyeY) / face.eyeDistance
        return (c * dx + s * dy).toFloat() to (-s * dx + c * dy).toFloat()
    }

    /** 결과 자리 ([x], [y])가 읽는 원래 그림의 자리 */
    private fun source(warps: List<MeshWarp>, x: Float, y: Float): Pair<Float, Float> {
        val out = FloatArray(2)
        mesh.sourceOf(x, y, out, warps)
        return out[0] to out[1]
    }

    @Test
    fun zeroValues_giveNoWarps() {
        assertTrue(FaceWarps.of(upright.landmarks(), FaceValues.ZERO).isEmpty())
        assertEquals(4, FaceWarps.of(upright.landmarks(), values(eye = 0.5f, nose = 0.5f, width = 0.5f)).size)
        assertEquals(1, FaceWarps.of(upright.landmarks(), values(nose = -0.2f)).size)
    }

    @Test
    fun values_areClampedAndMinusZeroIsZero() {
        val value = FaceValues.ZERO.with(FaceSlider.EYE_SIZE, 3f).with(FaceSlider.NOSE_WIDTH, -0f)

        assertEquals(1f, value[FaceSlider.EYE_SIZE], 0f)
        assertEquals(FaceValues.ZERO.with(FaceSlider.EYE_SIZE, 1f), value)
        assertTrue(FaceValues.ZERO.with(FaceSlider.FACE_WIDTH, -0f).isZero)
    }

    @Test
    fun eyeSize_growsTheEyesAboutTheirCenters_andPlusIsBigger() {
        val bigger = FaceWarps.of(upright.landmarks(), values(eye = 1f))
        val smaller = FaceWarps.of(upright.landmarks(), values(eye = -1f))

        // 오른쪽 눈 가운데(-0.485, 0.02)는 그대로, 눈꼬리(-0.72)는 바깥으로 약 1.22배(감쇠가 거의 1)
        val center = shown(upright, bigger, -0.485f, 0.02f)
        assertEquals(-0.485f, center.first, 1e-3f)
        assertEquals(0.02f, center.second, 1e-3f)
        val corner = shown(upright, bigger, -0.72f, 0.02f)
        val grown = (-0.485f - corner.first) / 0.235f
        assertTrue("grown $grown", grown in 1.18f..1.23f)
        val shrunk = (-0.485f - shown(upright, smaller, -0.72f, 0.02f).first) / 0.235f
        assertTrue("shrunk $shrunk", shrunk in 0.80f..0.84f)
        // 위 꺼풀도 위로(세로도 함께)
        assertTrue(shown(upright, bigger, -0.485f, -0.07f).second < -0.08f)
        // 왼쪽 눈도 같다(거울)
        assertEquals(-corner.first, shown(upright, bigger, 0.72f, 0.02f).first, 1e-3f)
    }

    @Test
    fun noseWidth_widensOnlyAlongTheWingLine() {
        val wider = FaceWarps.of(upright.landmarks(), values(nose = 1f))

        // 콧방울(0.3 떨어짐)은 감쇠 안이라 정확히 1.22배, 높이는 그대로
        val wing = shown(upright, wider, 0.3f, 0.68f)
        assertEquals(0.3f * FaceWarps.NOSE_MAX_SCALE, wing.first, 2e-3f)
        assertEquals(0.68f, wing.second, 1e-4f)
        // 코 가운데 줄은 그대로
        assertEquals(0f, shown(upright, wider, 0f, 0.5f).first, 1e-4f)
        val narrower = FaceWarps.of(upright.landmarks(), values(nose = -1f))
        assertEquals(0.3f / FaceWarps.NOSE_MAX_SCALE, shown(upright, narrower, 0.3f, 0.68f).first, 2e-3f)
    }

    @Test
    fun faceWidth_movesTheCheekOutlineSideways_andLeavesTheMiddle() {
        val wider = FaceWarps.of(upright.landmarks(), values(width = 1f))
        val narrower = FaceWarps.of(upright.landmarks(), values(width = -1f))
        // 볼 높이(v 0.8)의 윤곽: 타원 반폭 0.95 × √(1 - ((0.8 - 0.3)/1.35)²)
        val edge = 0.95f * kotlin.math.sqrt(1f - ((0.8f - 0.3f) / 1.35f).let { it * it })

        // U = 눈 사이(1)라 한쪽 약 [FaceWidthWarp.MAX_MOVE]씩(윤곽 바로 바깥 띠가 조금 덜 옮겨 그보다 조금 작다)
        val move = FaceWidthWarp.MAX_MOVE
        assertEquals(edge + move, shown(upright, wider, edge, 0.8f).first, 0.012f)
        assertEquals(-edge - move, shown(upright, wider, -edge, 0.8f).first, 0.012f)
        assertEquals(edge - move, shown(upright, narrower, edge, 0.8f).first, 0.012f)
        assertTrue(shown(upright, wider, edge, 0.8f).first <= edge + move)
        assertEquals(0.8f, shown(upright, wider, edge, 0.8f).second, 1e-4f)
        // 가운데(코·입)와 눈은 그대로
        for ((u, v) in listOf(0f to 0.6f, 0.38f to 1.0f, -0.5f to 0f, 0f to 1.2f)) {
            val point = shown(upright, wider, u, v)
            assertEquals("($u, $v)", u, point.first, 1e-4f)
        }
    }

    @Test
    fun outsideTheBoxes_andAtTheirEdges_nothingMoves() {
        val face = upright.landmarks()
        for (value in listOf(1f, -1f)) {
            val warps = FaceWarps.of(face, values(eye = value, nose = value, width = value))
            for (warp in warps) {
                // 상자 둘레에서 변위는 0(경계에서 매끈하게 0이 된다)
                val out = FloatArray(2)
                for (k in 0..200) {
                    val f = k / 200f
                    for ((x, y) in listOf(
                        warp.left + (warp.right - warp.left) * f to warp.top,
                        warp.left + (warp.right - warp.left) * f to warp.bottom,
                        warp.left to warp.top + (warp.bottom - warp.top) * f,
                        warp.right to warp.top + (warp.bottom - warp.top) * f
                    )) {
                        warp.displacement(x, y, out)
                        assertEquals(0f, hypot(out[0], out[1]), 1e-3f)
                    }
                }
            }
            // 얼굴에서 먼 배경(윤곽에서 띠보다 멀리)은 그대로
            for ((u, v) in listOf(1.3f to 0.8f, -1.3f to 0.8f, 0f to -1.5f, 0f to 2.2f, 1.2f to -0.6f)) {
                val (x, y) = upright.at(u, v)
                val (sx, sy) = source(warps, x, y)
                assertEquals("($u, $v)", 0f, hypot(sx - x, sy - y), 1e-4f)
            }
        }
    }

    @Test
    fun aTiltedSmallerFace_isChangedTheSameWayInItsOwnFrame() {
        val tilted = TestFace(eyeX = 420f, eyeY = 380f, eyeDistance = 100f, angle = (37 * PI / 180).toFloat())
        val change = values(eye = 0.7f, nose = -0.6f, width = 0.8f)
        val warpsA = FaceWarps.of(upright.landmarks(), change)
        val warpsB = FaceWarps.of(tilted.landmarks(), change)

        for (u in -12..12) {
            for (v in -8..22) {
                val fu = u * 0.1f
                val fv = v * 0.1f
                val (ax, ay) = upright.at(fu, fv)
                val (bx, by) = tilted.at(fu, fv)
                val a = source(warpsA, ax, ay).let { toFace(upright, it.first, it.second) }
                val b = source(warpsB, bx, by).let { toFace(tilted, it.first, it.second) }
                assertEquals("u at ($fu, $fv)", a.first, b.first, 1e-3f)
                assertEquals("v at ($fu, $fv)", a.second, b.second, 1e-3f)
            }
        }
    }

    @Test
    fun nothingFolds_atFullStrength() {
        val face = upright.landmarks()
        for (change in listOf(
            values(eye = 1f), values(eye = -1f), values(nose = 1f), values(nose = -1f), values(width = 1f), values(width = -1f),
            values(1f, 1f, 1f), values(-1f, -1f, -1f), values(1f, -1f, -1f), values(-1f, 1f, 1f)
        )) {
            val warps = FaceWarps.of(face, change)
            val worst = smallestJacobian(warps, upright, -1.4f..1.4f, -0.8f..2.1f)
            assertTrue("$change: Jacobian $worst", worst > 0.3f)
        }
        // 부위 하나씩은 계획의 한도 안이다(얼굴 너비 0.4, 눈·코 약 0.57)
        assertTrue(smallestJacobian(FaceWarps.of(face, values(width = -1f)), upright, -1.4f..1.4f, -0.8f..2.1f) >= 1f - FaceWidthWarp.SLOPE_LIMIT - 0.02f)
        assertTrue(smallestJacobian(FaceWarps.of(face, values(eye = -1f)), upright, -1.2f..1.2f, -0.6f..0.6f) > 0.55f)
    }

    @Test
    fun aTurnedFace_movesItsNarrowSideLessByHowMuchNarrowerItLooks() {
        val turned = TestFace(eyeX = 600f, eyeY = 450f, eyeDistance = 200f, turn = 0.4f)
        val warps = FaceWarps.of(turned.landmarks(), values(width = 1f))
        val edge = 0.95f * kotlin.math.sqrt(1f - ((0.8f - 0.3f) / 1.35f).let { it * it })

        val near = shown(turned, warps, edge, 0.8f).first - edge
        val far = -(shown(turned, warps, -edge * 0.6f, 0.8f).first + edge * 0.6f)
        assertEquals(FaceWidthWarp.MAX_MOVE, near, 0.012f)
        // 먼 쪽(사진에서 0.6배로 좁음)은 약 0.6배만 옮긴다
        assertEquals(0.6f, far / near, 0.06f)
    }

    @Test
    fun aDegenerateFace_givesNoWarps() {
        val points = FloatArray(FaceModel.LANDMARK_COUNT * 2) { 100f }

        assertTrue(FaceWarps.of(FaceLandmarks(points, 15f), values(1f, 1f, 1f)).isEmpty())
    }

    /** 얼굴 좌표 범위([us]×[vs], [step] 간격)에서 되돌려 찾는 지도(결과 자리 → 읽는 자리)의 야코비안 가장 작은 값 */
    private fun smallestJacobian(
        warps: List<MeshWarp>,
        face: TestFace,
        us: ClosedFloatingPointRange<Float>,
        vs: ClosedFloatingPointRange<Float>,
        step: Float = 0.01f
    ): Float {
        val h = 0.25f
        var worst = Float.POSITIVE_INFINITY
        var u = us.start
        while (u <= us.endInclusive) {
            var v = vs.start
            while (v <= vs.endInclusive) {
                val (x, y) = face.at(u, v)
                val (rx, ry) = source(warps, x + h, y)
                val (lx, ly) = source(warps, x - h, y)
                val (dx, dy) = source(warps, x, y + h)
                val (ux, uy) = source(warps, x, y - h)
                val det = ((rx - lx) * (dy - uy) - (ry - ly) * (dx - ux)) / (4 * h * h)
                if (det < worst) worst = det
                v += step
            }
            u += step
        }
        assertTrue(abs(worst) < 10f)
        return worst
    }

    private fun change(vararg pairs: Pair<FaceSlider, Float>): FaceValues = pairs.fold(FaceValues.ZERO) { values, (slider, value) -> values.with(slider, value) }

    /** 모든 슬라이더를 [value]로 */
    private fun all(value: Float): FaceValues = change(*FaceSlider.entries.map { it to value }.toTypedArray())

    /** [slider]만 [value]일 때 원래 그림의 얼굴 좌표 ([u], [v]) 점이 보이는 자리 */
    private fun moved(slider: FaceSlider, value: Float, u: Float, v: Float, face: TestFace = upright): Pair<Float, Float> =
        shown(face, FaceWarps.of(face.landmarks(), change(slider to value)), u, v)

    @Test
    fun eyeHeightAndWidth_stretchTheEyeOneWayOnly() {
        // 위 꺼풀(눈 가운데에서 0.09 위)은 1.2배로, 눈꼬리는 그대로
        assertEquals(0.02f - 0.09f * FaceWarps.EYE_HEIGHT_MAX_SCALE, moved(FaceSlider.EYE_HEIGHT, 1f, -0.485f, -0.07f).second, 2e-3f)
        assertEquals(-0.72f, moved(FaceSlider.EYE_HEIGHT, 1f, -0.72f, 0.02f).first, 1e-3f)
        // 눈꼬리(눈 가운데에서 0.235)는 1.15배로, 위 꺼풀 높이는 그대로
        assertEquals(-0.485f - 0.235f * FaceWarps.EYE_WIDTH_MAX_SCALE, moved(FaceSlider.EYE_WIDTH, 1f, -0.72f, 0.02f).first, 3e-3f)
        assertEquals(-0.07f, moved(FaceSlider.EYE_WIDTH, 1f, -0.485f, -0.07f).second, 1e-3f)
        assertEquals(0.485f + 0.235f / FaceWarps.EYE_WIDTH_MAX_SCALE, moved(FaceSlider.EYE_WIDTH, -1f, 0.72f, 0.02f).first, 3e-3f)
    }

    @Test
    fun eyeTilt_liftsBothOuterCorners_andLowersTheInnerOnes() {
        val lift = 0.235f * sin(FaceWarps.EYE_TILT_MAX_DEGREES * PI.toFloat() / 180f)
        for (side in listOf(-1f, 1f)) {
            assertEquals("outer $side", 0.02f - lift, moved(FaceSlider.EYE_TILT, 1f, side * 0.72f, 0.02f).second, 4e-3f)
            assertTrue("inner $side", moved(FaceSlider.EYE_TILT, 1f, side * 0.25f, 0.02f).second > 0.04f)
            assertTrue("minus $side", moved(FaceSlider.EYE_TILT, -1f, side * 0.72f, 0.02f).second > 0.04f)
        }
    }

    @Test
    fun eyeDistance_andTail_moveSideways() {
        val step = FaceWarps.EYE_DISTANCE_MAX
        assertEquals(-0.485f - step, moved(FaceSlider.EYE_DISTANCE, 1f, -0.485f, 0.02f).first, 2e-3f)
        assertEquals(0.485f + step, moved(FaceSlider.EYE_DISTANCE, 1f, 0.485f, 0.02f).first, 2e-3f)
        assertEquals(0.485f - step, moved(FaceSlider.EYE_DISTANCE, -1f, 0.485f, 0.02f).first, 2e-3f)
        // 뒷트임: 눈꼬리만 바깥으로(눈 폭 0.47의 13%), 눈 가운데는 거의(10% 안), 눈머리는 그대로
        val tail = FaceWarps.EYE_TAIL_MAX * 0.47f
        assertEquals(-0.72f - tail, moved(FaceSlider.EYE_TAIL, 1f, -0.72f, 0.02f).first, 3e-3f)
        assertEquals(0.72f + tail, moved(FaceSlider.EYE_TAIL, 1f, 0.72f, 0.02f).first, 3e-3f)
        assertEquals(-0.485f, moved(FaceSlider.EYE_TAIL, 1f, -0.485f, 0.02f).first, tail * 0.1f)
        assertEquals(0.25f, moved(FaceSlider.EYE_TAIL, 1f, 0.25f, 0.02f).first, 1e-4f)
    }

    @Test
    fun noseHeight_andBridgeWidth() {
        // 코는 위로 옮기고 눈은 그대로
        assertEquals(0.6f - FaceWarps.NOSE_HEIGHT_MAX, moved(FaceSlider.NOSE_HEIGHT, 1f, 0f, 0.6f).second, 3e-3f)
        assertEquals(0.68f - FaceWarps.NOSE_HEIGHT_MAX, moved(FaceSlider.NOSE_HEIGHT, 1f, 0.3f, 0.68f).second, 3e-3f)
        assertEquals(0.02f, moved(FaceSlider.NOSE_HEIGHT, 1f, -0.72f, 0.02f).second, 1e-4f)
        // 콧등 옆(콧등 줄에서 0.06)은 1.2배로 넓히고, 눈머리와 콧방울은 그대로
        assertEquals(0.06f * FaceWarps.BRIDGE_MAX_SCALE, moved(FaceSlider.BRIDGE_WIDTH, 1f, 0.06f, 0.32f).first, 2e-3f)
        assertEquals(0.06f / FaceWarps.BRIDGE_MAX_SCALE, moved(FaceSlider.BRIDGE_WIDTH, -1f, 0.06f, 0.32f).first, 2e-3f)
        for ((u, v) in listOf(0.25f to 0.02f, -0.25f to 0.02f, 0.3f to 0.68f, -0.3f to 0.68f)) {
            assertEquals("($u, $v)", u, moved(FaceSlider.BRIDGE_WIDTH, 1f, u, v).first, 1e-4f)
        }
    }

    @Test
    fun mouthSliders_moveTheirPartsTheRightWay() {
        val mouth = 0.76f
        // 미소: 입꼬리가 위로(입 폭의 6%), 조금 바깥으로. 입 가운데는 그대로
        val corner = moved(FaceSlider.SMILE, 1f, -0.38f, 1.0f)
        assertEquals(1.0f - FaceWarps.SMILE_MAX * mouth, corner.second, 3e-3f)
        assertTrue(corner.first < -0.38f - 0.01f)
        assertEquals(1.0f + FaceWarps.SMILE_MAX * mouth, moved(FaceSlider.SMILE, -1f, 0.38f, 1.0f).second, 3e-3f)
        assertEquals(1.0f, moved(FaceSlider.SMILE, 1f, 0f, 1.0f).second, 1e-4f)
        // 입술: 그 입술만 약 1.35배 두껍게(입선 v 1.0, 두께 윗입술 0.08·아랫입술 0.12, 키운 끝이 감쇠 띠에 조금 걸려 1.3배 넘게)
        val upperLip = (1.0f - moved(FaceSlider.UPPER_LIP, 1f, 0f, 0.92f).second) / 0.08f
        assertTrue("upper $upperLip", upperLip in 1.3f..FaceWarps.LIP_MAX_SCALE)
        assertEquals(1.12f, moved(FaceSlider.UPPER_LIP, 1f, 0f, 1.12f).second, 1e-4f)
        val lowerLip = (moved(FaceSlider.LOWER_LIP, 1f, 0f, 1.12f).second - 1.0f) / 0.12f
        assertTrue("lower $lowerLip", lowerLip in 1.3f..FaceWarps.LIP_MAX_SCALE)
        assertEquals(0.92f, moved(FaceSlider.LOWER_LIP, 1f, 0f, 0.92f).second, 1e-4f)
        val thinner = (moved(FaceSlider.LOWER_LIP, -1f, 0f, 1.12f).second - 1.0f) / 0.12f
        assertTrue("thinner $thinner", thinner in 1f / FaceWarps.LIP_MAX_SCALE..0.8f)
        // 입 너비: 입꼬리가 약 1.15배 바깥으로, 입 높이: 입이 위로
        assertEquals(-0.38f * FaceWarps.MOUTH_WIDTH_MAX_SCALE, moved(FaceSlider.MOUTH_WIDTH, 1f, -0.38f, 1.0f).first, 6e-3f)
        assertEquals(1.0f - FaceWarps.MOUTH_HEIGHT_MAX, moved(FaceSlider.MOUTH_HEIGHT, 1f, 0f, 1.0f).second, 3e-3f)
        assertEquals(1.0f + FaceWarps.MOUTH_HEIGHT_MAX, moved(FaceSlider.MOUTH_HEIGHT, -1f, 0f, 1.0f).second, 3e-3f)
    }

    @Test
    fun faceShapeSliders_moveTheOutline_andKeepTheFeatures() {
        // 이마: 이마 끝(윤곽 맨 위 v −1.05)이 위로, 눈썹은 그대로
        assertEquals(-1.05f - FaceWarps.FOREHEAD_MAX, moved(FaceSlider.FOREHEAD, 1f, 0f, -1.05f).second, 3e-3f)
        assertEquals(-0.36f, moved(FaceSlider.FOREHEAD, 1f, -0.5f, -0.36f).second, 1e-4f)
        assertEquals(-1.05f + FaceWarps.FOREHEAD_MAX, moved(FaceSlider.FOREHEAD, -1f, 0f, -1.05f).second, 3e-3f)
        // 턱 높이: 턱 끝(v 1.65)이 아래로, 아랫입술은 그대로
        assertEquals(1.65f + FaceWarps.CHIN_MAX, moved(FaceSlider.CHIN_HEIGHT, 1f, 0f, 1.65f).second, 3e-3f)
        assertEquals(1.12f, moved(FaceSlider.CHIN_HEIGHT, 1f, 0f, 1.12f).second, 1e-4f)
        // 턱선: 두 턱 모서리(윤곽 점 172·397)가 밖으로
        val face = upright.landmarks()
        for ((index, side) in listOf(FaceMeshIndex.JAW_RIGHT to -1f, FaceMeshIndex.JAW_LEFT to 1f)) {
            val (u, v) = toFace(upright, face.x(index), face.y(index))
            assertEquals("jaw $side", u + side * FaceWarps.JAW_MAX, moved(FaceSlider.JAWLINE, 1f, u, v).first, 3e-3f)
            assertEquals("jaw $side", u - side * FaceWarps.JAW_MAX, moved(FaceSlider.JAWLINE, -1f, u, v).first, 3e-3f)
        }
        assertEquals(0.38f, moved(FaceSlider.JAWLINE, 1f, 0.38f, 1.0f).first, 2e-3f)
    }

    @Test
    fun everySlider_foldsNothing_andLeavesTheFarBackground() {
        val face = upright.landmarks()
        // 하나씩은 0.3 넘게, 모두 함께 ±100%면 0.2 넘게 눌린다(눈꼬리 바깥에서 눈 슬라이더 여섯의 눌림이 곱해짐, 접히지는 않음)
        val changes = FaceSlider.entries.flatMap { listOf(change(it to 1f) to 0.3f, change(it to -1f) to 0.3f) } +
            listOf(all(1f) to 0.2f, all(-1f) to 0.2f, change(*FaceSlider.entries.mapIndexed { i, it -> it to if (i % 2 == 0) 1f else -1f }.toTypedArray()) to 0.2f)
        for ((values, limit) in changes) {
            val warps = FaceWarps.of(face, values)
            assertTrue("$values", warps.isNotEmpty())
            val worst = smallestJacobian(warps, upright, -1.5f..1.5f, -1.6f..2.2f, step = 0.02f)
            assertTrue("$values: Jacobian $worst", worst > limit)
            for ((u, v) in listOf(1.45f to 0.8f, -1.45f to 0.8f, 0f to -1.5f, 0f to 2.1f, 1.25f to -0.6f)) {
                val (x, y) = upright.at(u, v)
                val (sx, sy) = source(warps, x, y)
                assertEquals("$values at ($u, $v)", 0f, hypot(sx - x, sy - y), 1e-4f)
            }
            for (warp in warps) {
                val out = FloatArray(2)
                for (k in 0..100) {
                    val f = k / 100f
                    for ((x, y) in listOf(
                        warp.left + (warp.right - warp.left) * f to warp.top,
                        warp.left + (warp.right - warp.left) * f to warp.bottom,
                        warp.left to warp.top + (warp.bottom - warp.top) * f,
                        warp.right to warp.top + (warp.bottom - warp.top) * f
                    )) {
                        warp.displacement(x, y, out)
                        assertEquals("$values box", 0f, hypot(out[0], out[1]), 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun everySlider_followsATiltedSmallerFace() {
        val tilted = TestFace(eyeX = 420f, eyeY = 380f, eyeDistance = 100f, angle = (-23 * PI / 180).toFloat())
        val values = change(*FaceSlider.entries.mapIndexed { i, it -> it to (if (i % 3 == 0) 0.8f else -0.6f) }.toTypedArray())
        val warpsA = FaceWarps.of(upright.landmarks(), values)
        val warpsB = FaceWarps.of(tilted.landmarks(), values)

        for (u in -12..12) {
            for (v in -14..22) {
                val (ax, ay) = upright.at(u * 0.1f, v * 0.1f)
                val (bx, by) = tilted.at(u * 0.1f, v * 0.1f)
                val a = source(warpsA, ax, ay).let { toFace(upright, it.first, it.second) }
                val b = source(warpsB, bx, by).let { toFace(tilted, it.first, it.second) }
                assertEquals(a.first, b.first, 2e-3f)
                assertEquals(a.second, b.second, 2e-3f)
            }
        }
    }

    @Test
    fun aTurnedFace_movesItsNarrowSideLess_forEverySidewaysMove() {
        val turned = TestFace(eyeX = 600f, eyeY = 450f, eyeDistance = 200f, turn = 0.4f)
        val face = turned.landmarks()
        // 턱선: 좁게 보이는 쪽(− 쪽, 0.6배) 턱 모서리는 그만큼 덜 옮긴다
        val near = toFace(turned, face.x(FaceMeshIndex.JAW_LEFT), face.y(FaceMeshIndex.JAW_LEFT))
        val far = toFace(turned, face.x(FaceMeshIndex.JAW_RIGHT), face.y(FaceMeshIndex.JAW_RIGHT))
        val nearMove = moved(FaceSlider.JAWLINE, 1f, near.first, near.second, turned).first - near.first
        val farMove = far.first - moved(FaceSlider.JAWLINE, 1f, far.first, far.second, turned).first
        assertEquals(FaceWarps.JAW_MAX, nearMove, 3e-3f)
        assertEquals(0.6f, farMove / nearMove, 0.08f)
        // 눈 간격도 같다(눈은 좁히지 않은 시험 얼굴이라 눈 가운데 자리로 잰다)
        val eyeNear = moved(FaceSlider.EYE_DISTANCE, 1f, 0.485f, 0.02f, turned).first - 0.485f
        val eyeFar = -0.485f - moved(FaceSlider.EYE_DISTANCE, 1f, -0.485f, 0.02f, turned).first
        assertEquals(0.6f, eyeFar / eyeNear, 0.08f)
    }

    @Test
    fun slidersBelongToTheirParts_inScreenOrder() {
        assertEquals(
            listOf(FaceSlider.EYE_SIZE, FaceSlider.EYE_HEIGHT, FaceSlider.EYE_WIDTH, FaceSlider.EYE_TILT, FaceSlider.EYE_DISTANCE, FaceSlider.EYE_TAIL),
            FaceSlider.of(FacePart.EYES)
        )
        assertEquals(listOf(FaceSlider.NOSE_HEIGHT, FaceSlider.NOSE_WIDTH, FaceSlider.BRIDGE_WIDTH), FaceSlider.of(FacePart.NOSE))
        assertEquals(5, FaceSlider.of(FacePart.MOUTH).size)
        assertEquals(listOf(FaceSlider.FOREHEAD, FaceSlider.CHIN_HEIGHT, FaceSlider.JAWLINE, FaceSlider.FACE_WIDTH), FaceSlider.of(FacePart.FACE_SHAPE))
        assertEquals(18, FaceSlider.entries.size)
    }

    @Test
    fun values_saveAndRestoreBySliderCode_skippingUnknownSliders() {
        val values = FaceValues.ZERO.with(FaceSlider.EYE_SIZE, 0.4f).with(FaceSlider.FACE_WIDTH, -0.75f)

        assertEquals(listOf(FaceSlider.EYE_SIZE.code to 0.4f, FaceSlider.FACE_WIDTH.code to -0.75f), values.nonZero())
        assertEquals(values, FaceValues.of(values.nonZero()))
        // 더 새 앱의 슬라이더(모르는 번호)는 건너뛴다
        assertEquals(values, FaceValues.of(values.nonZero() + (999 to 0.5f)))
        assertTrue(FaceValues.ZERO.nonZero().isEmpty())
        // 저장 번호는 겹치지 않고, 번호로 슬라이더를 찾는다
        assertEquals(FaceSlider.entries.size, FaceSlider.entries.map { it.code }.toSet().size)
        for (slider in FaceSlider.entries) assertEquals(slider, FaceSlider.ofCode(slider.code))
        assertNull(FaceSlider.ofCode(999))
    }
}

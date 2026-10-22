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

    /** 얼굴 좌표 범위([us]×[vs], 0.01 간격)에서 되돌려 찾는 지도(결과 자리 → 읽는 자리)의 야코비안 가장 작은 값 */
    private fun smallestJacobian(warps: List<MeshWarp>, face: TestFace, us: ClosedFloatingPointRange<Float>, vs: ClosedFloatingPointRange<Float>): Float {
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
                v += 0.01f
            }
            u += 0.01f
        }
        assertTrue(abs(worst) < 10f)
        return worst
    }
}

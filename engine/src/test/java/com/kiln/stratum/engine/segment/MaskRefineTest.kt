package com.kiln.stratum.engine.segment

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskRefineTest {

    private val width = 200
    private val height = 160

    /** 밝은 회색 바탕에 [color] 원(가운데 (100, 80), 반지름 [radius])을 그린 길잡이 그림(R·G·B·A, 줄마다 width × 4) */
    private fun guideWithDisc(color: IntArray, radius: Float = 40f, background: IntArray = intArrayOf(200, 200, 200)): ByteArray {
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inside = hypot(x + 0.5f - 100f, y + 0.5f - 80f) <= radius
                val c = if (inside) color else background
                val i = (y * width + x) * 4
                for (k in 0..2) bytes[i + k] = c[k].toByte()
                bytes[i + 3] = 0xFF.toByte()
            }
        }
        return bytes
    }

    /** 가운데 ([cx], [cy]), 반지름 [radius]인 원 마스크(0 또는 255) */
    private fun disc(cx: Float, cy: Float, radius: Float) =
        ByteArray(width * height) { i -> if (hypot(i % width + 0.5f - cx, i / width + 0.5f - cy) <= radius) -1 else 0 }

    private fun inside(alpha: ByteArray) = BooleanArray(alpha.size) { (alpha[it].toInt() and 0xFF) >= 128 }

    /** 두 모양의 겹침(IoU) */
    private fun iou(a: BooleanArray, b: BooleanArray): Double {
        var both = 0
        var either = 0
        for (i in a.indices) {
            if (a[i] && b[i]) both++
            if (a[i] || b[i]) either++
        }
        return both.toDouble() / either
    }

    private val truth by lazy { inside(disc(100f, 80f, 40f)) }

    @Test
    fun aRoughMask_snapsToTheEdgeOfThePicture() {
        // 모델 결과처럼 2~4px 어긋나고 조금 작은 원(긴 변 1024 마스크에서 모델 한 칸이 4px)
        val rough = disc(102f, 79f, 38f)
        val guide = guideWithDisc(intArrayOf(40, 60, 90))

        val refined = MaskRefine.refine(rough, guide, width * 4, width, height, 100, 80)

        val before = iou(inside(rough), truth)
        val after = iou(inside(refined), truth)
        assertTrue("IoU $before -> $after", after > before && after > 0.97)
    }

    @Test
    fun aMaskThatSpillsOver_isPulledBackToTheEdge() {
        val rough = disc(102f, 82f, 42f)

        val refined = MaskRefine.refine(rough, guideWithDisc(intArrayOf(40, 60, 90)), width * 4, width, height, 100, 80)

        assertTrue("IoU ${iou(inside(refined), truth)}", iou(inside(refined), truth) > 0.97)
    }

    @Test
    fun aColorEdgeOfTheSameBrightness_isFollowedToo() {
        // 바탕과 밝기가 비슷한 빨간 원: 색으로만 갈린다
        val rough = disc(102f, 79f, 38f)
        val guide = guideWithDisc(intArrayOf(230, 60, 60), background = intArrayOf(100, 100, 100))

        val refined = MaskRefine.refine(rough, guide, width * 4, width, height, 100, 80)

        assertTrue("IoU ${iou(inside(refined), truth)}", iou(inside(refined), truth) > 0.97)
    }

    @Test
    fun farFromTheEdge_theAlphaIsFullOrEmpty() {
        val refined = MaskRefine.refine(disc(102f, 79f, 38f), guideWithDisc(intArrayOf(40, 60, 90)), width * 4, width, height, 100, 80)

        // 반지름(8px)과 넓힌 만큼보다 먼 곳에는 옅은 번짐이 없다
        for (i in refined.indices) {
            val distance = kotlin.math.abs(hypot(i % width + 0.5f - 100f, i / width + 0.5f - 80f) - 40f)
            val alpha = refined[i].toInt() and 0xFF
            if (distance > 12f) assertTrue("pixel $i alpha $alpha", alpha == 0 || alpha == 255)
        }
    }

    @Test
    fun onAFlatPicture_theShapeStaysWhereItWas() {
        val rough = disc(100f, 80f, 40f)
        val flat = guideWithDisc(intArrayOf(120, 120, 120), background = intArrayOf(120, 120, 120))

        val refined = MaskRefine.refine(rough, flat, width * 4, width, height, 100, 80)

        // 길잡이에 가장자리가 없으면 상자 평균처럼 둥글게 조금 깎일 뿐이다
        assertTrue("IoU ${iou(inside(refined), truth)}", iou(inside(refined), truth) > 0.95)
    }

    @Test
    fun aThinObjectThatWouldVanish_keepsTheOriginalMask() {
        // 길잡이에 없는 가는 선(2px): 다듬으면 대부분 사라지므로 처음 마스크를 쓴다
        val line = ByteArray(width * height) { i -> if (i / width in 79..80 && i % width in 20..180) -1 else 0 }

        val refined = MaskRefine.refine(line, guideWithDisc(intArrayOf(200, 200, 200)), width * 4, width, height, 100, 80)

        assertSame(line, refined)
    }

    @Test
    fun aGuideWithPaddedRows_isReadByRowBytes() {
        val rowBytes = width * 4 + 64
        val tight = guideWithDisc(intArrayOf(40, 60, 90))
        val padded = ByteArray(rowBytes * height)
        for (y in 0 until height) tight.copyInto(padded, y * rowBytes, y * width * 4, (y + 1) * width * 4)
        val rough = disc(102f, 79f, 38f)

        val a = MaskRefine.refine(rough, tight, width * 4, width, height, 100, 80)
        val b = MaskRefine.refine(rough, padded, rowBytes, width, height, 100, 80)

        assertEquals(a.toList(), b.toList())
    }
}

package com.kiln.stratum.engine.segment

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AI 선택 경계 다듬기(결정 기록 'AI 제거·AI 선택'의 '10단계 경계 다듬기'). 분할 모델의 모양(256²)을 키운 마스크는 경계가 둥글고
 * 사진의 가장자리에서 몇 픽셀 벗어난다. 같은 크기 그림(보이는 레이어를 합친 그림)을 길잡이로 색 가이드 필터(He et al.)를 걸어
 * 경계를 사진의 가장자리에 붙인다.
 *
 * - 빠른 판(He & Sun 2015): 계수 a·b는 [SUBSAMPLE]배 줄인 크기에서 구해 쌍선형으로 키우고, 원래 크기 길잡이에 건다(경계는 원래 크기 그대로).
 * - 결과에서 [EDGE_LOW]~[EDGE_HIGH]를 0~1로 펴서(smoothstep) 경계에서 먼 옅은 번짐을 없애고 경계는 1~2픽셀로 부드럽게 둔다.
 * - 다듬은 뒤 반(128) 이상인 모양을 [SegmentMask.clean]으로 다시 다듬는다(작은 조각·구멍).
 *   다듬은 모양이 처음 넓이의 [MIN_KEEP]보다 작아지면(가는 물체를 지움) 처음 마스크를 그대로 쓴다.
 *
 * Kotlin 반복은 긴 변 1024px 이하 마스크에서만 한다. 상수는 맥에서 실제 사진 7장으로 골랐다.
 */
object MaskRefine {

    /** 가이드 필터 반지름(마스크 px). 분할 모델 한 칸(긴 변 1024 마스크에서 4px)이 두 칸쯤 어긋나도 붙는다. */
    const val RADIUS = 8

    /** 길잡이(0..1 눈금) 분산에 더하는 값. 작을수록 사진의 가장자리를 세게 따른다. */
    const val EPSILON = 1e-3f

    /** 계수 a·b를 구하는 크기를 줄이는 배수 */
    const val SUBSAMPLE = 2

    /** 이 아래는 고르지 않음(0) */
    const val EDGE_LOW = 0.25f

    /** 이 위는 다 고름(1) */
    const val EDGE_HIGH = 0.75f

    /** 다듬은 넓이가 처음 넓이의 이만큼보다 작으면 처음 마스크를 쓴다 */
    const val MIN_KEEP = 0.5

    /**
     * [mask](0 또는 255, [width]×[height])를 같은 크기 길잡이 그림 [guide](R·G·B·A 바이트, 줄마다 [rowBytes], 불투명)의
     * 가장자리에 맞춘 알파(0..255). 탭한 픽셀 ([keepX], [keepY])가 든 조각은 작아도 남긴다.
     */
    fun refine(mask: ByteArray, guide: ByteArray, rowBytes: Int, width: Int, height: Int, keepX: Int, keepY: Int): ByteArray {
        require(width > 0 && height > 0 && mask.size == width * height) { "Mask must be ${width}x$height" }
        require(rowBytes >= width * 4 && guide.size >= rowBytes * (height - 1) + width * 4) { "Guide must cover ${width}x$height" }
        val s = SUBSAMPLE
        val sw = (width + s - 1) / s
        val sh = (height + s - 1) / s
        val plane = sw * sh

        // 줄인 길잡이(R·G·B, 0..1)와 마스크(0..1): s×s 칸 평균
        val red = FloatArray(plane)
        val green = FloatArray(plane)
        val blue = FloatArray(plane)
        val p = FloatArray(plane)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var r = 0f
                var g = 0f
                var b = 0f
                var m = 0f
                var count = 0
                for (dy in 0 until s) {
                    val yy = y * s + dy
                    if (yy >= height) continue
                    for (dx in 0 until s) {
                        val xx = x * s + dx
                        if (xx >= width) continue
                        val i = yy * rowBytes + xx * 4
                        r += (guide[i].toInt() and 0xFF)
                        g += (guide[i + 1].toInt() and 0xFF)
                        b += (guide[i + 2].toInt() and 0xFF)
                        m += (mask[yy * width + xx].toInt() and 0xFF)
                        count++
                    }
                }
                val k = y * sw + x
                red[k] = r / (count * 255f)
                green[k] = g / (count * 255f)
                blue[k] = b / (count * 255f)
                p[k] = m / (count * 255f)
            }
        }

        // 상자 평균(반지름 radius, 가장자리에서는 있는 칸만)
        val radius = max(1, RADIUS / s)
        val box = BoxMean(sw, sh, radius)
        fun product(a: FloatArray, b: FloatArray) = FloatArray(plane) { a[it] * b[it] }
        val meanR = box.of(red)
        val meanG = box.of(green)
        val meanB = box.of(blue)
        val meanP = box.of(p)
        val corrRP = box.of(product(red, p))
        val corrGP = box.of(product(green, p))
        val corrBP = box.of(product(blue, p))
        val corrRR = box.of(product(red, red))
        val corrRG = box.of(product(red, green))
        val corrRB = box.of(product(red, blue))
        val corrGG = box.of(product(green, green))
        val corrGB = box.of(product(green, blue))
        val corrBB = box.of(product(blue, blue))

        // 칸마다 a = (Σ + εI)⁻¹ cov(I, p), b = mean(p) - a·mean(I). 다 쓴 배열 자리에 a·b를 적는다.
        val aR = corrRR
        val aG = corrGG
        val aB = corrBB
        val bias = corrRP
        for (k in 0 until plane) {
            val mr = meanR[k]
            val mg = meanG[k]
            val mb = meanB[k]
            val mp = meanP[k]
            val cr = corrRP[k] - mr * mp
            val cg = corrGP[k] - mg * mp
            val cb = corrBP[k] - mb * mp
            val rr = corrRR[k] - mr * mr + EPSILON
            val rg = corrRG[k] - mr * mg
            val rb = corrRB[k] - mr * mb
            val gg = corrGG[k] - mg * mg + EPSILON
            val gb = corrGB[k] - mg * mb
            val bb = corrBB[k] - mb * mb + EPSILON
            // 대칭 3×3의 역행렬(여인수)
            val i00 = gg * bb - gb * gb
            val i01 = rb * gb - rg * bb
            val i02 = rg * gb - rb * gg
            val i11 = rr * bb - rb * rb
            val i12 = rb * rg - rr * gb
            val i22 = rr * gg - rg * rg
            val det = rr * i00 + rg * i01 + rb * i02
            val a0 = (i00 * cr + i01 * cg + i02 * cb) / det
            val a1 = (i01 * cr + i11 * cg + i12 * cb) / det
            val a2 = (i02 * cr + i12 * cg + i22 * cb) / det
            aR[k] = a0
            aG[k] = a1
            aB[k] = a2
            bias[k] = mp - a0 * mr - a1 * mg - a2 * mb
        }
        val meanAR = box.of(aR)
        val meanAG = box.of(aG)
        val meanAB = box.of(aB)
        val meanBias = box.of(bias)

        // 원래 크기: 쌍선형으로 키운 계수를 길잡이에 건다
        val refined = ByteArray(width * height)
        val span = EDGE_HIGH - EDGE_LOW
        for (y in 0 until height) {
            val v = (y + 0.5f) / s - 0.5f
            val y0 = floor(v).toInt()
            val ty = v - y0
            val top = y0.coerceIn(0, sh - 1) * sw
            val bottom = (y0 + 1).coerceIn(0, sh - 1) * sw
            for (x in 0 until width) {
                val u = (x + 0.5f) / s - 0.5f
                val x0 = floor(u).toInt()
                val tx = u - x0
                val left = x0.coerceIn(0, sw - 1)
                val right = (x0 + 1).coerceIn(0, sw - 1)
                fun sample(f: FloatArray): Float {
                    val upper = f[top + left] * (1f - tx) + f[top + right] * tx
                    val lower = f[bottom + left] * (1f - tx) + f[bottom + right] * tx
                    return upper * (1f - ty) + lower * ty
                }
                val i = y * rowBytes + x * 4
                val q = sample(meanAR) * (guide[i].toInt() and 0xFF) / 255f +
                    sample(meanAG) * (guide[i + 1].toInt() and 0xFF) / 255f +
                    sample(meanAB) * (guide[i + 2].toInt() and 0xFF) / 255f +
                    sample(meanBias)
                val t = ((q - EDGE_LOW) / span).coerceIn(0f, 1f)
                refined[y * width + x] = (t * t * (3f - 2f * t) * 255f).roundToInt().toByte()
            }
        }

        // 반 이상인 모양을 다시 다듬고(작은 조각 지우기·구멍 메우기), 바뀐 픽셀만 0·255로 고친다
        val inside = BooleanArray(width * height) { (refined[it].toInt() and 0xFF) >= 128 }
        val before = inside.count { it }
        val original = mask.count { it.toInt() != 0 }
        if (before < original * MIN_KEEP) return mask
        val minArea = max(1, (width.toLong() * height * SegmentMask.SPECK_FRACTION).roundToInt())
        val cleaned = SegmentMask.clean(inside, width, height, keepX.coerceIn(0, width - 1), keepY.coerceIn(0, height - 1), minArea)
        for (i in cleaned.indices) {
            if (cleaned[i] != inside[i]) refined[i] = if (cleaned[i]) 0xFF.toByte() else 0
        }
        return refined
    }

    /** [width]×[height] 칸의 상자 평균(가로·세로로 나눠 미끄러지는 합). 가장자리에서는 있는 칸만 나눈다. */
    private class BoxMean(private val width: Int, private val height: Int, private val radius: Int) {
        private val row = FloatArray(width * height)

        fun of(values: FloatArray): FloatArray {
            // 가로
            for (y in 0 until height) {
                val base = y * width
                var sum = 0.0
                for (x in 0 until min(radius, width)) sum += values[base + x]
                for (x in 0 until width) {
                    val add = x + radius
                    val drop = x - radius - 1
                    if (add < width) sum += values[base + add]
                    if (drop >= 0) sum -= values[base + drop]
                    val count = min(width - 1, x + radius) - max(0, x - radius) + 1
                    row[base + x] = (sum / count).toFloat()
                }
            }
            // 세로
            val out = FloatArray(width * height)
            for (x in 0 until width) {
                var sum = 0.0
                for (y in 0 until min(radius, height)) sum += row[y * width + x]
                for (y in 0 until height) {
                    val add = y + radius
                    val drop = y - radius - 1
                    if (add < height) sum += row[add * width + x]
                    if (drop >= 0) sum -= row[drop * width + x]
                    val count = min(height - 1, y + radius) - max(0, y - radius) + 1
                    out[y * width + x] = (sum / count).toFloat()
                }
            }
            return out
        }
    }
}

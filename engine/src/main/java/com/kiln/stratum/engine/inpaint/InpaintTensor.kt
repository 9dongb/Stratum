package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.retouch.HoleFill

/**
 * 모델 입력·출력과 채울 그림 계산(결정 기록 'AI 제거·AI 선택'). 모두 모델 크기(한 변 [size], 512) 배열에서만 돈다.
 * 그림 바이트는 알파를 곱한 RGBA(픽셀마다 R·G·B·A, 줄마다 이어 붙임)로, 엔진 타일을 읽은 형식과 같다.
 * 구멍까지의 거리는 [InpaintMask.distanceToHole]의 것이고, 넓힌 폭(반지름) 안이 모델이 채울 곳이다.
 */
object InpaintTensor {

    /** 알파를 푼 색(0..255, 픽셀마다 R·G·B)과 알파(0..255) */
    class Straight(val rgb: FloatArray, val alpha: FloatArray)

    /** [rgba]의 알파를 푼다. 알파가 0인 픽셀의 색은 0이다. */
    fun unpremultiply(rgba: ByteArray): Straight {
        require(rgba.size % 4 == 0) { "RGBA bytes must come in fours: ${rgba.size}" }
        val count = rgba.size / 4
        val rgb = FloatArray(count * 3)
        val alpha = FloatArray(count)
        for (p in 0 until count) {
            val a = rgba[p * 4 + 3].toInt() and 0xFF
            alpha[p] = a.toFloat()
            if (a == 0) continue
            val scale = 255f / a
            for (c in 0 until 3) rgb[p * 3 + c] = minOf(255f, (rgba[p * 4 + c].toInt() and 0xFF) * scale)
        }
        return Straight(rgb, alpha)
    }

    /** 모델이 색을 볼 수 있는 곳: 넓힌 구멍([radius] 안) 밖이고, 알파가 [InpaintPlan.ALPHA_KNOWN] 이상인 픽셀 */
    fun known(straight: Straight, distance: FloatArray, radius: Float): BooleanArray =
        BooleanArray(distance.size) { distance[it] > radius && straight.alpha[it] >= InpaintPlan.ALPHA_KNOWN }

    /**
     * 모델 입력(1×4×[size]², NCHW). 0채널은 마스크 - 0.5, 1~3채널은 (색 / 127.5 - 1) × 마스크이고,
     * 마스크는 [known]이면 1(남길 곳), 아니면 0(채울 곳)이다.
     */
    fun input(straight: Straight, known: BooleanArray, size: Int): FloatArray {
        val plane = size * size
        require(known.size == plane && straight.alpha.size == plane) { "Input must be ${size}x$size" }
        val input = FloatArray(InpaintModel.INPUT_CHANNELS * plane)
        for (p in 0 until plane) {
            val keep = if (known[p]) 1f else 0f
            input[p] = keep - 0.5f
            if (keep == 0f) continue
            for (c in 0 until 3) input[(c + 1) * plane + p] = straight.rgb[p * 3 + c] / 127.5f - 1f
        }
        return input
    }

    /** 모델 출력(-1..1, NCHW 1×3×[size]²)을 0..255 색(픽셀마다 R·G·B)으로 바꾼다. 범위 밖은 자른다. */
    fun output(out: FloatArray, size: Int): FloatArray {
        val plane = size * size
        require(out.size == InpaintModel.OUTPUT_CHANNELS * plane) { "Output must be 3x${size}x$size" }
        return FloatArray(plane * 3) { i ->
            val p = i / 3
            val c = i % 3
            ((out[c * plane + p] + 1f) * 127.5f).coerceIn(0f, 255f)
        }
    }

    /**
     * 쓰기 몫(0..1): 구멍에서의 거리가 넓힌 폭 [radius]에서 [feather]를 뺀 것 안이면 1이고, [radius] + 0.5에서 0이 된다.
     * 섞는 곳은 넓힌 띠 안이라, 그 밖의 원래 픽셀은 그대로 남는다.
     */
    fun writeWeights(distance: FloatArray, radius: Float, feather: Float = InpaintPlan.FEATHER): FloatArray {
        require(feather > 0f) { "Feather must be positive: $feather" }
        return FloatArray(distance.size) { ((radius + 0.5f - distance[it]) / feather).coerceIn(0f, 1f) }
    }

    /**
     * 채울 그림(알파를 곱한 RGBA 0..1, 픽셀마다 4개). 넓힌 구멍(거리 ≤ [radius]) 밖은 [rgba] 그대로다.
     *
     * 구멍 안은 모델이 채운 색([filled], 0..255 R·G·B)을 둘레의 밝기·색에 맞춘 것이다(복구 브러시의 막과 같다).
     * - 구멍 안의 채운 색을 구멍 밖으로 매끈하게 늘이고, 구멍 바로 바깥 띠([ring] 칸 안의 아는 곳)에서 '원래 - 늘인 색' 차이를 잰 뒤,
     *   [HoleFill]로 구멍 안에 매끈하게 이어 더한다. 그래서 경계를 사이에 둔 밝기·색 차이만 맞춘다.
     * - 모델이 아는 곳에 다시 그린 색([filled]의 구멍 밖)은 쓰지 않는다. MI-GAN은 아는 곳을 원래와 다르게(보통 더 밝게) 다시 그려,
     *   그 차이를 더하면 채운 곳이 어두워졌다(폴드8 시험, 2026-10-20).
     * - 다른 곳과 크게 다른 차이(어긋난 경계)는 [HoleFill.influence]로 덜 번지게 하고, [InpaintPlan.MATCH_OUTLIER]보다 다르면
     *   (경계에 걸친 다른 물체) 아예 쓰지 않는다. 띠가 없으면 모델 색 그대로다.
     * - 알파는 구멍 밖이 모두 불투명하면 1이고, 아니면 구멍 밖 알파를 같은 방법으로 잇는다.
     */
    fun fillImage(
        rgba: ByteArray,
        straight: Straight,
        distance: FloatArray,
        radius: Float,
        filled: FloatArray,
        size: Int,
        ring: Float = InpaintPlan.MATCH_RING
    ): FloatArray {
        val plane = size * size
        require(rgba.size == plane * 4 && distance.size == plane && filled.size == plane * 3) { "Buffers must be ${size}x$size" }
        require(size <= HoleFill.MAX_EDGE) { "Matching runs on small images only: $size" }
        val inHole = BooleanArray(plane) { distance[it] <= radius }

        // 밝기·색: 구멍 안 채운 색을 밖으로 늘이고, 띠에서 잰 '원래 - 늘인 색' 차이(0..1 눈금)를 구멍 안으로 잇는다
        val inside = FloatArray(plane * 3)
        val insideWeights = FloatArray(plane)
        for (p in 0 until plane) {
            if (!inHole[p]) continue
            insideWeights[p] = 1f
            for (c in 0 until 3) inside[p * 3 + c] = filled[p * 3 + c] / 255f
        }
        val extended = HoleFill.fill(inside, insideWeights, size, size, 3)
        val differences = FloatArray(plane * 3)
        val ringWeights = FloatArray(plane)
        for (p in 0 until plane) {
            if (inHole[p] || distance[p] > radius + ring || straight.alpha[p] < InpaintPlan.ALPHA_KNOWN) continue
            ringWeights[p] = 1f
            for (c in 0 until 3) differences[p * 3 + c] = straight.rgb[p * 3 + c] / 255f - extended[p * 3 + c]
        }
        val influence = HoleFill.influence(differences, ringWeights, 3, cutoff = InpaintPlan.MATCH_OUTLIER)
        val correction = HoleFill.fill(differences, ringWeights, size, size, 3, influence)

        // 알파: 구멍 밖은 다 아는 값이다
        var opaque = true
        for (p in 0 until plane) if (!inHole[p] && straight.alpha[p] < 255f) opaque = false
        val alpha = if (opaque) {
            null
        } else {
            val values = FloatArray(plane) { straight.alpha[it] / 255f }
            val weights = FloatArray(plane) { if (inHole[it]) 0f else 1f }
            HoleFill.fill(values, weights, size, size, 1)
        }

        val result = FloatArray(plane * 4)
        for (p in 0 until plane) {
            if (!inHole[p]) {
                for (c in 0 until 4) result[p * 4 + c] = (rgba[p * 4 + c].toInt() and 0xFF) / 255f
                continue
            }
            val a = alpha?.get(p)?.coerceIn(0f, 1f) ?: 1f
            for (c in 0 until 3) {
                val color = (filled[p * 3 + c] / 255f + correction[p * 3 + c]).coerceIn(0f, 1f)
                result[p * 4 + c] = color * a
            }
            result[p * 4 + 3] = a
        }
        return result
    }
}

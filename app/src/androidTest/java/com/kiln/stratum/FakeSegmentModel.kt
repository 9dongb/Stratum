package com.kiln.stratum

import com.kiln.stratum.engine.segment.SegmentFeatures
import com.kiln.stratum.engine.segment.SegmentModel

/**
 * 에뮬레이터 테스트용 분할 모델. 읽은 그림을 그대로 특징으로 두고, 탭한 칸에서 빨강 값이 비슷한 칸으로 이어진 곳을 물체로 본다.
 * 후보는 SAM 2.1 Tiny가 단순한 그림에서 내는 것처럼 0번이 그림 전체, 1·2번이 물체다([found]가 false면 모두 물체 아님).
 * 읽기·찾기·미리 불러오기·내려놓기 횟수를 세고, [failure]가 있으면 그것을 던진다.
 * [pendingLoadBytes]는 처음 읽기 전까지만 알린다(불러온 것처럼).
 */
class FakeSegmentModel(
    private val loadBytes: Long = 0L,
    private val failure: Throwable? = null,
    @Volatile var found: Boolean = true
) : SegmentModel {

    @Volatile
    var encodes = 0
        private set

    @Volatile
    var decodes = 0
        private set

    @Volatile
    var prepares = 0
        private set

    @Volatile
    var releases = 0
        private set

    override val pendingLoadBytes: Long
        get() = if (encodes == 0 && decodes == 0) loadBytes else 0L

    private class Features(val image: FloatArray) : SegmentFeatures

    override fun encode(image: FloatArray): SegmentFeatures {
        failure?.let { throw it }
        encodes++
        return Features(image)
    }

    override fun decode(features: SegmentFeatures, x: Float, y: Float): FloatArray {
        failure?.let { throw it }
        decodes++
        val image = (features as Features).image
        val input = SegmentModel.INPUT_SIZE
        val size = SegmentModel.MASK_SIZE
        val plane = size * size
        fun red(px: Int, py: Int) = image[py.coerceIn(0, input - 1) * input + px.coerceIn(0, input - 1)]
        val tapped = red(x.toInt(), y.toInt())
        val cell = input / size
        val similar = BooleanArray(plane) { q -> kotlin.math.abs(red((q % size) * cell + cell / 2, (q / size) * cell + cell / 2) - tapped) < SIMILAR }
        // 물체: 탭한 칸에서 비슷한 색으로 이어진 칸들(위아래·좌우)
        val inside = BooleanArray(plane)
        val start = (y.toInt() / cell).coerceIn(0, size - 1) * size + (x.toInt() / cell).coerceIn(0, size - 1)
        if (found && similar[start]) {
            val queue = IntArray(plane)
            var head = 0
            var end = 0
            queue[end++] = start
            inside[start] = true
            while (head < end) {
                val q = queue[head++]
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = q % size + dx
                    val ny = q / size + dy
                    if (nx !in 0 until size || ny !in 0 until size) continue
                    val n = ny * size + nx
                    if (!inside[n] && similar[n]) {
                        inside[n] = true
                        queue[end++] = n
                    }
                }
            }
        }
        val logits = FloatArray(SegmentModel.MASK_COUNT * plane)
        for (q in 0 until plane) {
            logits[q] = if (found) 4f else -8f
            logits[plane + q] = if (inside[q]) 8f else -8f
            logits[2 * plane + q] = if (inside[q]) 8f else -8f
        }
        return logits
    }

    override fun prepare() {
        prepares++
    }

    override fun release() {
        releases++
    }

    private companion object {
        /** 정규화한 빨강 값(표준편차 0.229 눈금)이 이만큼 안이면 비슷한 색이다(0..255로 약 17) */
        const val SIMILAR = 0.3f
    }
}

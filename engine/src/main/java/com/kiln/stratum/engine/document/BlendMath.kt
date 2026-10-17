package com.kiln.stratum.engine.document

/**
 * 혼합 모드 계산의 기준(순수 계산). GPU(안드로이드 그리기의 혼합 모드)가 픽셀마다 똑같이 계산한다.
 * 큰 사진은 GPU가 그리고, 이 코드는 단위 테스트와 GPU 결과를 맞춰 보는 데 쓴다(CLAUDE.md 2장).
 *
 * 색은 알파를 미리 곱한 sRGB 0..1이다(타일 비트맵과 같다). 공식은 W3C 합성 규격의 분리형 혼합이고,
 * 포토샵·안드로이드(Skia)와 같다. 감마를 풀지 않은 sRGB 값에서 섞는다(포토샵 기본과 같다).
 *
 * - 위 레이어가 투명한 곳은 아래가 그대로, 아래가 투명한 곳은 위가 그대로다(어느 모드든).
 * - 불투명도는 위 색(알파 포함)에 곱한 뒤 섞는다. 포토샵의 '섞은 결과와 아래를 불투명도만큼 잇기'와 같은 값이다.
 */
object BlendMath {

    /** 아래 색 [backdrop]에 위 색 [source]를 [mode]로 얹은 색. 둘 다 알파를 미리 곱한 RGBA(0..1)다. */
    fun blend(mode: BlendMode, source: FloatArray, backdrop: FloatArray): FloatArray {
        val sourceAlpha = source[3]
        val backdropAlpha = backdrop[3]
        val result = FloatArray(4)
        for (channel in 0..2) {
            val s = source[channel]
            val d = backdrop[channel]
            result[channel] = when (mode) {
                BlendMode.NORMAL -> s + d * (1f - sourceAlpha)
                BlendMode.MULTIPLY -> s * (1f - backdropAlpha) + d * (1f - sourceAlpha) + s * d
                BlendMode.SCREEN -> s + d - s * d
                // 아래가 어두우면(절반 이하) 곱하기, 밝으면 스크린 쪽으로 섞는다
                BlendMode.OVERLAY -> s * (1f - backdropAlpha) + d * (1f - sourceAlpha) +
                    if (2f * d <= backdropAlpha) 2f * s * d else sourceAlpha * backdropAlpha - 2f * (backdropAlpha - d) * (sourceAlpha - s)
            }.coerceIn(0f, 1f)
        }
        result[3] = sourceAlpha + backdropAlpha * (1f - sourceAlpha)
        return result
    }

    /** 알파를 미리 곱한 [color]에 불투명도 [opacity]를 건 색 */
    fun withOpacity(color: FloatArray, opacity: Float): FloatArray = FloatArray(4) { color[it] * opacity }
}

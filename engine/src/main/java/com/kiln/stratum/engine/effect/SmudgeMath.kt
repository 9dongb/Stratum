package com.kiln.stratum.engine.effect

import kotlin.math.pow

/**
 * 스머지(손가락)의 계산 기준. 획이 지나며 집은 색은 자국마다 '남김' 만큼 남고, 나머지는 그 자리 색과 섞인다
 * (집은 색 = 집은 색 × 남김 + 그 자리 색 × (1 - 남김)).
 *
 * 강도는 집은 색이 반으로 줄 때까지 끌고 가는 거리([halfDistance])로 정한다: 지름 × 0.5 × 강도 / (1 - 강도).
 * 강도 50%면 지름의 반을 지나 반이 남고, 80%면 지름의 두 배, 100%면 끝까지 처음 색을 끈다(포토샵의 손가락 도구 100%와 같다).
 * 자국 간격이 달라도(작은 붓은 1px 간격) 같은 거리에서 같은 만큼 남도록 자국 하나의 남김은 0.5^(간격 / 반 거리)다.
 * 강도에 따른 거리는 폴드8에서 써 보고 고칠 수 있게 여기 한곳에 둔다.
 */
object SmudgeMath {

    /** 집은 색이 반으로 줄 때까지 끌고 가는 거리(문서 픽셀). 강도 100%면 끝없이 끈다(무한대). */
    fun halfDistance(strength: Float, brushSize: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        if (s >= 1f) return Float.POSITIVE_INFINITY
        return brushSize * 0.5f * s / (1f - s)
    }

    /** 자국 하나마다 집은 색을 남기는 몫(0..1). 자국 간격 [spacing], 붓 지름 [brushSize](문서 픽셀). */
    fun retention(strength: Float, spacing: Float, brushSize: Float): Float {
        val half = halfDistance(strength, brushSize)
        if (half.isInfinite()) return 1f
        if (half <= 0f) return 0f
        return 0.5f.pow(spacing / half)
    }
}

package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.brush.StrokePoint

/** AI 제거로 채울 곳(결정 기록 'AI 제거·AI 선택') */
sealed interface InpaintArea {

    /**
     * 칠한 브러시 자국. [radius]는 자국 반지름(문서 px)이다.
     * 조금이라도 칠한 곳과, 고리처럼 둘러 칠한 안쪽을 채운다.
     */
    data class Painted(val dabs: List<StrokePoint>, val radius: Float) : InpaintArea {
        init {
            require(dabs.isNotEmpty()) { "A painted area needs at least one dab" }
            require(radius > 0f) { "Dab radius must be positive: $radius" }
        }
    }

    /** 지금의 선택 영역(고른 정도가 0보다 큰 곳). 부드러운 경계는 고른 정도만큼 섞는다. */
    data object Selected : InpaintArea
}

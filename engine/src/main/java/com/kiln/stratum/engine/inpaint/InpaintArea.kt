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

    /**
     * 물체 모양(AI 제거에서 톡 눌러 고른 것, AI 선택과 같은 분할 모델). 알파 [alphas](0..255, 줄마다 이어 붙인 [width]×[height])가
     * 문서 왼쪽 위부터 덮고, 한 칸은 문서 [cellX]×[cellY] px다(끝 칸은 문서 밖으로 조금 넘을 수 있다).
     * 조금이라도 덮인 곳을 칠한 자국처럼 조금 넓혀 채운다(물체 가장자리의 번진 색까지). 안쪽 구멍은 메우지 않는다.
     * 비교는 같은 객체인지만 본다.
     */
    class Masked(val alphas: ByteArray, val width: Int, val height: Int, val cellX: Float, val cellY: Float) : InpaintArea {
        init {
            require(width > 0 && height > 0 && alphas.size == width * height) { "Mask must be ${width}x$height: ${alphas.size} bytes" }
            require(cellX > 0f && cellY > 0f) { "Cells must be positive: ${cellX}x$cellY" }
        }
    }
}

package com.kiln.stratum.engine.skin

/**
 * 피부 색 보정(결정 기록 '피부 보정'): [피부 보정] 그룹의 색 보정 조정 레이어가 지닌 값([com.kiln.stratum.engine.document.AdjustmentContent.skin]).
 * 그 아래에 합친 그림의 피부(레이어 마스크) 색만 바꾸고 밝기는 그대로 둔다(3단계).
 *
 * @property local 부분 붉은기 줄이기(0..1): 그 얼굴의 보통 피부보다 붉은 곳만 당긴다.
 * @property global 전체 붉은기 줄이기(0..1): 피부 전체를 자연스러운 피부색 쪽으로 옮긴다.
 * @property even 톤 균일화(0..1): 기준 피부색에서 벗어난 얼룩을 줄인다.
 */
data class SkinColor(val local: Float = 0f, val global: Float = 0f, val even: Float = 0f) {

    init {
        require(local in 0f..1f && global in 0f..1f && even in 0f..1f) { "Skin color values must be within 0..1: $local, $global, $even" }
    }

    /** 바꾸는 것이 없는지(슬라이더가 모두 0) */
    val isIdentity: Boolean
        get() = local == 0f && global == 0f && even == 0f
}

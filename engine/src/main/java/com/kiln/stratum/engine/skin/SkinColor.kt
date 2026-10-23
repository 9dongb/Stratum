package com.kiln.stratum.engine.skin

/**
 * 피부 색 보정(결정 기록 '피부 보정'): [피부 보정] 그룹의 색 보정 조정 레이어가 지닌 값([com.kiln.stratum.engine.document.AdjustmentContent.skin]).
 * 그 아래에 합친 그림의 피부(레이어 마스크) 색만 바꾸고 밝기는 그대로 둔다(3단계, 계산은 [SkinColorMath]).
 *
 * @property local 부분 붉은기 줄이기(0..1): 그 얼굴의 보통 피부보다 붉은 곳만 당긴다.
 * @property global 전체 붉은기 줄이기(0..1): 피부 전체를 자연스러운 피부색(또는 스포이드로 고른 색) 쪽으로 옮긴다.
 * @property even 톤 균일화(0..1): 기준 피부색에서 벗어난 얼룩을 줄인다.
 * @property faces 얼굴마다의 자리·피부 통계·기준(그룹을 만들 때 잰다). 픽셀마다 가까운 얼굴의 것을 쓴다. 없으면 바꾸는 것이 없다.
 */
data class SkinColor(
    val local: Float = 0f,
    val global: Float = 0f,
    val even: Float = 0f,
    val faces: List<SkinFaceTone> = emptyList()
) {

    init {
        require(local in 0f..1f && global in 0f..1f && even in 0f..1f) { "Skin color values must be within 0..1: $local, $global, $even" }
    }

    /** 바꾸는 것이 없는지(슬라이더가 모두 0이거나 잰 얼굴이 없다) */
    val isIdentity: Boolean
        get() = (local == 0f && global == 0f && even == 0f) || faces.isEmpty()

    /** 스포이드로 고른 기준이 있는 얼굴이 있는지 */
    val hasReference: Boolean
        get() = faces.any { it.reference != null }

    /** 슬라이더 값만 바꾼 것(얼굴은 그대로) */
    fun withValues(local: Float = this.local, global: Float = this.global, even: Float = this.even): SkinColor =
        copy(local = local.coerceIn(0f, 1f) + 0f, global = global.coerceIn(0f, 1f) + 0f, even = even.coerceIn(0f, 1f) + 0f)

    /** [index] 얼굴의 기준을 [reference]로(null이면 자동) 바꾼 것 */
    fun withReference(index: Int, reference: SkinReference?): SkinColor =
        copy(faces = faces.mapIndexed { i, face -> if (i == index) face.copy(reference = reference) else face })

    /** 모든 얼굴의 기준을 자동(자기 피부의 가운데 색)으로 되돌린 것 */
    fun withoutReferences(): SkinColor = copy(faces = faces.map { it.copy(reference = null) })

    /** 얼굴 자리를 옮긴 것(자르기·회전·크기 변경·옮기기, [SkinFaceArea.mapped]) */
    fun mapped(scale: Float, map: (Float, Float) -> Pair<Float, Float>): SkinColor = copy(faces = faces.map { it.mapped(scale, map) })
}

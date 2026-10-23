package com.kiln.stratum.engine.skin

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /** 문서 자리 ([x], [y])에서 가장 가까운 얼굴(색을 섞는 무게가 가장 큰 얼굴, [SkinColorMath.weight])의 번호. 얼굴이 없으면 -1. */
    fun nearestFace(x: Float, y: Float): Int {
        var best = -1
        var bestWeight = -1f
        for (i in faces.indices) {
            val weight = SkinColorMath.weight(faces[i].area, x, y)
            if (weight > bestWeight) {
                best = i
                bestWeight = weight
            }
        }
        return best
    }

    /**
     * 스포이드로 고른 [argb](sRGB, 색 보정 바로 아래까지 합친 색)를 문서 자리 ([x], [y])에서 가까운 얼굴의 기준으로 한 것(결정 기록 '피부 보정'의 3b).
     * 다른 얼굴은 그대로다. 피부 색이 아니면(색상·채도가 피부 범위 밖, [SkinColorMath.isSkinLike]) 또는 얼굴이 없으면 null이다.
     * OKLab은 색 자체라 문서 색 공간과 상관없이 같은 값이다.
     */
    fun withReferenceFrom(x: Float, y: Float, argb: Int): SkinColor? {
        val index = nearestFace(x, y)
        if (index < 0) return null
        val lab = FloatArray(3)
        OkLab.fromRgb(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, lab)
        val hue = atan2(lab[2], lab[1])
        val chroma = hypot(lab[1], lab[2])
        if (!SkinColorMath.isSkinLike(hue, chroma)) return null
        return withReference(index, SkinReference(hue, chroma))
    }

    /**
     * 문서 자리 ([x], [y])에서 가까운 얼굴의 지금 기준색(스포이드로 고른 것, 없으면 그 사람 피부의 가운데 색)을 sRGB ARGB로(불투명).
     * 스포이드 고리의 아래쪽 반이 보인다. 얼굴이 없으면 null.
     */
    fun referenceColorAt(x: Float, y: Float): Int? {
        val face = faces.getOrNull(nearestFace(x, y)) ?: return null
        val hue = face.reference?.hue ?: face.tone.hue
        val chroma = face.reference?.chroma ?: face.tone.chroma
        val rgb = FloatArray(3)
        OkLab.toRgb(face.tone.lightness, chroma * cos(hue), chroma * sin(hue), rgb)
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
    }
}

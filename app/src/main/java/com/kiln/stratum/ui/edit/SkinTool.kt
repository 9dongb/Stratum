package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.skin.SkinColor

/*
 * 피부 도구(사용자 요청, 결정 기록 '피부 보정'). 고르면 고른 사진 레이어 바로 위에 [피부 보정] 그룹을 만들고(이미 있으면 그 그룹을 고친다),
 * 옵션은 다른 도구처럼 펼친 화면은 띠, 커버는 한 줄 막대다(사용자 결정 "도구, 옵션은 띠·막대 (추천)").
 * 띠·막대: [피부 보정 만들기]·[마스크 보기](2c), 기준(자동·스포이드)과 부분 붉은기·전체 붉은기·톤 균일화(3단계).
 * 피부 정돈(4단계)·잡티 제거(5단계) 칸은 그 단계에서 더한다.
 */

/** 띠·막대 칸의 이름([StripItem.key]·[BarItem.key]) */
internal const val SKIN_CREATE_KEY = "skinCreate"
internal const val SKIN_MASK_KEY = "skinMask"
internal const val SKIN_REFERENCE_KEY = "skinReference"

/** 피부 색 보정의 기준 고르기(결정 기록 '피부 보정'의 3단계): 자동(그 사람 피부의 가운데 색), 스포이드(누른 자리 피부색) */
enum class SkinReferenceChoice(@get:StringRes val label: Int, @get:DrawableRes val icon: Int) {
    AUTO(R.string.skin_reference_auto, R.drawable.ic_face),
    PICK(R.string.skin_reference_pick, R.drawable.ic_colorize)
}

/**
 * 화면에 보일 [피부 보정] 그룹의 기록([SkinRecord] 가운데 화면이 쓰는 것). 그룹 줄의 [LayerSummary.skin]에 담는다.
 * 얼굴 특징점처럼 큰 값은 담지 않는다(화면이 쓰지 않고, 레이어 요약을 견줄 때 느려지지 않게).
 *
 * @property faceCount 찾은 얼굴 수, [segmented]는 분할 모델로 피부를 골랐는지(아니면 얼굴 윤곽만)
 */
data class SkinSummary(
    val source: LayerId,
    val color: LayerId,
    val smooth: LayerId,
    val spots: LayerId,
    val faceCount: Int,
    val segmented: Boolean,
    val texture: Float
) {
    companion object {
        fun of(record: SkinRecord): SkinSummary =
            SkinSummary(record.source, record.color, record.smooth, record.spots, record.faces.size, record.segmented, record.texture)
    }
}

/** 피부 도구의 색 슬라이더를 끄는 동안의 값(기록 전, 결정 기록 '피부 보정'의 3단계): 그룹의 색 보정 레이어 [layer]와 그 피부 색 */
data class LiveSkinColor(val layer: LayerId, val color: SkinColor)

/** 피부 도구가 하는 일(결정 기록 '피부 보정'의 2c 화면·3단계). 편집 화면이 ViewModel에 넘긴다. */
interface SkinActions {

    /** 피부 도구를 골랐다: 고른 레이어가 고칠 피부 보정 그룹이 없으면 만든다(사진 레이어가 아니면 알린다). */
    fun picked()

    /** [피부 보정 만들기]를 눌렀다: 고른 사진 레이어 위에 그룹을 만든다(실행 취소 한 단계). */
    fun create()

    /** [마스크 보기]: 그룹의 색 보정 레이어([colorLayer])를 고르고, 피부 마스크 밖을 빨간 막으로 보이거나 끈다. */
    fun toggleMask(colorLayer: LayerId)

    /** 색 슬라이더를 끄는 동안 [colorLayer]의 피부 색을 [change]한 값으로 미리 본다(기록하지 않음) */
    fun previewColor(colorLayer: LayerId, change: (SkinColor) -> SkinColor) {}

    /** 색 슬라이더를 놓았다: 끄던 값을 실행 취소 한 단계로 기록한다 */
    fun commitColor() {}

    /** 기준을 '자동'(그 사람 피부의 가운데 색)으로 되돌린다(스포이드로 고른 기준을 지움, 실행 취소 한 단계) */
    fun autoReference(colorLayer: LayerId) {}

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : SkinActions {
        override fun picked() = Unit
        override fun create() = Unit
        override fun toggleMask(colorLayer: LayerId) = Unit
    }
}

/**
 * 피부 도구의 띠·막대가 보일 것. [group]은 고른 레이어가 고칠 피부 보정 그룹([skinGroupFor])이고, 없으면 [피부 보정 만들기]만 보인다.
 *
 * @property color 그룹의 색 보정 레이어의 피부 색(끄는 중이면 그 값). 색 보정 레이어가 없거나 잰 얼굴이 없으면 null이라 색 칸이 없다.
 * @property maskShown 마스크 보기를 켰는지(그 그룹의 색 보정 레이어를 골라 빨간 막을 보이는 중)
 * @property maskAvailable 마스크를 볼 수 있는지(색 보정 레이어가 남아 있고 마스크가 있다). 사용자가 색 보정 레이어를 지웠으면 꺼진다.
 * @property picking 기준 스포이드를 켜서 캔버스를 누르기를 기다리는 중인지
 * @property onColor 색 슬라이더를 끄는 동안(지금 값을 받아 새 값을 돌려줌), [onColorFinished]는 놓을 때
 * @property onAutoReference 기준을 '자동'으로, [onPickReference]는 스포이드로 고르기 시작
 */
data class SkinControls(
    val group: SkinSummary?,
    val color: SkinColor?,
    val maskShown: Boolean,
    val maskAvailable: Boolean,
    val busy: Boolean,
    val picking: Boolean = false,
    val onCreate: () -> Unit,
    val onToggleMask: () -> Unit,
    val onColor: ((SkinColor) -> SkinColor) -> Unit = {},
    val onColorFinished: () -> Unit = {},
    val onAutoReference: () -> Unit = {},
    val onPickReference: () -> Unit = {}
)

/**
 * [active] 레이어를 고른 채 피부 도구가 고칠 그룹 줄. 엔진의 [SkinRecord.groupOf]와 같은 규칙이다: 그 레이어가 그 그룹이거나,
 * 그 그룹 안의 레이어(가까운 그룹부터)이거나, 그 그룹의 원본이면(원본이 같은 그룹이 여럿이면 맨 위) 그 그룹이다. 없으면 null.
 */
internal fun skinGroupFor(layers: List<LayerSummary>, active: LayerId?): LayerSummary? {
    val byId = layers.associateBy { it.id }
    val layer = active?.let(byId::get) ?: return null
    generateSequence(layer) { it.parent?.let(byId::get) }.take(layers.size + 1).firstOrNull { it.skin != null }?.let { return it }
    return layers.lastOrNull { it.skin?.source == layer.id }
}

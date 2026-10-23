package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.SkinRecord

/*
 * 피부 도구(사용자 요청, 결정 기록 '피부 보정'). 고르면 고른 사진 레이어 바로 위에 [피부 보정] 그룹을 만들고(이미 있으면 그 그룹을 고친다),
 * 옵션은 다른 도구처럼 펼친 화면은 띠, 커버는 한 줄 막대다(사용자 결정 "도구, 옵션은 띠·막대 (추천)").
 * 2c 단계는 [피부 보정 만들기]와 [마스크 보기]이고, 붉은기·톤 균일화(3단계)·피부 정돈(4단계)·잡티 제거(5단계) 칸은 그 단계에서 더한다.
 */

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

/** 피부 도구가 하는 일(결정 기록 '피부 보정'의 2c 화면). 편집 화면이 ViewModel에 넘긴다. */
interface SkinActions {

    /** 피부 도구를 골랐다: 고른 레이어가 고칠 피부 보정 그룹이 없으면 만든다(사진 레이어가 아니면 알린다). */
    fun picked()

    /** [피부 보정 만들기]를 눌렀다: 고른 사진 레이어 위에 그룹을 만든다(실행 취소 한 단계). */
    fun create()

    /** [마스크 보기]: 그룹의 색 보정 레이어([colorLayer])를 고르고, 피부 마스크 밖을 빨간 막으로 보이거나 끈다. */
    fun toggleMask(colorLayer: LayerId)

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
 * @property maskShown 마스크 보기를 켰는지(그 그룹의 색 보정 레이어를 골라 빨간 막을 보이는 중)
 * @property maskAvailable 마스크를 볼 수 있는지(색 보정 레이어가 남아 있고 마스크가 있다). 사용자가 색 보정 레이어를 지웠으면 꺼진다.
 * @property onToggleMask 마스크 보기 켜고 끄기, [onCreate]는 그룹 만들기
 */
data class SkinControls(
    val group: SkinSummary?,
    val maskShown: Boolean,
    val maskAvailable: Boolean,
    val busy: Boolean,
    val onCreate: () -> Unit,
    val onToggleMask: () -> Unit
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

/** 띠·막대 칸의 이름([StripItem.key]·[BarItem.key]) */
private const val CREATE_KEY = "skinCreate"
private const val MASK_KEY = "skinMask"

/**
 * 피부 도구의 띠(펼친 화면): 그룹이 없으면 [피부 보정 만들기](파란 버튼), 있으면 [마스크 보기](켜고 끔).
 * 붉은기·톤 균일화·피부 정돈·질감 유지의 수치 칸은 3·4단계에서 그 아래에 더한다.
 */
@Composable
internal fun skinStrip(controls: SkinControls): List<StripItem> {
    if (controls.group == null) {
        return listOf(
            IconCell(
                CREATE_KEY, R.drawable.ic_face, stringResource(R.string.skin_create), controls.onCreate,
                enabled = !controls.busy, accent = true, tag = EditScreenTags.SKIN_CREATE
            )
        )
    }
    return listOf(
        IconCell(
            MASK_KEY, R.drawable.ic_vignette, stringResource(R.string.skin_mask_view_description), controls.onToggleMask,
            enabled = controls.maskAvailable, checked = controls.maskShown, tag = EditScreenTags.SKIN_MASK
        )
    )
}

/** 피부 도구의 막대(커버): 띠와 같은 것을 칩으로 둔다([skinStrip]). 커버 가로의 세로 기둥도 같은 칸이다. */
@Composable
internal fun skinBar(controls: SkinControls): List<BarItem> {
    if (controls.group == null) {
        return listOf(
            BarButton(
                CREATE_KEY, stringResource(R.string.skin_create), R.drawable.ic_face, controls.onCreate,
                enabled = !controls.busy, accent = true, tag = EditScreenTags.SKIN_CREATE
            )
        )
    }
    return listOf(
        BarToggle(
            MASK_KEY, stringResource(R.string.skin_mask_view), controls.maskShown, { controls.onToggleMask() },
            enabled = controls.maskAvailable, tag = EditScreenTags.SKIN_MASK
        )
    )
}

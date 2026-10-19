package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 흐림 도구(흐림·선명·스머지) 설정. 브러시 모양(크기·경도)과 강도(0..1). 세 하위 도구가 같이 쓴다(포토샵의 강도와 같다).
 * 스머지의 강도는 집은 색을 끌고 가는 거리다(SmudgeMath). 브러시 불투명도는 쓰지 않는다(세기는 강도가 정한다).
 */
data class BlurOptions(
    val brush: BrushSettings = BrushSettings(size = 100f, hardness = 0.3f),
    val strength: Float = 0.5f
)

/** 캔버스의 흐림·선명·모자이크 손가락이 엔진 쪽(ViewModel)에 보내는 일. 좌표는 문서 픽셀이다. */
interface EffectActions {

    /** 한 손가락이 닿아 [kind] 획을 시작한다 */
    fun begin(x: Float, y: Float, kind: EffectKind)

    /** 한 손가락이 닿아 스머지 획을 시작한다 */
    fun beginSmudge(x: Float, y: Float)

    /** 손가락이 움직였다 */
    fun extend(x: Float, y: Float)

    /** 손가락을 뗐다. 획을 기록한다. */
    fun end()

    /** 두 손가락 조작이 시작돼 획을 버린다 */
    fun cancel()

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : EffectActions {
        override fun begin(x: Float, y: Float, kind: EffectKind) = Unit
        override fun beginSmudge(x: Float, y: Float) = Unit
        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/** 흐림 도구 하위 도구의 효과. 스머지(효과가 아니라 따로 끄는 획)와 흐림 도구의 것이 아닌 하위 도구는 null. */
val SubTool.effectKind: EffectKind?
    get() = when (this) {
        SubTool.EFFECT_BLUR -> EffectKind.BLUR
        SubTool.EFFECT_SHARPEN -> EffectKind.SHARPEN
        else -> null
    }

/**
 * 흐림 옵션이 쓰는 값과 할 일. 설정은 ViewModel이 들고(앱을 끌 때까지 남음), 바꾸면 [onChange]로 알린다.
 * [sizePreview]는 크기·경도 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태다(없으면 보이지 않음).
 */
@Stable
class BlurControls(val options: BlurOptions, val sizePreview: SizePreviewState? = null, val onChange: (BlurOptions) -> Unit)

/**
 * 흐림 옵션(커버): 하위 도구(흐림·선명·스머지), 강도, 크기, 경도, 안내 한 줄. 바꾸면 다음 획부터 쓴다(기록하지 않는다).
 */
@Composable
fun BlurToolOptions(mode: SubTool, controls: BlurControls, onChoose: (SubTool) -> Unit, modifier: Modifier = Modifier) {
    val options = controls.options
    val brush = options.brush
    // 패널 안에서 내용만큼만 차지하고, 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(다른 도구 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.BLUR_OPTIONS), gap = 8.dp, fillHeight = false) {
        SubToolSegments(EditTool.Blur.subTools, mode, onChoose)
        OptionSlider(
            label = stringResource(R.string.effect_strength),
            shown = stringResource(R.string.layer_percent, (options.strength * 100).roundToInt()),
            fraction = options.strength,
            onFraction = { controls.onChange(options.copy(strength = strengthOf(it))) },
            tag = EditScreenTags.BLUR_STRENGTH
        )
        SizeSlider(brush.size, { controls.onChange(options.copy(brush = brush.copy(size = it))) }, EditScreenTags.BLUR_SIZE, controls.sizePreview)
        HardnessSlider(brush.hardness, { controls.onChange(options.copy(brush = brush.copy(hardness = it))) }, EditScreenTags.BLUR_HARDNESS, controls.sizePreview)
        Text(
            text = stringResource(blurHint(mode)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(EditScreenTags.BLUR_HINT),
            color = StratumTheme.colors.textDescription,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** 강도 슬라이더 눈금(0..1)을 강도로: 1% 단위, 0%면 아무것도 바뀌지 않으니 1%에서 멈춘다(명암의 노출과 같다) */
internal fun strengthOf(fraction: Float): Float = ((fraction * 100).roundToInt() / 100f).coerceIn(0.01f, 1f)

/** 흐림 하위 도구의 안내 */
internal fun blurHint(mode: SubTool?): Int =
    when (mode) {
        SubTool.EFFECT_SHARPEN -> R.string.blur_hint_sharpen
        SubTool.EFFECT_SMUDGE -> R.string.blur_hint_smudge
        else -> R.string.blur_hint_blur
    }

/**
 * 흐림·모자이크 도구를 고르면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 끌어 [kind] 획을 그린다(브러시와 같은 손가락 처리).
 * [kind]가 null이면 스머지 획이다. [cursorSize](문서 픽셀 지름)가 있으면 칠하는 손가락 자리에 브러시 커서를 보인다. [tag]는 화면 테스트 이름표다.
 */
@Composable
fun EffectOverlay(
    view: () -> CanvasView,
    document: PixelSize,
    kind: EffectKind?,
    actions: EffectActions,
    tag: String,
    modifier: Modifier = Modifier,
    cursorSize: Float? = null
) {
    PaintGestureOverlay(
        view = view,
        document = document,
        tag = tag,
        onBegin = { x, y -> if (kind == null) actions.beginSmudge(x, y) else actions.begin(x, y, kind) },
        onExtend = actions::extend,
        onEnd = actions::end,
        onCancel = actions::cancel,
        modifier = modifier,
        cursorSize = cursorSize
    )
}

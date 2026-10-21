package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.tone.ToneRange
import com.kiln.stratum.ui.components.HelpButton
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import kotlin.math.roundToInt

/**
 * 명암 도구(닷지·번) 설정. 브러시 모양(크기·단단함), 바꿀 밝기 범위, 노출(0..1). 닷지·번 모두 같이 쓴다(포토샵의 범위·노출과 같다).
 * 브러시 불투명도는 쓰지 않는다(세기는 노출이 정한다).
 */
data class ToneOptions(
    val brush: BrushSettings = BrushSettings(size = 100f, hardness = 0.3f),
    val range: ToneRange = ToneRange.MIDTONES,
    val exposure: Float = 0.5f
)

/** 캔버스의 명암 손가락이 엔진 쪽(ViewModel)에 보내는 일. 좌표는 문서 픽셀이다. */
interface ToneActions {

    /** 한 손가락이 닿아 획을 시작한다([dodge]면 밝게, 아니면 어둡게) */
    fun begin(x: Float, y: Float, dodge: Boolean)

    /** 손가락이 움직였다 */
    fun extend(x: Float, y: Float)

    /** 손가락을 뗐다. 획을 기록한다. */
    fun end()

    /** 두 손가락 조작이 시작돼 획을 버린다 */
    fun cancel()

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : ToneActions {
        override fun begin(x: Float, y: Float, dodge: Boolean) = Unit
        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 명암 옵션이 쓰는 값과 할 일. 설정은 ViewModel이 들고(앱을 끌 때까지 남음), 바꾸면 [onChange]로 알린다.
 * [sizePreview]는 크기·단단함 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태다(없으면 보이지 않음).
 */
@Stable
class ToneControls(val options: ToneOptions, val sizePreview: SizePreviewState? = null, val onChange: (ToneOptions) -> Unit)

/**
 * 명암 옵션: 범위(어두운 영역·중간 영역·밝은 영역), 크기, 경도, 노출, 맨 아래 하위 도구(닷지·번)와 그 오른쪽 끝의 물음표 도움말.
 * 바꾸면 다음 획부터 쓴다(기록하지 않는다). 설명 줄 대신 물음표를 누르면 안내가 말풍선으로 뜬다(사용자 피드백 8차 1-7).
 */
@Composable
fun ToneToolOptions(mode: SubTool, controls: ToneControls, onChoose: (SubTool) -> Unit, modifier: Modifier = Modifier) {
    val options = controls.options
    val brush = options.brush
    // 패널 안에서 내용만큼만 차지하고, 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(다른 도구 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.TONE_OPTIONS), gap = 8.dp, fillHeight = false) {
        ToneRangeRow(options.range) { controls.onChange(options.copy(range = it)) }
        // 슬라이더는 크기 → 경도 → 노출(강도 자리) 순서다(사용자 피드백 9차, 띠와 같다)
        SizeSlider(brush.size, { controls.onChange(options.copy(brush = brush.copy(size = it))) }, EditScreenTags.TONE_SIZE, controls.sizePreview)
        HardnessSlider(brush.hardness, { controls.onChange(options.copy(brush = brush.copy(hardness = it))) }, EditScreenTags.TONE_HARDNESS, controls.sizePreview)
        OptionSlider(
            label = stringResource(R.string.tone_exposure),
            shown = stringResource(R.string.layer_percent, (options.exposure * 100).roundToInt()),
            fraction = options.exposure,
            // 0%면 아무것도 바뀌지 않으니 1%에서 멈춘다
            onFraction = { controls.onChange(options.copy(exposure = ((it * 100).roundToInt() / 100f).coerceAtLeast(0.01f))) },
            tag = EditScreenTags.TONE_EXPOSURE
        )
        // 하위 도구 줄은 맨 아래(커버 공통 규칙), 그 오른쪽 끝에 물음표 도움말
        val help = checkNotNull(toolHelpText(EditTool.Tone, mode))
        SubToolSegments(EditTool.Tone.subTools, mode, onChoose) {
            HelpButton(help, stringResource(R.string.tool_help), Modifier.testTag(EditScreenTags.TOOL_HELP))
        }
    }
}

/** 바꿀 밝기 범위 고르기 */
@Composable
private fun ToneRangeRow(range: ToneRange, onRange: (ToneRange) -> Unit) {
    val ranges = ToneRange.entries
    StratumSegmentedRow {
        ranges.forEachIndexed { index, entry ->
            StratumSegment(
                selected = entry == range,
                onClick = { onRange(entry) },
                index = index,
                count = ranges.size,
                modifier = Modifier.testTag(EditScreenTags.toneRange(entry))
            ) {
                // 한국어는 글자마다 줄을 바꿀 수 있어, 이름 전체 폭(최대 고유 폭)을 쓰게 한다(하위 도구 줄과 같다)
                Text(stringResource(toneRangeLabel(entry)), modifier = Modifier.width(IntrinsicSize.Max), maxLines = 1, softWrap = false)
            }
        }
    }
}

internal fun toneRangeLabel(range: ToneRange): Int =
    when (range) {
        ToneRange.SHADOWS -> R.string.tone_shadows
        ToneRange.MIDTONES -> R.string.tone_midtones
        ToneRange.HIGHLIGHTS -> R.string.tone_highlights
    }

/** 범위의 아이콘(메인 화면 띠의 범위 칸): 어두운 영역은 밝기 낮음, 중간은 반, 밝은 영역은 밝기 높음(Material Icons) */
internal fun toneRangeIcon(range: ToneRange): Int =
    when (range) {
        ToneRange.SHADOWS -> R.drawable.ic_brightness_low
        ToneRange.MIDTONES -> R.drawable.ic_brightness_medium
        ToneRange.HIGHLIGHTS -> R.drawable.ic_brightness_high
    }

/**
 * 명암 도구를 고르면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 끌어 닷지([dodge]) 또는 번 획을 그린다(브러시와 같은 손가락 처리).
 * [cursorSize](문서 픽셀 지름)가 있으면 칠하는 손가락 자리에 브러시 커서를 보인다.
 */
@Composable
fun ToneOverlay(view: () -> CanvasView, document: PixelSize, dodge: Boolean, actions: ToneActions, modifier: Modifier = Modifier, cursorSize: Float? = null) {
    PaintGestureOverlay(
        view = view,
        document = document,
        tag = EditScreenTags.TONE_OVERLAY,
        onBegin = { x, y -> actions.begin(x, y, dodge) },
        onExtend = actions::extend,
        onEnd = actions::end,
        onCancel = actions::cancel,
        modifier = modifier,
        cursorSize = cursorSize
    )
}

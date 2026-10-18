package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 브러시·지우개 옵션이 쓰는 값과 할 일. 설정은 ViewModel이 들고(앱을 끌 때까지 남음), 바꾸면 [onChange]로 알린다.
 *
 * @property erase 지우개 옵션인지. 지우개는 색을 고르지 않는다.
 * @property sizePreview 크기·단단함 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태(없으면 보이지 않음)
 * @property colors 색 줄(지금 색·최근 색, 브러시만). 없으면 색 줄을 보이지 않는다.
 */
@Stable
class BrushControls(
    val settings: BrushSettings,
    val erase: Boolean,
    val onChange: (BrushSettings) -> Unit,
    val sizePreview: SizePreviewState? = null,
    val colors: ColorControls? = null
)

/**
 * 브러시·지우개 옵션: (브러시만) 색, 크기, 단단함, 불투명도. 바꾸면 다음 획부터 쓴다(기록하지 않는다).
 * 크기는 1~1000px을 고르게 고를 수 있게 로그 눈금이다(작은 크기를 세밀하게).
 */
@Composable
fun BrushOptions(controls: BrushControls, modifier: Modifier = Modifier) {
    val settings = controls.settings
    // 패널 안에서 내용만큼만 차지하고, 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(자르기·변형 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.BRUSH_OPTIONS), fillHeight = false) {
        if (!controls.erase) controls.colors?.let { ColorRow(it) }
        SizeSlider(settings.size, { controls.onChange(settings.copy(size = it)) }, EditScreenTags.BRUSH_SIZE, controls.sizePreview)
        HardnessSlider(settings.hardness, { controls.onChange(settings.copy(hardness = it)) }, EditScreenTags.BRUSH_HARDNESS, controls.sizePreview)
        OptionSlider(
            label = stringResource(R.string.layer_opacity),
            shown = stringResource(R.string.layer_percent, (settings.opacity * 100).roundToInt()),
            fraction = settings.opacity,
            // 0%면 아무것도 그려지지 않으니 1%에서 멈춘다
            onFraction = { controls.onChange(settings.copy(opacity = ((it * 100).roundToInt() / 100f).coerceAtLeast(0.01f))) },
            tag = EditScreenTags.BRUSH_OPACITY
        )
    }
}

/**
 * 크기 슬라이더(1~1000px, 로그 눈금). 끄는 동안 [preview]가 캔버스에 그 크기를 보인다(손을 떼면 사라짐).
 * 브러시·지우개·리터칭·명암·유동화가 같이 쓴다.
 */
@Composable
internal fun SizeSlider(size: Float, onSize: (Float) -> Unit, tag: String, preview: SizePreviewState?) {
    OptionSlider(
        label = stringResource(R.string.brush_size),
        shown = stringResource(R.string.brush_size_value, size.roundToInt()),
        fraction = sizeToFraction(size),
        onFraction = {
            preview?.onChange()
            onSize(fractionToSize(it))
        },
        tag = tag,
        onFinished = { preview?.onFinished() }
    )
}

/**
 * 단단함 슬라이더(0~100%). 끄는 동안 [preview]가 캔버스에 브러시 크기와 단단함 자리를 보인다(손을 떼면 사라짐).
 * 브러시·지우개·리터칭·명암이 같이 쓴다.
 */
@Composable
internal fun HardnessSlider(hardness: Float, onHardness: (Float) -> Unit, tag: String, preview: SizePreviewState?) {
    OptionSlider(
        label = stringResource(R.string.brush_hardness),
        shown = stringResource(R.string.layer_percent, (hardness * 100).roundToInt()),
        fraction = hardness,
        onFraction = {
            preview?.onChange()
            onHardness((it * 100).roundToInt() / 100f)
        },
        tag = tag,
        onFinished = { preview?.onFinished() }
    )
}

/**
 * 이름·가는 슬라이더(0..1)·숫자 한 줄. 끄는 대로 바로 바꾼다(그리기 전 설정이라 기록이 따로 없다).
 * [onFinished]는 손을 뗄 때 부른다.
 */
@Composable
internal fun OptionSlider(label: String, shown: String, fraction: Float, onFraction: (Float) -> Unit, tag: String, onFinished: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        ThinSlider(
            value = fraction,
            onValueChange = onFraction,
            onValueChangeFinished = onFinished,
            valueRange = 0f..1f,
            enabled = true,
            centered = false,
            modifier = Modifier
                .weight(1f)
                .testTag(tag)
                .semantics {
                    contentDescription = label
                    stateDescription = shown
                }
        )
        Text(
            text = shown,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** 크기 슬라이더의 눈금(0..1)을 크기(px)로. 로그 눈금이라 1~10px도 슬라이더의 1/3을 쓴다. */
internal fun fractionToSize(fraction: Float): Float {
    val min = ln(BrushSettings.MIN_SIZE)
    val max = ln(BrushSettings.MAX_SIZE)
    return exp(min + (max - min) * fraction.coerceIn(0f, 1f)).roundToInt().toFloat().coerceIn(BrushSettings.MIN_SIZE, BrushSettings.MAX_SIZE)
}

/** 크기(px)를 크기 슬라이더의 눈금(0..1)으로 */
internal fun sizeToFraction(size: Float): Float {
    val min = ln(BrushSettings.MIN_SIZE)
    val max = ln(BrushSettings.MAX_SIZE)
    return ((ln(size.coerceIn(BrushSettings.MIN_SIZE, BrushSettings.MAX_SIZE)) - min) / (max - min)).coerceIn(0f, 1f)
}

package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.ui.components.StratumSlider
import com.kiln.stratum.ui.theme.StratumTheme
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
 * 끄는 도중에 슬라이더가 사라지면(옵션 창이 닫힘, 접기·펼치기) 손을 뗐다는 알림이 오지 않는다.
 * 그때도 캔버스의 크기 원이 남지 않게, 사라질 때 끝낸 것으로 본다.
 */
@Composable
internal fun EndPreviewOnDispose(preview: SizePreviewState?) {
    DisposableEffect(preview) {
        onDispose { preview?.onFinished() }
    }
}

/**
 * 이름·가는 슬라이더(0..1)·숫자 한 줄(커버 옵션·유동화 옵션). 끄는 대로 바로 바꾼다(그리기 전 설정이라 기록이 따로 없다).
 * [onFinished]는 손을 뗄 때 부른다. 펼친 화면 띠의 슬라이더는 세로 슬라이더 창이다(`SliderCell`).
 */
@Composable
internal fun OptionSlider(
    label: String,
    shown: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    tag: String,
    onFinished: () -> Unit = {}
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            color = StratumTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        StratumSlider(
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
            color = StratumTheme.colors.textPrimary,
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

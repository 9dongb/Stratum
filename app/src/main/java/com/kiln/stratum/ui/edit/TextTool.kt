package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.ui.components.StratumSlider
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.WidthClass
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 글자 옵션이 쓰는 모양과 바꾸기. 모양은 ViewModel이 든다(앱을 끌 때까지 남음).
 * [colors]는 색 줄(지금 색·최근 색)이고, 없으면 색 줄을 보이지 않는다.
 */
@Stable
class TextControls(val style: TextStyle, val onChange: (TextStyle) -> Unit, val colors: ColorControls? = null)

/**
 * 글자 옵션: 안내 한 줄, 색, 크기. 캔버스를 누르면 그 자리에 글자를 넣는 창이 뜬다(편집 화면).
 * 크기는 8~2000px을 고르게 고를 수 있게 로그 눈금이다.
 */
@Composable
fun TextOptions(controls: TextControls, modifier: Modifier = Modifier) {
    val style = controls.style
    val label = stringResource(R.string.brush_size)
    val shown = stringResource(R.string.brush_size_value, style.size.roundToInt())
    // 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(자르기·변형 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.TEXT_OPTIONS), fillHeight = false) {
        Text(
            text = stringResource(R.string.text_hint),
            // 힌지를 피하는 칸은 좁은 줄을 가운데 놓으므로, 왼쪽에 맞추려고 줄을 꽉 채운다
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        controls.colors?.let { ColorRow(it) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            StratumSlider(
                value = textSizeToFraction(style.size),
                onValueChange = { controls.onChange(style.copy(size = fractionToTextSize(it))) },
                onValueChangeFinished = {},
                valueRange = 0f..1f,
                enabled = true,
                centered = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag(EditScreenTags.TEXT_SIZE)
                    .semantics {
                        contentDescription = label
                        stateDescription = shown
                    }
            )
            Text(text = shown, modifier = Modifier.width(64.dp), textAlign = TextAlign.End, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 글자 도구를 고르면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 톡 누르면 그 자리(문서 좌표)를 [onTap]으로 알린다.
 * 두 손가락 확대·이동은 그대로 된다(부모가 먼저 읽는다).
 */
@Composable
fun TextOverlay(view: () -> CanvasView, document: PixelSize, onTap: (x: Float, y: Float) -> Unit, modifier: Modifier = Modifier) {
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier.testTag(EditScreenTags.TEXT_OVERLAY).pointerInput(document) {
            detectTapGestures { position ->
                val transform = view().transform(PixelSize(size.width, size.height), document)
                currentOnTap(transform.toDocumentX(position.x.toDouble()).toFloat(), transform.toDocumentY(position.y.toDouble()).toFloat())
            }
        }
    )
}

/**
 * 글자를 적는 창. 커버 화면(힌지 없는 좁은 화면)은 아래에서 올라오는 시트, 그 밖에는 접히는 선을 피해 뜨는 창이다(크기 변경 창과 같다).
 * 적은 글자는 접기·펼치기에도 남는다. '넣기'를 누르면 [onAdd]로 넘긴다. 비었으면 누를 수 없다.
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPrompt(widthClass: WidthClass, busy: Boolean, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val title = stringResource(R.string.text_prompt_title)
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(TextStyle.MAX_LENGTH) },
            label = { Text(stringResource(R.string.text_prompt_label)) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag(EditScreenTags.TEXT_FIELD)
        )
    }
    val buttons: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            TextButton(
                onClick = { onAdd(text) },
                enabled = text.isNotBlank() && !busy,
                modifier = Modifier.testTag(EditScreenTags.TEXT_ADD)
            ) {
                Text(stringResource(R.string.action_add))
            }
        }
    }
    if (widthClass == WidthClass.Compact && LocalHinges.current.isEmpty()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                field()
                Spacer(Modifier.height(16.dp))
                buttons(Modifier.align(Alignment.End))
            }
        }
    } else {
        HingeAwareDialog(onDismissRequest = onDismiss, title = title, buttons = { buttons(Modifier) }) {
            field()
        }
    }
}

/** 글자 크기 슬라이더의 눈금(0..1)을 크기(px)로(로그 눈금) */
internal fun fractionToTextSize(fraction: Float): Float {
    val min = ln(TextStyle.MIN_SIZE)
    val max = ln(TextStyle.MAX_SIZE)
    return exp(min + (max - min) * fraction.coerceIn(0f, 1f)).roundToInt().toFloat().coerceIn(TextStyle.MIN_SIZE, TextStyle.MAX_SIZE)
}

/** 글자 크기(px)를 슬라이더 눈금(0..1)으로 */
internal fun textSizeToFraction(size: Float): Float {
    val min = ln(TextStyle.MIN_SIZE)
    val max = ln(TextStyle.MAX_SIZE)
    return ((ln(size.coerceIn(TextStyle.MIN_SIZE, TextStyle.MAX_SIZE)) - min) / (max - min)).coerceIn(0f, 1f)
}

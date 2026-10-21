package com.kiln.stratum.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.settings.ColorSlot
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import com.kiln.stratum.ui.layout.HingeAwareDialog
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.WidthClass
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 글자 옵션이 쓰는 모양과 바꾸기. 모양은 ViewModel이 든다(앱을 끌 때까지 남음).
 * [colors]는 색 줄(지금 색·최근 색)이고, 없으면 색 줄을 보이지 않는다.
 */
@Stable
class TextControls(val style: TextStyle, val onChange: (TextStyle) -> Unit, val colors: ColorControls? = null)

/**
 * 글자 옵션: 색, 크기. 캔버스를 누르면 그 자리에 글자를 넣는 창이 뜬다(편집 화면).
 * 크기는 8~2000px을 고르게 고를 수 있게 로그 눈금이다. 설명 줄은 두지 않는다(사용자 피드백 8차 1-7: 작업 공간 확보).
 */
@Composable
fun TextOptions(controls: TextControls, modifier: Modifier = Modifier) {
    // 가로 힌지의 띠에 걸리는 줄은 띠 아래로 비켜 놓는다(자르기·변형 옵션과 같다)
    HingeAvoidingColumn(modifier.testTag(EditScreenTags.TEXT_OPTIONS), fillHeight = false) {
        controls.colors?.let { ColorRow(it) }
        TextSizeSlider(controls)
    }
}

/** 글자 크기 슬라이더(8~2000px, 로그 눈금, 커버 옵션). 펼친 화면 띠는 세로 슬라이더(`textStrip`)다. */
@Composable
internal fun TextSizeSlider(controls: TextControls) {
    val style = controls.style
    OptionSlider(
        label = stringResource(R.string.brush_size),
        shown = stringResource(R.string.brush_size_value, style.size.roundToInt()),
        fraction = textSizeToFraction(style.size),
        onFraction = { controls.onChange(style.copy(size = fractionToTextSize(it))) },
        tag = EditScreenTags.TEXT_SIZE
    )
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
 * 글자 창에서 고르는 것(사용자 피드백 8차 1-6: 넣은 글자를 다시 눌러 고치기, 내용·크기·색 모두).
 * 새 글자는 [layer]가 null이고 문서 좌표 ([x], [y])(글자 상자 왼쪽 위)에 넣는다. 고치기는 [layer] 글자 레이어를 고친다.
 * [documentSize]는 창을 열 때의 문서 크기다(다른 크기의 문서를 보게 되면 창을 닫는다).
 * [palette]가 있으면 같은 창이 색 고르기 쪽이다(창을 겹치지 않는다). 접기·펼치기에도 남게 [Saver]로 저장한다.
 */
data class TextDraft(
    val layer: LayerId?,
    val x: Float,
    val y: Float,
    val documentSize: PixelSize,
    val text: String,
    val size: Float,
    val color: Int,
    val palette: PaletteDraft? = null
) {
    /** 고른 크기·색 */
    val style: TextStyle
        get() = TextStyle(size, color)

    companion object {
        /** 문서 좌표 ([x], [y])에 넣을 새 글자. 글자 옵션([style]: 크기·전경색)으로 시작한다. */
        fun add(x: Float, y: Float, documentSize: PixelSize, style: TextStyle): TextDraft =
            TextDraft(null, x, y, documentSize, "", style.size, style.color)

        /** [layer] 글자 레이어([spec])를 고치는 창. 그 글자·크기·색으로 시작한다. */
        fun edit(layer: LayerId, spec: TextSpec, documentSize: PixelSize): TextDraft =
            TextDraft(layer, spec.x, spec.y, documentSize, spec.text, spec.style.size, spec.style.color)

        val Saver: Saver<TextDraft?, List<Any>> = Saver(
            save = { draft ->
                draft?.let {
                    listOf(
                        it.layer?.value ?: -1L, it.x, it.y, it.documentSize.width, it.documentSize.height, it.text, it.size, it.color,
                        it.palette?.let { palette -> with(PaletteDraft.Saver) { save(palette) } } ?: emptyList<Any>()
                    )
                }
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val palette = (saved[8] as List<Any>).takeIf { it.isNotEmpty() }?.let { PaletteDraft.Saver.restore(it) }
                TextDraft(
                    layer = (saved[0] as Long).takeIf { it >= 0 }?.let(::LayerId),
                    x = saved[1] as Float,
                    y = saved[2] as Float,
                    documentSize = PixelSize(saved[3] as Int, saved[4] as Int),
                    text = saved[5] as String,
                    size = saved[6] as Float,
                    color = saved[7] as Int,
                    palette = palette
                )
            }
        )
    }
}

/**
 * 글자 창. 커버 화면(힌지 없는 좁은 화면)은 아래에서 올라오는 시트, 그 밖에는 접히는 선을 피해 뜨는 창이다(크기 변경 창과 같다).
 * 글자 칸, 크기 슬라이더, 색 칸이 있다(사용자 피드백 8차 1-6). 색 칸을 누르면 같은 창이 팔레트로 바뀌고, 확인·취소하면 글자 쪽으로 돌아온다.
 * 고르는 것은 [draft]이고 [onChange]로 바꾼다. 새 글자는 '넣기', 고치기는 '적용'이 [onConfirm]이다(비었으면 누를 수 없다).
 * 창 밖·뒤로는 팔레트 쪽이면 글자 쪽으로 돌아오고, 글자 쪽이면 [onDismiss]다.
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPrompt(
    draft: TextDraft,
    widthClass: WidthClass,
    windowHeight: Dp,
    busy: Boolean,
    onChange: (TextDraft) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = draft.palette
    val title = stringResource(
        when {
            palette != null -> R.string.text_color_title
            draft.layer == null -> R.string.text_prompt_title
            else -> R.string.text_edit_title
        }
    )
    val dismiss = { if (palette != null) onChange(draft.copy(palette = null)) else onDismiss() }
    val content: @Composable () -> Unit = {
        if (palette != null) {
            PaletteFormIn(palette, { onChange(draft.copy(palette = it)) }, windowHeight)
        } else {
            TextForm(draft, onChange)
        }
    }
    val buttons: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (palette != null) {
                // 색 고르기 쪽: 취소는 색을 그대로 두고 글자 쪽으로, 확인은 그 색으로 글자 쪽으로
                QuietButton(onClick = { onChange(draft.copy(palette = null)) }, modifier = Modifier.testTag(EditScreenTags.PALETTE_CANCEL)) {
                    Text(stringResource(R.string.action_cancel))
                }
                AccentButton(onClick = { onChange(draft.copy(color = palette.color, palette = null)) }, modifier = Modifier.testTag(EditScreenTags.PALETTE_OK)) {
                    Text(stringResource(R.string.action_ok))
                }
            } else {
                QuietButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                AccentButton(
                    onClick = onConfirm,
                    enabled = draft.text.isNotBlank() && !busy,
                    modifier = Modifier.testTag(EditScreenTags.TEXT_ADD)
                ) {
                    Text(stringResource(if (draft.layer == null) R.string.action_add else R.string.action_apply))
                }
            }
        }
    }
    if (widthClass == WidthClass.Compact && LocalHinges.current.isEmpty()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        // 시트는 창 밖을 누르거나 아래로 밀면 먼저 내려간 뒤 onDismissRequest를 부른다(M3).
        // 팔레트 쪽이면 글자 쪽으로 바꾸고 시트를 다시 올린다(내려간 채로 두면 보이지 않는 시트 창이 터치를 막는다).
        val sheetDismiss: () -> Unit = {
            if (palette != null) {
                onChange(draft.copy(palette = null))
                scope.launch { sheetState.show() }
            } else {
                onDismiss()
            }
        }
        ModalBottomSheet(onDismissRequest = sheetDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            // 팔레트 쪽의 뒤로 가기는 시트 창 안에서 먼저 받아, 시트를 내리지 않고 글자 쪽으로 바꾼다.
            // M3에 맡기면 시트가 내려가고, 밀어서 뒤로 가기(예측 뒤로 가기)의 줄어든 모습이 다시 올린 시트에 남는다.
            BackHandler(enabled = palette != null) { onChange(draft.copy(palette = null)) }
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Spacer(Modifier.height(16.dp))
                buttons(Modifier.align(Alignment.End))
            }
        }
    } else {
        HingeAwareDialog(onDismissRequest = dismiss, title = title, buttons = { buttons(Modifier) }) {
            content()
        }
    }
}

/** 글자 창의 글자 쪽: 글자 칸, 크기(8~2000px, 로그 눈금), 색 칸(누르면 같은 창의 팔레트) */
@Composable
private fun TextForm(draft: TextDraft, onChange: (TextDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.text,
            onValueChange = { onChange(draft.copy(text = it.take(TextStyle.MAX_LENGTH))) },
            label = { Text(stringResource(R.string.text_prompt_label)) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag(EditScreenTags.TEXT_FIELD)
        )
        OptionSlider(
            label = stringResource(R.string.brush_size),
            shown = stringResource(R.string.brush_size_value, draft.size.roundToInt()),
            fraction = textSizeToFraction(draft.size),
            onFraction = { onChange(draft.copy(size = fractionToTextSize(it))) },
            tag = EditScreenTags.TEXT_PROMPT_SIZE
        )
        ColorSwatchButton(
            color = draft.color,
            label = stringResource(R.string.text_color),
            description = stringResource(R.string.text_color_value, colorHex(draft.color)),
            tag = EditScreenTags.TEXT_PROMPT_COLOR,
            onClick = { onChange(draft.copy(palette = PaletteDraft.start(ColorSlot.FOREGROUND, draft.color))) }
        )
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








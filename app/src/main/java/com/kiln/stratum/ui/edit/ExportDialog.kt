package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.export.ExportSizes
import com.kiln.stratum.engine.export.MAX_EXPORT_QUALITY
import com.kiln.stratum.engine.export.MIN_EXPORT_QUALITY
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.components.StratumSegment
import com.kiln.stratum.ui.components.StratumSegmentedRow
import com.kiln.stratum.ui.components.StratumSlider
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.StratumDialog
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 창 내용이 이 폭 이상이면 형식과 품질을 한 줄에 둔다(창 높이를 줄여 낮은 화면에서도 스크롤 없이 들어가게). */
private val WideFormWidth = 480.dp

/**
 * 내보내기 창. 크기 변경 창과 같은 방식으로, 커버 화면(좁은 화면)은 아래에서 올라오는 시트,
 * 그 밖에는 화면 가운데의 창이다. 그래서 화면 전체를 덮는 자리에 놓아야 한다.
 * 형식·품질·크기를 고르고([onChoiceChange]) '공유'나 '저장'을 누른다([onExport]). 숫자 칸이 없어 키보드가 뜨지 않는다.
 * [hdr]이면(HDR 문서) HDR 밝기가 남는지 한 줄로 알린다(JPEG만 담긴다).
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportPrompt(
    choice: ExportChoice,
    documentSize: PixelSize,
    widthClass: WidthClass,
    busy: Boolean,
    onChoiceChange: (ExportChoice) -> Unit,
    onDismiss: () -> Unit,
    onExport: (ExportTarget) -> Unit,
    hdr: Boolean = false
) {
    val title = stringResource(R.string.action_export)
    if (widthClass == WidthClass.Compact) {
        // 처음부터 끝까지 펼쳐야 버튼이 가려지지 않는다
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            HideStatusBarInSheet()
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                // 내용만 스크롤되고 버튼은 아래에 고정된다
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ExportForm(choice, documentSize, hdr, onChoiceChange)
                }
                Spacer(Modifier.height(16.dp))
                ExportButtons(busy, onCancel = onDismiss, onExport = onExport, Modifier.align(Alignment.End))
            }
        }
    } else {
        StratumDialog(
            onDismissRequest = onDismiss,
            title = title,
            buttons = { ExportButtons(busy, onCancel = onDismiss, onExport = onExport) }
        ) {
            ExportForm(choice, documentSize, hdr, onChoiceChange)
        }
    }
}

/**
 * 형식·품질, 크기, 안내 한 줄(HDR 문서면 한 줄 더).
 * 폭이 넉넉하면(메인 화면) 형식과 품질을 한 줄에 두어 창을 낮게 한다. 좁으면 위아래로 쌓는다.
 */
@Composable
private fun ExportForm(choice: ExportChoice, documentSize: PixelSize, hdr: Boolean, onChoiceChange: (ExportChoice) -> Unit) {
    BoxWithConstraints {
        val wide = maxWidth >= WideFormWidth
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormatRow(choice, onChoiceChange)
                    QualityRow(choice, onChoiceChange, Modifier.weight(1f))
                }
            } else {
                FormatRow(choice, onChoiceChange)
                QualityRow(choice, onChoiceChange, Modifier.fillMaxWidth())
            }
            SizeRow(choice, documentSize, onChoiceChange)
            val size = choice.settingsFor(documentSize).size
            Text(
                text = stringResource(
                    if (choice.format.keepsTransparency) R.string.export_result else R.string.export_result_jpeg,
                    size.width,
                    size.height
                ),
                color = StratumTheme.colors.textDescription,
                style = MaterialTheme.typography.bodyMedium
            )
            if (hdr) {
                Text(
                    text = stringResource(if (choice.format == ExportFormat.JPEG) R.string.export_hdr_jpeg else R.string.export_hdr_lost),
                    modifier = Modifier.testTag(EditScreenTags.EXPORT_HDR_HINT),
                    color = StratumTheme.colors.textDescription,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 형식: JPEG | PNG | WebP */
@Composable
private fun FormatRow(choice: ExportChoice, onChoiceChange: (ExportChoice) -> Unit) {
    StratumSegmentedRow {
        ExportFormat.entries.forEachIndexed { index, format ->
            StratumSegment(
                selected = choice.format == format,
                onClick = { onChoiceChange(choice.copy(format = format)) },
                index = index,
                count = ExportFormat.entries.size,
                modifier = Modifier.testTag(EditScreenTags.exportFormat(format))
            ) {
                Text(
                    stringResource(
                        when (format) {
                            ExportFormat.JPEG -> R.string.export_format_jpeg
                            ExportFormat.PNG -> R.string.export_format_png
                            ExportFormat.WEBP -> R.string.export_format_webp
                        }
                    ),
                    modifier = Modifier.width(IntrinsicSize.Max),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * 품질: 이름, 슬라이더(1~100), 숫자. PNG는 품질을 쓰지 않아 그 자리에 손실 없이 저장한다고 보인다.
 * 형식을 바꿔도 아래 줄이 움직이지 않게 높이를 48dp로 맞춘다.
 */
@Composable
private fun QualityRow(choice: ExportChoice, onChoiceChange: (ExportChoice) -> Unit, modifier: Modifier) {
    if (!choice.format.hasQuality) {
        Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                stringResource(R.string.export_lossless),
                color = StratumTheme.colors.textDescription,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    val label = stringResource(R.string.export_quality)
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        StratumSlider(
            value = choice.quality.toFloat(),
            onValueChange = { value ->
                val quality = value.roundToInt().coerceIn(MIN_EXPORT_QUALITY, MAX_EXPORT_QUALITY)
                if (quality != choice.quality) onChoiceChange(choice.copy(quality = quality))
            },
            valueRange = MIN_EXPORT_QUALITY.toFloat()..MAX_EXPORT_QUALITY.toFloat(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = label }
                .testTag(EditScreenTags.EXPORT_QUALITY)
        )
        Text(
            text = choice.quality.toString(),
            modifier = Modifier.widthIn(min = 32.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 크기: 원본 | 3840 | 2048 | 1080(고를 수 있는 것만). 고를 것이 하나뿐이면(작은 사진) 줄을 숨긴다.
 * 고른 크기를 이 문서에 쓸 수 없으면 쓸 수 있는 가장 큰 크기가 선택된 것으로 보인다.
 * 칩은 화면 읽기 기능이 체크박스로 읽어서, 하나만 고르는 버튼 줄로 한다.
 */
@Composable
private fun SizeRow(choice: ExportChoice, documentSize: PixelSize, onChoiceChange: (ExportChoice) -> Unit) {
    val choices = ExportSizes.choices(documentSize, choice.format)
    if (choices.size < 2) return
    val selected = choice.sizeFor(documentSize)
    StratumSegmentedRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, size ->
            val description = when (size) {
                ExportSize.Original -> null
                is ExportSize.LongEdge -> stringResource(R.string.export_size_long_edge, size.pixels)
            }
            StratumSegment(
                selected = selected == size,
                onClick = { onChoiceChange(choice.copy(size = size)) },
                index = index,
                count = choices.size,
                // 좁은 화면(메인 가로의 오른쪽 절반)에서도 네 개가 한 줄에 들어가게 안쪽 여백을 줄인다
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .testTag(EditScreenTags.exportSize(size))
                    .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            ) {
                Text(
                    text = when (size) {
                        ExportSize.Original -> stringResource(R.string.export_size_original)
                        is ExportSize.LongEdge -> size.pixels.toString()
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** 취소 · 공유 · 저장. 공유와 저장은 처리 중이면 누를 수 없다. */
@Composable
private fun ExportButtons(
    busy: Boolean,
    onCancel: () -> Unit,
    onExport: (ExportTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
        QuietButton(
            onClick = { onExport(ExportTarget.SHARE) },
            enabled = !busy,
            modifier = Modifier.testTag(EditScreenTags.EXPORT_SHARE)
        ) {
            Text(stringResource(R.string.action_share))
        }
        AccentButton(
            onClick = { onExport(ExportTarget.GALLERY) },
            enabled = !busy,
            modifier = Modifier.testTag(EditScreenTags.EXPORT_SAVE)
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

/**
 * 내보내기 창에서 고르던 것. 창이 열려 있는 동안 접기·펼치기에도 남게 글자·숫자로 저장한다.
 * 창이 닫혀 있으면(null) 저장하지 않는다.
 */
val ExportDraftSaver: Saver<ExportChoice?, List<Any>> = Saver(
    save = { choice ->
        choice?.let { listOf(it.format.name, it.quality, (it.size as? ExportSize.LongEdge)?.pixels ?: 0) }
    },
    restore = {
        val longEdge = it[2] as Int
        ExportChoice(
            format = ExportFormat.valueOf(it[0] as String),
            quality = it[1] as Int,
            size = if (longEdge == 0) ExportSize.Original else ExportSize.LongEdge(longEdge)
        )
    }
)

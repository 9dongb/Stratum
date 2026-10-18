package com.kiln.stratum.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.export.ExportSizes
import com.kiln.stratum.engine.export.MAX_EXPORT_QUALITY
import com.kiln.stratum.engine.export.MIN_EXPORT_QUALITY
import com.kiln.stratum.settings.PreviewQuality
import com.kiln.stratum.settings.SettingsValues
import com.kiln.stratum.ui.layout.HingeAvoidingColumn
import com.kiln.stratum.ui.layout.HingeAvoidingScrollColumn
import com.kiln.stratum.ui.layout.HingeSplitRow
import com.kiln.stratum.ui.layout.WidthClass
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.topBarItem
import com.kiln.stratum.ui.layout.widthClassOf
import com.kiln.stratum.ui.layout.windowInsets
import kotlin.math.roundToInt

/** 설정의 분류 */
enum class SettingsCategory(@get:StringRes val label: Int) {
    EXPORT(R.string.settings_export),
    PREVIEW(R.string.settings_preview),
    GESTURES(R.string.settings_gestures),
    ABOUT(R.string.settings_about)
}

/** 항목 사이 간격 */
private val ItemGap = 12.dp

/** 화면 테스트가 설정 화면의 부분을 찾을 때 쓰는 이름표 */
object SettingsTags {
    const val LIST = "settingsList"
    const val DETAIL = "settingsDetail"
    const val QUALITY = "settingsQuality"
    const val TWO_FINGER_UNDO = "settingsTwoFingerUndo"
    const val LICENSES = "settingsLicenses"

    fun category(category: SettingsCategory) = "settingsCategory_${category.name}"
    fun format(format: ExportFormat) = "settingsFormat_${format.name}"
    fun size(size: ExportSize) = when (size) {
        ExportSize.Original -> "settingsSize_original"
        is ExportSize.LongEdge -> "settingsSize_${size.pixels}"
    }
    fun preview(quality: PreviewQuality) = "settingsPreview_${quality.name}"
}

/**
 * 설정 화면(홈의 설정 버튼). 기획서대로 커버(좁은 화면)는 한 줄 목록, 메인은 2단(왼쪽 분류 · 오른쪽 항목)이다.
 * 2단은 세로 힌지가 가운데를 지나면 힌지 양쪽에 하나씩 놓인다([HingeSplitRow]).
 * 가로 힌지(메인 세로)가 지나면 분류 줄과 항목을 띠 아래로 비켜 놓는다. 커버(한 줄 목록)는 힌지를 보지 않는다.
 * 바꾸는 즉시 저장된다(저장 버튼 없음).
 *
 * @param onChange 설정을 바꾸는 함수를 받는다(앞 값에 걸어 새 값을 만든다)
 * @param versionName 앱 버전(앱 정보에 보인다)
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    values: SettingsValues,
    onChange: ((SettingsValues) -> SettingsValues) -> Unit,
    onBack: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier
) {
    var category by rememberSaveable { mutableStateOf(SettingsCategory.EXPORT) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = widthClassOf(maxWidth.value)
        Scaffold(
            topBar = {
                // 상태 표시줄을 숨겨 바가 맨 위에 붙는다. 카메라 구멍이 제목·버튼에 걸릴 때만 내린다(결정 기록 '전체 화면').
                val barInsets = rememberTopBarInsets(TopAppBarDefaults.TopAppBarExpandedHeight)
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title), Modifier.topBarItem(barInsets, "title")) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.topBarItem(barInsets, "back")) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    windowInsets = barInsets.windowInsets(),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            val body = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            if (widthClass == WidthClass.Compact) {
                // 한 줄 목록: 분류마다 제목과 항목을 차례로
                Column(body.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(SettingsTags.LIST)) {
                    for (entry in SettingsCategory.entries) {
                        SectionTitle(entry.label)
                        Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                            CategoryItems(entry, values, onChange, versionName)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
            } else {
                // 2단: 왼쪽 분류, 오른쪽 그 분류의 항목. 메인 세로에서는 가로 힌지의 띠에 걸리는 줄·항목을 띠 아래로 비켜 놓는다.
                HingeSplitRow(
                    first = {
                        HingeAvoidingColumn(Modifier.fillMaxSize().padding(8.dp).testTag(SettingsTags.LIST)) {
                            for (entry in SettingsCategory.entries) {
                                CategoryRow(entry, selected = entry == category) { category = entry }
                            }
                        }
                    },
                    second = {
                        // 분류를 바꾸면 맨 위부터 보인다
                        key(category) {
                            HingeAvoidingScrollColumn(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(SettingsTags.DETAIL),
                                gap = ItemGap
                            ) {
                                SectionTitle(category.label)
                                CategoryItems(category, values, onChange, versionName)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    },
                    modifier = body,
                    gap = 16.dp
                )
            }
        }
    }
}

/** 2단 왼쪽의 분류 한 줄. 고른 분류는 바탕을 칠한다. 누르는 영역은 높이 56dp다. */
@Composable
private fun CategoryRow(category: SettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 24.dp)
            .testTag(SettingsTags.category(category)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(category.label),
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

/** 분류 제목 */
@Composable
private fun SectionTitle(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall
    )
}

/** [category]의 항목들 */
@Composable
private fun CategoryItems(
    category: SettingsCategory,
    values: SettingsValues,
    onChange: ((SettingsValues) -> SettingsValues) -> Unit,
    versionName: String
) {
    when (category) {
        SettingsCategory.EXPORT -> ExportItems(values, onChange)
        SettingsCategory.PREVIEW -> PreviewItems(values, onChange)
        SettingsCategory.GESTURES -> GestureItems(values, onChange)
        SettingsCategory.ABOUT -> AboutItems(versionName)
    }
}

/**
 * 내보내기 기본값: 형식, 품질(JPEG·WebP), 크기.
 * 항목마다 하나의 덩어리(이름 + 내용)로 내보낸다. 부모가 간격을 두고 쌓는다(2단에서는 가로 힌지를 피해).
 */
@Composable
private fun ExportItems(values: SettingsValues, onChange: ((SettingsValues) -> SettingsValues) -> Unit) {
    val export = values.export
    Description(R.string.settings_export_description)
    LabeledItem(R.string.export_format) {
        SingleChoiceSegmentedButtonRow {
            ExportFormat.entries.forEachIndexed { index, format ->
                SegmentedButton(
                    selected = export.format == format,
                    onClick = { onChange { it.copy(export = it.export.copy(format = format)) } },
                    shape = SegmentedButtonDefaults.itemShape(index, ExportFormat.entries.size),
                    icon = {},
                    modifier = Modifier.testTag(SettingsTags.format(format))
                ) {
                    Text(
                        stringResource(
                            when (format) {
                                ExportFormat.JPEG -> R.string.export_format_jpeg
                                ExportFormat.PNG -> R.string.export_format_png
                                ExportFormat.WEBP -> R.string.export_format_webp
                            }
                        )
                    )
                }
            }
        }
    }
    LabeledItem(R.string.export_quality) {
        if (export.format.hasQuality) {
            QualitySlider(export.quality) { quality -> onChange { it.copy(export = it.export.copy(quality = quality)) } }
        } else {
            Description(R.string.export_lossless)
        }
    }
    LabeledItem(R.string.export_size) {
        val sizes = listOf(ExportSize.Original) + ExportSizes.LONG_EDGE_PRESETS.map { ExportSize.LongEdge(it) }
        SingleChoiceSegmentedButtonRow {
            sizes.forEachIndexed { index, size ->
                // 숫자만 보이므로 화면 읽기 기능에는 '긴 변 N px'로 알린다(내보내기 창과 같다)
                val description = (size as? ExportSize.LongEdge)?.let { stringResource(R.string.export_size_long_edge, it.pixels) }
                SegmentedButton(
                    selected = export.size == size,
                    onClick = { onChange { it.copy(export = it.export.copy(size = size)) } },
                    shape = SegmentedButtonDefaults.itemShape(index, sizes.size),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .testTag(SettingsTags.size(size))
                        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                ) {
                    Text(
                        when (size) {
                            ExportSize.Original -> stringResource(R.string.export_size_original)
                            is ExportSize.LongEdge -> size.pixels.toString()
                        },
                        maxLines = 1
                    )
                }
            }
        }
        Description(R.string.settings_export_size_description)
    }
}

/**
 * 품질 슬라이더와 숫자. 끄는 동안은 숫자만 바꾸고, 손을 떼면 한 번 저장한다(끄는 동안 매번 저장하지 않게).
 * 끄는 도중에 사라지면(접기·펼치기) 놓은 것으로 보고 끌던 값을 저장한다.
 */
@Composable
private fun QualitySlider(quality: Int, onQuality: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val save by rememberUpdatedState(onQuality)
    fun finish() {
        dragging?.let { save(it.roundToInt().coerceIn(MIN_EXPORT_QUALITY, MAX_EXPORT_QUALITY)) }
        dragging = null
    }
    DisposableEffect(Unit) {
        onDispose { finish() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = dragging ?: quality.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = { finish() },
            valueRange = MIN_EXPORT_QUALITY.toFloat()..MAX_EXPORT_QUALITY.toFloat(),
            modifier = Modifier.weight(1f).testTag(SettingsTags.QUALITY)
        )
        Text(
            text = (dragging?.roundToInt() ?: quality).toString(),
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** 미리 보기 화질: 표준 / 가볍게. 줄마다 하나의 항목이다. */
@Composable
private fun PreviewItems(values: SettingsValues, onChange: ((SettingsValues) -> SettingsValues) -> Unit) {
    for (quality in PreviewQuality.entries) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(
                    selected = values.previewQuality == quality,
                    role = Role.RadioButton,
                    onClick = { onChange { it.copy(previewQuality = quality) } }
                )
                .testTag(SettingsTags.preview(quality)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = values.previewQuality == quality, onClick = null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(if (quality == PreviewQuality.STANDARD) R.string.settings_preview_standard else R.string.settings_preview_light),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(if (quality == PreviewQuality.STANDARD) R.string.settings_preview_standard_description else R.string.settings_preview_light_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** 제스처: 두 손가락으로 톡 → 실행 취소 */
@Composable
private fun GestureItems(values: SettingsValues, onChange: ((SettingsValues) -> SettingsValues) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = values.twoFingerTapUndo,
                role = Role.Switch,
                onValueChange = { on -> onChange { it.copy(twoFingerTapUndo = on) } }
            )
            .testTag(SettingsTags.TWO_FINGER_UNDO),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_two_finger_undo), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_two_finger_undo_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = values.twoFingerTapUndo, onCheckedChange = null)
    }
}

/** 앱 정보: 버전, 개인정보, 오픈소스 라이선스(누르면 펼친다) */
@Composable
private fun AboutItems(versionName: String) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    LabeledItem(R.string.settings_version) {
        Text(versionName, style = MaterialTheme.typography.bodyLarge)
    }
    LabeledItem(R.string.settings_privacy) {
        Description(R.string.settings_privacy_description)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = showLicenses, role = Role.Button, onValueChange = { showLicenses = it })
                .testTag(SettingsTags.LICENSES),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_licenses), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(
                painterResource(if (showLicenses) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showLicenses) Description(R.string.settings_licenses_text)
    }
}

/** 항목 이름과 그 아래 내용. 이름이 내용과 떨어지지 않게 한 덩어리로 놓는다. */
@Composable
private fun LabeledItem(@StringRes label: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemLabel(label)
        content()
    }
}

/** 항목 이름 */
@Composable
private fun ItemLabel(@StringRes label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 설명 글 */
@Composable
private fun Description(@StringRes text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

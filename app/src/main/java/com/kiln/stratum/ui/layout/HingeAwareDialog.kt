package com.kiln.stratum.ui.layout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 창 카드의 화면 테스트용 이름표. */
const val HINGE_AWARE_DIALOG_CARD = "hingeAwareDialogCard"

/** 창 카드의 폭 범위와 영역 가장자리와의 간격. Material 대화상자와 같다. */
private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp
private val DialogMargin = 24.dp

/** 창 뒤 화면을 어둡게 덮는 정도. Material 모달 시트와 같다. */
private const val ScrimAlpha = 0.32f

/**
 * 접히는 부분(힌지)을 피해 띄우는 창. Material 대화상자(AlertDialog)와 같은 모양이다.
 *
 * 힌지가 없으면 화면 가운데에 뜬다. 힌지가 있으면 시스템 바·키보드·힌지 띠를 뺀 영역 중 카드가 잘 들어가는 쪽
 * 가운데에 뜬다(같으면 오른쪽·위쪽, [dialogRegion]). 그래서 펼친 폴드에서도 버튼이 힌지에 놓이지 않는다(CLAUDE.md 6장).
 *
 * 별도 창(Window)이 아니라 같은 화면 위에 덮어 그린다. 힌지 좌표가 그대로 맞고, 화면 테스트의 크기 강제도 적용된다.
 * 그래서 화면 전체를 덮는 자리(Box의 마지막 자식 등)에 놓아야 한다.
 * - 바깥(어두운 바탕)을 누르거나 뒤로 가기를 하면 [onDismissRequest]를 부른다. 카드 안을 눌러도 닫히지 않는다.
 * - 화면 읽기 기능은 창 안만 읽고([title]이 창 이름), 키보드 초점은 창 밖으로 나가지 않는다.
 * - 카드의 크기와 자리는 배치하는 순간 자기 위치와 시스템 바·키보드 영역을 읽어 정한다. 한 프레임 늦지 않고,
 *   키보드가 열리고 닫히면 다시 배치된다.
 *
 * [content]는 길면 스크롤되고, [buttons]는 카드 아래에 고정된다(오른쪽 정렬).
 */
@Composable
fun HingeAwareDialog(
    onDismissRequest: () -> Unit,
    title: String,
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val hinges = LocalHinges.current
    BackHandler(onBack = onDismissRequest)
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha)
    Layout(
        contents = listOf(
            {
                // 카드와 형제로 아래에 깔아서, 카드 안을 누른 것은 여기까지 오지 않는다
                Box(
                    Modifier
                        .background(scrimColor)
                        .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                )
            },
            { DialogCard(title, buttons, content) }
        ),
        // 화면 전체를 덮는 '창'이라고 알려, 화면 읽기 기능이 뒤의 화면을 읽지 않게 한다
        modifier = modifier.semantics { dialog() }
    ) { (scrimMeasurables, cardMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scrim = scrimMeasurables.single().measure(Constraints.fixed(width, height))
        val marginPx = DialogMargin.roundToPx()
        val minCardPx = DialogMinWidth.roundToPx()
        val maxCardPx = DialogMaxWidth.roundToPx()
        val hingeMarginPx = HingeMargin.roundToPx()
        layout(width, height) {
            scrim.place(0, 0)
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 카드를 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val safe = WindowInsetsRulers.SafeDrawing.current
            val area = Region(
                left = safe.left.current(0f).roundToInt(),
                top = safe.top.current(0f).roundToInt(),
                right = safe.right.current(width.toFloat()).roundToInt(),
                bottom = safe.bottom.current(height.toFloat()).roundToInt()
            )
            val region = dialogRegion(
                area = area,
                verticalBands = hinges.filter { it.isVertical }
                    .map { it.band(hingeMarginPx).shiftedBy(-origin.x.roundToInt()) },
                horizontalBands = hinges.filter { !it.isVertical }
                    .map { it.band(hingeMarginPx).shiftedBy(-origin.y.roundToInt()) },
                maxCardWidth = maxCardPx + 2 * marginPx
            )
            val maxWidth = minOf(maxCardPx, region.width - 2 * marginPx).coerceAtLeast(0)
            val maxHeight = (region.height - 2 * marginPx).coerceAtLeast(0)
            val card = cardMeasurables.single().measure(
                Constraints(minWidth = minOf(minCardPx, maxWidth), maxWidth = maxWidth, maxHeight = maxHeight)
            )
            card.place(
                region.left + (region.width - card.width) / 2,
                region.top + (region.height - card.height) / 2
            )
        }
    }
}

/** 창 카드: 제목, 스크롤되는 내용, 아래에 고정된 버튼. */
@Composable
private fun DialogCard(title: String, buttons: @Composable () -> Unit, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .testTag(HINGE_AWARE_DIALOG_CARD)
            .semantics { paneTitle = title }
            // 키보드 초점(Tab·방향키)이 창 밖 화면으로 나가지 않게 한다
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                text = title,
                color = AlertDialogDefaults.titleContentColor,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))
            // 카드 높이가 모자라면 내용만 줄어들고 스크롤된다. 버튼은 늘 보인다.
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.align(Alignment.End)) { buttons() }
        }
    }
}

package com.kiln.stratum.ui.layout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 창 카드의 화면 테스트용 이름표. */
const val DIALOG_CARD = "dialogCard"

/** 창 카드의 폭 범위와 영역 가장자리와의 간격. Material 대화상자와 같다. */
private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp
private val DialogMargin = 24.dp

/**
 * 앱의 창(대화상자). Material 대화상자(AlertDialog)와 같은 구성이고, 모양은 앱 테마(모서리 12dp, 패널 색)다.
 *
 * 시스템 바·키보드를 뺀 영역 가운데에 뜬다. 접히는 선은 보지 않는다(사용자 피드백 11차 1: 펼친 화면은 평평한 한 화면으로 쓴다).
 *
 * 별도 창(Window)이 아니라 같은 화면 위에 덮어 그린다. 화면 테스트의 크기 강제가 그대로 적용되고, 상태 표시줄을 숨긴 앱 창의 설정도 그대로다.
 * 그래서 화면 전체를 덮는 자리(Box의 마지막 자식 등)에 놓아야 한다.
 * - 바깥(어두운 바탕)을 누르거나 뒤로 가기를 하면 [onDismissRequest]를 부른다. 카드 안을 눌러도 닫히지 않는다.
 * - 화면 읽기 기능은 창 안만 읽고([title]이 창 이름), 키보드 초점은 창 밖으로 나가지 않는다.
 * - 카드의 크기와 자리는 배치하는 순간 시스템 바·키보드 영역을 읽어 정한다. 한 프레임 늦지 않고,
 *   키보드가 열리고 닫히면 다시 배치된다.
 *
 * [content]는 길면 스크롤되고, [buttons]는 카드 아래에 고정된다(오른쪽 정렬).
 *
 * 두 칸 창(새 캔버스)은 다음을 바꾼다:
 * - [maxWidth]: 카드의 최대 폭(기본은 Material 대화상자의 560dp)
 * - [scrollContent]: false면 내용 전체를 스크롤하지 않고 남는 높이만 준다. 내용의 칸이 스스로 밀어 본다.
 */
@Composable
fun StratumDialog(
    onDismissRequest: () -> Unit,
    title: String,
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = DialogMaxWidth,
    scrollContent: Boolean = true,
    content: @Composable () -> Unit
) {
    BackHandler(onBack = onDismissRequest)
    val scrimColor = StratumTheme.colors.scrim
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
            { DialogCard(title, buttons, scrollContent, content) }
        ),
        // 화면 전체를 덮는 '창'이라고 알려, 화면 읽기 기능이 뒤의 화면을 읽지 않게 한다
        modifier = modifier.semantics { dialog() }
    ) { (scrimMeasurables, cardMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scrim = scrimMeasurables.single().measure(Constraints.fixed(width, height))
        val marginPx = DialogMargin.roundToPx()
        val minCardPx = DialogMinWidth.roundToPx()
        val maxCardPx = maxWidth.roundToPx()
        layout(width, height) {
            scrim.place(0, 0)
            // 시스템 바·키보드를 뺀 영역(이 창의 좌표)
            val safe = WindowInsetsRulers.SafeDrawing.current
            val region = Region(
                left = safe.left.current(0f).roundToInt(),
                top = safe.top.current(0f).roundToInt(),
                right = safe.right.current(width.toFloat()).roundToInt(),
                bottom = safe.bottom.current(height.toFloat()).roundToInt()
            )
            val cardMaxWidth = minOf(maxCardPx, region.width - 2 * marginPx).coerceAtLeast(0)
            val maxHeight = (region.height - 2 * marginPx).coerceAtLeast(0)
            val card = cardMeasurables.single().measure(
                Constraints(minWidth = minOf(minCardPx, cardMaxWidth), maxWidth = cardMaxWidth, maxHeight = maxHeight)
            )
            card.place(
                region.left + (region.width - card.width) / 2,
                region.top + (region.height - card.height) / 2
            )
        }
    }
}

/** 창 카드: 제목, 스크롤되는 내용([scrollContent]가 false면 남는 높이만 주는 내용), 아래에 고정된 버튼. */
@Composable
private fun DialogCard(title: String, buttons: @Composable () -> Unit, scrollContent: Boolean, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .testTag(DIALOG_CARD)
            .semantics { paneTitle = title }
            // 키보드 초점(Tab·방향키)이 창 밖 화면으로 나가지 않게 한다
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        shape = StratumShapes.Dialog,
        color = StratumTheme.colors.panel,
        contentColor = StratumTheme.colors.textPrimary
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                text = title,
                color = StratumTheme.colors.textPrimary,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))
            // 카드 높이가 모자라면 내용만 줄어들고 스크롤된다. 버튼은 늘 보인다.
            Box(Modifier.weight(1f, fill = false).then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                CompositionLocalProvider(LocalContentColor provides StratumTheme.colors.textSecondary) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.align(Alignment.End)) { buttons() }
        }
    }
}

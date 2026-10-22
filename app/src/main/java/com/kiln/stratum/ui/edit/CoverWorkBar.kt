package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.AccentIconButton
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.components.scrollFadeEdges
import com.kiln.stratum.ui.components.stratumTopAppBarColors
import com.kiln.stratum.ui.layout.workTopBarHeight
import com.kiln.stratum.ui.layout.rememberTopBarInsets
import com.kiln.stratum.ui.layout.topBarItem
import com.kiln.stratum.ui.layout.windowInsets
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 닫힌 화면(커버) 새 창의 맨 아래 줄(닫힌 화면 공통 규칙, 결정 기록 '닫힌 화면 새 창'): 왼쪽 X(취소·나가기), 오른쪽 ✓(완료·적용, 파란 채움).
 * 한 손으로 쥐었을 때 엄지가 닿는 아래 양 끝에 둔다. 자르기·픽셀 유동화가 같이 쓰고, 앞으로의 전용 작업 창도 이것을 쓴다.
 * 시스템 뒤로 가기는 X와 같게 부르는 쪽이 처리한다. 아래 시스템 바와 옆쪽 카메라 구멍은 피한다.
 * [cancelTag]·[doneTag]는 화면 테스트 이름표(창마다 지금 쓰는 취소·적용 이름표를 그대로 쓴다).
 * [middle]은 X와 ✓ 사이 가운데에 둘 것(자르기·회전 창의 [자르기 | 회전] 탭 등, 사용자 피드백 8차 1-1: 고르는 탭은 맨 아래 X와 ✓ 사이)이다.
 * [vertical]이면(커버를 가로로 돌림, 사용자 피드백 12차 3) 창 오른쪽 끝의 기둥이다: 위 ✓, 아래 X, [middle]은 그 사이(넘치면 위아래로 밀어 봄).
 * 그때 [middle]은 세로로 쌓은 것을 넘긴다.
 */
@Composable
fun CoverWorkBar(
    onCancel: () -> Unit,
    onDone: () -> Unit,
    doneEnabled: Boolean,
    cancelTag: String,
    doneTag: String,
    modifier: Modifier = Modifier,
    cancelEnabled: Boolean = true,
    vertical: Boolean = false,
    middle: (@Composable () -> Unit)? = null
) {
    val colors = StratumTheme.colors
    val cancel: @Composable () -> Unit = {
        StratumIconButton(onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.testTag(cancelTag)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
        }
    }
    val done: @Composable () -> Unit = {
        AccentIconButton(onClick = onDone, enabled = doneEnabled, modifier = Modifier.testTag(doneTag)) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.action_done))
        }
    }
    if (vertical) {
        Surface(modifier.fillMaxHeight().edgeDivider(DividerEdge.Start, colors.divider), color = colors.panel) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                    .width(coverRowHeight())
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                done()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (middle != null) {
                        val scroll = rememberScrollState()
                        Column(
                            Modifier.scrollFadeEdges(scroll, colors.panel).verticalScroll(scroll),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            middle()
                        }
                    }
                }
                cancel()
            }
        }
    } else {
        Surface(modifier.fillMaxWidth().edgeDivider(DividerEdge.Top, colors.divider), color = colors.panel) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                cancel()
                Spacer(Modifier.weight(1f))
                if (middle != null) {
                    middle()
                    Spacer(Modifier.weight(1f))
                }
                done()
            }
        }
    }
}

/**
 * 닫힌 화면 새 창의 위 줄: 창 이름만 보인다(취소·완료는 맨 아래 [CoverWorkBar]). 편집 화면 상단 바와 같은 높이(상태 표시줄 높이)이고,
 * 카메라 구멍이 이름에 걸리면 구멍 아래로 내린다(편집 화면 상단 바와 같다).
 */
// TopAppBar의 scrollBehavior 매개변수 타입이 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverWindowTopBar(title: String, modifier: Modifier = Modifier) {
    val barHeight = workTopBarHeight()
    val barInsets = rememberTopBarInsets(barHeight)
    TopAppBar(
        title = { Text(title, Modifier.topBarItem(barInsets, "title")) },
        modifier = modifier.edgeDivider(DividerEdge.Bottom, StratumTheme.colors.divider).testTag(EditScreenTags.COVER_WINDOW_TOP_BAR),
        expandedHeight = barHeight,
        windowInsets = barInsets.windowInsets(),
        colors = stratumTopAppBarColors()
    )
}

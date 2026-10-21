package com.kiln.stratum.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.launch

/** 도움말 말풍선의 화면 테스트 이름표 */
const val HELP_TOOLTIP_TAG = "helpTooltip"

/** 물음표 원의 보이는 크기. 누르는 영역은 아이콘 버튼의 48dp다. */
private val HelpIconSize = 20.dp

/**
 * 물음표 도움말 버튼(사용자 피드백 8차 1-7): 작은 물음표 원(보이는 크기 20dp, 누르는 영역 48dp)을 누르면 위에 설명 말풍선([text])이 뜬다
 * (Material의 '정보 아이콘 + 설명 말풍선'). 말풍선은 다른 곳을 누르거나 뒤로 가기로 닫는다. 화면 읽기 기능은 [description]('도움말')으로 읽는다.
 * 조작이 모호한 도구에만 둔다(사용자 결정: AI 제거·리터칭·명암).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpButton(text: String, description: String, modifier: Modifier = Modifier) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val colors = StratumTheme.colors
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                modifier = Modifier.testTag(HELP_TOOLTIP_TAG),
                shape = StratumShapes.Card,
                colors = TooltipDefaults.richTooltipColors(containerColor = colors.panel, contentColor = colors.textPrimary)
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        state = state,
        // 말풍선이 떠 있는 동안 뒤로 가기는 말풍선을 닫는다
        focusable = true,
        // 길게 누르기로는 띄우지 않고, 눌러야 띄운다
        enableUserInput = false
    ) {
        StratumIconButton(onClick = { scope.launch { state.show() } }, modifier = modifier) {
            Icon(painter = painterResource(R.drawable.ic_help_outline), contentDescription = description, modifier = Modifier.size(HelpIconSize))
        }
    }
}

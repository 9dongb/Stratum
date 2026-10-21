package com.kiln.stratum.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

// 앱의 버튼(결정 기록 '디자인(시안)'): 알약 대신 모서리 8dp, 좌우 여백 12dp, 보이는 높이 30dp.
// 누르는 영역과 차지하는 자리는 M3처럼 48dp라 버튼을 바꿔도 배치가 움직이지 않는다.

/** 주요 버튼(내보내기·적용·확인 등): 파란 채움 */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = StratumDimens.ButtonPadding,
    content: @Composable RowScope.() -> Unit
) {
    val colors = StratumTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = StratumDimens.ButtonHeight),
        enabled = enabled,
        shape = StratumShapes.Control,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.divider,
            disabledContentColor = colors.textDisabled
        ),
        contentPadding = contentPadding,
        content = content
    )
}

/** 글자 버튼(취소·모두 초기화 등): 바탕 없이 선택 색 글자. [color]로 글자 색을 바꾼다(지우기는 오류 색). */
@Composable
fun QuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = StratumTheme.colors.selectedContent,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = StratumDimens.ButtonHeight),
        enabled = enabled,
        shape = StratumShapes.Control,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = StratumTheme.colors.textDisabled),
        contentPadding = StratumDimens.ButtonPadding,
        content = content
    )
}

/** 보조 버튼(홈의 새 캔버스 등): 테두리만 있고 기본 글자색 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = StratumDimens.ButtonPadding,
    content: @Composable RowScope.() -> Unit
) {
    val colors = StratumTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = StratumDimens.ButtonHeight),
        enabled = enabled,
        shape = StratumShapes.Control,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary, disabledContentColor = colors.textDisabled),
        border = BorderStroke(StratumDimens.Divider, if (enabled) colors.fieldBorder else colors.divider),
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * 아이콘 아래에 짧은 이름이 있는 동작 버튼(회전·뒤집기, 선택 동작). 아이콘·이름은 보조 색이고 꺼지면 비활성 색이다.
 * M3 글자 버튼과 같은 여백이라 줄 배치가 그대로다. [description]이 있으면 화면 읽기 기능은 그 긴 이름으로 읽는다.
 */
@Composable
fun ToolActionButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 64.dp,
    maxLines: Int = 1,
    iconRotation: Float = 0f,
    description: String? = null
) {
    val colors = StratumTheme.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = StratumShapes.Control,
        colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary, disabledContentColor = colors.textDisabled),
        modifier = modifier
            .widthIn(min = minWidth)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.rotate(iconRotation))
            Text(
                text = label,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = TextAlign.Center,
                maxLines = maxLines,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

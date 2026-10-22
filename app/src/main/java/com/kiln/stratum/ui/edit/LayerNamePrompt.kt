package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.project.ProjectNames
import com.kiln.stratum.ui.components.AccentButton
import com.kiln.stratum.ui.components.QuietButton
import com.kiln.stratum.ui.layout.HideStatusBarInSheet
import com.kiln.stratum.ui.layout.StratumDialog
import com.kiln.stratum.ui.layout.WidthClass

/**
 * 레이어 이름을 바꾸는 창(레이어 메뉴의 '이름 바꾸기', 2026-10-22 사용자 요청).
 * 커버 화면(좁은 화면)은 아래에서 올라오는 시트, 그 밖에는 화면 가운데의 창이다(홈의 프로젝트 이름 바꾸기와 같다).
 * 이름은 프로젝트 이름과 같은 규칙으로 다듬어([ProjectNames.clean]) [onRename]으로 넘긴다. 다듬은 이름이 비거나 편집을 처리하는 중([busy])이면
 * 확인 버튼이 꺼진다(이름 없는 레이어를 만들지 않음). 적은 글자는 접기·펼치기에도 남는다([layerId]마다 따로).
 */
// ModalBottomSheet가 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerNamePrompt(layerId: LayerId, initial: String, widthClass: WidthClass, busy: Boolean, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable(layerId.value) { mutableStateOf(initial) }
    val cleaned = ProjectNames.clean(text)
    val canRename = !busy && cleaned.isNotEmpty()
    val confirm = { if (canRename) onRename(cleaned) }
    val title = stringResource(R.string.layer_rename)
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(ProjectNames.MAX_LENGTH) },
            label = { Text(stringResource(R.string.layer_rename_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth().testTag(EditScreenTags.LAYER_RENAME_FIELD)
        )
    }
    val buttons: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            AccentButton(onClick = confirm, enabled = canRename, modifier = Modifier.testTag(EditScreenTags.LAYER_RENAME_OK)) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
    if (widthClass == WidthClass.Compact) {
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
        StratumDialog(onDismissRequest = onDismiss, title = title, buttons = { buttons(Modifier) }) {
            field()
        }
    }
}

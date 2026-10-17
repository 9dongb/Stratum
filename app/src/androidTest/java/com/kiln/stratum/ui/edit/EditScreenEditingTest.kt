package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 편집 화면의 실행 취소·다시 실행 버튼, 처리 중 표시, 알림 막대를 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditScreenEditingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(400, 300)

    private fun ready(canUndo: Boolean = false, canRedo: Boolean = false, busy: Boolean = false): EditorState.Ready {
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(
            PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)),
            document,
            canUndo = canUndo,
            canRedo = canRedo,
            busy = busy
        )
    }

    private fun show(
        state: EditorState,
        onUndo: () -> Unit = {},
        onRedo: () -> Unit = {},
        messages: Flow<EditorMessage> = emptyFlow()
    ) {
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = state,
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {},
                    onUndo = onUndo,
                    onRedo = onRedo,
                    messages = messages
                )
            }
        }
    }

    @Test
    fun undoAndRedo_followTheHistory() {
        var undos = 0
        show(ready(canUndo = true), onUndo = { undos++ })

        composeRule.onNodeWithTag(EditScreenTags.REDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.UNDO).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(1, undos) }
    }

    @Test
    fun busy_blocksUndoAndRedo_andShowsProgress() {
        show(ready(canUndo = true, canRedo = true, busy = true))

        composeRule.onNodeWithTag(EditScreenTags.UNDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.REDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.BUSY).assertExists()
    }

    @Test
    fun message_showsInTheSnackbar() {
        show(ready(), messages = flowOf(EditorMessage.EDIT_NOT_ENOUGH_MEMORY))

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.edit_not_enough_memory)).assertIsDisplayed()
    }
}

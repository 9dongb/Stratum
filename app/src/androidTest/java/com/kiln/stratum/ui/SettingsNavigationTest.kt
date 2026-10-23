package com.kiln.stratum.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.edit.EditScreenTags
import com.kiln.stratum.ui.edit.EditTool
import com.kiln.stratum.ui.edit.EditorState
import com.kiln.stratum.ui.edit.EditorViewModel
import com.kiln.stratum.ui.edit.deleteTestProjects
import com.kiln.stratum.ui.home.HomeTags
import com.kiln.stratum.ui.home.NewCanvasTags
import com.kiln.stratum.ui.settings.SettingsTags
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설정은 편집 화면 상단 바 맨 오른쪽 버튼으로 열고, 뒤로 가면 보던 편집 화면 그대로 돌아온다(사용자 피드백 11차 4).
 * 문서를 닫지 않고, 고른 도구 같은 화면 상태도 남는다. 홈으로 나가면 화면 상태를 잊는다(다음 문서는 도구 없이 시작).
 * 앱처럼 [StratumApp]에 실제 ViewModel을 넣고 홈의 새 캔버스로 들어간다. 만든 프로젝트는 끝날 때 지운다.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val viewModel = EditorViewModel(context)

    @After
    fun cleanUp() {
        deleteTestProjects(context, viewModel)
    }

    @Test
    fun settingsFromTheEditScreen_backReturnsToTheSameDocumentAndTool() {
        composeRule.setContent { StratumTheme { StratumApp(viewModel) } }
        createSmallCanvas()
        val opened = composeRule.runOnIdle { viewModel.state.value as EditorState.Ready }
        val move = toolTag(EditTool.Move)
        composeRule.onNodeWithTag(move).performClick()
        composeRule.onNodeWithTag(move).assertIsSelected()

        composeRule.onNodeWithTag(EditScreenTags.SETTINGS).performClick()
        composeRule.onNodeWithTag(SettingsTags.LIST).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOP_BAR).assertDoesNotExist()

        // 설정의 뒤로 버튼 → 보던 편집 화면(같은 문서, 이동 도구를 고른 채)
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        composeRule.onNodeWithTag(SettingsTags.LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(toolTag(EditTool.Move)).assertIsSelected()
        composeRule.runOnIdle { assertSame("the document was not reopened", opened, viewModel.state.value) }

        // 시스템 뒤로도 같다
        composeRule.onNodeWithTag(EditScreenTags.SETTINGS).performClick()
        composeRule.onNodeWithTag(SettingsTags.LIST).assertExists()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(SettingsTags.LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(toolTag(EditTool.Move)).assertIsSelected()
    }

    @Test
    fun goingHome_forgetsTheEditScreen() {
        composeRule.setContent { StratumTheme { StratumApp(viewModel) } }
        createSmallCanvas()
        composeRule.onNodeWithTag(toolTag(EditTool.Move)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SETTINGS).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

        // 홈으로(상단 바 뒤로) 나갔다가 새 캔버스를 만들면 도구 없이 시작한다
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        composeRule.onNodeWithTag(HomeTags.TOP_BAR).assertExists()
        createSmallCanvas()
        composeRule.onNodeWithTag(toolTag(EditTool.Move)).assertIsNotSelected()
    }

    /** 홈의 '+'로 작은 새 캔버스를 만들고 편집 화면이 문서를 보일 때까지 기다린다 */
    private fun createSmallCanvas() {
        composeRule.onNodeWithTag(HomeTags.NEW_CANVAS).performClick()
        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).performTextReplacement("400")
        composeRule.onNodeWithTag(NewCanvasTags.HEIGHT).performTextReplacement("300")
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).performClick()
        composeRule.waitUntil(20_000) { (viewModel.state.value as? EditorState.Ready)?.documentSize == PixelSize(400, 300) }
        composeRule.waitForIdle()
        assertEquals(PixelSize(400, 300), (viewModel.state.value as EditorState.Ready).documentSize)
    }

    /** 펼친 화면은 도구 막대, 커버는 탭 줄의 [tool](탭 줄에 있는 도구만: 브러시 등은 커버에서 더보기 줄에 있다) */
    private fun toolTag(tool: EditTool): String {
        val rail = EditScreenTags.railTool(tool)
        return if (composeRule.onAllNodesWithTag(rail).fetchSemanticsNodes().isNotEmpty()) rail else EditScreenTags.tabTool(tool)
    }
}

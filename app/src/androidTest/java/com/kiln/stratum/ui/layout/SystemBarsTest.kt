package com.kiln.stratum.ui.layout

import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 전체 화면(결정 기록 '전체 화면'): 상태 표시줄을 숨긴 창에서 아래 시트를 열어도 상태 표시줄이 다시 나오지 않는지 확인한다.
 * 시트는 화면을 덮는 따로 창이라, 시트 안에서 숨기지 않으면 시트가 앞에 올 때 상태 표시줄이 나온다(아래 두 번째 테스트).
 */
@RunWith(AndroidJUnit4::class)
class SystemBarsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 시트 내용이 붙은 뷰(시트 창) */
    private var sheetView: View? = null

    @Test
    fun hideStatusBar_hidesOnlyTheStatusBar() {
        hideAppStatusBar()

        // 아래 제스처 막대(내비게이션 바)는 그대로다
        assertTrue(composeRule.runOnUiThread { isVisible(composeRule.activity.window.decorView, WindowInsets.Type.navigationBars()) })
    }

    @Test
    fun sheet_keepsTheStatusBarHidden() {
        hideAppStatusBar()
        showSheet(hideInSheet = true)

        // 시트가 앞에 온 뒤에도 잠시 지켜본다(나온다면 시트 창이 앞에 온 직후에 나온다)
        val until = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < until) {
            assertFalse("status bar shown", statusBarVisible(composeRule.activity.window.decorView))
            assertFalse("status bar shown in sheet", statusBarVisible(sheetView!!))
            Thread.sleep(50)
        }
    }

    @Test
    fun sheetWithoutHiding_showsTheStatusBar() {
        hideAppStatusBar()
        showSheet(hideInSheet = false)

        composeRule.waitUntil(5_000) { statusBarVisible(sheetView!!) }
    }

    private fun hideAppStatusBar() {
        composeRule.runOnUiThread { hideStatusBar(composeRule.activity.window) }
        composeRule.waitUntil(5_000) { !statusBarVisible(composeRule.activity.window.decorView) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSheet(hideInSheet: Boolean) {
        composeRule.setContent {
            StratumTheme {
                ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    if (hideInSheet) HideStatusBarInSheet()
                    sheetView = LocalView.current
                    Text("sheet", Modifier.testTag(SHEET))
                }
            }
        }
        composeRule.onNodeWithTag(SHEET).assertExists()
        composeRule.waitForIdle()
        assertNotNull(sheetView)
    }

    private fun statusBarVisible(view: View): Boolean = composeRule.runOnUiThread { isVisible(view, WindowInsets.Type.statusBars()) }

    private fun isVisible(view: View, type: Int): Boolean = view.rootWindowInsets?.isVisible(type) ?: true

    private companion object {
        const val SHEET = "sheet"
    }
}

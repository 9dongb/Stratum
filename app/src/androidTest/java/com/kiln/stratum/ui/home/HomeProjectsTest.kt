package com.kiln.stratum.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.ui.components.LongPressTimes
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 홈의 최근 프로젝트 격자: 칸 수, 열기, 지우기(묻고 나서), 접히는 선 피하기를 확인한다. */
@RunWith(AndroidJUnit4::class)
class HomeProjectsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()

    /** 작은 그림 파일은 없어도 된다(빈 칸으로 보인다). 짝수 번째는 이름이 있고, 홀수 번째는 이름이 없다('제목 없음'). */
    private fun projects(count: Int): List<ProjectInfo> =
        List(count) {
            ProjectInfo(
                id = "p$it",
                width = 4000,
                height = 3000,
                modifiedMillis = 1_792_000_000_000 - it * 60_000L,
                thumbnail = File("/nonexistent/$it.webp"),
                name = if (it % 2 == 0) "Project $it" else ""
            )
        }

    private fun show(projects: List<ProjectInfo>, widthDp: Int = 411, heightDp: Int = 800) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        HomeScreen(
                            onOpenPhoto = {},
                            onCreateCanvas = { _, _, _, _ -> },
                            projects = projects,
                            onOpenProject = { opened += it },
                            onDeleteProjects = { deleted += it },
                            onRenameProject = { id, name -> renamed += id to name }
                        )
                    }
                }
            }
        }
    }

    private fun bounds(id: String): Rect = composeRule.onNodeWithTag(HomeTags.project(id)).fetchSemanticsNode().layoutBoundsInWindow()

    private fun text(id: Int): String = composeRule.activity.getString(id)

    @Test
    fun cover_showsTwoColumns() {
        show(projects(3))

        val (first, second, third) = listOf("p0", "p1", "p2").map(::bounds)
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.right)
        // 셋째는 다음 줄이다
        assertTrue(third.top > first.bottom)
    }

    @Test
    fun mainScreen_showsFourColumns() {
        show(projects(5), widthDp = 701, heightDp = 841)

        val cards = (0 until 5).map { bounds("p$it") }
        for (index in 1 until 4) assertEquals(cards[0].top, cards[index].top, 1f)
        assertTrue(cards[4].top > cards[0].bottom)
    }

    @Test
    fun tap_opensTheProject() {
        show(projects(2))

        composeRule.onNodeWithTag(HomeTags.project("p1")).performClick()

        composeRule.runOnIdle { assertEquals(listOf("p1"), opened) }
    }

    @Test
    fun cardLongPress_isInTheMenuGroup_andOpensAfterThreeTenthsOfASecond() {
        show(projects(2))

        // 사용자 요청: 메뉴 꾹 누르기 묶음은 0.3초
        composeRule.onNodeWithTag(HomeTags.project("p0")).performTouchInput {
            down(center)
            advanceEventTime(LongPressTimes.MENU_MILLIS + 20)
            up()
        }

        composeRule.onNodeWithText(text(R.string.action_delete)).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test
    fun longPressDelete_asksFirst() {
        show(projects(2))

        // 묻는 창에서 취소하면 지우지 않는다
        composeRule.onNodeWithTag(HomeTags.project("p0")).performTouchInput { longClick() }
        composeRule.onNodeWithText(text(R.string.action_delete)).performClick()
        composeRule.onNodeWithText(text(R.string.home_delete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        composeRule.runOnIdle { assertTrue(deleted.isEmpty()) }

        composeRule.onNodeWithTag(HomeTags.project("p0")).performTouchInput { longClick() }
        composeRule.onNodeWithText(text(R.string.action_delete)).performClick()
        composeRule.onNodeWithTag(HomeTags.DELETE_CONFIRM).performClick()

        composeRule.runOnIdle { assertEquals(listOf("p0"), deleted) }
    }

    // 이름으로 화면 읽기 동작을 부르는 테스트 함수가 아직 실험 기능이다
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun screenReaderDelete_alsoAsksFirst() {
        show(projects(1))

        composeRule.onNodeWithTag(HomeTags.project("p0")).performCustomAccessibilityActionWithLabel(text(R.string.action_delete))
        composeRule.onNodeWithTag(HomeTags.DELETE_CONFIRM).performClick()

        composeRule.runOnIdle { assertEquals(listOf("p0"), deleted) }
    }

    @Test
    fun noProjects_showsTheHint() {
        show(emptyList())

        composeRule.onNodeWithText(text(R.string.home_recent_projects_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.PROJECT_GRID).assertDoesNotExist()
    }

    @Test
    fun expanded_cardsAvoidTheVerticalHinge() {
        show(projects(8), widthDp = 930, heightDp = 700)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        // 버튼과 카드 모두 띠를 피한다
        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun cards_showTheNameOrUntitled_andTheSize() {
        show(projects(2))

        composeRule.onNodeWithText("Project 0").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.project_untitled)).assertIsDisplayed()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.home_project_size, 4000, 3000)).assertCountEquals(2)
    }

    @Test
    fun tappingTheName_renames() {
        show(projects(2))

        composeRule.onNodeWithTag(HomeTags.projectName("p0")).performClick()
        composeRule.onNodeWithTag(HomeTags.RENAME_FIELD).performTextReplacement("Sea at dusk")
        composeRule.onNodeWithTag(HomeTags.RENAME_OK).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("p0" to "Sea at dusk"), renamed)
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun longPressRename_alsoRenames() {
        show(projects(1))

        composeRule.onNodeWithTag(HomeTags.project("p0")).performTouchInput { longClick() }
        composeRule.onNodeWithText(text(R.string.home_rename)).performClick()
        composeRule.onNodeWithTag(HomeTags.RENAME_OK).performClick()

        composeRule.runOnIdle { assertEquals(listOf("p0" to "Project 0"), renamed) }
    }

    @Test
    fun select_togglesCards_andDeletesTheChosenOnesAfterAsking() {
        show(projects(3))
        composeRule.onNodeWithTag(HomeTags.DELETE_SELECTED).assertDoesNotExist()

        composeRule.onNodeWithTag(HomeTags.SELECT).performClick()
        composeRule.onNodeWithTag(HomeTags.DELETE_SELECTED).assertIsNotEnabled()
        // 고르는 중에는 눌러도 열지 않고 고르고 푼다
        composeRule.onNodeWithTag(HomeTags.project("p0")).performClick()
        composeRule.onNodeWithTag(HomeTags.project("p2")).performClick()
        composeRule.onNodeWithTag(HomeTags.project("p1")).performClick()
        composeRule.onNodeWithTag(HomeTags.project("p1")).performClick()
        composeRule.onNodeWithText(composeRule.activity.resources.getQuantityString(R.plurals.home_selected_count, 2, 2)).assertIsDisplayed()

        composeRule.onNodeWithTag(HomeTags.DELETE_SELECTED).performClick()
        composeRule.onNodeWithTag(HomeTags.DELETE_CONFIRM).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("p0", "p2"), deleted)
            assertTrue(opened.isEmpty())
        }
        // 지우면 고르기를 끝낸다
        composeRule.onNodeWithTag(HomeTags.SELECT).assertIsDisplayed()
    }

    @Test
    fun back_endsSelecting() {
        show(projects(2))
        composeRule.onNodeWithTag(HomeTags.SELECT).performClick()
        composeRule.onNodeWithTag(HomeTags.project("p0")).performClick()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag(HomeTags.SELECT).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.project("p0")).performClick()
        composeRule.runOnIdle { assertEquals(listOf("p0"), opened) }
    }
}

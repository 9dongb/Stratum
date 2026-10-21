package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.DIALOG_CARD
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 내보내기 창에서 형식·품질·크기를 고르고 저장·공유를 누르는 것과, 화면 배치별 자리를 확인한다. */
@RunWith(AndroidJUnit4::class)
class ExportDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val exports = mutableListOf<Pair<ExportChoice, ExportTarget>>()
    private var lastChoice by mutableStateOf(ExportChoice())

    /** [document] 크기 문서가 열린 상태([hdr]이면 HDR 문서). 축소본은 1/8 단계로 작게 만든다. */
    private fun ready(document: PixelSize, busy: Boolean = false, hdr: Boolean = false): EditorState.Ready {
        val preview = Bitmap.createBitmap((document.width + 7) / 8, (document.height + 7) / 8, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 3)), document, busy = busy, hdr = hdr)
    }

    @Composable
    private fun content(state: EditorState) {
        EditScreen(
            state = state,
            onBack = {},
            onCanvasSize = { _, _ -> },
            onViewport = {},
            onChooseSize = {},
            exportChoice = lastChoice,
            onExport = { choice, target ->
                exports += choice to target
                lastChoice = choice
            }
        )
    }

    private fun show(document: PixelSize = PixelSize(4000, 3000), widthDp: Int = 411, heightDp: Int = 800, busy: Boolean = false, hdr: Boolean = false) {
        val state = ready(document, busy, hdr)
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme { content(state) }
                }
            }
        }
    }

    private fun open() = composeRule.onNodeWithTag(EditScreenTags.EXPORT).performClick()

    private fun text(id: Int, vararg args: Any): String = composeRule.activity.getString(id, *args)

    /** 노드 내용이 스크롤 영역 등 부모에 잘리지 않고 다 보이는지. 잘린 창 좌표의 높이와 원래 높이를 비교한다. */
    private fun assertFullyVisible(interaction: SemanticsNodeInteraction) {
        val coordinates = interaction.fetchSemanticsNode().layoutInfo.coordinates
        assertEquals("visible height", coordinates.size.height.toFloat(), coordinates.boundsInWindow().height, 1f)
    }

    @Test
    fun png_hidesQuality_andJpegTellsAboutWhite() {
        show()
        open()

        // 기본은 JPEG 90, 원본
        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.JPEG)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_QUALITY).assertExists()
        composeRule.onNodeWithText(text(R.string.export_result_jpeg, 4000, 3000)).assertExists()

        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.PNG)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.EXPORT_QUALITY).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.export_lossless)).assertExists()
        composeRule.onNodeWithText(text(R.string.export_result, 4000, 3000)).assertExists()
    }

    @Test
    fun hdrPhoto_tellsThatOnlyJpegKeepsTheHdr() {
        show(hdr = true)
        open()

        composeRule.onNodeWithTag(EditScreenTags.EXPORT_HDR_HINT).assertTextEquals(text(R.string.export_hdr_jpeg))
        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.WEBP)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_HDR_HINT).assertTextEquals(text(R.string.export_hdr_lost))
    }

    @Test
    fun sdrPhoto_hasNoHdrHint() {
        show()
        open()

        composeRule.onNodeWithTag(EditScreenTags.EXPORT_HDR_HINT).assertDoesNotExist()
    }

    @Test
    fun smallPhoto_showsOnlySmallerPresets() {
        show(PixelSize(2048, 1536))
        open()

        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.Original)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(1080))).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(2048))).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(3840))).assertDoesNotExist()
    }

    @Test
    fun tinyPhoto_hidesTheSizeRow() {
        show(PixelSize(1000, 800))
        open()

        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.Original)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.export_result_jpeg, 1000, 800)).assertExists()
    }

    @Test
    fun saveAndShare_sendTheChoice_andTheNextDialogStartsFromIt() {
        show()
        open()
        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.WEBP)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(2048))).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_QUALITY).performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        composeRule.onNodeWithText(text(R.string.export_result, 2048, 1536)).assertExists()

        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).performClick()

        val chosen = ExportChoice(ExportFormat.WEBP, 50, ExportSize.LongEdge(2048))
        composeRule.runOnIdle { assertEquals(listOf(chosen to ExportTarget.GALLERY), exports) }
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertDoesNotExist()

        // 다시 열면 마지막에 고른 것으로 시작한다
        open()
        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.WEBP)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SHARE).performClick()
        composeRule.runOnIdle { assertEquals(chosen to ExportTarget.SHARE, exports.last()) }
    }

    @Test
    fun exportButton_isBlockedWhileBusy() {
        show(busy = true)

        composeRule.onNodeWithTag(EditScreenTags.EXPORT).assertIsNotEnabled()
    }

    @Test
    fun exportButton_isBlockedWhileCropping() {
        show(widthDp = 930, heightDp = 700)
        composeRule.onNodeWithTag(EditScreenTags.EXPORT).assertIsEnabled()

        // 자르기·변형 도구를 고르면 자르기부터 시작한다
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.EXPORT).assertIsNotEnabled()
    }

    @Test
    fun cover_cropWindowHasNoExportButton() {
        show()

        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Transform)).performClick()

        // 닫힌 화면 자르기는 새 창이라 편집 상단 바(내보내기)가 없다(결정 기록 '닫힌 화면 새 창')
        composeRule.onNodeWithTag(EditScreenTags.COVER_WINDOW_TOP_BAR).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT).assertDoesNotExist()
    }

    @Test
    fun cover_usesTheSheet() {
        show()
        open()

        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertExists()
        composeRule.onNodeWithTag(DIALOG_CARD).assertDoesNotExist()
    }

    @Test
    fun medium_everythingIsVisibleWithoutScrolling() {
        show(widthDp = 701, heightDp = 841)

        open()

        // 스크롤하지 않아도 형식·품질·크기·안내가 다 보인다
        assertFullyVisible(composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.JPEG)))
        assertFullyVisible(composeRule.onNodeWithTag(EditScreenTags.EXPORT_QUALITY))
        assertFullyVisible(composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(1080))))
        assertFullyVisible(composeRule.onNodeWithText(text(R.string.export_result_jpeg, 4000, 3000)))
    }

    @Test
    fun expanded_isCentered_evenAcrossAVerticalHinge() {
        // 사용자 피드백 11차 1: 전에는 세로 힌지가 있으면 선 오른쪽에 띄웠다
        show(widthDp = 930, heightDp = 700)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }

        open()

        val card = composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(screen.center.x, card.center.x, 2f)
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertExists()
    }

    @Test
    fun choice_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        val state = ready(PixelSize(4000, 3000))
        tester.setContent { StratumTheme { content(state) } }
        open()
        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.PNG)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(1080))).performClick()

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(EditScreenTags.exportFormat(ExportFormat.PNG)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.exportSize(ExportSize.LongEdge(1080))).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).performClick()
        composeRule.runOnIdle {
            assertEquals(ExportChoice(ExportFormat.PNG, 90, ExportSize.LongEdge(1080)) to ExportTarget.GALLERY, exports.single())
        }
    }
}

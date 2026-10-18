package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.settings.AppSettings
import com.kiln.stratum.settings.PreviewQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 설정(내보내기 기본값, 미리 보기 화질)이 편집 상태에 이어지는지 에뮬레이터에서 확인한다. 앱의 설정은 건드리지 않는다. */
@RunWith(AndroidJUnit4::class)
class EditorSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val settings = AppSettings.inMemory()
    private val viewModel = EditorViewModel(context, SavedStateHandle(), settings)

    /** 테스트가 만든 캔버스는 자동 저장되므로, 끝나면 그 프로젝트들을 지운다 */
    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitState(predicate: (EditorState) -> Boolean): EditorState = runBlocking {
        withTimeout(30_000) { viewModel.state.first(predicate) }
    }

    private fun awaitExportChoice(expected: ExportChoice) = runBlocking {
        withTimeout(10_000) { viewModel.exportChoice.first { it == expected } }
    }

    /** 캔버스 영역 [CANVAS_AREA]에 넓은 흰 캔버스 [WIDE]를 연다. 표준이면 1/2 축소본으로 보인다. */
    private fun openWideCanvas(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(CANVAS_AREA.width, CANVAS_AREA.height)
            viewModel.newCanvas(WIDE.width, WIDE.height, CanvasFill.Solid(Color.WHITE))
        }
        return awaitState { it is EditorState.Ready && it.documentSize == WIDE } as EditorState.Ready
    }

    private fun viewportOf(view: CanvasView): Viewport = view.viewport(CANVAS_AREA, WIDE)

    @Test
    fun exportChoice_startsFromTheSettings_andFollowsThem() {
        assertEquals(ExportChoice(), viewModel.exportChoice.value)

        val png = ExportChoice(ExportFormat.PNG, 90, ExportSize.LongEdge(2048))
        onMain { settings.update { it.copy(export = png) } }

        awaitExportChoice(png)
    }

    @Test
    fun lastExport_winsOverTheSettings() {
        onMain { viewModel.newCanvas(600, 400, CanvasFill.Solid(Color.WHITE)) }
        awaitState { it is EditorState.Ready && it.documentSize == PixelSize(600, 400) }
        val exported = ExportChoice(ExportFormat.WEBP, 70, ExportSize.Original)

        onMain { viewModel.export(exported, ExportTarget.SHARE) }
        runBlocking { withTimeout(30_000) { viewModel.shareRequests.first() } }
        awaitExportChoice(exported)

        // 이번 실행에서 내보낸 뒤에는 설정을 바꿔도 마지막 고르기가 남는다
        onMain { settings.update { it.copy(export = ExportChoice(ExportFormat.PNG)) } }
        Thread.sleep(300)
        assertEquals(exported, viewModel.exportChoice.value)
    }

    @Test
    fun standardPreview_drawsTheScreenSize() {
        assertEquals(1, openWideCanvas().base.region.level)
    }

    @Test
    fun lightPreview_drawsHalfSize_andSharpensOnlyWhenZoomedIn() {
        onMain { settings.update { it.copy(previewQuality = PreviewQuality.LIGHT) } }
        val ready = openWideCanvas()
        assertEquals(2, ready.base.region.level)

        // 맞춤으로 보면 반 크기 축소본을 그대로 둔다
        onMain { viewModel.onViewport(viewportOf(CanvasView.Fit)) }
        Thread.sleep(SHARPEN_DELAY_MILLIS + 700)
        assertNull((viewModel.state.value as EditorState.Ready).detail)

        // 확대하면 보이는 곳을 선명하게 다시 그린다
        onMain { viewModel.onViewport(viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))) }
        val sharpened = awaitState { it is EditorState.Ready && it.detail != null } as EditorState.Ready
        assertEquals(0, checkNotNull(sharpened.detail).region.level)
    }

    @Test
    fun previewQuality_isReadWhenADocumentOpens() {
        assertEquals(1, openWideCanvas().base.region.level)

        onMain { settings.update { it.copy(previewQuality = PreviewQuality.LIGHT) } }
        // 다음에 여는 문서부터 따른다
        onMain { viewModel.newCanvas(WIDE.width, WIDE.height, CanvasFill.Solid(Color.BLACK)) }

        awaitState { it is EditorState.Ready && it.base.region.level == 2 }
    }

    private companion object {
        val CANVAS_AREA = PixelSize(1000, 800)
        val WIDE = PixelSize(2600, 520)
    }
}

package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 자동 저장과 저장한 프로젝트 열기·되살리기·지우기를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorProjectTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val library = ProjectLibrary.get(context)
    private val projectsRoot = File(context.filesDir, "projects")

    /** 테스트마다 빈 프로젝트 모음에서 시작한다 */
    @Before
    @After
    fun deleteProjects() {
        runBlocking { library.awaitSaves() }
        projectsRoot.deleteRecursively()
        runBlocking { library.refresh().join() }
    }

    /** 편집 상태는 메인 스레드에서만 다루므로, 부르는 것도 메인 스레드에서 한다. */
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitState(viewModel: EditorViewModel, predicate: (EditorState) -> Boolean): EditorState = runBlocking {
        withTimeout(30_000) { viewModel.state.first(predicate) }
    }

    private fun awaitReady(viewModel: EditorViewModel, width: Int, height: Int): EditorState.Ready =
        awaitState(viewModel) { it is EditorState.Ready && !it.busy && it.documentSize == PixelSize(width, height) } as EditorState.Ready

    private fun awaitProject(id: String): ProjectInfo = runBlocking {
        withTimeout(30_000) { library.projects.first { list -> list.any { it.id == id } } }.first { it.id == id }
    }

    private fun newCanvas(width: Int, height: Int): EditorViewModel {
        val viewModel = EditorViewModel(context)
        onMain { viewModel.newCanvas(width, height, CanvasFill.Solid(Color.WHITE)) }
        awaitReady(viewModel, width, height)
        return viewModel
    }

    @Test
    fun newCanvas_isSavedAsAProject_withAThumbnail() {
        val viewModel = newCanvas(640, 480)
        val id = checkNotNull(viewModel.projectId)

        runBlocking { library.awaitSaves() }

        val info = awaitProject(id)
        assertEquals(PixelSize(640, 480), PixelSize(info.width, info.height))
        assertTrue(info.thumbnail.exists())
    }

    @Test
    fun edit_isSavedAfterAPause_andReopensWithoutUndo() {
        val viewModel = newCanvas(640, 480)
        val id = checkNotNull(viewModel.projectId)
        runBlocking { library.awaitSaves() }

        onMain { viewModel.edit(RotateImage(clockwise = true)) }
        awaitReady(viewModel, 480, 640)
        // 편집이 멈추고 조금 지나면 저장한다
        Thread.sleep(EditorViewModel.AUTOSAVE_DELAY_MILLIS + 500)
        runBlocking { library.awaitSaves() }

        val reopened = EditorViewModel(context)
        onMain { reopened.openProject(id) }
        val ready = awaitReady(reopened, 480, 640)
        // 실행 취소 기록은 남기지 않는다(결정 기록 '자동 저장')
        assertFalse(ready.canUndo)
        assertEquals(id, reopened.projectId)
    }

    @Test
    fun close_savesTheLastEdit() {
        val viewModel = newCanvas(640, 480)
        val id = checkNotNull(viewModel.projectId)
        onMain { viewModel.edit(RotateImage(clockwise = true)) }
        awaitReady(viewModel, 480, 640)

        // 저장을 기다리는 사이에 바로 홈으로 나간다
        onMain { viewModel.close() }
        runBlocking { library.awaitSaves() }

        assertEquals(PixelSize(480, 640), awaitProject(id).let { PixelSize(it.width, it.height) })
        val reopened = EditorViewModel(context)
        onMain { reopened.openProject(id) }
        awaitReady(reopened, 480, 640)
    }

    @Test
    fun restoredViewModel_reopensTheProject() {
        val first = newCanvas(700, 500)
        val id = checkNotNull(first.projectId)
        runBlocking { library.awaitSaves() }

        // 안드로이드가 앱을 종료했다가 되살린 것처럼, 저장된 상태에 프로젝트 번호만 있는 새 ViewModel을 만든다
        lateinit var restored: EditorViewModel
        onMain { restored = EditorViewModel(context, SavedStateHandle(mapOf(EditorViewModel.KEY_PROJECT to id))) }

        awaitReady(restored, 700, 500)
        assertEquals(id, restored.projectId)
    }

    @Test
    fun brokenProject_showsTheFailure() {
        val id = UUID.randomUUID().toString()
        File(projectsRoot, id).mkdirs()
        File(File(projectsRoot, id), "project.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        val viewModel = EditorViewModel(context)

        onMain { viewModel.openProject(id) }

        assertEquals(EditorState.Failed(EditorFailure.PROJECT_UNREADABLE), awaitState(viewModel) { it is EditorState.Failed })
    }

    @Test
    fun deletedProject_leavesTheList() {
        val viewModel = newCanvas(320, 240)
        val id = checkNotNull(viewModel.projectId)
        onMain { viewModel.close() }
        runBlocking { library.awaitSaves() }
        awaitProject(id)

        runBlocking { library.delete(id).join() }

        assertTrue(library.projects.value.none { it.id == id })
        assertFalse(File(projectsRoot, id).exists())
    }
}

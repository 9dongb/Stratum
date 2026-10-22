package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.raster.PixelSize
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 사진 넣기(사용자 피드백 12차: 레이어 패널의 사진 넣기)를 편집 흐름(ViewModel)에서 확인한다:
 * 고른 레이어 바로 위 새 레이어로 문서 가운데에 넣고 그 레이어를 고르며, 실행 취소 한 단계다. 사진이 아니면 알리고 그대로다.
 * 끌어다 놓은 사진(사용자 피드백 12차 4)은 먼저 앱 임시 폴더에 복사하고 권한을 놓은 뒤 넣고(홈이면 연다), 복사본은 지운다.
 */
@RunWith(AndroidJUnit4::class)
class EditorInsertPhotoTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)
    private val dir = File(context.cacheDir, "insert-photo-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        deleteTestProjects(context, viewModel)
        dir.deleteRecursively()
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    private fun openCanvas(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(CANVAS)) }
        return awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
    }

    /** 캔버스 축소본의 문서 좌표 ([x], [y]) 픽셀 */
    private fun pixel(ready: EditorState.Ready, x: Int, y: Int): Int {
        val bitmap = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val level = ready.base.region.level
        return bitmap.getPixel(x shr level, y shr level)
    }

    /** 한 가지 색 PNG 파일의 주소 */
    private fun png(name: String, width: Int, height: Int, color: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun insert_putsThePhotoInTheMiddleAboveTheActiveLayer_andPicksIt_inOneUndoStep() {
        openCanvas()
        onMain { viewModel.insertPhoto(png("blue.png", 100, 50, Color.BLUE)) }
        val inserted = awaitReady { !it.busy && it.layers.size == 2 }

        val top = inserted.layers.last()
        assertEquals(top.id, inserted.activeLayer)
        // 파일 주소는 표시 이름이 없어 '사진 N'(N은 다음 레이어 번호)
        assertEquals(context.getString(R.string.layer_photo_name, 1), top.name)
        // 가운데 (150, 125)~(250, 175)는 파랑, 그 밖은 캔버스 색
        assertEquals(Color.BLUE, pixel(inserted, 200, 150))
        assertEquals(CANVAS, pixel(inserted, 10, 10))
        assertEquals(CANVAS, pixel(inserted, 200, 110))
        assertTrue(inserted.canUndo)

        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.layers.size == 1 }
        assertEquals(LayerId(0), undone.activeLayer)
        assertEquals(CANVAS, pixel(undone, 200, 150))
    }

    @Test
    fun aFileThatIsNotAPhoto_isReported_andTheDocumentStays() {
        val opened = openCanvas()
        val file = File(dir, "note.png").apply { writeText("not a photo") }

        onMain { viewModel.insertPhoto(Uri.fromFile(file)) }

        assertEquals(EditorMessage.PhotoUnreadable, runBlocking { withTimeout(10_000) { viewModel.messages.first() } })
        val now = viewModel.state.value as EditorState.Ready
        assertEquals(1, now.layers.size)
        assertEquals(opened.revision, now.revision)
    }

    @Test
    fun aDroppedPhoto_isCopiedFirst_thenInserted_andTheCopyIsRemoved() {
        openCanvas()
        var released = 0
        // 놓을 때 받은 권한은 복사한 뒤 바로 놓는다(끌어 놓기, 사용자 피드백 12차 4)
        onMain { viewModel.insertDropped(png("dropped.png", 100, 50, Color.GREEN)) { released++ } }
        val inserted = awaitReady { !it.busy && it.layers.size == 2 }

        assertEquals(1, released)
        assertEquals(Color.GREEN, pixel(inserted, 200, 150))
        assertEquals(inserted.layers.last().id, inserted.activeLayer)
        // 앱 임시 폴더의 복사본은 넣은 뒤 지운다
        val dropped = File(context.cacheDir, EditorViewModel.DROPPED_DIR)
        val left = (dropped.listFiles() ?: emptyArray()).filter { it.isFile }
        assertTrue("$left should be removed", left.isEmpty())
    }

    @Test
    fun aDroppedPhotoOnHome_opensAsANewProject() {
        var released = 0
        onMain { viewModel.openDropped(png("opened.png", 120, 80, Color.RED)) { released++ } }
        val opened = awaitReady { !it.busy && it.documentSize == PixelSize(120, 80) }

        assertEquals(1, released)
        assertEquals(1, opened.layers.size)
        assertEquals(Color.RED, pixel(opened, 60, 40))
    }

    @Test
    fun aDroppedFileThatIsNotAPhoto_isReported() {
        openCanvas()
        val file = File(dir, "dropped-note.png").apply { writeText("not a photo") }
        var released = 0

        onMain { viewModel.insertDropped(Uri.fromFile(file)) { released++ } }

        assertEquals(EditorMessage.PhotoUnreadable, runBlocking { withTimeout(10_000) { viewModel.messages.first() } })
        assertEquals(1, released)
        assertEquals(1, (viewModel.state.value as EditorState.Ready).layers.size)
    }

    private companion object {
        val CANVAS = Color.rgb(200, 120, 40)
    }
}

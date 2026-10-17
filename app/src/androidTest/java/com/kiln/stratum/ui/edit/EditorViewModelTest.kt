package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.raster.PixelSize
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 편집 화면 상태를 에뮬레이터에서 확인한다.
 * 새 캔버스를 만들고 바꾸는지, 확대해 보면 보이는 곳을 선명하게 다시 그리는지, 편집·실행 취소·다시 실행이 맞게 도는지 본다.
 */
@RunWith(AndroidJUnit4::class)
class EditorViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    /** 편집 상태는 메인 스레드에서만 다루므로, 부르는 것도 메인 스레드에서 한다. */
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    /** [predicate]를 만족하는 상태가 될 때까지 기다린다. */
    private fun awaitState(predicate: (EditorState) -> Boolean): EditorState = runBlocking {
        withTimeout(30_000) { viewModel.state.first(predicate) }
    }

    private fun readyWithSize(width: Int, height: Int): (EditorState) -> Boolean = {
        it is EditorState.Ready && it.documentSize == PixelSize(width, height)
    }

    @Test
    fun newCanvas_becomesReadyWithDocumentSize() {
        onMain { viewModel.newCanvas(1001, 801, CanvasFill.Solid(Color.WHITE)) }

        val state = awaitState { it is EditorState.Ready || it is EditorState.Failed }

        assertEquals(PixelSize(1001, 801), (state as EditorState.Ready).documentSize)
    }

    @Test
    fun newCanvas_replacesTheOpenDocument() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Transparent) }
        awaitState(readyWithSize(640, 480))

        onMain { viewModel.newCanvas(300, 200, CanvasFill.Solid(Color.BLACK)) }

        awaitState(readyWithSize(300, 200))
    }

    /** 캔버스 영역 [CANVAS_AREA]에 넓은 흰 캔버스 [WIDE]를 연다. 화면에 맞추면 1/2 축소본으로 보인다. */
    private fun openWideCanvas(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(CANVAS_AREA.width, CANVAS_AREA.height)
            viewModel.newCanvas(WIDE.width, WIDE.height, CanvasFill.Solid(Color.WHITE))
        }
        return awaitState(readyWithSize(WIDE.width, WIDE.height)) as EditorState.Ready
    }

    private fun viewportOf(view: CanvasView): Viewport = view.viewport(CANVAS_AREA, WIDE)

    @Test
    fun zoomedIn_sharpensVisiblePart_fitDropsIt() {
        val ready = openWideCanvas()
        assertEquals(1, ready.base.region.level)
        assertNull(ready.detail)

        // 400%로 문서 가운데를 본다. 1/2 축소본을 8배로 키우면 흐리므로, 보이는 곳을 원본 단계로 다시 그린다.
        val zoomed = viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))
        onMain { viewModel.onViewport(zoomed) }

        val sharpened = awaitState { it is EditorState.Ready && it.detail != null } as EditorState.Ready
        val detail = checkNotNull(sharpened.detail)
        assertEquals(0, detail.region.level)
        assertTrue("${detail.region.rect} does not cover ${zoomed.visible}", zoomed.visible in detail.region.rect)
        assertEquals(detail.region.previewWidth, detail.bitmap.width)
        assertEquals(detail.region.previewHeight, detail.bitmap.height)
        assertEquals(Bitmap.Config.HARDWARE, detail.bitmap.config)
        // 전체 축소본은 그대로다
        assertSame(ready.base, sharpened.base)

        // 화면에 맞추면 부분 그림이 필요 없어 지운다
        onMain { viewModel.onViewport(viewportOf(CanvasView.Fit)) }
        awaitState { it is EditorState.Ready && it.detail == null }
    }

    @Test
    fun close_whileSharpening_staysEmpty() {
        openWideCanvas()
        onMain { viewModel.onViewport(viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))) }

        // 기다림이 끝나 그리기를 시작할 즈음 닫는다
        Thread.sleep(SHARPEN_DELAY_MILLIS)
        onMain { viewModel.close() }

        // 그리던 것이 끝날 만큼 기다려도, 늦게 끝난 그림이 빈 상태를 덮지 않는다
        Thread.sleep(1_000)
        assertEquals(EditorState.Empty, viewModel.state.value)
    }

    @Test
    fun close_returnsToEmpty() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Transparent) }
        awaitState(readyWithSize(640, 480))

        onMain { viewModel.close() }

        assertEquals(EditorState.Empty, viewModel.state.value)
    }

    /** 편집을 마치고(바쁘지 않음) [revision]보다 새 문서를 보이는 상태가 될 때까지 기다린다. */
    private fun awaitEdited(after: Long, predicate: (EditorState.Ready) -> Boolean = { true }): EditorState.Ready =
        awaitState { it is EditorState.Ready && !it.busy && it.revision > after && predicate(it) } as EditorState.Ready

    @Test
    fun rotate_swapsSize_andUndoRedoSwitchBack() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Solid(Color.WHITE)) }
        val opened = awaitState(readyWithSize(640, 480)) as EditorState.Ready
        assertFalse(opened.canUndo)

        onMain { viewModel.edit(RotateImage(clockwise = true)) }
        val rotated = awaitEdited(opened.revision)
        assertEquals(PixelSize(480, 640), rotated.documentSize)
        assertTrue(rotated.canUndo)
        assertFalse(rotated.canRedo)

        onMain { viewModel.undo() }
        val undone = awaitEdited(rotated.revision)
        assertEquals(PixelSize(640, 480), undone.documentSize)
        assertFalse(undone.canUndo)
        assertTrue(undone.canRedo)

        onMain { viewModel.redo() }
        assertEquals(PixelSize(480, 640), awaitEdited(undone.revision).documentSize)
    }

    @Test
    fun secondEditWhileBusy_isIgnored() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Solid(Color.WHITE)) }
        val opened = awaitState(readyWithSize(640, 480)) as EditorState.Ready

        // 두 번 눌러도 처리하는 동안이라 한 번만 돈다
        onMain {
            viewModel.edit(RotateImage(clockwise = true))
            viewModel.edit(RotateImage(clockwise = true))
        }

        assertEquals(PixelSize(480, 640), awaitEdited(opened.revision).documentSize)
        onMain { viewModel.undo() }
        assertFalse(awaitEdited(opened.revision + 1).canUndo)
    }

    @Test
    fun flip_dropsSharpDetail_thenSharpensTheNewDocument() {
        openWideCanvas()
        onMain { viewModel.onViewport(viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))) }
        val sharpened = awaitState { it is EditorState.Ready && it.detail != null } as EditorState.Ready

        onMain { viewModel.edit(FlipImage(horizontal = true)) }

        // 크기는 같아도 문서가 바뀌었으니, 앞 문서의 부분 그림은 버리고 새 문서로 다시 그린다
        val flipped = awaitEdited(sharpened.revision)
        assertTrue(flipped.detail !== sharpened.detail)
        val sharpenedAgain = awaitState { it is EditorState.Ready && it.revision == flipped.revision && it.detail != null }
        assertTrue((sharpenedAgain as EditorState.Ready).detail !== sharpened.detail)
    }

    @Test
    fun notEnoughMemory_keepsTheDocumentAndTellsTheScreen() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Solid(Color.WHITE)) }
        val opened = awaitState(readyWithSize(640, 480)) as EditorState.Ready

        onMain { viewModel.edit(OutOfMemoryCommand) }

        assertEquals(EditorMessage.EditNotEnoughMemory, runBlocking { withTimeout(10_000) { viewModel.messages.first() } })
        val after = awaitState { it is EditorState.Ready && !it.busy } as EditorState.Ready
        assertEquals(opened.revision, after.revision)
        assertFalse(after.canUndo)
    }

    @Test
    fun close_whileEditing_staysEmpty() {
        openStripedPhoto(2000, 1500)

        // 편집을 시작하자마자 닫는다
        onMain {
            viewModel.edit(RotateImage(clockwise = true))
            viewModel.close()
        }

        // 계산하던 것이 끝날 만큼 기다려도, 늦게 끝난 결과가 빈 상태를 덮지 않는다
        Thread.sleep(2_000)
        assertEquals(EditorState.Empty, viewModel.state.value)
    }

    /** 공유 요청을 기다린다. */
    private fun awaitShare(): ShareRequest = runBlocking { withTimeout(30_000) { viewModel.shareRequests.first() } }

    /** 다음 알림을 기다린다. */
    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(30_000) { viewModel.messages.first() } }

    @Test
    fun exportToShare_keepsTheDocument_andSendsTheChosenSize() {
        val opened = openStripedPhoto(1200, 900)
        val choice = ExportChoice(ExportFormat.PNG, 90, ExportSize.LongEdge(1080))

        onMain { viewModel.export(choice, ExportTarget.SHARE) }

        val request = awaitShare()
        assertEquals("image/png", request.mimeType)
        val bytes = checkNotNull(context.contentResolver.openInputStream(request.uri)).use { it.readBytes() }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(PixelSize(1080, 810), PixelSize(decoded.width, decoded.height))
        // 문서와 실행 취소 기록은 그대로이고, 바쁨이 풀린다. 고른 것은 기억한다.
        val after = awaitState { it is EditorState.Ready && !it.busy } as EditorState.Ready
        assertEquals(opened.revision, after.revision)
        assertEquals(PixelSize(1200, 900), after.documentSize)
        assertFalse(after.canUndo)
        assertEquals(choice, viewModel.exportChoice.value)
    }

    @Test
    fun secondExportWhileBusy_isIgnored() {
        openStripedPhoto(1200, 900)

        onMain {
            viewModel.export(ExportChoice(ExportFormat.JPEG, 90, ExportSize.LongEdge(1080)), ExportTarget.SHARE)
            viewModel.export(ExportChoice(ExportFormat.PNG), ExportTarget.SHARE)
        }

        assertEquals("image/jpeg", awaitShare().mimeType)
        awaitState { it is EditorState.Ready && !it.busy }
        assertNull(runBlocking { withTimeoutOrNull(1_500) { viewModel.shareRequests.first() } })
        assertEquals(ExportFormat.JPEG, viewModel.exportChoice.value.format)
    }

    @Test
    fun exportWithoutMemory_tellsTheScreen_andKeepsTheDocument() {
        val opened = openStripedPhoto(600, 400)
        viewModel.availableMemory = { 0L }

        onMain { viewModel.export(ExportChoice(), ExportTarget.GALLERY) }

        assertEquals(EditorMessage.ExportNotEnoughMemory, awaitMessage())
        val after = awaitState { it is EditorState.Ready && !it.busy } as EditorState.Ready
        assertEquals(opened.revision, after.revision)
    }

    @Test
    fun exportToGallery_tellsTheScreen() {
        openStripedPhoto(600, 400)
        val startSeconds = System.currentTimeMillis() / 1000

        onMain { viewModel.export(ExportChoice(ExportFormat.JPEG, 80), ExportTarget.GALLERY) }

        val saved = awaitMessage() as EditorMessage.ExportSaved
        assertEquals("image/jpeg", saved.mimeType)
        // 테스트가 갤러리에 만든 사진을 찾아 지운다. 하나만 만들었다.
        assertEquals(1, deleteExportedSince(startSeconds))
    }

    @Test
    fun exportToGallery_keepsTheOpenedPhoto_andAddsACopy() {
        // 갤러리에 원본 사진을 하나 넣고, 사진 선택기로 고른 것처럼 그 주소로 연다
        val pixels = IntArray(300 * 200) { if (it % 300 < 150) Color.RED else Color.BLUE }
        val png = java.io.ByteArrayOutputStream().also {
            check(Bitmap.createBitmap(pixels, 300, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray()
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val original = checkNotNull(resolver.insert(collection, android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "StratumOriginal_${System.nanoTime()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/StratumTest")
        }))
        val startSeconds = System.currentTimeMillis() / 1000
        try {
            checkNotNull(resolver.openOutputStream(original)).use { it.write(png) }
            // 갤러리가 새 파일을 훑어 수정 시각을 적을 때까지 기다린다(바로 읽으면 0이다)
            val deadline = System.currentTimeMillis() + 10_000
            while (dateModified(original) == 0L && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val modifiedBefore = dateModified(original)
            assertTrue(modifiedBefore > 0)
            onMain { viewModel.openPhoto(original) }
            awaitState(readyWithSize(300, 200))

            onMain { viewModel.export(ExportChoice(ExportFormat.JPEG, 90), ExportTarget.GALLERY) }
            val saved = awaitMessage() as EditorMessage.ExportSaved

            // 원본 파일은 내용도 수정 시각도 그대로다
            org.junit.Assert.assertArrayEquals(png, checkNotNull(resolver.openInputStream(original)).use { it.readBytes() })
            assertEquals(modifiedBefore, dateModified(original))
            // 사본은 사진/Stratum 폴더에 따로 하나 생겼다
            val copies = checkNotNull(resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                arrayOf("Pictures/Stratum/", startSeconds.toString()),
                null
            )).use { cursor -> List(cursor.count) { cursor.moveToPosition(it); cursor.getLong(0) } }
            assertEquals(1, copies.size)
            assertTrue(copies.single() != android.content.ContentUris.parseId(original))
            // 알림의 '보기'는 그 사본을 연다
            assertEquals(copies.single(), android.content.ContentUris.parseId(saved.uri))
        } finally {
            resolver.delete(original, null, null)
            deleteExportedSince(startSeconds)
        }
    }

    /** 갤러리 항목의 수정 시각(초) */
    private fun dateModified(uri: Uri): Long =
        checkNotNull(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    @Test
    fun close_whileExporting_staysEmpty_andDropsTheShare() {
        openStripedPhoto(2000, 1500)

        onMain {
            viewModel.export(ExportChoice(ExportFormat.PNG), ExportTarget.SHARE)
            viewModel.close()
        }

        // 내보내던 것이 끝날 만큼 기다려도 빈 상태이고, 공유 창을 띄우라는 요청도 오지 않는다
        Thread.sleep(2_000)
        assertEquals(EditorState.Empty, viewModel.state.value)
        assertNull(runBlocking { withTimeoutOrNull(500) { viewModel.shareRequests.first() } })
    }

    /** 갤러리의 사진/Stratum 폴더에 [startSeconds] 뒤에 생긴 사진을 지우고 그 수를 돌려준다. */
    private fun deleteExportedSince(startSeconds: Long): Int {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        return context.contentResolver.delete(collection, selection, arrayOf("Pictures/Stratum/", startSeconds.toString()))
    }

    /** 세로줄 사진(한 가지 색이 아니라 회전할 때 타일을 새로 만든다)을 파일로 만들어 연다. */
    private fun openStripedPhoto(width: Int, height: Int): EditorState.Ready {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pixels = IntArray(width * height) { if (it % width % 2 == 0) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "striped.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode" } }
        onMain { viewModel.openPhoto(Uri.fromFile(file)) }
        return awaitState(readyWithSize(width, height)) as EditorState.Ready
    }

    /** 메모리가 모자란 것처럼 실패하는 테스트용 편집. */
    private object OutOfMemoryCommand : CancellableCommand {
        override fun applyTo(document: Document, checkCanceled: () -> Unit): Document = throw NotEnoughMemoryException()
    }

    private companion object {
        val CANVAS_AREA = PixelSize(1000, 800)
        val WIDE = PixelSize(2600, 520)
    }
}

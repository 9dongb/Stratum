package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.history.EditHistory
import com.kiln.stratum.engine.importing.ImportFailure
import com.kiln.stratum.engine.importing.ImportPlan
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.importing.ImportSizing
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.RenderedImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.export.ExportStorage
import com.kiln.stratum.export.exportFileName
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 보기가 이만큼(밀리초) 멈춰 있으면 보이는 곳을 선명하게 다시 그린다. 움직이는 동안 그려 화면이 멈칫하지 않게 한다. */
internal const val SHARPEN_DELAY_MILLIS = 150L

/** 편집 화면에 보여 줄 실패 까닭. 화면이 알맞은 문구를 고른다. */
enum class EditorFailure {
    /** 사진 파일을 읽을 수 없다. */
    PHOTO_UNREADABLE,

    /** 메모리가 부족해 사진을 열 수 없다. */
    PHOTO_NOT_ENOUGH_MEMORY,

    /** 메모리가 부족해 새 캔버스를 만들 수 없다. */
    CANVAS_NOT_ENOUGH_MEMORY
}

/** 편집 화면에 잠깐 알릴 일. 화면이 알맞은 문구를 골라 알림 막대로 보여 준다. */
sealed interface EditorMessage {

    /** 메모리가 부족해 편집하지 못했다. 문서는 그대로다. */
    data object EditNotEnoughMemory : EditorMessage

    /**
     * 갤러리의 사진/Stratum 폴더에 사본([uri])을 저장했다. 원본 사진은 그대로다.
     * 화면은 '보기'로 그 사본을 바로 열어 볼 수 있게 한다.
     */
    data class ExportSaved(val uri: Uri, val mimeType: String) : EditorMessage

    /** 메모리가 부족해 내보내지 못했다. 더 작은 크기는 될 수 있다. */
    data object ExportNotEnoughMemory : EditorMessage

    /** 파일을 쓰지 못해 내보내지 못했다. */
    data object ExportFailed : EditorMessage
}

/** 공유 창으로 보낼 사진([uri])과 그 형식. 화면이 공유 창을 띄운다. */
data class ShareRequest(val uri: Uri, val mimeType: String)

/**
 * 엔진이 그린 축소본 한 장과, 그것이 덮는 문서 영역·단계. 비트맵 픽셀 하나가 문서 픽셀 2^단계개다.
 * [bitmap]은 하드웨어 비트맵이고, 화면이 그리고 있을 수 있어 ViewModel이 버릴 때 recycle()하지 않는다.
 */
data class PreviewImage(val bitmap: Bitmap, val region: PreviewRegion)

/** 편집 화면에 보여 줄 상태. */
sealed interface EditorState {

    /** 열린 문서가 없다(백그라운드 종료 뒤 돌아오면 이렇다. 자동 저장 때 해결). */
    data object Empty : EditorState

    /** 사진을 여는 중이다. */
    data object Opening : EditorState

    /** 새 캔버스를 만드는 중이다. */
    data object Creating : EditorState

    /**
     * 큰 사진이라 원본 크기로 열지 줄여서 열지 묻는다.
     * [originalAllowed]가 false면 기기 메모리로는 원본 크기가 무리라 '원본 크기'를 끈다.
     */
    data class AskSize(
        val originalMegapixels: Int,
        val originalAllowed: Boolean,
        val reducedMegapixels: Int
    ) : EditorState

    /**
     * 문서가 열렸다. [base]는 엔진이 그린 문서 전체 축소본이다.
     * 축소본은 크기를 올림해서 줄이므로, 화면에 맞출 때는 문서의 원래 크기 [documentSize]를 쓴다.
     * [detail]은 확대해서 볼 때 보이는 곳만 선명하게 다시 그린 그림이다. 없으면 [base]만 그린다.
     *
     * @property revision 보이는 문서가 바뀔 때마다(편집·실행 취소·다시 실행) 늘어나는 번호.
     *   화면은 자르기 테두리처럼 문서마다 새로 시작할 상태를 이것으로 구별한다. 화면 크기 때문에 다시 그린 것은 같은 번호다.
     * @property busy 편집을 계산하거나 그 결과를 그리는 중이다. 화면은 편집 버튼을 막고 처리 중 표시를 보인다.
     */
    data class Ready(
        val base: PreviewImage,
        val documentSize: PixelSize,
        val detail: PreviewImage? = null,
        val revision: Long = 0,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val busy: Boolean = false
    ) : EditorState

    /** 문서를 열거나 만들지 못했다. */
    data class Failed(val reason: EditorFailure) : EditorState
}

/**
 * 편집 화면이 쓰는 엔진 상태를 들고 있다. 접기·펼치기로 Activity가 다시 만들어져도 남는다.
 *
 * 편집 상태의 원본은 엔진의 [EditHistory]이고, 이 클래스는 그것을 들고 화면에 상태를 알려 주기만 한다.
 * 무거운 일(해독, 축소본 그리기)은 백그라운드에서 하고, [EditHistory]는 메인 스레드에서만 만들고 다룬다.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _messages = Channel<EditorMessage>(Channel.BUFFERED)

    /** 잠깐 알릴 일. 화면이 하나씩 받아 보여 준다. 화면이 다시 만들어지는 동안 온 것은 기다렸다가 전한다. */
    val messages: Flow<EditorMessage> = _messages.receiveAsFlow()

    private val _shareRequests = Channel<ShareRequest>(Channel.BUFFERED)

    /** 공유 창으로 보낼 사진. 화면이 하나씩 받아 공유 창을 띄운다. 화면이 없는 동안 온 것은 기다렸다가 전한다. */
    val shareRequests: Flow<ShareRequest> = _shareRequests.receiveAsFlow()

    private val _exportChoice = MutableStateFlow(ExportChoice())

    /** 마지막으로 내보낸 고르기. 내보내기 창이 이것으로 시작한다. 앱을 끌 때까지 남는다(다른 문서를 열어도). */
    val exportChoice: StateFlow<ExportChoice> = _exportChoice.asStateFlow()

    /** 지금 더 쓸 수 있는 메모리(바이트). 테스트가 메모리 부족을 흉내 낼 수 있게 바꿀 수 있다. */
    internal var availableMemory: () -> Long = { MemoryBudget.availableBytes(getApplication()) }

    /** 실행 취소 기록과 지금 문서. 메인 스레드에서만 다룬다. */
    private var history: EditHistory? = null

    /** 사진을 여는 작업. 끝나도 지우지 않는다. 다음에 열 때 이 작업이 끝날 때까지 기다려 메모리가 겹치지 않게 한다. */
    private var openJob: Job? = null

    /** 축소본을 다시 그리는 작업(화면 크기가 바뀔 때). */
    private var previewJob: Job? = null

    /** 보이는 곳을 선명하게 다시 그리는 작업. 보기가 바뀔 때마다 앞 작업을 취소하고 새로 시작한다. */
    private var detailJob: Job? = null

    /**
     * 축소본(전체·부분)은 한 번에 하나만 그린다. 취소된 작업도 그리던 묶음은 마치므로,
     * 다음 작업은 그것이 끝날 때까지 기다려 GPU 메모리가 겹치지 않게 한다.
     */
    private val renderMutex = Mutex()

    /** 화면이 마지막으로 알려 준 보기. 문서를 닫거나 새로 열면 지운다. */
    private var viewport: Viewport? = null

    /** 편집·실행 취소·다시 실행을 계산하고 그 결과를 그리는 작업. */
    private var editJob: Job? = null

    /** 편집을 처리하는 중인지. 이 동안에는 다른 편집·실행 취소·다시 실행을 받지 않는다. */
    private var editing = false

    /** 화면(Ready)에 보이는 문서. 기록의 지금 문서와 다르면 아직 새 축소본을 그리는 중이다. */
    private var shownDocument: Document? = null

    /** 보이는 문서가 바뀔 때마다 늘리는 번호([EditorState.Ready.revision]). */
    private var revision = 0L

    /** 사용자가 크기를 고르기를 기다리는 사진. */
    private var pendingPhoto: PendingPhoto? = null

    /** 캔버스 영역의 크기(픽셀). 화면이 알려 주기 전에는 기기 화면 크기로 짐작한다. */
    private var canvasSize: PixelSize? = null

    /** 지금 보이는 축소본의 단계. 없으면 -1. */
    private var previewLevel = -1

    /** 축소본을 그릴 GPU 메모리가 없을 때 보여 줄 까닭. 사진을 열었는지, 새 캔버스를 만들었는지에 따라 다르다. */
    private var memoryFailure = EditorFailure.PHOTO_NOT_ENOUGH_MEMORY

    private class PendingPhoto(val source: ImageDecoder.Source, val plan: ImportPlan)

    /** [uri] 사진을 연다. 열려 있던 문서는 닫는다. */
    fun openPhoto(uri: Uri) {
        previewJob?.cancel()
        detailJob?.cancel()
        pendingPhoto = null
        val previous = openJob
        val previousEdit = editJob
        openJob = viewModelScope.launch {
            // 앞 작업의 해독은 중간에 멈출 수 없어서, 끝날 때까지 기다린 뒤 시작한다
            previous?.cancelAndJoin()
            previousEdit?.cancelAndJoin()
            release()
            memoryFailure = EditorFailure.PHOTO_NOT_ENOUGH_MEMORY
            _state.value = EditorState.Opening
            val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
            val info = withContext(Dispatchers.Default) { PhotoImporter.readInfo(source) }
            if (info == null) {
                _state.value = EditorState.Failed(EditorFailure.PHOTO_UNREADABLE)
                return@launch
            }
            val plan = ImportSizing.plan(
                original = info.size,
                mimeType = info.mimeType,
                budgetBytes = MemoryBudget.importBudgetBytes(getApplication()),
                maxDecodeBytes = PhotoImporter.decodeLimitBytes
            )
            when {
                plan.cannotOpen -> _state.value = EditorState.Failed(EditorFailure.PHOTO_NOT_ENOUGH_MEMORY)
                plan.needsChoice -> {
                    pendingPhoto = PendingPhoto(source, plan)
                    _state.value = EditorState.AskSize(
                        originalMegapixels = plan.original.megapixels,
                        originalAllowed = plan.originalFits,
                        reducedMegapixels = checkNotNull(plan.reducedSize).megapixels
                    )
                }
                else -> importAndShow(source, sampleSize = 1)
            }
        }
    }

    /** 큰 사진 선택 창에서 고른 크기로 연다. [original]이면 원본 크기, 아니면 줄여서 연다. */
    fun chooseSize(original: Boolean) {
        val photo = pendingPhoto ?: return
        if (original && !photo.plan.originalFits) return
        pendingPhoto = null
        val sampleSize = if (original) 1 else checkNotNull(photo.plan.reducedSampleSize)
        val previous = openJob
        openJob = viewModelScope.launch {
            previous?.join()
            _state.value = EditorState.Opening
            importAndShow(photo.source, sampleSize)
        }
    }

    /**
     * [width]×[height] 빈 새 캔버스를 [fill]로 채워 연다. 열려 있던 문서는 닫는다.
     * 크기는 CanvasLimits 안이어야 한다(새 캔버스 창이 검사하고 넘긴다).
     */
    fun newCanvas(width: Int, height: Int, fill: CanvasFill) {
        previewJob?.cancel()
        detailJob?.cancel()
        pendingPhoto = null
        val previous = openJob
        val previousEdit = editJob
        openJob = viewModelScope.launch {
            // 앞에서 사진을 여는 중이면 해독이 끝날 때까지 기다려 메모리가 겹치지 않게 한다
            previous?.cancelAndJoin()
            previousEdit?.cancelAndJoin()
            release()
            memoryFailure = EditorFailure.CANVAS_NOT_ENOUGH_MEMORY
            _state.value = EditorState.Creating
            val layerName = getApplication<Application>().getString(R.string.layer_background)
            val document = withContext(Dispatchers.Default) { BlankCanvas.create(width, height, fill, layerName) }
            if (document == null) {
                _state.value = EditorState.Failed(EditorFailure.CANVAS_NOT_ENOUGH_MEMORY)
                return@launch
            }
            history = newHistory(document)
            showPreview(document)
        }
    }

    /** 캔버스 영역의 크기(픽셀)가 정해지거나 바뀌었다. 축소본 단계가 달라지면 다시 그린다. */
    fun onCanvasSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        canvasSize = PixelSize(width, height)
        val document = history?.document ?: return
        if (_state.value !is EditorState.Ready) return
        if (levelFor(document) == previewLevel) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch { showPreview(document) }
    }

    /**
     * 화면이 지금 보기를 알린다. 두 손가락으로 움직이는 동안에는 매 프레임, 화면 크기가 바뀌거나 맞춤으로 돌아갈 때도 알린다.
     * 보기가 [SHARPEN_DELAY_MILLIS] 동안 그대로면 보이는 곳을 선명하게 다시 그린다. 그사이 또 바뀌면 앞 계획은 취소한다.
     */
    fun onViewport(viewport: Viewport) {
        this.viewport = viewport
        planDetail()
    }

    /**
     * [command]로 문서를 편집한다. 무거운 계산은 백그라운드에서 하고, 다 되면 실행 취소 기록에 남기고 새 축소본을 그린다.
     * 다른 편집·실행 취소·다시 실행을 처리하는 중이면 무시한다(두 번 눌러도 한 번만 된다).
     * 메모리가 모자라면 오래된 실행 취소 단계부터 버리고, 그래도 모자라면 편집하지 않고 알린다.
     */
    fun edit(command: EditCommand) {
        val history = history ?: return
        if (editing || _state.value !is EditorState.Ready) return
        startEditing()
        val before = history.document
        editJob = viewModelScope.launch {
            val after = try {
                // 축소본을 그리는 일과 메모리가 겹치지 않게 같은 잠금 안에서 계산한다
                renderMutex.withLock {
                    makeRoom(history, command, before)
                    withContext(Dispatchers.Default) {
                        if (command is CancellableCommand) command.applyTo(before) { ensureActive() } else command.applyTo(before)
                    }
                }
            } catch (e: NotEnoughMemoryException) {
                Log.w(TAG, "Not enough memory for $command", e)
                finishEditing(EditorMessage.EditNotEnoughMemory)
                return@launch
            }
            if (!history.commit(command, before, after)) {
                // 바뀐 것이 없다
                finishEditing()
                return@launch
            }
            if (!showPreview(after, finishEdit = true)) {
                // 새 축소본을 그리지 못하면 편집을 되돌려 화면과 기록을 맞춘다(다시 실행할 수 있게 남는다)
                history.undo()
                finishEditing(EditorMessage.EditNotEnoughMemory)
            }
        }
    }

    /**
     * 지금 문서를 [choice]대로 [target]에 내보낸다. 문서와 실행 취소 기록은 바꾸지 않는다(CLAUDE.md 5장).
     * 다른 편집·내보내기를 처리하는 중이면 무시한다(두 번 눌러도 한 번만 된다).
     *
     * - 그리기는 축소본을 그리는 일과 같은 잠금 안에서 하고, 압축·쓰기는 그 밖에서 한다.
     * - 압축까지 메모리가 모자랄 것 같으면 실행 취소 기록은 버리지 않고 알린다. 더 작은 크기는 될 수 있다.
     * - 갤러리에 저장하면 알리고, 공유면 화면이 공유 창을 띄우게 한다([shareRequests]).
     * - 홈으로 나가면 취소되고, 쓰다 만 항목·파일은 지워진다. 압축은 중간에 멈추지 못해 끝난 뒤 지운다.
     */
    fun export(choice: ExportChoice, target: ExportTarget) {
        val history = history ?: return
        if (editing || _state.value !is EditorState.Ready) return
        _exportChoice.value = choice
        val document = history.document
        val settings = choice.settingsFor(PixelSize(document.width, document.height))
        startEditing()
        editJob = viewModelScope.launch {
            val message = try {
                if (settings.estimatedBytes > availableMemory()) throw NotEnoughMemoryException()
                val image = renderMutex.withLock {
                    withContext(Dispatchers.Default) { ImageExporter.render(document, settings) { ensureActive() } }
                }
                image.use { write(it, target) }
            } catch (e: NotEnoughMemoryException) {
                Log.w(TAG, "Not enough memory to export $settings", e)
                EditorMessage.ExportNotEnoughMemory
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory to encode $settings", e)
                EditorMessage.ExportNotEnoughMemory
            } catch (e: IOException) {
                Log.w(TAG, "Could not export $settings", e)
                EditorMessage.ExportFailed
            }
            finishEditing(message)
            // 내보내는 동안 멈춘 부분 그림을 다시 계획한다
            planDetail()
        }
    }

    /** 그린 [image]를 압축해 [target]에 쓴다. 갤러리면 알릴 일을 돌려주고, 공유면 공유 요청을 보내고 null을 돌려준다. */
    private suspend fun write(image: RenderedImage, target: ExportTarget): EditorMessage? {
        val app = getApplication<Application>()
        val format = image.settings.format
        val name = exportFileName(LocalDateTime.now(), format)
        return when (target) {
            ExportTarget.GALLERY -> {
                val uri = withContext(Dispatchers.IO) {
                    ExportStorage.saveToGallery(app, name, format.mimeType) { out -> image.writeTo(out) { ensureActive() } }
                }
                EditorMessage.ExportSaved(uri, format.mimeType)
            }
            ExportTarget.SHARE -> {
                val uri = withContext(Dispatchers.IO) {
                    ExportStorage.writeShareFile(app, name) { out -> image.writeTo(out) { ensureActive() } }
                }
                // 메인 스레드에서 보낸다. 그사이 문서를 닫았으면 위 withContext가 취소로 끝나 보내지 않는다.
                _shareRequests.trySend(ShareRequest(uri, format.mimeType))
                null
            }
        }
    }

    /** 한 단계 실행 취소한다. 편집을 처리하는 중이면 무시한다. */
    fun undo() = moveInHistory(step = EditHistory::undo, revert = EditHistory::redo)

    /** 실행 취소한 단계를 다시 실행한다. 편집을 처리하는 중이면 무시한다. */
    fun redo() = moveInHistory(step = EditHistory::redo, revert = EditHistory::undo)

    /** 기록에서 한 단계 옮기고([step]) 그 문서의 축소본을 그린다. 그리지 못하면 [revert]로 되돌린다. */
    private fun moveInHistory(step: (EditHistory) -> Boolean, revert: (EditHistory) -> Boolean) {
        val history = history ?: return
        if (editing || _state.value !is EditorState.Ready) return
        if (!step(history)) return
        startEditing()
        previewJob?.cancel()
        val document = history.document
        editJob = viewModelScope.launch {
            if (!showPreview(document, finishEdit = true)) {
                revert(history)
                finishEditing(EditorMessage.EditNotEnoughMemory)
            }
        }
    }

    /** 편집을 시작한다. 화면은 곧바로 편집 버튼을 막고, 하던 부분 그림은 멈춘다. */
    private fun startEditing() {
        editing = true
        detailJob?.cancel()
        (_state.value as? EditorState.Ready)?.let { _state.value = it.copy(busy = true) }
    }

    /** 편집을 마친다(새 축소본을 그리지 않은 경우). 화면은 그대로 두고 버튼만 다시 켠다. [message]가 있으면 알린다. */
    private fun finishEditing(message: EditorMessage? = null) {
        editing = false
        val history = history
        (_state.value as? EditorState.Ready)?.let {
            _state.value = it.copy(busy = false, canUndo = history?.canUndo == true, canRedo = history?.canRedo == true)
        }
        if (message != null) _messages.trySend(message)
    }

    /**
     * 편집 전에 메모리를 확인한다. 새로 생길 메모리만큼 실행 취소 기록에서 오래된 단계를 버리고,
     * 기기에 남은 메모리(버린 만큼 더해서)로도 모자라면 [NotEnoughMemoryException]을 던진다.
     */
    private fun makeRoom(history: EditHistory, command: EditCommand, before: Document) {
        val needed = (command as? CancellableCommand)?.estimateNewBytes(before) ?: 0L
        if (needed <= 0L) return
        val used = history.bytesInUse
        val remaining = history.releaseOldSteps((history.maxBytes - needed).coerceAtLeast(0L))
        // 버린 단계의 타일은 가비지 컬렉터가 곧 돌려준다
        val available = MemoryBudget.availableBytes(getApplication()) + (used - remaining)
        if (needed > available) throw NotEnoughMemoryException()
    }

    /** 문서를 닫고 메모리를 비운다. 홈으로 돌아갈 때 부른다. */
    fun close() {
        // 작업은 취소만 하고 지우지 않는다. 다음에 열 때 끝날 때까지 기다린다.
        openJob?.cancel()
        previewJob?.cancel()
        detailJob?.cancel()
        editJob?.cancel()
        pendingPhoto = null
        // 아직 화면이 받지 않은 공유 요청은 버린다. 다음에 편집 화면이 열릴 때 뜬금없이 공유 창이 뜨지 않게 한다.
        while (_shareRequests.tryReceive().isSuccess) Unit
        release()
        _state.value = EditorState.Empty
    }

    private suspend fun importAndShow(source: ImageDecoder.Source, sampleSize: Int) {
        val layerName = getApplication<Application>().getString(R.string.layer_background)
        val result = withContext(Dispatchers.Default) {
            PhotoImporter.import(source, layerName, sampleSize) { ensureActive() }
        }
        when (result) {
            is ImportResult.Failure -> _state.value = EditorState.Failed(
                when (result.reason) {
                    ImportFailure.NOT_ENOUGH_MEMORY -> EditorFailure.PHOTO_NOT_ENOUGH_MEMORY
                    ImportFailure.UNREADABLE -> EditorFailure.PHOTO_UNREADABLE
                }
            )
            is ImportResult.Success -> {
                history = newHistory(result.document)
                showPreview(result.document)
            }
        }
    }

    /**
     * [document]의 축소본을 그려 화면에 보인다. 보였으면 true.
     * [finishEdit]이면 편집(실행 취소·다시 실행 포함)을 마치는 그리기라, 보일 때 편집 버튼을 다시 켠다.
     *
     * 그리는 동안 기록의 지금 문서가 다른 것으로 바뀌었으면 보이지 않는다. 그 문서를 그리는 쪽이 따로 있다.
     * 그리지 못하면, 처음 여는 문서는 실패 화면을 보이고 이미 보이던 문서가 있으면 화면을 그대로 둔다.
     */
    private suspend fun showPreview(document: Document, finishEdit: Boolean = false): Boolean {
        val level = levelFor(document)
        renderMutex.withLock {
            val preview = withContext(Dispatchers.Default) {
                PreviewRenderer.render(document, level) { ensureActive() }
            }
            if (preview == null) {
                // GPU가 축소본을 그리지 못했다. 대개 그래픽 메모리가 모자랄 때다.
                Log.w(TAG, "Could not render preview level $level for ${document.width}x${document.height}")
                if (_state.value !is EditorState.Ready) _state.value = EditorState.Failed(memoryFailure)
                return false
            }
            val history = history
            if (history == null || history.document !== document) return false
            previewLevel = level
            // 접기·펼치기로 축소본을 다시 그리는지 확인할 수 있게 남긴다(adb logcat -s EditorViewModel)
            Log.d(TAG, "Preview level $level: ${preview.width}x${preview.height} for ${document.width}x${document.height}")
            val size = PixelSize(document.width, document.height)
            val shown = _state.value as? EditorState.Ready
            val sameDocument = shown != null && document === shownDocument
            if (!sameDocument) {
                revision++
                shownDocument = document
            }
            // 화면 크기가 바뀌어 같은 문서를 다시 그린 것이면, 부분 그림이 새 축소본보다 선명할 때만 남긴다.
            // 문서가 바뀌었으면(뒤집기처럼 크기가 같아도) 버린다.
            val detail = if (sameDocument) shown?.detail?.takeIf { it.region.level < level } else null
            if (finishEdit) editing = false
            // 앞 축소본은 화면이 아직 그리고 있을 수 있어 recycle()하지 않고 가비지 컬렉터에 맡긴다
            _state.value = EditorState.Ready(
                base = PreviewImage(preview, PreviewLevels.wholeRegion(size, level)),
                documentSize = size,
                detail = detail,
                revision = revision,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                busy = editing
            )
        }
        // 축소본 단계가 바뀌었을 수 있어, 마지막 보기로 부분 그림을 다시 계획한다
        planDetail()
        return true
    }

    /** 앞 계획을 취소하고, 보기가 [SHARPEN_DELAY_MILLIS] 동안 그대로면 부분 그림을 고친다. */
    private fun planDetail() {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            delay(SHARPEN_DELAY_MILLIS)
            renderMutex.withLock { updateDetail() }
        }
    }

    /**
     * 마지막 보기에 맞춰 보이는 곳을 선명하게 다시 그리거나, 필요 없으면 지운다. [renderMutex] 안에서 부른다.
     * 지금 부분 그림이 보이는 곳을 이미 덮으면 그대로 둔다. 그리지 못하면(메모리 부족) 흐린 그림을 그대로 둔다.
     */
    private suspend fun updateDetail() {
        val ready = _state.value as? EditorState.Ready ?: return
        val document = history?.document ?: return
        // 편집 중이거나 새 문서의 축소본을 아직 그리는 중이면, 그 축소본을 그린 뒤 다시 계획한다
        if (editing || document !== shownDocument) return
        val viewport = viewport ?: return
        val size = PixelSize(document.width, document.height)
        // 다른 문서를 보던 때의 보기는 쓰지 않는다
        if (viewport.documentSize != size || ready.documentSize != size) return
        val planned = PreviewLevels.detailRegion(size, ready.base.region.level, viewport.scale, viewport.visible)
        val current = ready.detail
        if (planned == null) {
            if (current != null) _state.value = ready.copy(detail = null)
            return
        }
        if (current != null && PreviewLevels.canReuse(current.region, planned, size, viewport.visible)) return
        val bitmap = withContext(Dispatchers.Default) {
            PreviewRenderer.renderRegion(document, planned) { ensureActive() }
        }
        if (bitmap == null) {
            Log.w(TAG, "Could not render detail level ${planned.level} at ${planned.rect}")
            return
        }
        // 그리는 동안 문서를 닫았거나 다른 문서를 열었으면 버린다. 화면에 보인 적 없는 그림이라 바로 비운다.
        val latest = _state.value
        if (latest !is EditorState.Ready || history?.document !== document) {
            bitmap.recycle()
            return
        }
        Log.d(TAG, "Detail level ${planned.level}: ${bitmap.width}x${bitmap.height} at ${planned.rect}")
        // 앞 부분 그림은 화면이 아직 그리고 있을 수 있어 recycle()하지 않는다
        _state.value = latest.copy(detail = PreviewImage(bitmap, planned))
    }

    private companion object {
        const val TAG = "EditorViewModel"
    }

    private fun levelFor(document: Document): Int {
        val canvas = canvasSize ?: getApplication<Application>().resources.displayMetrics.let {
            PixelSize(it.widthPixels, it.heightPixels)
        }
        return PreviewLevels.levelFor(PixelSize(document.width, document.height), canvas)
    }

    /** 새 문서의 실행 취소 기록. 메모리 한도는 기기 메모리에 맞춘다. */
    private fun newHistory(document: Document): EditHistory =
        EditHistory(document, maxBytes = MemoryBudget.historyBudgetBytes(getApplication()))

    private fun release() {
        history = null
        previewLevel = -1
        viewport = null
        editing = false
        shownDocument = null
    }
}

package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiln.stratum.R
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.history.EditHistory
import com.kiln.stratum.engine.importing.ImportFailure
import com.kiln.stratum.engine.importing.ImportPlan
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.importing.ImportSizing
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 편집 화면에 보여 줄 상태. */
sealed interface EditorState {

    /** 열린 문서가 없다(새 캔버스는 아직 없고, 백그라운드 종료 뒤 돌아와도 이렇다). */
    data object Empty : EditorState

    /** 사진을 여는 중이다. */
    data object Opening : EditorState

    /**
     * 큰 사진이라 원본 크기로 열지 줄여서 열지 묻는다.
     * [originalAllowed]가 false면 기기 메모리로는 원본 크기가 무리라 '원본 크기'를 끈다.
     */
    data class AskSize(
        val originalMegapixels: Int,
        val originalAllowed: Boolean,
        val reducedMegapixels: Int
    ) : EditorState

    /** 사진을 열었다. [preview]는 엔진이 그린 화면용 축소본(하드웨어 비트맵)이다. */
    data class Ready(val preview: Bitmap) : EditorState

    /** 사진을 열지 못했다. */
    data class Failed(val reason: ImportFailure) : EditorState
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

    /** 실행 취소 기록과 지금 문서. 메인 스레드에서만 다룬다. */
    private var history: EditHistory? = null

    /** 사진을 여는 작업. 끝나도 지우지 않는다. 다음에 열 때 이 작업이 끝날 때까지 기다려 메모리가 겹치지 않게 한다. */
    private var openJob: Job? = null

    /** 축소본을 다시 그리는 작업(화면 크기가 바뀔 때). */
    private var previewJob: Job? = null

    /** 사용자가 크기를 고르기를 기다리는 사진. */
    private var pendingPhoto: PendingPhoto? = null

    /** 캔버스 영역의 크기(픽셀). 화면이 알려 주기 전에는 기기 화면 크기로 짐작한다. */
    private var canvasSize: PixelSize? = null

    /** 지금 보이는 축소본의 단계. 없으면 -1. */
    private var previewLevel = -1

    private class PendingPhoto(val source: ImageDecoder.Source, val plan: ImportPlan)

    /** [uri] 사진을 연다. 열려 있던 문서는 닫는다. */
    fun openPhoto(uri: Uri) {
        previewJob?.cancel()
        pendingPhoto = null
        val previous = openJob
        openJob = viewModelScope.launch {
            // 앞 작업의 해독은 중간에 멈출 수 없어서, 끝날 때까지 기다린 뒤 시작한다
            previous?.cancelAndJoin()
            release()
            _state.value = EditorState.Opening
            val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
            val info = withContext(Dispatchers.Default) { PhotoImporter.readInfo(source) }
            if (info == null) {
                _state.value = EditorState.Failed(ImportFailure.UNREADABLE)
                return@launch
            }
            val plan = ImportSizing.plan(
                original = info.size,
                mimeType = info.mimeType,
                budgetBytes = MemoryBudget.importBudgetBytes(getApplication()),
                maxDecodeBytes = PhotoImporter.decodeLimitBytes
            )
            when {
                plan.cannotOpen -> _state.value = EditorState.Failed(ImportFailure.NOT_ENOUGH_MEMORY)
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

    /** 문서를 닫고 메모리를 비운다. 홈으로 돌아갈 때 부른다. */
    fun close() {
        // 작업은 취소만 하고 지우지 않는다. 다음에 열 때 끝날 때까지 기다린다.
        openJob?.cancel()
        previewJob?.cancel()
        pendingPhoto = null
        release()
        _state.value = EditorState.Empty
    }

    private suspend fun importAndShow(source: ImageDecoder.Source, sampleSize: Int) {
        val layerName = getApplication<Application>().getString(R.string.layer_background)
        val result = withContext(Dispatchers.Default) {
            PhotoImporter.import(source, layerName, sampleSize) { ensureActive() }
        }
        when (result) {
            is ImportResult.Failure -> _state.value = EditorState.Failed(result.reason)
            is ImportResult.Success -> {
                history = EditHistory(result.document)
                showPreview(result.document)
            }
        }
    }

    private suspend fun showPreview(document: Document) {
        val level = levelFor(document)
        val preview = withContext(Dispatchers.Default) {
            PreviewRenderer.render(document, level) { ensureActive() }
        }
        if (preview == null) {
            // GPU가 축소본을 그리지 못했다. 대개 그래픽 메모리가 모자랄 때다.
            _state.value = EditorState.Failed(ImportFailure.NOT_ENOUGH_MEMORY)
            return
        }
        previewLevel = level
        // 접기·펼치기로 축소본을 다시 그리는지 확인할 수 있게 남긴다(adb logcat -s EditorViewModel)
        Log.d(TAG, "Preview level $level: ${preview.width}x${preview.height} for ${document.width}x${document.height}")
        // 앞 축소본은 화면이 아직 그리고 있을 수 있어 recycle()하지 않고 가비지 컬렉터에 맡긴다
        _state.value = EditorState.Ready(preview)
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

    private fun release() {
        history = null
        previewLevel = -1
    }
}

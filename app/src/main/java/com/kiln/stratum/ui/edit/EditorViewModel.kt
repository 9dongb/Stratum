package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.CancellableCommand
import com.kiln.stratum.engine.command.DeleteLayer
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.command.EditSequence
import com.kiln.stratum.engine.command.MoveLayer
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.command.SetLayerAdjustments
import com.kiln.stratum.engine.command.SetLayerBlendMode
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.history.EditHistory
import com.kiln.stratum.engine.importing.ImportFailure
import com.kiln.stratum.engine.importing.ImportPlan
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.importing.ImportSizing
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.CopySelectedToLayer
import com.kiln.stratum.engine.platform.DeleteSelected
import com.kiln.stratum.engine.platform.Deselect
import com.kiln.stratum.engine.platform.InvertSelection
import com.kiln.stratum.engine.platform.SelectAll
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.platform.SelectSimilarColor
import com.kiln.stratum.engine.platform.SelectionPreview
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.AddTextLayer
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiquifyLayer
import com.kiln.stratum.engine.platform.LiquifyRenderer
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.RenderedImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.preview.PreviewRegion
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.export.ExportStorage
import com.kiln.stratum.export.exportFileName
import com.kiln.stratum.project.ProjectInfo
import com.kiln.stratum.project.ProjectLibrary
import com.kiln.stratum.settings.AppSettings
import com.kiln.stratum.settings.PreviewQuality
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    CANVAS_NOT_ENOUGH_MEMORY,

    /** 저장한 프로젝트를 읽을 수 없다(망가졌거나 지워짐). */
    PROJECT_UNREADABLE,

    /** 메모리가 부족해 저장한 프로젝트를 열 수 없다. */
    PROJECT_NOT_ENOUGH_MEMORY
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

    /** 자동 저장하지 못했다(저장 공간 부족 등). 편집은 계속할 수 있다. */
    data object AutosaveFailed : EditorMessage
}

/** 공유 창으로 보낼 사진([uri])과 그 형식. 화면이 공유 창을 띄운다. */
data class ShareRequest(val uri: Uri, val mimeType: String)

/**
 * 화면에 보일 레이어 하나의 속성(보정·레이어 패널). 아래 레이어부터의 순서로 [EditorState.Ready.layers]에 담는다.
 *
 * @property thumbnail 레이어 목록의 작은 그림(보정을 건 레이어 픽셀, 하드웨어 비트맵). 빈 레이어는 null이다.
 */
data class LayerSummary(
    val id: LayerId,
    val name: String,
    val opacity: Float,
    val blendMode: BlendMode,
    val adjustments: Adjustments,
    val thumbnail: Bitmap? = null
)

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

    /** 저장한 프로젝트를 여는 중이다. */
    data object LoadingProject : EditorState

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
     * @property layers 레이어들(아래부터). 보정 값처럼 화면이 보일 속성만 담는다.
     * @property activeLayer 고른 레이어. 보정은 이 레이어에 한다.
     * @property selection 선택 영역 윤곽선(문서 전체, 줄인 마스크에서 찾음). 없으면 고른 곳이 없다(모든 곳을 편집한다).
     * @property selectionDetail 확대해 볼 때 [detail]과 같은 자리에서 더 정확하게 다시 찾은 윤곽선. 없으면 [selection]만 그린다.
     */
    data class Ready(
        val base: PreviewImage,
        val documentSize: PixelSize,
        val detail: PreviewImage? = null,
        val revision: Long = 0,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val busy: Boolean = false,
        val layers: List<LayerSummary> = emptyList(),
        val activeLayer: LayerId? = null,
        val selection: SelectionOutline? = null,
        val selectionDetail: SelectionOutline? = null
    ) : EditorState {

        /** 고른 레이어의 속성 */
        val active: LayerSummary?
            get() = layers.firstOrNull { it.id == activeLayer }
    }

    /** 문서를 열거나 만들지 못했다. */
    data class Failed(val reason: EditorFailure) : EditorState
}

/**
 * 편집 화면이 쓰는 엔진 상태를 들고 있다. 접기·펼치기로 Activity가 다시 만들어져도 남는다.
 *
 * 편집 상태의 원본은 엔진의 [EditHistory]이고, 이 클래스는 그것을 들고 화면에 상태를 알려 주기만 한다.
 * 무거운 일(해독, 축소본 그리기)은 백그라운드에서 하고, [EditHistory]는 메인 스레드에서만 만들고 다룬다.
 */
class EditorViewModel internal constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val appSettings: AppSettings
) : AndroidViewModel(application) {

    /** 앱에서는 안드로이드가 저장된 상태와 함께 만든다. 설정은 앱 전체의 설정이다. */
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState, AppSettings.get(application))

    /** 저장된 상태 없이, 기본 설정(저장하지 않음)으로 만든다(테스트). 테스트가 앱의 설정에 따라 달라지지 않는다. */
    constructor(application: Application) : this(application, SavedStateHandle(), AppSettings.inMemory())

    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _messages = Channel<EditorMessage>(Channel.BUFFERED)

    /** 잠깐 알릴 일. 화면이 하나씩 받아 보여 준다. 화면이 다시 만들어지는 동안 온 것은 기다렸다가 전한다. */
    val messages: Flow<EditorMessage> = _messages.receiveAsFlow()

    private val _shareRequests = Channel<ShareRequest>(Channel.BUFFERED)

    /** 공유 창으로 보낼 사진. 화면이 하나씩 받아 공유 창을 띄운다. 화면이 없는 동안 온 것은 기다렸다가 전한다. */
    val shareRequests: Flow<ShareRequest> = _shareRequests.receiveAsFlow()

    /** 이번 실행에서 마지막으로 내보낸 고르기. 아직 내보내지 않았으면 null이다. */
    private val lastExportChoice = MutableStateFlow<ExportChoice?>(null)

    /**
     * 내보내기 창이 처음 보일 고르기. 이번 실행에서 내보낸 적이 있으면 마지막 고르기(앱을 끌 때까지, 다른 문서를 열어도),
     * 아직 없으면 설정의 기본값이다(설정을 바꾸면 바로 따른다).
     */
    val exportChoice: StateFlow<ExportChoice> = combine(lastExportChoice, appSettings.values) { last, values -> last ?: values.export }
        .stateIn(viewModelScope, SharingStarted.Eagerly, appSettings.values.value.export)

    /** 열린 문서의 미리 보기 화질. 문서를 열 때 설정에서 읽는다. */
    private var previewQuality = PreviewQuality.STANDARD

    /** 지금 더 쓸 수 있는 메모리(바이트). 테스트가 메모리 부족을 흉내 낼 수 있게 바꿀 수 있다. */
    internal var availableMemory: () -> Long = { MemoryBudget.availableBytes(getApplication()) }

    /** 앱 내부 저장소의 프로젝트들. 자동 저장은 여기서 끝까지 한다(편집 화면이 사라져도). */
    private val library = ProjectLibrary.get(application)

    /** 홈에 보일 최근 프로젝트 */
    val projects: StateFlow<List<ProjectInfo>> = library.projects

    /** 열려 있는 프로젝트: 번호와 처음 만든 시각 */
    private class OpenProject(val id: String, val createdMillis: Long)

    private var project: OpenProject? = null

    /** 열려 있는 프로젝트 번호(테스트용) */
    internal val projectId: String?
        get() = project?.id

    /** 편집이 멈추면 저장하는 작업. 편집할 때마다 앞 계획을 취소하고 다시 잡는다. */
    private var autosaveJob: Job? = null

    /** 레이어별 축소본. 보정·불투명도만 바뀌면 레이어를 다시 그리지 않고 합친다. [renderMutex] 안에서만 쓴다. */
    private val layerPreviews = LayerPreviewCache()

    /** 고른 레이어. 문서를 열면 맨 위 레이어이고, 그 레이어가 없어지면(실행 취소 등) 다시 맨 위 레이어다. */
    private var activeLayer: LayerId? = null

    /** 슬라이더를 끄는 동안 미리 볼 문서(기록하지 않음). 그리는 작업은 가장 나중 것만 그린다. */
    private var liveDocument: Document? = null

    /** [liveDocument]를 그리는 작업 */
    private var liveJob: Job? = null

    /** 축소본이 미리 보기(기록하지 않은 문서)를 보이고 있는지 */
    private var liveShown = false

    private val _liveAdjustments = MutableStateFlow<Adjustments?>(null)

    /**
     * 끄는 동안의 보정 값(기록 전). 화면의 슬라이더는 이 값을, 없으면 고른 레이어의 기록된 값을 보인다.
     * 기록하면(또는 바뀐 것이 없거나 기록하지 못하면) null로 돌아간다. 그래서 슬라이더가 적용되지 않은 값을 보이는 일이 없다.
     */
    val liveAdjustments: StateFlow<Adjustments?> = _liveAdjustments.asStateFlow()

    private val _liveOpacity = MutableStateFlow<Float?>(null)

    /** 끄는 동안의 불투명도(기록 전). [liveAdjustments]와 같은 방식이다. */
    val liveOpacity: StateFlow<Float?> = _liveOpacity.asStateFlow()

    /** 다른 편집을 처리하는 동안 놓은 값(보정·불투명도). 그 편집이 끝나면 기록한다(놓은 값을 잃지 않게). */
    private var liveCommitPending = false

    /** 보정 패널이 보내는 일 */
    val adjustActions: AdjustActions = object : AdjustActions {
        override fun preview(change: (Adjustments) -> Adjustments) = previewAdjustments(change)
        override fun commit() = commitLive()
        override fun record(change: (Adjustments) -> Adjustments) = recordAdjustments(change)
    }

    private val _brush = MutableStateFlow(BrushSettings())

    /** 브러시 설정(크기·단단함·불투명도·색). 앱을 끌 때까지 남는다(다른 문서를 열어도). */
    val brush: StateFlow<BrushSettings> = _brush.asStateFlow()

    private val _eraser = MutableStateFlow(BrushSettings(size = 60f, hardness = 0.7f))

    /** 지우개 설정(색은 쓰지 않는다). 브러시와 따로 기억한다. */
    val eraser: StateFlow<BrushSettings> = _eraser.asStateFlow()

    private val _textStyle = MutableStateFlow(TextStyle())

    /**
     * 글자 모양(크기·색). 앱을 끌 때까지 남는다. 크기를 직접 고르기 전에는 문서를 열 때마다 문서에 알맞은 크기(짧은 변의 6%)로 맞춘다.
     */
    val textStyle: StateFlow<TextStyle> = _textStyle.asStateFlow()

    /** 글자 크기를 직접 골랐는지 */
    private var textSizeChosen = false

    /** 획 하나: 그릴 레이어, 설정, 지우개인지, 시작할 때의 선택 영역(미리 보기를 그 안으로 자름), 지금까지 놓은 자국 */
    private class Stroke(val layer: LayerId, val settings: BrushSettings, val erase: Boolean, val selection: Selection?) {
        val dabs = StrokeDabs(settings.spacing)

        /** 그리는 동안 바뀌지 않는 미리 보기용 사본 */
        fun live(): LiveStroke = LiveStroke(layer, ArrayList(dabs.dabs), settings, erase, selection)
    }

    /** 끄는 중인 획 */
    private var drawing: Stroke? = null

    /** 손을 뗐지만 아직 기록하지 못한 획(앞에서부터 하나씩 기록한다). 미리 보기에는 그대로 보인다. */
    private val queuedStrokes = ArrayList<Stroke>()

    /** 획을 기록하는 편집을 처리하는 중인지. 그동안에도 다음 획은 그릴 수 있다. */
    private var strokeCommitting = false

    /** 획들을 그리기 전의 확대 그림. 쉬운 경우(맨 위 표준 레이어에 그릴 때) 끄는 동안 그 위에 획을 얹어 선명하게 보인다. */
    private var detailBeforeStroke: PreviewImage? = null

    /** [detailBeforeStroke] 뒤로 그린 획들(기록한 것도). 확대 그림 위에 차례로 얹는다. */
    private val detailStrokes = ArrayList<Stroke>()

    /** 획 미리 보기를 그리는 작업과, 그사이 자국이 더해졌는지 */
    private var strokeJob: Job? = null
    private var strokeDirty = false

    /** 캔버스의 브러시·지우개 손가락이 보내는 일 */
    val strokeActions: StrokeActions = object : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) = beginStroke(StrokePoint(x, y), erase)
        override fun extend(x: Float, y: Float) = extendStroke(StrokePoint(x, y))
        override fun end() = endStroke()
        override fun cancel() = cancelStroke()
    }

    private val _selectionOptions = MutableStateFlow(SelectionOptions())

    /** 선택 도구 설정(합치는 방법·페더·허용치·인접). 앱을 끌 때까지 남는다(다른 문서를 열어도). */
    val selectionOptions: StateFlow<SelectionOptions> = _selectionOptions.asStateFlow()

    /** 선택 도구(캔버스 손가락과 옵션 버튼)가 보내는 일 */
    val selectionActions: SelectionActions = object : SelectionActions {
        override fun shape(shape: SelectionShape) = selectShape(shape)
        override fun similar(x: Float, y: Float) = selectSimilar(x, y)
        override fun all() = edit(SelectAll)
        override fun none() = edit(Deselect)
        override fun invert() = edit(InvertSelection)
        override fun delete() = deleteSelected()
        override fun copyToLayer(cut: Boolean) = copySelectionToLayer(cut)
    }

    /** 마지막으로 윤곽선을 찾은 선택 영역과 그 윤곽선(문서 전체). 선택 영역이 그대로면 다시 찾지 않는다. */
    private var outlined: Pair<Selection, SelectionOutline>? = null

    /** 부분 윤곽선([EditorState.Ready.selectionDetail])을 찾은 선택 영역 */
    private var detailOutlined: Selection? = null

    private val _liquify = MutableStateFlow<LiquifyState?>(null)

    /** 픽셀 유동화 작업 모드. 열려 있으면 화면이 편집 화면 위를 덮는다(CLAUDE.md 6장 '전체 화면 작업 모드'). */
    val liquify: StateFlow<LiquifyState?> = _liquify.asStateFlow()

    /**
     * 열려 있는 유동화 작업: 고른 레이어, 미리 보기 단계, 그 레이어만 그린 축소본, 변위 격자와 GPU에 올릴 그림,
     * 작업 모드 안의 되돌리기(획마다 앞 격자, 최대 [LIQUIFY_UNDO_STEPS]개), 끄는 손가락 자리.
     */
    private class LiquifySession(val layer: LayerId, val level: Int, val image: Bitmap, var mesh: LiquifyMesh, val field: Bitmap) {
        val displacements = FloatArray(mesh.columns * mesh.rows * 2)
        val halves = ShortArray(mesh.columns * mesh.rows * 4)
        val undo = ArrayList<LiquifyMesh>()
        var lastX = 0f
        var lastY = 0f
        var pressed = false
    }

    private var liquifySession: LiquifySession? = null
    private var liquifyRenderJob: Job? = null
    private var liquifyDirty = false
    private var liquifyHoldJob: Job? = null

    /** 유동화 도구·크기(문서 픽셀 지름, 처음에는 문서에 맞춤)·압력. 앱을 끌 때까지 남는다. */
    private var liquifyTool = LiquifyTool.PUSH
    private var liquifySize: Float? = null
    private var liquifyStrength = 0.5f

    /** 픽셀 유동화 작업 모드가 보내는 일 */
    val liquifyActions: LiquifyActions = object : LiquifyActions {
        override fun begin(x: Float, y: Float) = liquifyBegin(x, y)
        override fun move(x: Float, y: Float) = liquifyMove(x, y)
        override fun end() = liquifyEnd()
        override fun setTool(tool: LiquifyTool) {
            liquifyTool = tool
            republishLiquify()
        }
        override fun setSize(size: Float) {
            liquifySize = size
            republishLiquify()
        }
        override fun setStrength(strength: Float) {
            liquifyStrength = strength.coerceIn(0.01f, 1f)
            republishLiquify()
        }
        override fun undo() = undoLiquify()
        override fun reset() = resetLiquify()
        override fun apply() = applyLiquify()
        override fun cancel() = cancelLiquify()
    }

    /** 레이어 패널이 보내는 일 */
    val layerActions: LayerActions = object : LayerActions {
        override fun select(id: LayerId) = selectLayer(id)
        override fun add() = addLayer()
        override fun delete() = deleteLayer()
        override fun move(id: LayerId, toIndex: Int) = moveLayer(id, toIndex)
        override fun previewOpacity(opacity: Float) = this@EditorViewModel.previewOpacity(opacity)
        override fun commitOpacity() = commitLive()
        override fun setBlendMode(mode: BlendMode) = this@EditorViewModel.setBlendMode(mode)
    }

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

    init {
        library.refresh()
        // 자동 저장하지 못하면 알린다(지금 연 프로젝트만)
        viewModelScope.launch {
            library.saveFailures.collect { id -> if (id == project?.id) _messages.trySend(EditorMessage.AutosaveFailed) }
        }
        // 안드로이드가 백그라운드에서 앱을 종료했다가 되살렸으면, 열려 있던 프로젝트를 다시 연다
        savedState.get<String>(KEY_PROJECT)?.let { id ->
            if (ProjectLibrary.isValidId(id)) openProject(id) else savedState.remove<String>(KEY_PROJECT)
        }
    }

    /** [uri] 사진을 연다. 열려 있던 문서는 닫는다(저장은 끝까지 한다). 새 프로젝트가 된다. */
    fun openPhoto(uri: Uri) {
        previewJob?.cancel()
        detailJob?.cancel()
        pendingPhoto = null
        closeProject()
        val previous = openJob
        val previousEdit = editJob
        openJob = viewModelScope.launch {
            // 앞 작업의 해독은 중간에 멈출 수 없어서, 끝날 때까지 기다린 뒤 시작한다
            previous?.cancelAndJoin()
            previousEdit?.cancelAndJoin()
            release()
            memoryFailure = EditorFailure.PHOTO_NOT_ENOUGH_MEMORY
            _state.value = EditorState.Opening
            // 앞 문서를 저장하던 것이 끝나야 메모리가 겹치지 않는다
            library.awaitSaves()
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
        closeProject()
        val previous = openJob
        val previousEdit = editJob
        openJob = viewModelScope.launch {
            // 앞에서 사진을 여는 중이면 해독이 끝날 때까지 기다려 메모리가 겹치지 않게 한다
            previous?.cancelAndJoin()
            previousEdit?.cancelAndJoin()
            release()
            memoryFailure = EditorFailure.CANVAS_NOT_ENOUGH_MEMORY
            _state.value = EditorState.Creating
            library.awaitSaves()
            val layerName = getApplication<Application>().getString(R.string.layer_background)
            val document = withContext(Dispatchers.Default) { BlankCanvas.create(width, height, fill, layerName) }
            if (document == null) {
                _state.value = EditorState.Failed(EditorFailure.CANVAS_NOT_ENOUGH_MEMORY)
                return@launch
            }
            history = newHistory(document)
            startProject()
            showPreview(document)
        }
    }

    /**
     * 저장한 [id] 프로젝트를 연다. 열려 있던 문서는 닫는다(저장은 끝까지 한다). 실행 취소 기록은 없이 시작한다.
     * 읽을 수 없거나 메모리가 모자라면 실패 화면을 보인다.
     */
    fun openProject(id: String) {
        previewJob?.cancel()
        detailJob?.cancel()
        pendingPhoto = null
        closeProject()
        val previous = openJob
        val previousEdit = editJob
        openJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            previousEdit?.cancelAndJoin()
            release()
            memoryFailure = EditorFailure.PROJECT_NOT_ENOUGH_MEMORY
            _state.value = EditorState.LoadingProject
            val loaded = try {
                library.open(id, availableMemory()) { ensureActive() }
            } catch (e: NotEnoughMemoryException) {
                Log.w(TAG, "Not enough memory to open project $id", e)
                _state.value = EditorState.Failed(EditorFailure.PROJECT_NOT_ENOUGH_MEMORY)
                return@launch
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory to open project $id", e)
                _state.value = EditorState.Failed(EditorFailure.PROJECT_NOT_ENOUGH_MEMORY)
                return@launch
            } catch (e: IOException) {
                Log.w(TAG, "Could not open project $id", e)
                _state.value = EditorState.Failed(EditorFailure.PROJECT_UNREADABLE)
                return@launch
            }
            history = newHistory(loaded.document)
            project = OpenProject(id, loaded.createdMillis)
            savedState[KEY_PROJECT] = id
            showPreview(loaded.document)
        }
    }

    /**
     * [id] 레이어를 고른다. 보정·불투명도는 고른 레이어에 한다. 없는 레이어면 무시한다.
     * 슬라이더를 끄는 중이거나 놓은 값을 기록하는 중이면 무시한다(그 값은 앞 레이어의 것이다).
     */
    fun selectLayer(id: LayerId) {
        val document = history?.document ?: return
        if (document.indexOfLayer(id) < 0 || hasLiveValues) return
        activeLayer = id
        (_state.value as? EditorState.Ready)?.let { _state.value = it.copy(activeLayer = id) }
    }

    /**
     * 고른 레이어로 픽셀 유동화 작업 모드를 연다. 그 레이어만 축소본 단계로 그려 미리 보기를 만든다.
     * 다른 편집을 처리하는 중이거나 이미 열려 있으면 무시한다. 그리지 못하면(메모리 부족) 알린다.
     */
    fun startLiquify() {
        val document = history?.document ?: return
        if (editing || hasLiveValues || liquifySession != null || _state.value !is EditorState.Ready) return
        val layer = activeLayerIn(document)
        val level = levelFor(document)
        startEditing()
        editJob = viewModelScope.launch {
            val image = renderMutex.withLock {
                withContext(Dispatchers.Default) { LiquifyRenderer.layerPreview(document, layer, level) }
            }
            if (image == null) {
                finishEditing(EditorMessage.EditNotEnoughMemory)
                return@launch
            }
            if (history?.document !== document) {
                image.recycle()
                finishEditing()
                return@launch
            }
            val size = PixelSize(document.width, document.height)
            val mesh = LiquifyMesh.identity(size)
            val session = LiquifySession(layer, level, image, mesh, LiquifyRenderer.fieldBitmap(mesh))
            liquifySession = session
            if (liquifySize == null) liquifySize = defaultLiquifySize(size)
            finishEditing()
            publishLiquify(session, PreviewImage(image, PreviewLevels.wholeRegion(size, level)))
        }
    }

    /** 처음 브러시 지름: 문서 짧은 변의 15%(포토샵 기본 브러시처럼 화면에서 넉넉한 크기) */
    private fun defaultLiquifySize(size: PixelSize): Float = (minOf(size.width, size.height) * 0.15f).coerceIn(4f, 1000f)

    private fun publishLiquify(session: LiquifySession, preview: PreviewImage, busy: Boolean = false) {
        _liquify.value = LiquifyState(
            preview = preview,
            documentSize = session.mesh.size,
            tool = liquifyTool,
            size = checkNotNull(liquifySize),
            strength = liquifyStrength,
            canUndo = session.undo.isNotEmpty(),
            changed = !session.mesh.isIdentity(),
            busy = busy
        )
    }

    /** 미리 보기는 그대로 두고 도구·크기·되돌리기 상태만 새로 알린다 */
    private fun republishLiquify() {
        val session = liquifySession ?: return
        val shown = _liquify.value ?: return
        publishLiquify(session, shown.preview, shown.busy)
    }

    /** 한 손가락이 닿았다: 앞 격자를 되돌리기에 남기고, 뒤틀기가 아니면 그 자리에 한 번 건다(누르고 있으면 계속) */
    private fun liquifyBegin(x: Float, y: Float) {
        val session = liquifySession ?: return
        if (_liquify.value?.busy == true) return
        session.undo += session.mesh.copy()
        if (session.undo.size > LIQUIFY_UNDO_STEPS) session.undo.removeAt(0)
        session.lastX = x
        session.lastY = y
        session.pressed = true
        if (liquifyTool != LiquifyTool.PUSH) {
            liquifyBrush(session, x, y, 0f, 0f)
            liquifyHoldJob?.cancel()
            liquifyHoldJob = viewModelScope.launch {
                // 누르고 있는 동안 조금씩 더 건다(포토샵의 오목·볼록·재구성과 같다)
                while (session.pressed && liquifySession === session) {
                    delay(LIQUIFY_HOLD_MILLIS)
                    if (session.pressed) liquifyBrush(session, session.lastX, session.lastY, 0f, 0f)
                }
            }
        }
        republishLiquify()
    }

    private fun liquifyMove(x: Float, y: Float) {
        val session = liquifySession ?: return
        if (!session.pressed) return
        liquifyBrush(session, x, y, x - session.lastX, y - session.lastY)
        session.lastX = x
        session.lastY = y
    }

    private fun liquifyEnd() {
        val session = liquifySession ?: return
        session.pressed = false
        liquifyHoldJob?.cancel()
        republishLiquify()
    }

    private fun liquifyBrush(session: LiquifySession, x: Float, y: Float, moveX: Float, moveY: Float) {
        session.mesh.brush(liquifyTool, x, y, checkNotNull(liquifySize) / 2f, liquifyStrength, moveX, moveY)
        requestLiquifyRender()
    }

    /** 미리 보기를 다시 그린다. 그리는 중이면 끝난 뒤 한 번 더 그린다(여러 번 바뀌어도 한 번에). */
    private fun requestLiquifyRender() {
        liquifyDirty = true
        if (liquifyRenderJob?.isActive == true) return
        liquifyRenderJob = viewModelScope.launch {
            while (liquifyDirty) {
                liquifyDirty = false
                val session = liquifySession ?: return@launch
                // 격자는 메인 스레드에서만 바꾸므로, 여기서 사본을 받아 GPU에 올린다
                session.mesh.copyInto(session.displacements)
                val cellSize = session.mesh.cellSize
                val drawn = renderMutex.withLock {
                    withContext(Dispatchers.Default) {
                        LiquifyRenderer.fill(session.field, session.displacements, session.halves)
                        LiquifyRenderer.preview(session.image, session.level, session.field, cellSize)
                    }
                }
                if (drawn == null || liquifySession !== session) continue
                publishLiquify(session, PreviewImage(drawn, PreviewLevels.wholeRegion(session.mesh.size, session.level)), _liquify.value?.busy == true)
            }
        }
    }

    /** 작업 모드 안에서 마지막 한 획을 되돌린다 */
    private fun undoLiquify() {
        val session = liquifySession ?: return
        if (session.pressed || session.undo.isEmpty() || _liquify.value?.busy == true) return
        session.mesh = session.undo.removeAt(session.undo.lastIndex)
        requestLiquifyRender()
        republishLiquify()
    }

    /** 모두 원래대로(되돌리기에 남긴다) */
    private fun resetLiquify() {
        val session = liquifySession ?: return
        if (session.pressed || session.mesh.isIdentity() || _liquify.value?.busy == true) return
        session.undo += session.mesh.copy()
        if (session.undo.size > LIQUIFY_UNDO_STEPS) session.undo.removeAt(0)
        session.mesh.reset()
        requestLiquifyRender()
        republishLiquify()
    }

    /**
     * 비튼 것을 레이어에 적용해(원본 해상도, 실행 취소 한 단계) 작업 모드를 닫는다. 움직인 곳이 없으면 그냥 닫는다.
     * 메모리가 모자라 적용하지 못하면 작업 모드를 그대로 두고 알린다(다시 해 보거나 취소할 수 있게).
     */
    private fun applyLiquify() {
        val session = liquifySession ?: return
        if (session.pressed || editing) return
        if (session.mesh.isIdentity()) {
            cancelLiquify()
            return
        }
        val shown = _liquify.value ?: return
        publishLiquify(session, shown.preview, busy = true)
        runEdit(
            LiquifyLayer(session.layer, session.mesh.copy()),
            onCommitted = { closeLiquify() },
            onFinished = {
                // 기록하지 못했으면 작업 모드가 남아 있다(다시 해 보거나 취소할 수 있게 버튼을 켠다)
                if (liquifySession === session) _liquify.value = _liquify.value?.copy(busy = false)
            }
        )
    }

    /** 바꾼 것을 버리고 작업 모드를 닫는다 */
    private fun cancelLiquify() {
        if (_liquify.value?.busy == true) return
        closeLiquify()
    }

    private fun closeLiquify() {
        liquifySession = null
        liquifyRenderJob?.cancel()
        liquifyHoldJob?.cancel()
        // 보이던 미리 보기는 화면이 아직 그리고 있을 수 있어 recycle()하지 않고 가비지 컬렉터에 맡긴다
        _liquify.value = null
    }

    /** 선택 도구 설정을 바꾼다 */
    fun setSelectionOptions(options: SelectionOptions) {
        _selectionOptions.value = options
    }

    /** [shape]로 고른다(선택 도구 설정의 합치는 방법·페더, 실행 취소 한 단계). 다른 편집을 처리하는 중이면 무시한다. */
    fun selectShape(shape: SelectionShape) {
        val options = _selectionOptions.value
        edit(SelectShape(shape, options.mode, options.feather))
    }

    /** 고른 레이어에서 문서 좌표 ([x], [y])의 색과 비슷한 곳을 고른다(자동 선택, 실행 취소 한 단계). */
    fun selectSimilar(x: Float, y: Float) {
        val document = history?.document ?: return
        val options = _selectionOptions.value
        edit(SelectSimilarColor(activeLayerIn(document), x, y, options.tolerance, options.contiguous, options.mode))
    }

    /** 고른 레이어에서 고른 곳의 픽셀을 지운다(투명하게, 실행 취소 한 단계). 선택 영역이 없으면 무시한다. */
    fun deleteSelected() {
        val document = history?.document ?: return
        if (document.selection == null) return
        edit(DeleteSelected(activeLayerIn(document)))
    }

    /**
     * 고른 레이어에서 고른 곳을 바로 위의 새 레이어로 복사하고([cut]이면 잘라 내고) 새 레이어를 고른다(실행 취소 한 단계).
     * 이름은 '레이어 N'이다. 선택 영역이 없거나 다른 편집을 처리하는 중이면 무시한다.
     */
    fun copySelectionToLayer(cut: Boolean) {
        val document = history?.document ?: return
        if (editing || hasLiveValues || document.selection == null) return
        val name = getApplication<Application>().getString(R.string.layer_new_name, document.nextLayerId)
        runEdit(CopySelectedToLayer(activeLayerIn(document), name, cut), select = LayerId(document.nextLayerId))
    }

    /**
     * 고른 레이어 바로 위에 빈 레이어를 더하고 그 레이어를 고른다(실행 취소 한 단계).
     * 이름은 '레이어 N'이다(N은 문서의 다음 레이어 번호라 지우고 더해도 겹치지 않는다). 다른 편집을 처리하는 중이면 무시한다.
     */
    fun addLayer() {
        val document = history?.document ?: return
        if (editing || hasLiveValues) return
        val name = getApplication<Application>().getString(R.string.layer_new_name, document.nextLayerId)
        runEdit(AddLayer(name, activeIndexIn(document) + 1), select = LayerId(document.nextLayerId))
    }

    /**
     * 고른 레이어를 지우고(실행 취소 한 단계) 그 아래 레이어를, 맨 아래였으면 새 맨 아래 레이어를 고른다.
     * 레이어가 하나뿐이면 지우지 않는다. 다른 편집을 처리하는 중이면 무시한다.
     */
    fun deleteLayer() {
        val document = history?.document ?: return
        if (editing || hasLiveValues || document.layers.size < 2) return
        val index = activeIndexIn(document)
        val next = document.layers[if (index > 0) index - 1 else 1].id
        runEdit(DeleteLayer(document.layers[index].id), select = next)
    }

    /**
     * [id] 레이어를 [toIndex](0이 맨 아래) 자리로 옮긴다(실행 취소 한 단계). 고른 레이어는 그대로다.
     * 화면의 레이어 목록은 바로 새 순서로 보인다(끌어서 놓은 줄이 앞 자리로 튀었다 돌아오지 않게).
     * 기록하면 같은 순서로 다시 그려지고, 하지 못하면 [finishEditing]이 기록된 순서로 되돌린다.
     */
    fun moveLayer(id: LayerId, toIndex: Int) {
        val document = history?.document ?: return
        // 다른 편집을 처리하는 중이면 옮기지 않는다(목록도 그대로 둔다)
        if (editing || hasLiveValues || toIndex !in document.layers.indices) return
        edit(MoveLayer(id, toIndex))
        val ready = _state.value as? EditorState.Ready ?: return
        val from = ready.layers.indexOfFirst { it.id == id }
        if (!editing || from < 0 || from == toIndex) return
        _state.value = ready.copy(layers = ready.layers.toMutableList().apply { add(toIndex, removeAt(from)) })
    }

    /** 고른 레이어의 혼합 모드를 [mode]로 바꾼다(실행 취소 한 단계). */
    fun setBlendMode(mode: BlendMode) {
        val layer = currentLayer() ?: return
        if (hasLiveValues) return
        edit(SetLayerBlendMode(layer.id, mode))
    }

    /** 글자 모양을 바꾼다. 다음에 넣는 글자부터 쓴다. */
    fun setTextStyle(style: TextStyle) {
        if (style.size != _textStyle.value.size) textSizeChosen = true
        _textStyle.value = style
    }

    /**
     * [text]를 그린 새 레이어를 고른 레이어 바로 위에 더하고 그 레이어를 고른다(실행 취소 한 단계).
     * 글자 상자의 왼쪽 위가 문서 좌표 ([x], [y])다. 레이어 이름은 글자의 첫 줄(앞 20자)이다. 다른 편집을 처리하는 중이면 무시한다.
     */
    fun addText(text: String, x: Float, y: Float) {
        val document = history?.document ?: return
        if (editing || hasLiveValues || drawing != null || text.isBlank()) return
        val firstLine = text.trim().lineSequence().first().trim()
        val name = if (firstLine.length > 20) firstLine.take(20) + "…" else firstLine
        runEdit(
            AddTextLayer(text.take(TextStyle.MAX_LENGTH), x, y, _textStyle.value, name, activeIndexIn(document) + 1),
            select = LayerId(document.nextLayerId)
        )
    }

    /** 글자 크기를 아직 고르지 않았으면 [document]에 알맞은 크기로 맞춘다(짧은 변의 6%) */
    private fun fitTextSize(document: Document) {
        if (textSizeChosen) return
        val size = (minOf(document.width, document.height) * 0.06f).coerceIn(TextStyle.MIN_SIZE, TextStyle.MAX_SIZE)
        _textStyle.value = _textStyle.value.copy(size = kotlin.math.round(size))
    }

    /** 브러시 설정을 바꾼다. 다음 획부터 쓴다(기록하지 않는다). */
    fun setBrush(settings: BrushSettings) {
        _brush.value = settings
    }

    /** 지우개 설정을 바꾼다. 다음 획부터 쓴다. */
    fun setEraser(settings: BrushSettings) {
        _eraser.value = settings
    }

    /**
     * 고른 레이어에 획을 그리기 시작한다([erase]면 지우개). 손가락을 떼면([endStroke]) 실행 취소 한 단계로 기록한다.
     * 앞 획을 기록하는 동안에도 그릴 수 있다(차례로 기록한다). 다른 종류의 편집을 처리하는 중이거나 슬라이더를 끄는 중이면 무시한다.
     */
    fun beginStroke(point: StrokePoint, erase: Boolean) {
        val ready = _state.value as? EditorState.Ready ?: return
        if (drawing != null || hasLiveValues || (editing && !strokeCommitting)) return
        val layer = currentLayer() ?: return
        val stroke = Stroke(layer.id, if (erase) _eraser.value else _brush.value, erase, history?.document?.selection)
        stroke.dabs.add(point)
        drawing = stroke
        if (detailStrokes.isEmpty()) detailBeforeStroke = ready.detail
        detailStrokes += stroke
        showStrokes()
    }

    /** 끄는 손가락이 [point]까지 왔다. 새 자국이 생기면 미리 보기를 다시 그린다. */
    fun extendStroke(point: StrokePoint) {
        val stroke = drawing ?: return
        if (stroke.dabs.add(point).isNotEmpty()) showStrokes()
    }

    /** 손가락을 뗐다. 획을 기록할 차례에 넣는다(앞 획을 기록하는 중이면 그 뒤에). */
    fun endStroke() {
        val stroke = drawing ?: return
        drawing = null
        queuedStrokes += stroke
        commitNextStroke()
    }

    /** 두 손가락 조작이 시작돼 끄던 획을 버린다. 기록하지 않는다. */
    fun cancelStroke() {
        val stroke = drawing ?: return
        drawing = null
        detailStrokes.remove(stroke)
        showStrokes()
    }

    /**
     * 슬라이더를 끄는 동안 고른 레이어의 보정을 미리 본다. 기록하지 않는다(설계 메모 '슬라이더').
     * [change]는 지금 값(미리 보는 값, 없으면 기록된 값)을 받아 새 값을 돌려준다. 그래서 화면이 한 박자 늦은 값을 들고 있어도
     * 다른 항목을 덮어쓰지 않는다. 픽셀은 그대로라 레이어별 축소본을 합치기만 해서 빠르다. 미리 보는 동안은 확대 그림을 숨긴다.
     * 다른 편집을 처리하는 중이면 값만 받아 두고, 끝나면 그린다. 놓았던 값을 아직 기록하지 못했으면 이번 끌기와 합쳐 한 단계로 기록한다.
     */
    fun previewAdjustments(change: (Adjustments) -> Adjustments) {
        val layer = currentLayer() ?: return
        _liveAdjustments.value = change(_liveAdjustments.value ?: layer.adjustments).normalized()
        afterLiveChange()
    }

    /** 불투명도 슬라이더를 끄는 동안 고른 레이어의 불투명도를 [opacity](0..1)로 미리 본다. 보정 미리 보기와 같은 방식이다. */
    fun previewOpacity(opacity: Float) {
        currentLayer() ?: return
        _liveOpacity.value = opacity.coerceIn(0f, 1f) + 0f
        afterLiveChange()
    }

    /**
     * 끄던 값(보정·불투명도)을 고른 레이어에 기록한다(실행 취소 한 단계, 슬라이더를 놓을 때). 끄던 값이 없으면 아무것도 하지 않는다.
     * 기록된 값과 같으면 기록하지 않고, 미리 보기를 보였으면 기록된 문서로 다시 그린다.
     * 다른 편집을 처리하는 중이면 그 편집이 끝난 뒤 기록한다.
     */
    fun commitLive() {
        val adjustments = _liveAdjustments.value
        val opacity = _liveOpacity.value
        if (adjustments == null && opacity == null) return
        if (editing) {
            liveCommitPending = true
            return
        }
        val layer = activeLayer
        if (layer == null || history == null || _state.value !is EditorState.Ready) {
            _liveAdjustments.value = null
            _liveOpacity.value = null
            return
        }
        liveDocument = null
        val commands = liveCommands(layer)
        runEdit(commands.singleOrNull() ?: EditSequence(commands)) {
            // 그사이 다시 끌기 시작했으면 그 값은 남긴다(끝나면 그린다)
            if (_liveAdjustments.value == adjustments) _liveAdjustments.value = null
            if (_liveOpacity.value == opacity) _liveOpacity.value = null
        }
    }

    /** 지금 보정 값에 [change]를 걸어 바로 기록한다(초기화·점 지우기 버튼). */
    fun recordAdjustments(change: (Adjustments) -> Adjustments) {
        val layer = currentLayer() ?: return
        _liveAdjustments.value = change(_liveAdjustments.value ?: layer.adjustments).normalized()
        liveCommitPending = false
        commitLive()
    }

    /** 끄는 동안의 값이 있는지(놓은 값을 아직 기록하지 못한 것 포함) */
    private val hasLiveValues: Boolean
        get() = _liveAdjustments.value != null || _liveOpacity.value != null

    /** 끄는 값이 바뀌었다. 놓았던 값은 이번 끌기와 합쳐 기록하고, 편집 중이 아니면 바로 그린다. */
    private fun afterLiveChange() {
        liveCommitPending = false
        if (!editing) showLive()
    }

    /** 고른 레이어(기록된 문서의 것). 문서가 없거나 고른 레이어가 없으면 null. */
    private fun currentLayer(): Layer? {
        if (_state.value !is EditorState.Ready) return null
        val document = history?.document ?: return null
        val index = activeLayer?.let { document.indexOfLayer(it) } ?: return null
        return document.layers.getOrNull(index)
    }

    /** [document]에서 고른 레이어의 자리. 고른 레이어가 없으면 맨 위다. */
    private fun activeIndexIn(document: Document): Int =
        activeLayer?.let { document.indexOfLayer(it) }?.takeIf { it >= 0 } ?: document.layers.lastIndex

    /** 끄는 값을 [layer]에 거는 명령들(보정, 불투명도 순) */
    private fun liveCommands(layer: LayerId): List<EditCommand> =
        listOfNotNull(
            _liveAdjustments.value?.let { SetLayerAdjustments(layer, it) },
            _liveOpacity.value?.let { SetLayerOpacity(layer, it) }
        )

    /** 끄는 값을 고른 레이어에 건 문서를 그린다. 그리는 동안 또 바뀌면 가장 나중 것만 그린다. */
    private fun showLive() {
        val history = history ?: return
        val layer = activeLayer ?: return
        val commands = liveCommands(layer)
        if (commands.isEmpty()) return
        liveDocument = commands.fold(history.document) { document, command -> command.applyTo(document) }
        detailJob?.cancel()
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            while (true) {
                val document = liveDocument ?: break
                val level = previewLevel.takeIf { it >= 0 } ?: levelFor(document)
                val strokes = liveStrokes()
                val bitmap = renderMutex.withLock {
                    withContext(Dispatchers.Default) { layerPreviews.render(document, level, strokes) { ensureActive() } }
                }
                // 그사이 놓았거나(기록) 다른 편집이 새 축소본을 그렸으면 그쪽을 따른다
                if (liveDocument == null) break
                val ready = _state.value as? EditorState.Ready ?: break
                if (bitmap != null) {
                    val region = PreviewLevels.wholeRegion(PixelSize(document.width, document.height), level)
                    _state.value = ready.copy(base = PreviewImage(bitmap, region), detail = null)
                    liveShown = true
                }
                if (liveDocument === document) break
            }
        }
    }

    /**
     * 편집이 끝난 뒤 기다리던 일을 이어서 한다. 처리하는 동안 놓은 값이 있으면 기록하고,
     * 아직 끄는 중이면 그 값으로 미리 보기를 그린다. 기록을 기다리는 획이 있으면 다음 획을 기록한다.
     * 그릴 획이 다 끝났으면 미리 보느라 멈춘 확대 그림을 다시 그린다.
     */
    private fun runPendingLive() {
        if (editing) return
        if (queuedStrokes.isNotEmpty()) {
            commitNextStroke()
            return
        }
        if (drawing == null && detailStrokes.isNotEmpty()) {
            detailStrokes.clear()
            detailBeforeStroke = null
            planDetail()
        }
        if (liveCommitPending) {
            liveCommitPending = false
            commitLive()
        } else if (hasLiveValues && liveDocument == null) {
            showLive()
        }
    }

    /** 미리 보기에 얹을 획들: 기록을 기다리는 것들, 그다음 끄는 것 */
    private fun liveStrokes(): List<LiveStroke> = queuedStrokes.map { it.live() } + listOfNotNull(drawing?.live())

    /**
     * 기록을 기다리는 맨 앞 획을 기록한다(실행 취소 한 단계). 다른 편집을 처리하는 중이면 그 편집이 끝난 뒤 한다.
     * 기록하는 순간 기다리는 목록에서 빼서, 새 축소본(기록한 문서 + 남은 획)에 두 번 그려지지 않게 한다.
     */
    private fun commitNextStroke() {
        if (editing) return
        val stroke = queuedStrokes.firstOrNull() ?: return
        strokeCommitting = true
        runEdit(
            PaintStroke(stroke.layer, ArrayList(stroke.dabs.dabs), stroke.settings, stroke.erase),
            onCommitted = { queuedStrokes.remove(stroke) }
        ) {
            // 기록하지 못했거나(메모리) 바뀐 것이 없어도 목록에서 뺀다
            queuedStrokes.remove(stroke)
            strokeCommitting = false
        }
    }

    /**
     * 끄는 획(과 기록을 기다리는 획)을 얹은 축소본을 그린다. 그리는 동안 자국이 더해지면 끝난 뒤 한 번 더 그린다.
     * 쉬운 경우([strokesFitDetail])에는 확대 그림에도 획을 얹어 확대한 채로 선명하게 보인다. 아니면 확대 그림을 숨긴다.
     */
    private fun showStrokes() {
        strokeDirty = true
        detailJob?.cancel()
        if (strokeJob?.isActive == true) return
        strokeJob = viewModelScope.launch {
            while (strokeDirty) {
                strokeDirty = false
                val history = history ?: break
                val document = history.document
                val strokes = liveStrokes()
                val detailBase = detailBeforeStroke?.takeIf { strokesFitDetail(document) }
                val detailLive = detailStrokes.map { it.live() }
                val level = previewLevel.takeIf { it >= 0 } ?: levelFor(document)
                val (base, detail) = renderMutex.withLock {
                    withContext(Dispatchers.Default) {
                        val base = layerPreviews.render(document, level, strokes) { ensureActive() }
                        val detail = detailBase?.let { layerPreviews.strokeOver(it.bitmap, it.region, detailLive) }
                        base to detail
                    }
                }
                // 그사이 획을 기록했으면 새 축소본(기록한 문서 + 남은 획)이 이미 보인다
                if (this@EditorViewModel.history?.document !== document) continue
                val ready = _state.value as? EditorState.Ready ?: break
                if (base == null) continue
                val region = PreviewLevels.wholeRegion(PixelSize(document.width, document.height), level)
                _state.value = ready.copy(
                    base = PreviewImage(base, region),
                    detail = if (detail != null && detailBase != null) PreviewImage(detail, detailBase.region) else null
                )
                liveShown = true
            }
        }
    }

    /**
     * 확대 그림 위에 획을 바로 얹어도 결과가 같은지. 획들이 모두 맨 위 보이는 레이어(표준, 불투명도 1, 보정 없음)에 그리고,
     * 지우개는 그 레이어가 보이는 유일한 레이어일 때다. 그러면 그 위에 아무것도 없어 합친 그림에 바로 그린 것과 같다.
     */
    private fun strokesFitDetail(document: Document): Boolean {
        val visible = document.layers.filter { it.opacity > 0f }
        val top = visible.lastOrNull() ?: return false
        if (top.blendMode != BlendMode.NORMAL || top.opacity < 1f || !top.adjustments.isIdentity) return false
        return detailStrokes.all { it.layer == top.id && (!it.erase || visible.size == 1) }
    }

    /** 홈에서 [id] 프로젝트를 지운다(되돌릴 수 없다). 열려 있는 프로젝트는 지우지 않는다. */
    fun deleteProject(id: String) {
        if (id == project?.id || !ProjectLibrary.isValidId(id)) return
        library.delete(id)
    }

    /** 앱이 화면에서 사라질 때(onStop) 부른다. 기다리지 않고 지금 문서를 저장한다. */
    fun flush() = saveNow()

    /** 지금 문서로 새 프로젝트를 시작하고 바로 저장한다. 사진을 열거나 새 캔버스를 만든 직후에 부른다. */
    private fun startProject() {
        val id = library.newId()
        project = OpenProject(id, System.currentTimeMillis())
        savedState[KEY_PROJECT] = id
        saveNow()
    }

    /** 편집이 [AUTOSAVE_DELAY_MILLIS] 동안 멈추면 저장한다. 그사이 또 편집하면 다시 기다린다. */
    private fun scheduleSave() {
        if (project == null) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            requestSave()
        }
    }

    /** 기다리던 저장 계획을 지우고 지금 문서를 바로 저장한다. */
    private fun saveNow() {
        autosaveJob?.cancel()
        autosaveJob = null
        requestSave()
    }

    /** 지금 문서를 저장해 달라고 한다. 저장은 프로젝트 모음이 백그라운드에서 끝까지 한다. 이미 저장한 문서면 하지 않는다. */
    private fun requestSave() {
        val project = project ?: return
        val document = history?.document ?: return
        library.save(project.id, project.createdMillis, document)
    }

    /** 열려 있던 프로젝트를 저장하고 놓는다. 되살릴 번호도 지운다. */
    private fun closeProject() {
        saveNow()
        project = null
        savedState.remove<String>(KEY_PROJECT)
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
    fun edit(command: EditCommand) = runEdit(command)

    /**
     * [edit]과 같고, 편집이 끝나면(기록했거나, 바뀐 것이 없거나, 하지 못했으면) 화면 상태를 바꾸는 바로 그때 [onFinished]를 부른다.
     * 바뀐 것이 없는데 미리 보기를 보이고 있었으면 기록된 문서로 다시 그린다.
     * [select]가 있으면 기록한 문서에서 그 레이어를 고른다(레이어를 더하거나 지울 때). 기록을 되돌리면 앞 레이어로 돌아간다.
     * [onCommitted]는 기록한 바로 그때, 새 축소본을 그리기 전에 부른다(그 축소본에 다시 그리지 않을 획을 빼는 데 쓴다).
     */
    private fun runEdit(
        command: EditCommand,
        select: LayerId? = null,
        onCommitted: () -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
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
                onFinished()
                // 미리 보기(획 등)를 보이고 있었으면 기록된 문서로 되돌려 그린다
                if (liveShown) showPreview(before, finishEdit = true)
                finishEditing(EditorMessage.EditNotEnoughMemory)
                return@launch
            }
            if (!history.commit(command, before, after)) {
                // 바뀐 것이 없다. 슬라이더를 처음 값으로 되돌려 놓았으면 축소본이 아직 미리 보기라 다시 그린다.
                onFinished()
                if (liveShown && showPreview(before, finishEdit = true)) runPendingLive() else finishEditing()
                return@launch
            }
            val previousActive = activeLayer
            if (select != null) activeLayer = select
            onCommitted()
            // 끄던 값은 새 상태를 내보내는 바로 그 순간에 지운다(지우고 나서 보이는 상태가 앞 값으로 되돌아가지 않게)
            var finished = false
            if (showPreview(after, finishEdit = true) { finished = true; onFinished() }) {
                scheduleSave()
                runPendingLive()
            } else {
                // 새 축소본을 그리지 못하면 편집을 되돌려 화면과 기록을 맞춘다(다시 실행할 수 있게 남는다)
                history.undo()
                activeLayer = previousActive
                if (!finished) onFinished()
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
        lastExportChoice.value = choice
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
            if (showPreview(document, finishEdit = true)) {
                scheduleSave()
                runPendingLive()
            } else {
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
            _state.value = it.copy(
                busy = false,
                canUndo = history?.canUndo == true,
                canRedo = history?.canRedo == true,
                layers = it.layers.inOrderOf(history?.document)
            )
        }
        if (message != null) _messages.trySend(message)
        // 미리 보느라 숨긴 부분 그림을 다시 그린다
        planDetail()
        runPendingLive()
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

    /** 문서를 닫고 메모리를 비운다. 홈으로 돌아갈 때 부른다. 마지막 문서는 저장한다(백그라운드에서 끝까지). */
    fun close() {
        closeProject()
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
                startProject()
                showPreview(result.document)
            }
        }
    }

    /**
     * [document]의 축소본을 그려 화면에 보인다. 보였으면 true.
     * [finishEdit]이면 편집(실행 취소·다시 실행 포함)을 마치는 그리기라, 보일 때 편집 버튼을 다시 켠다.
     *
     * 그리는 동안 기록의 지금 문서가 다른 것으로 바뀌었으면 보이지 않는다. 그 문서를 그리는 쪽이 따로 있다.
     * [beforeShow]는 새 상태를 내보내기 바로 전에 부른다(보였을 때만).
     * 그리지 못하면, 처음 여는 문서는 실패 화면을 보이고 이미 보이던 문서가 있으면 화면을 그대로 둔다.
     */
    private suspend fun showPreview(document: Document, finishEdit: Boolean = false, beforeShow: () -> Unit = {}): Boolean {
        val level = levelFor(document)
        renderMutex.withLock {
            // 기록을 기다리거나 끄는 획은 그대로 보이게 얹는다
            val strokes = liveStrokes()
            val shownBefore = _state.value as? EditorState.Ready
            // 선택 영역만 바뀌었으면(픽셀은 같음) 보이던 축소본을 그대로 쓴다.
            // 끄는 동안의 미리 보기(보정·불투명도·획)를 보이고 있었으면 그 그림은 기록된 문서가 아니라 다시 그린다.
            val reusable = shownBefore?.base?.takeIf {
                !liveShown && strokes.isEmpty() && it.region.level == level && samePixels(shownDocument, document)
            }
            val preview = reusable?.bitmap ?: withContext(Dispatchers.Default) {
                layerPreviews.render(document, level, strokes) { ensureActive() }
            }
            // 선택 영역 윤곽선. 선택 영역이 바뀐 때만 새로 찾는다.
            val outline = withContext(Dispatchers.Default) { outlineOf(document) }
            // 레이어 패널의 작은 그림. 바뀐 레이어만 새로 만든다.
            val thumbnails = withContext(Dispatchers.Default) { layerPreviews.thumbnails(document, level) { ensureActive() } }
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
            val previous = shownDocument
            val sameDocument = shown != null && document === previous
            val sameImage = shown != null && samePixels(previous, document)
            if (!sameDocument) {
                revision++
                shownDocument = document
            }
            // 화면 크기가 바뀌어 같은 문서를 다시 그린 것이면, 부분 그림이 새 축소본보다 선명할 때만 남긴다.
            // 문서가 바뀌었으면(뒤집기처럼 크기가 같아도) 버린다. 선택 영역만 바뀌었으면 남긴다(픽셀이 같다).
            val detail = if (sameDocument || sameImage) shown?.detail?.takeIf { it.region.level < level } else null
            // 부분 윤곽선은 같은 선택 영역의 것만 남긴다. 바뀌었으면 부분 그림을 계획할 때 다시 찾는다.
            val selectionDetail = shown?.selectionDetail?.takeIf { detail != null && detailOutlined === document.selection }
            if (finishEdit) editing = false
            // 앞 축소본은 화면이 아직 그리고 있을 수 있어 recycle()하지 않고 가비지 컬렉터에 맡긴다
            liveDocument = null
            liveShown = false
            beforeShow()
            _state.value = EditorState.Ready(
                base = PreviewImage(preview, PreviewLevels.wholeRegion(size, level)),
                documentSize = size,
                detail = detail,
                revision = revision,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                busy = editing,
                layers = document.layers.map { LayerSummary(it.id, it.name, it.opacity, it.blendMode, it.adjustments, thumbnails[it.id]) },
                activeLayer = activeLayerIn(document),
                selection = outline,
                selectionDetail = selectionDetail
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
        // 편집 중이거나 새 문서의 축소본을 아직 그리는 중이면, 그 축소본을 그린 뒤 다시 계획한다.
        // 슬라이더로 미리 보는 동안도 그리지 않는다(기록한 문서의 그림이라 미리 보기와 다르다).
        if (editing || document !== shownDocument || liveDocument != null || detailStrokes.isNotEmpty()) return
        val viewport = viewport ?: return
        val size = PixelSize(document.width, document.height)
        // 다른 문서를 보던 때의 보기는 쓰지 않는다
        if (viewport.documentSize != size || ready.documentSize != size) return
        // '가볍게'는 맞춤(또는 더 작게) 볼 때 반 크기 전체 축소본을 그대로 보이고, 확대했을 때만 선명하게 다시 그린다
        val atFit = viewport.scale <= viewport.fitScale * FIT_SCALE_TOLERANCE
        val planned = if (previewQuality == PreviewQuality.LIGHT && atFit) null
        else PreviewLevels.detailRegion(size, ready.base.region.level, viewport.scale, viewport.visible)
        val current = ready.detail
        if (planned == null) {
            if (current != null || ready.selectionDetail != null) _state.value = ready.copy(detail = null, selectionDetail = null)
            return
        }
        val selection = document.selection
        val reuse = current != null && PreviewLevels.canReuse(current.region, planned, size, viewport.visible)
        val region = if (reuse) checkNotNull(current).region else planned
        // 부분 윤곽선은 부분 그림과 같은 자리에서, 같은 선택 영역으로 찾은 것이면 그대로 쓴다
        val outlineReady = selection == null ||
            (detailOutlined === selection && ready.selectionDetail?.region == region.rect)
        if (reuse && outlineReady) return
        val bitmap = if (reuse) {
            null
        } else {
            withContext(Dispatchers.Default) { PreviewRenderer.renderRegion(document, planned) { ensureActive() } }
                ?: run {
                    Log.w(TAG, "Could not render detail level ${planned.level} at ${planned.rect}")
                    return
                }
        }
        val outline = when {
            selection == null -> null
            outlineReady -> ready.selectionDetail
            else -> withContext(Dispatchers.Default) { SelectionOutline(SelectionPreview.outline(selection, region), region.rect) }
        }
        // 그리는 동안 문서를 닫았거나 다른 문서를 열었으면 버린다. 화면에 보인 적 없는 그림이라 바로 비운다.
        val latest = _state.value
        if (latest !is EditorState.Ready || history?.document !== document) {
            bitmap?.recycle()
            return
        }
        if (bitmap != null) Log.d(TAG, "Detail level ${planned.level}: ${bitmap.width}x${bitmap.height} at ${planned.rect}")
        detailOutlined = selection
        // 앞 부분 그림은 화면이 아직 그리고 있을 수 있어 recycle()하지 않는다
        _state.value = latest.copy(detail = if (bitmap != null) PreviewImage(bitmap, planned) else latest.detail, selectionDetail = outline)
    }

    /** [document]의 선택 영역 윤곽선(문서 전체). 선택 영역이 없으면 null. 같은 선택 영역이면 앞에 찾은 것을 쓴다. */
    private fun outlineOf(document: Document): SelectionOutline? {
        val selection = document.selection ?: return null
        outlined?.let { (source, outline) -> if (source === selection) return outline }
        val size = PixelSize(document.width, document.height)
        val region = PreviewLevels.wholeRegion(size, SelectionPreview.outlineLevel(size))
        return SelectionOutline(SelectionPreview.outline(selection, region)).also { outlined = selection to it }
    }

    /** 두 문서의 픽셀(크기·레이어)이 같은지. 선택 영역만 바꾼 편집은 레이어 목록을 그대로 함께 쓴다. */
    private fun samePixels(a: Document?, b: Document): Boolean =
        a != null && a.layers === b.layers && a.width == b.width && a.height == b.height

    internal companion object {
        private const val TAG = "EditorViewModel"

        /** 저장된 상태에 둔 열린 프로젝트 번호. 백그라운드 종료 뒤 되살릴 때 쓴다. */
        const val KEY_PROJECT = "projectId"

        /** 편집이 이만큼(밀리초) 멈추면 자동 저장한다 */
        const val AUTOSAVE_DELAY_MILLIS = 1_500L

        /** 유동화 작업 모드 안에서 되돌릴 수 있는 획 수 */
        const val LIQUIFY_UNDO_STEPS = 8

        /** 오목·볼록·재구성을 누르고 있을 때 다시 거는 간격(밀리초) */
        const val LIQUIFY_HOLD_MILLIS = 40L

        /** '가볍게' 미리 보기에서 전체 축소본을 화면에서 키워 보여도 되는 정도 */
        const val LIGHT_PREVIEW_MAGNIFICATION = 2.0

        /** 맞춤 배율과 같다고 볼 오차(배율 계산의 반올림) */
        private const val FIT_SCALE_TOLERANCE = 1.000001
    }

    private fun levelFor(document: Document): Int {
        val canvas = canvasSize ?: getApplication<Application>().resources.displayMetrics.let {
            PixelSize(it.widthPixels, it.heightPixels)
        }
        return PreviewLevels.levelFor(PixelSize(document.width, document.height), canvas, previewMagnification())
    }

    /** 전체 축소본을 화면에서 키워 보여도 되는 정도. '가볍게'는 두 배까지 키워 보여, 화면보다 큰 사진은 한 단계(반) 작게 그린다. */
    private fun previewMagnification(): Double =
        if (previewQuality == PreviewQuality.LIGHT) LIGHT_PREVIEW_MAGNIFICATION else 1.0

    /** 새 문서의 실행 취소 기록. 메모리 한도는 기기 메모리에 맞춘다. 미리 보기 화질은 이때 설정에서 읽는다. */
    private fun newHistory(document: Document): EditHistory {
        previewQuality = appSettings.values.value.previewQuality
        fitTextSize(document)
        return EditHistory(document, maxBytes = MemoryBudget.historyBudgetBytes(getApplication()))
    }

    private fun release() {
        history = null
        previewLevel = -1
        viewport = null
        editing = false
        shownDocument = null
        activeLayer = null
        liveDocument = null
        liveShown = false
        liveJob?.cancel()
        _liveAdjustments.value = null
        _liveOpacity.value = null
        liveCommitPending = false
        drawing = null
        queuedStrokes.clear()
        strokeCommitting = false
        detailBeforeStroke = null
        detailStrokes.clear()
        strokeJob?.cancel()
        layerPreviews.clear()
        outlined = null
        detailOutlined = null
        closeLiquify()
    }

    /** 레이어 요약을 [document]의 레이어 순서로. 레이어가 서로 다르면(더하거나 지움) 그대로 둔다. */
    private fun List<LayerSummary>.inOrderOf(document: Document?): List<LayerSummary> {
        if (document == null || size != document.layers.size) return this
        val byId = associateBy { it.id }
        return document.layers.map { byId[it.id] ?: return this }
    }

    /** [document]에서 고른 레이어. 고른 레이어가 없거나 없어졌으면 맨 위 레이어를 고른다. */
    private fun activeLayerIn(document: Document): LayerId {
        val chosen = activeLayer?.takeIf { document.indexOfLayer(it) >= 0 }
        return (chosen ?: document.layers.last().id).also { activeLayer = it }
    }
}

package com.kiln.stratum.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kiln.stratum.settings.AppSettings
import com.kiln.stratum.ui.edit.EditScreen
import com.kiln.stratum.ui.edit.EditorViewModel
import com.kiln.stratum.ui.home.HomeScreen
import com.kiln.stratum.ui.settings.SettingsScreen

/**
 * 앱의 화면.
 *
 * 기획상 화면은 홈·편집·설정뿐이다. 새 캔버스, 도구 옵션, 내보내기 같은 나머지는
 * 이 화면들 안의 시트·패널로 띄운다.
 */
enum class AppScreen { Home, Edit, Settings }

/** 앱 전체. 지금 보여 줄 화면을 고르고 화면 사이 이동을 맡는다. */
@Composable
fun StratumApp(editor: EditorViewModel) {
    // 앱을 새로 실행하면 항상 홈에서 시작한다.
    // rememberSaveable이라 접기·펼치기로 Activity가 다시 만들어지거나, 안드로이드가 백그라운드의 앱을
    // 종료했다가 되살려도 보던 화면이 그대로 나온다.
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }
    // 편집 화면의 화면 상태(고른 도구·보기·반전·패널 등). 편집 화면에서 연 설정에서 돌아와도 이어지게 화면을 바꿀 때 남겨 두고,
    // 홈으로 나갈 때 지운다(사용자 피드백 11차 4). 접기·펼치기·백그라운드 종료에도 함께 저장된다.
    val screenStates = rememberSaveableStateHolder()
    // 앱 설정. 설정 화면에서 바꾸면 편집 화면(두 손가락 톡)이 바로 따른다.
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.get(context) }
    val settingsValues by settings.values.collectAsState()

    // 안드로이드 사진 선택기(저장소 권한이 필요 없다, CLAUDE.md 5장).
    // 화면과 상관없이 늘 등록해 두어야, 선택기를 연 사이 Activity가 다시 만들어져도 고른 사진을 받는다.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 선택기에서 아무것도 고르지 않고 돌아오면 홈에 그대로 있는다
        if (uri != null) {
            editor.openPhoto(uri)
            screen = AppScreen.Edit
        }
    }

    // 레이어 패널의 사진 넣기(사용자 피드백 12차): 고른 사진을 편집 중인 문서의 새 레이어로 넣는다. 열기와 같은 까닭으로 늘 등록해 둔다.
    val insertPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) editor.insertPhoto(uri)
    }

    when (screen) {
        AppScreen.Home -> {
            val projects by editor.projects.collectAsState()
            HomeScreen(
                onOpenPhoto = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onCreateCanvas = { width, height, fill, name ->
                    editor.newCanvas(width, height, fill, name)
                    screen = AppScreen.Edit
                },
                projects = projects,
                onOpenProject = { id ->
                    editor.openProject(id)
                    screen = AppScreen.Edit
                },
                onDeleteProjects = editor::deleteProjects,
                onRenameProject = editor::renameProject,
                // 다른 앱에서 끌어다 놓은 사진은 사진 열기와 같이 새 프로젝트로 연다(사용자 피드백 12차 4)
                onDropPhoto = { uri, release ->
                    editor.openDropped(uri, release)
                    screen = AppScreen.Edit
                }
            )
        }

        AppScreen.Settings -> {
            // 설정은 편집 화면 상단 바에서 연다(사용자 피드백 11차 4). 상단 바의 뒤로 버튼과 시스템 뒤로는 보던 편집 화면으로 돌아간다.
            // 문서는 열린 채이고, 바꾼 미리 보기 화질은 돌아갈 때 바로 건다.
            val backToEdit = {
                editor.applyPreviewQuality()
                screen = AppScreen.Edit
            }
            BackHandler(onBack = backToEdit)
            val versionName = remember(context) {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }
            SettingsScreen(values = settingsValues, onChange = settings::update, onBack = backToEdit, versionName = versionName)
        }

        AppScreen.Edit -> {
            // 홈으로 갈 때 문서를 닫아 메모리를 비우고, 남겨 둔 편집 화면 상태도 지운다(다음 문서는 처음부터). 마지막 작업은 자동 저장된다.
            val goHome = {
                editor.close()
                screenStates.removeState(EDIT_SCREEN_STATE)
                screen = AppScreen.Home
            }
            // 시스템 뒤로(버튼·제스처)도 상단 바의 뒤로 버튼과 똑같이 홈으로 돌아간다
            BackHandler(onBack = goHome)
            val state by editor.state.collectAsState()
            val exportChoice by editor.exportChoice.collectAsState()
            // 끄는 동안 자주 바뀌므로 State로 받아 두고, 보정 패널만 읽게 한다
            val liveAdjustments = editor.liveAdjustments.collectAsState()
            val liveOpacity = editor.liveOpacity.collectAsState()
            val liveSkin = editor.liveSkin.collectAsState()
            val liveSkinSmooth = editor.liveSkinSmooth.collectAsState()
            val brush by editor.brush.collectAsState()
            val eraser by editor.eraser.collectAsState()
            val textStyle by editor.textStyle.collectAsState()
            val selectionOptions by editor.selectionOptions.collectAsState()
            val liquify by editor.liquify.collectAsState()
            val retouch by editor.retouch.collectAsState()
            val cloneSource by editor.cloneSource.collectAsState()
            val removePick by editor.removePick.collectAsState()
            val textureFill by editor.textureFill.collectAsState()
            val rasterizeAsk by editor.rasterizeAsk.collectAsState()
            val toneOptions by editor.tone.collectAsState()
            val blurOptions by editor.blur.collectAsState()
            val mosaicOptions by editor.mosaic.collectAsState()
            // 스포이드 고리는 끄는 동안 자주 바뀌므로 State로 받아 두고, 고리만 읽게 한다
            val eyedropper = editor.eyedropper.collectAsState()
            screenStates.SaveableStateProvider(EDIT_SCREEN_STATE) {
                EditScreen(
                    state = state,
                    onBack = goHome,
                    onCanvasSize = editor::onCanvasSize,
                    onViewport = editor::onViewport,
                    onChooseSize = editor::chooseSize,
                    onUndo = editor::undo,
                    onRedo = editor::redo,
                    messages = editor.messages,
                    onEdit = editor::edit,
                    exportChoice = exportChoice,
                    onExport = editor::export,
                    shareRequests = editor.shareRequests,
                    liveAdjustments = { liveAdjustments.value },
                    adjust = editor.adjustActions,
                    liveOpacity = { liveOpacity.value },
                    layerActions = editor.layerActions,
                    brush = brush,
                    onBrush = editor::setBrush,
                    eraser = eraser,
                    onEraser = editor::setEraser,
                    strokeActions = editor.strokeActions,
                    textStyle = textStyle,
                    onTextStyle = editor::setTextStyle,
                    onAddText = editor::addText,
                    onEditText = editor::editText,
                    rasterizeAsk = rasterizeAsk,
                    onConfirmRasterize = editor::confirmRasterize,
                    onDismissRasterize = editor::dismissRasterize,
                    twoFingerTapUndo = settingsValues.twoFingerTapUndo,
                    threeFingerGestures = settingsValues.threeFingerGestures,
                    selectionOptions = selectionOptions,
                    onSelectionOptions = editor::setSelectionOptions,
                    selectionActions = editor.selectionActions,
                    liquify = liquify,
                    liquifyActions = editor.liquifyActions,
                    onStartLiquify = editor::startLiquify,
                    retouch = retouch,
                    onRetouch = editor::setRetouch,
                    cloneSource = cloneSource,
                    retouchActions = editor.retouchActions,
                    removePick = removePick,
                    textureFill = textureFill,
                    toneOptions = toneOptions,
                    onToneOptions = editor::setTone,
                    toneActions = editor.toneActions,
                    blurOptions = blurOptions,
                    onBlurOptions = editor::setBlur,
                    mosaicOptions = mosaicOptions,
                    onMosaicOptions = editor::setMosaic,
                    effectActions = editor.effectActions,
                    paintColors = settingsValues.paintColors,
                    onChooseColor = editor::chooseColor,
                    onSwapColors = editor::swapColors,
                    moveActions = editor.moveActions,
                    skinActions = editor.skinActions,
                    liveSkin = { liveSkin.value },
                    liveSkinSmooth = { liveSkinSmooth.value },
                    onAddPhoto = { insertPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    // 편집 화면에 끌어다 놓은 사진은 사진 넣기와 같이 새 레이어로 넣는다(사용자 피드백 12차 4)
                    onDropPhoto = editor::insertDropped,
                    eyedropper = { eyedropper.value },
                    eyedropperActions = editor.eyedropperActions,
                    // 메인 화면 도구 옵션 띠를 손잡이로 옮겨 놓은 자리는 설정에 남는다(사용자 피드백 9차)
                    stripPosition = settingsValues.stripPosition,
                    onStripPosition = { spot -> settings.update { it.copy(stripPosition = spot) } },
                    // 리터칭·명암의 안내 알림은 하위 도구마다 이번 실행에서 한 번(사용자 피드백 10차 5)
                    toolHelpSeen = editor.toolHelpSeen,
                    // 상단 바 맨 오른쪽 설정 버튼(사용자 피드백 11차 4). 이 화면의 상태는 남겨 두어, 설정에서 돌아오면 그대로다.
                    onOpenSettings = { screen = AppScreen.Settings }
                )
            }
        }
    }
}

/** 편집 화면 상태를 남겨 두는 열쇠([rememberSaveableStateHolder]) */
private const val EDIT_SCREEN_STATE = "edit"

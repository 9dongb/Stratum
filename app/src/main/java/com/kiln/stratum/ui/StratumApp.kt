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
                onOpenSettings = { screen = AppScreen.Settings }
            )
        }

        AppScreen.Settings -> {
            // 시스템 뒤로도 상단 바의 뒤로 버튼과 똑같이 홈으로 돌아간다
            val goHome = { screen = AppScreen.Home }
            BackHandler(onBack = goHome)
            val versionName = remember(context) {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }
            SettingsScreen(values = settingsValues, onChange = settings::update, onBack = goHome, versionName = versionName)
        }

        AppScreen.Edit -> {
            // 홈으로 갈 때 문서를 닫아 메모리를 비운다. 마지막 작업은 자동 저장된다.
            val goHome = {
                editor.close()
                screen = AppScreen.Home
            }
            // 시스템 뒤로(버튼·제스처)도 상단 바의 뒤로 버튼과 똑같이 홈으로 돌아간다
            BackHandler(onBack = goHome)
            val state by editor.state.collectAsState()
            val exportChoice by editor.exportChoice.collectAsState()
            // 끄는 동안 자주 바뀌므로 State로 받아 두고, 보정 패널만 읽게 한다
            val liveAdjustments = editor.liveAdjustments.collectAsState()
            val liveOpacity = editor.liveOpacity.collectAsState()
            val brush by editor.brush.collectAsState()
            val eraser by editor.eraser.collectAsState()
            val textStyle by editor.textStyle.collectAsState()
            val selectionOptions by editor.selectionOptions.collectAsState()
            val liquify by editor.liquify.collectAsState()
            val retouch by editor.retouch.collectAsState()
            val cloneSource by editor.cloneSource.collectAsState()
            val toneOptions by editor.tone.collectAsState()
            val blurOptions by editor.blur.collectAsState()
            val mosaicOptions by editor.mosaic.collectAsState()
            // 스포이드 고리는 끄는 동안 자주 바뀌므로 State로 받아 두고, 고리만 읽게 한다
            val eyedropper = editor.eyedropper.collectAsState()
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
                eyedropper = { eyedropper.value },
                eyedropperActions = editor.eyedropperActions
            )
        }
    }
}

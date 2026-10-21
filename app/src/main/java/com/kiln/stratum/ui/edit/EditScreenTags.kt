package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize

/** 화면 테스트가 편집 화면의 부분을 찾을 때 쓰는 이름표(testTag). 화면에는 보이지 않는다. */
object EditScreenTags {
    const val CANVAS = "editCanvas"

    /** 제스처 알림(두 손가락·세 손가락 톡의 짧은 알림) */
    const val MESSAGE_BAR = "messageBar"
    const val GESTURE_TOAST = "gestureToast"

    /** 메인 화면의 도구 옵션 띠, 칸을 누르면 뜨는 옵션 창, 떠 있는 동안 캔버스를 덮어 누르면 닫는 바탕 */
    const val OPTION_STRIP = "optionStrip"

    /** 도구 옵션 띠 맨 위의 손잡이(끌어 띠를 옮김, 사용자 피드백 9차) */
    const val STRIP_HANDLE = "stripHandle"

    /** 세로 슬라이더 창의 값 표시('경도 82%') */
    const val STRIP_SLIDER_VALUE = "stripSliderValue"
    const val OPTION_FLYOUT = "optionFlyout"
    const val FLOATING_DISMISS = "floatingDismiss"

    /** 도구 옵션 띠의 [key] 칸 */
    fun strip(key: String): String = "strip_$key"

    /** 칠하는 손가락 자리의 브러시 커서(크기 원) */
    const val BRUSH_CURSOR = "brushCursor"

    /** 색 줄의 지금 색(누르면 팔레트) */
    /** 색 줄의 바꾸기 버튼(전경색·배경색 맞바꿈). 띠의 바꾸기 칸도 같은 이름표다. */
    const val COLOR_SWAP = "colorSwap"

    /** 색 줄의 스포이드 버튼, 켜면 캔버스에 겹치는 스포이드 터치 자리, 손가락 둘레 고리 */
    const val COLOR_EYEDROPPER = "colorEyedropper"
    const val EYEDROPPER_OVERLAY = "eyedropperOverlay"
    const val EYEDROPPER_LOUPE = "eyedropperLoupe"

    /** 색 팔레트와 그 안의 채도·밝기 네모, 색상 띠, 16진 코드, 원래 색, 버튼 */
    const val PALETTE = "palette"
    const val PALETTE_SQUARE = "paletteSquare"
    const val PALETTE_HUE = "paletteHue"
    const val PALETTE_HEX = "paletteHex"
    const val PALETTE_NEW = "paletteNew"
    const val PALETTE_ORIGINAL = "paletteOriginal"
    const val PALETTE_OK = "paletteOk"
    const val PALETTE_CANCEL = "paletteCancel"

    /** 편집 화면 상단 바 */
    const val TOP_BAR = "editTopBar"
    const val ZOOM_CHIP = "zoomChip"
    const val BUSY = "editBusy"
    const val UNDO = "undo"
    const val REDO = "redo"
    /** 상단 바 맨 오른쪽 설정 버튼(사용자 피드백 11차 4) */
    const val SETTINGS = "editSettings"
    const val TOOL_OPTIONS = "toolOptions"
    /** 닫힌 화면 새 창(자르기 등)의 위 줄 */
    const val COVER_WINDOW_TOP_BAR = "coverWindowTopBar"
    /** 닫힌 화면 자르기 창의 옵션(하위 도구·비율) */
    const val COVER_CROP_OPTIONS = "coverCropOptions"
    const val ROTATE_LEFT = "rotateLeft"
    const val ROTATE_RIGHT = "rotateRight"
    const val FLIP_HORIZONTAL = "flipHorizontal"
    const val FLIP_VERTICAL = "flipVertical"
    const val CROP_OVERLAY = "cropOverlay"
    const val CROP_SWAP = "cropSwap"
    const val CROP_CANCEL = "cropCancel"
    const val CROP_APPLY = "cropApply"
    const val RESIZE_WIDTH = "resizeWidth"
    const val RESIZE_HEIGHT = "resizeHeight"
    const val RESIZE_LOCK = "resizeLock"
    const val RESIZE_APPLY = "resizeApply"
    const val EXPORT = "export"
    const val EXPORT_QUALITY = "exportQuality"
    const val EXPORT_SHARE = "exportShare"
    const val EXPORT_SAVE = "exportSave"

    /** 내보내기 창의 HDR 안내 한 줄(HDR 문서만) */
    const val EXPORT_HDR_HINT = "exportHdrHint"
    const val TOOL_RAIL = "toolRail"
    const val TOOL_TAB_BAR = "toolTabBar"

    /** 커버 탭 줄 안의 칸들 */
    const val TOOL_TAB_ROW = "toolTabRow"

    /** 커버 탭 줄 맨 끝의 더보기 칸 */
    const val MORE_TAB = "moreTab"

    /** 커버 화면 더보기 줄(탭 줄에 없는 도구들) */
    const val COVER_MORE_TOOLS = "coverMoreTools"

    /** 커버 화면 더보기 줄의 [tool] 칸 */
    fun moreTool(tool: EditTool): String = "moreTool_${tool.name}"

    /** 커버 화면 한 줄 막대(결정 기록 '커버 화면 개편') */
    const val COVER_TOOL_BAR = "coverToolBar"

    /** 한 줄 막대의 [key] 칩 */
    fun bar(key: String): String = "bar_$key"

    /** 한 줄 막대의 안내 글(도구를 고르지 않음)·도구 이름(옵션이 없는 이동 도구) */
    const val BAR_HINT = "barHint"

    /** 한 줄 막대 오른쪽 끝의 ✕(도구 닫기) */
    const val BAR_CLOSE = "barClose"

    /** 펼친 칩을 접는 ✓ */
    const val BAR_DONE = "barDone"

    /** 커버 화면 레이어 시트(한 줄 막대 위에 올라옴, 결정 기록 '커버 화면 개편' Q2 A) */
    const val COVER_LAYER_SHEET = "coverLayerSheet"

    /** 레이어 시트 머리의 제목 자리(누르면 반 높이·전체 높이를 오감) */
    const val COVER_LAYER_SHEET_HANDLE = "coverLayerSheetHandle"

    /** 커버 조정 막대에서 곡선을 펼치면 막대 위에 뜨는 곡선 편집기 */
    const val COVER_CURVE_SHEET = "coverCurveSheet"

    /** 메인 화면의 떠 있는 레이어·조정 패널 */
    const val FLOATING_PANEL = "floatingPanel"

    /** 상단 바의 [panel] 패널 버튼(커버·메인 화면) */
    fun panelToggle(panel: MainPanel): String = "panelToggle_${panel.name}"
    const val ADJUST_PANEL = "adjustPanel"
    const val ADJUST_RESET_ALL = "adjustResetAll"
    const val CURVE_EDITOR = "curveEditor"
    const val CURVE_RESET = "curveReset"
    const val CURVE_DELETE_POINT = "curveDeletePoint"
    const val LAYER_PANEL = "layerPanel"
    const val BRUSH_OPTIONS = "brushOptions"
    const val BRUSH_SIZE = "brushSize"
    const val BRUSH_HARDNESS = "brushHardness"
    const val BRUSH_OPACITY = "brushOpacity"
    const val BRUSH_COLORS = "brushColors"
    const val STROKE_OVERLAY = "strokeOverlay"

    /** 크기 슬라이더를 끄는 동안 캔버스에 보이는 브러시 크기 원 */
    const val SIZE_PREVIEW = "sizePreview"
    const val TEXT_OPTIONS = "textOptions"
    const val TEXT_SIZE = "textSize"
    const val TEXT_OVERLAY = "textOverlay"
    const val TEXT_FIELD = "textField"
    const val TEXT_ADD = "textAdd"
    const val TEXT_PROMPT_SIZE = "textPromptSize"
    const val TEXT_PROMPT_COLOR = "textPromptColor"
    const val RASTERIZE_CONFIRM = "rasterizeConfirm"
    const val LAYER_LIST = "layerList"
    const val LAYER_ADD = "layerAdd"
    const val LAYER_DELETE = "layerDelete"
    /** 레이어 탭 버튼 줄의 화면 반전(보기만 뒤집음) */
    const val VIEW_FLIP_HORIZONTAL = "viewFlipHorizontal"
    const val VIEW_FLIP_VERTICAL = "viewFlipVertical"
    const val LAYER_OPACITY = "layerOpacity"
    const val LAYER_BLEND = "layerBlend"

    /** 레이어 줄을 길게 누르면 뜨는 메뉴의 항목 */
    const val LAYER_MENU_DUPLICATE = "layerMenuDuplicate"
    const val LAYER_MENU_MERGE_DOWN = "layerMenuMergeDown"
    const val LAYER_MENU_MERGE_VISIBLE = "layerMenuMergeVisible"
    const val LAYER_MENU_DELETE = "layerMenuDelete"
    const val LAYER_MENU_RASTERIZE = "layerMenuRasterize"

    /** 왼쪽 도구 막대의 [tool] 버튼 */
    fun railTool(tool: EditTool): String = "railTool_${tool.name}"

    /** 커버 화면 탭 줄의 [tool] 칸 */
    fun tabTool(tool: EditTool): String = "tabTool_${tool.name}"

    /** 길게 눌러 뜬 메뉴의 [subTool] 항목 */
    fun subTool(subTool: SubTool): String = "subToolMenu_${subTool.name}"

    /** 하위 도구 메뉴 카드와, 메뉴 밖을 누르면 닫는 바탕 */
    const val SUB_TOOL_MENU = "subToolMenu"
    const val SUB_TOOL_MENU_DISMISS = "subToolMenuDismiss"

    /** 옵션 위쪽에서 [subTool]로 바꾸는 버튼 */
    fun subToolOption(subTool: SubTool): String = "subToolOption_${subTool.name}"

    /** 선택 도구 옵션 */
    const val SELECTION_OPTIONS = "selectionOptions"
    const val SELECTION_FEATHER = "selectionFeather"
    const val SELECTION_TOLERANCE = "selectionTolerance"
    const val SELECTION_CONTIGUOUS = "selectionContiguous"
    const val SELECTION_ALL = "selectionAll"

    /** 메인 화면 선택 도구의 아래 가운데 작업 표시줄(카드) */
    const val SELECTION_ACTION_BAR = "selectionActionBar"
    const val SELECTION_NONE = "selectionNone"
    const val SELECTION_INVERT = "selectionInvert"
    const val SELECTION_DELETE = "selectionDelete"
    const val SELECTION_COPY = "selectionCopy"
    const val SELECTION_CUT = "selectionCut"
    const val SELECTION_AI_FILL = "selectionAiFill"

    /** 선택 도구를 고르면 캔버스 위에 겹치는 터치 자리 */
    const val SELECTION_OVERLAY = "selectionOverlay"

    /** 선택 영역 윤곽선(개미 행렬) */
    const val SELECTION_OUTLINE = "selectionOutline"

    /** 픽셀 유동화 작업 모드 */
    const val LIQUIFY_MODE = "liquifyMode"
    const val LIQUIFY_TOP_BAR = "liquifyTopBar"
    const val LIQUIFY_CANVAS = "liquifyCanvas"
    const val LIQUIFY_OVERLAY = "liquifyOverlay"
    const val LIQUIFY_CANCEL = "liquifyCancel"
    const val LIQUIFY_UNDO = "liquifyUndo"
    const val LIQUIFY_REDO = "liquifyRedo"
    const val LIQUIFY_RESET = "liquifyReset"
    const val LIQUIFY_APPLY = "liquifyApply"
    const val LIQUIFY_SIZE = "liquifySize"
    const val LIQUIFY_STRENGTH = "liquifyStrength"

    /** 리터칭(스팟 복구·복구 브러시·복제 도장) 옵션과 캔버스 터치 자리 */
    const val RETOUCH_OPTIONS = "retouchOptions"
    const val RETOUCH_OVERLAY = "retouchOverlay"
    const val RETOUCH_SIZE = "retouchSize"
    const val RETOUCH_HARDNESS = "retouchHardness"
    const val RETOUCH_OPACITY = "retouchOpacity"
    const val RETOUCH_REMOVE_PICKED = "retouchRemovePicked"
    const val RETOUCH_SOURCE_MARKER = "retouchSourceMarker"

    /** 명암(닷지·번) 옵션과 캔버스 터치 자리 */
    const val TONE_OPTIONS = "toneOptions"
    const val TONE_OVERLAY = "toneOverlay"
    const val TONE_EXPOSURE = "toneExposure"
    const val TONE_SIZE = "toneSize"
    const val TONE_HARDNESS = "toneHardness"
    const val BLUR_OPTIONS = "blurOptions"
    const val BLUR_OVERLAY = "blurOverlay"
    const val BLUR_STRENGTH = "blurStrength"
    const val BLUR_SIZE = "blurSize"
    const val BLUR_HARDNESS = "blurHardness"
    const val MOSAIC_OPTIONS = "mosaicOptions"
    /** 이동 도구의 캔버스 손가락 */
    const val MOVE_OVERLAY = "moveOverlay"
    const val MOSAIC_OVERLAY = "mosaicOverlay"
    const val MOSAIC_CELL = "mosaicCell"
    const val MOSAIC_SIZE = "mosaicSize"
    const val MOSAIC_HARDNESS = "mosaicHardness"

    /** 명암 옵션의 [range] 범위 버튼 */
    fun toneRange(range: com.kiln.stratum.engine.tone.ToneRange): String = "toneRange_${range.name}"

    /** 유동화 작업 모드의 [tool] 도구 버튼 */
    fun liquifyTool(tool: com.kiln.stratum.engine.liquify.LiquifyTool): String = "liquifyTool_${tool.name}"

    /** 선택 도구 옵션의 합치는 방법 [mode] 버튼 */
    fun selectionMode(mode: com.kiln.stratum.engine.selection.SelectionMode): String = "selectionMode_${mode.name}"

    /** 보정 패널의 [item] 슬라이더 줄 */
    fun adjustSlider(item: AdjustItem): String = "adjustSlider_${item.name}"

    /** 레이어 목록의 [id] 레이어 줄 */
    fun layerRow(id: com.kiln.stratum.engine.document.LayerId): String = "layerRow_${id.value}"

    /** 레이어 줄의 순서 손잡이 */
    fun layerHandle(id: com.kiln.stratum.engine.document.LayerId): String = "layerHandle_${id.value}"

    /** 레이어 줄의 눈(보이기·숨기기) */
    fun layerVisibility(id: com.kiln.stratum.engine.document.LayerId): String = "layerVisibility_${id.value}"

    /** 레이어 줄 작은 그림 구석의 글자 레이어 표시 */
    fun layerTextBadge(id: com.kiln.stratum.engine.document.LayerId): String = "layerTextBadge_${id.value}"

    /** 색 줄의 전경색·배경색 칸 */
    fun colorSlot(slot: com.kiln.stratum.settings.ColorSlot): String = "colorSlot_${slot.name}"

    /** 혼합 모드 메뉴의 [mode] 항목 */
    fun blendMode(mode: com.kiln.stratum.engine.document.BlendMode): String = "blendMode_${mode.name}"

    /** 커버 화면 보정 옵션의 [item] 칩 */
    fun adjustChip(item: AdjustItem): String = "adjustChip_${item.name}"

    /** 자르기 비율 [ratio] 칩 */
    fun cropRatio(ratio: CropRatio): String = "cropRatio_${ratio.name}"

    /** 닫힌 화면 자르기·회전 창 맨 아래의 탭(자르기·회전) */
    fun cropTab(tab: CropTab): String = "cropTab_${tab.name}"

    /** 크기 변경 창의 [unit] 단위 버튼 */
    fun resizeUnit(unit: ResizeUnit): String = "resizeUnit_${unit.name}"

    /** 내보내기 창의 [format] 형식 버튼 */
    fun exportFormat(format: ExportFormat): String = "exportFormat_${format.name}"

    /** 내보내기 창의 [size] 크기 버튼. 원본은 `exportSize_original`, 긴 변은 `exportSize_2048`처럼 픽셀이다. */
    fun exportSize(size: ExportSize): String =
        when (size) {
            ExportSize.Original -> "exportSize_original"
            is ExportSize.LongEdge -> "exportSize_${size.pixels}"
        }
}

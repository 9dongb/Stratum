package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize

/** 화면 테스트가 편집 화면의 부분을 찾을 때 쓰는 이름표(testTag). 화면에는 보이지 않는다. */
object EditScreenTags {
    const val CANVAS = "editCanvas"
    const val ZOOM_CHIP = "zoomChip"
    const val BUSY = "editBusy"
    const val UNDO = "undo"
    const val REDO = "redo"
    const val TOOL_OPTIONS = "toolOptions"
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
    const val TOOL_RAIL = "toolRail"
    const val TOOL_TAB_BAR = "toolTabBar"

    /** 커버 탭 줄 안의 칸들(옆으로 밀어 보는 줄) */
    const val TOOL_TAB_ROW = "toolTabRow"
    const val BOTTOM_PANEL = "bottomPanel"
    const val SIDE_PANEL = "sidePanel"
    const val ADJUST_PANEL = "adjustPanel"
    const val ADJUST_RESET_ALL = "adjustResetAll"
    const val CURVE_EDITOR = "curveEditor"
    const val CURVE_RESET = "curveReset"
    const val CURVE_DELETE_POINT = "curveDeletePoint"
    const val SIDE_ADJUST = "sideAdjust"
    const val SIDE_LAYERS = "sideLayers"
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
    const val LAYER_LIST = "layerList"
    const val LAYER_ADD = "layerAdd"
    const val LAYER_DELETE = "layerDelete"
    const val LAYER_UP = "layerUp"
    const val LAYER_DOWN = "layerDown"
    const val LAYER_OPACITY = "layerOpacity"
    const val LAYER_BLEND = "layerBlend"

    /** 왼쪽 도구 막대의 [tool] 버튼 */
    fun railTool(tool: EditTool): String = "railTool_${tool.name}"

    /** 커버 화면 탭 줄의 [tool] 칸 */
    fun tabTool(tool: EditTool): String = "tabTool_${tool.name}"

    /** 길게 눌러 뜬 메뉴의 [subTool] 항목 */
    fun subTool(subTool: SubTool): String = "subToolMenu_${subTool.name}"

    /** 옵션 위쪽에서 [subTool]로 바꾸는 버튼 */
    fun subToolOption(subTool: SubTool): String = "subToolOption_${subTool.name}"

    /** 선택 도구 옵션 */
    const val SELECTION_OPTIONS = "selectionOptions"
    const val SELECTION_FEATHER = "selectionFeather"
    const val SELECTION_TOLERANCE = "selectionTolerance"
    const val SELECTION_CONTIGUOUS = "selectionContiguous"
    const val SELECTION_ALL = "selectionAll"
    const val SELECTION_NONE = "selectionNone"
    const val SELECTION_INVERT = "selectionInvert"
    const val SELECTION_DELETE = "selectionDelete"
    const val SELECTION_COPY = "selectionCopy"
    const val SELECTION_CUT = "selectionCut"

    /** 선택 도구를 고르면 캔버스 위에 겹치는 터치 자리 */
    const val SELECTION_OVERLAY = "selectionOverlay"

    /** 선택 영역 윤곽선(개미 행렬) */
    const val SELECTION_OUTLINE = "selectionOutline"

    /** 픽셀 유동화 작업 모드 */
    const val LIQUIFY_MODE = "liquifyMode"
    const val LIQUIFY_CANVAS = "liquifyCanvas"
    const val LIQUIFY_OVERLAY = "liquifyOverlay"
    const val LIQUIFY_OPTIONS = "liquifyOptions"
    const val LIQUIFY_CANCEL = "liquifyCancel"
    const val LIQUIFY_UNDO = "liquifyUndo"
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
    const val RETOUCH_SOURCE = "retouchSource"
    const val RETOUCH_HINT = "retouchHint"

    /** 명암(닷지·번) 옵션과 캔버스 터치 자리 */
    const val TONE_OPTIONS = "toneOptions"
    const val TONE_OVERLAY = "toneOverlay"
    const val TONE_EXPOSURE = "toneExposure"
    const val TONE_SIZE = "toneSize"
    const val TONE_HARDNESS = "toneHardness"
    const val TONE_HINT = "toneHint"

    /** 명암 옵션의 [range] 범위 버튼 */
    fun toneRange(range: com.kiln.stratum.engine.tone.ToneRange): String = "toneRange_${range.name}"

    /** 유동화 작업 모드의 [tool] 도구 버튼 */
    fun liquifyTool(tool: com.kiln.stratum.engine.liquify.LiquifyTool): String = "liquifyTool_${tool.name}"

    /** 선택 도구 옵션의 합치는 방법 [mode] 버튼 */
    fun selectionMode(mode: com.kiln.stratum.engine.selection.SelectionMode): String = "selectionMode_${mode.name}"

    /** 아래 패널의 [tab] 탭 */
    fun panelTab(tab: PanelTab): String = "panelTab_${tab.name}"

    /** 보정 패널의 [item] 슬라이더 줄 */
    fun adjustSlider(item: AdjustItem): String = "adjustSlider_${item.name}"

    /** 레이어 목록의 [id] 레이어 줄 */
    fun layerRow(id: com.kiln.stratum.engine.document.LayerId): String = "layerRow_${id.value}"

    /** 레이어 줄의 순서 손잡이 */
    fun layerHandle(id: com.kiln.stratum.engine.document.LayerId): String = "layerHandle_${id.value}"

    /** 브러시 색 줄의 [index]번째 색 */
    fun brushColor(index: Int): String = "brushColor_$index"

    /** 혼합 모드 메뉴의 [mode] 항목 */
    fun blendMode(mode: com.kiln.stratum.engine.document.BlendMode): String = "blendMode_${mode.name}"

    /** 커버 화면 보정 옵션의 [item] 칩 */
    fun adjustChip(item: AdjustItem): String = "adjustChip_${item.name}"

    /** 자르기 비율 [ratio] 칩 */
    fun cropRatio(ratio: CropRatio): String = "cropRatio_${ratio.name}"

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

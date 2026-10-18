package com.kiln.stratum.ui.home

import androidx.annotation.StringRes
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.canvas.CanvasLimits
import com.kiln.stratum.engine.canvas.CanvasSizeCheck

/** 새 캔버스 프리셋 목록의 묶음(목록 제목) */
enum class PresetGroup(@get:StringRes val label: Int) {
    BASIC(R.string.preset_group_basic),
    FOLD8(R.string.preset_group_fold8)
}

/**
 * 새 캔버스 프리셋. 크기(픽셀)는 progress.md 결정 기록 '새 캔버스'대로다.
 *
 * @property ratioWidth 화면에 보일 비율의 가로. 폴드8 배경화면처럼 비율이 딱 떨어지지 않으면 0이고, 비율을 보이지 않는다.
 * @property group 목록에서 묶이는 곳
 */
enum class CanvasPreset(
    @get:StringRes val label: Int,
    val width: Int,
    val height: Int,
    val ratioWidth: Int = 0,
    val ratioHeight: Int = 0,
    val group: PresetGroup = PresetGroup.BASIC
) {
    SQUARE(R.string.preset_square, 3000, 3000, 1, 1),
    PORTRAIT(R.string.preset_portrait, 2400, 3000, 4, 5),
    STORY(R.string.preset_story, 2160, 3840, 9, 16),
    LANDSCAPE(R.string.preset_landscape, 3840, 2160, 16, 9),
    PHOTO_3_2(R.string.preset_photo, 3600, 2400, 3, 2),
    PHOTO_4_3(R.string.preset_photo, 4000, 3000, 4, 3),

    /** 폴드8 커버 화면 해상도(기획서 '대상 화면 사양') */
    FOLD8_COVER(R.string.preset_fold8_cover, 1248, 1972, group = PresetGroup.FOLD8),

    /** 폴드8 메인 화면을 세로로 든 해상도(기획서 '대상 화면 사양') */
    FOLD8_MAIN(R.string.preset_fold8_main, 1848, 2448, group = PresetGroup.FOLD8);

    companion object {
        /**
         * 크기가 [width]×[height]인 프리셋. 없으면 null(직접 입력).
         * 가로·세로를 바꾸면 다른 프리셋이 되거나(16:9 → 9:16) 프리셋이 아니게 된다.
         */
        fun matching(width: Int, height: Int): CanvasPreset? =
            entries.firstOrNull { it.width == width && it.height == height }
    }
}

/** 새 캔버스 배경으로 고를 수 있는 것(progress.md 결정 기록 '새 캔버스'). */
enum class CanvasBackground(@get:StringRes val label: Int, val fill: CanvasFill) {
    WHITE(R.string.new_canvas_background_white, CanvasFill.Solid(0xFFFFFFFF.toInt())),
    BLACK(R.string.new_canvas_background_black, CanvasFill.Solid(0xFF000000.toInt())),
    TRANSPARENT(R.string.new_canvas_background_transparent, CanvasFill.Transparent)
}

/** 가로·세로 입력 칸에 넣을 수 있는 최대 글자 수. 한도(16383)보다 큰 수도 들어가야 '범위 밖'을 알려 줄 수 있다. */
const val CANVAS_SIZE_MAX_DIGITS = 5

/** 입력 칸에 들어온 글자에서 0~9만 남기고, 너무 길면 자른다(붙여넣기도 이렇게 걸러진다). */
fun sizeDigits(text: String): String = text.filter { it in '0'..'9' }.take(CANVAS_SIZE_MAX_DIGITS)

/** 입력 칸의 가로·세로로 캔버스를 만들 수 있는지. 칸이 비었으면 범위 밖으로 본다. */
fun checkSizeInput(widthText: String, heightText: String): CanvasSizeCheck {
    val width = widthText.toIntOrNull() ?: return CanvasSizeCheck.SIDE_OUT_OF_RANGE
    val height = heightText.toIntOrNull() ?: return CanvasSizeCheck.SIDE_OUT_OF_RANGE
    return CanvasLimits.check(width, height)
}

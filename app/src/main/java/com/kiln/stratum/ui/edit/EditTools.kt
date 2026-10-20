package com.kiln.stratum.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R

/**
 * 하위 도구. 자르기·변형 도구, 선택 도구, 리터칭 도구, 명암 도구, 흐림 도구의 것이다. 어느 도구의 것인지는 [EditTool.subTools]가 정한다.
 *
 * @property shortLabel 옵션 위쪽 버튼에 보이는 짧은 이름. 커버 화면 폭에 한 줄로 들어가게 따로 둔다.
 * @property oneShot 고르면 창을 띄워 한 번 하고 끝나는 하위 도구(크기 변경). 도구를 다시 눌렀을 때 시작할 하위 도구로 기억하지 않는다.
 */
enum class SubTool(
    @get:StringRes val label: Int,
    @get:DrawableRes val icon: Int,
    @get:StringRes val shortLabel: Int = label,
    val oneShot: Boolean = false
) {
    CROP(R.string.subtool_crop, R.drawable.ic_crop),
    ROTATE(R.string.subtool_rotate, R.drawable.ic_rotate_90_degrees_cw, R.string.subtool_rotate_short),
    RESIZE(R.string.subtool_resize, R.drawable.ic_photo_size_select_large, R.string.subtool_resize_short, oneShot = true),

    /** 사각형 선택: 끌어서 사각형으로 고른다 */
    SELECT_RECT(R.string.subtool_select_rect, R.drawable.ic_crop_square, R.string.subtool_select_rect_short),

    /** 원형 선택: 끌어서 타원으로 고른다 */
    SELECT_ELLIPSE(R.string.subtool_select_ellipse, R.drawable.ic_panorama_fish_eye),

    /** 올가미: 손가락으로 둘러 그린 모양으로 고른다 */
    SELECT_LASSO(R.string.subtool_select_lasso, R.drawable.ic_gesture),

    /** 자동 선택(마술봉): 누른 곳과 비슷한 색을 고른다 */
    SELECT_WAND(R.string.subtool_select_wand, R.drawable.ic_auto_fix_high, R.string.subtool_select_wand_short),

    /** AI 선택: 누른 물체를 AI(분할 모델)가 모양대로 고른다(결정 기록 'AI 제거·AI 선택') */
    SELECT_AI(R.string.subtool_select_ai, R.drawable.ic_ads_click, R.string.subtool_select_ai_short),

    /** 스팟 복구 브러시: 칠한 곳을 둘레의 비슷한 곳에서 가져와 메운다 */
    RETOUCH_SPOT(R.string.subtool_spot_heal, R.drawable.ic_blur_circular, R.string.subtool_spot_heal_short),

    /** 복구 브러시: 정한 곳의 무늬를 가져와 둘레 밝기·색에 맞춘다 */
    RETOUCH_HEAL(R.string.subtool_heal, R.drawable.ic_healing, R.string.subtool_heal_short),

    /** 복제 도장: 정한 곳을 그대로 베낀다 */
    RETOUCH_CLONE(R.string.subtool_clone, R.drawable.ic_approval, R.string.subtool_clone_short),

    /** AI 제거: 칠한 곳을 AI(채우기 모델)가 둘레에 맞게 새로 그려 채운다(결정 기록 'AI 제거·AI 선택') */
    RETOUCH_REMOVE(R.string.subtool_ai_remove, R.drawable.ic_auto_awesome),

    /** 닷지: 칠한 곳을 밝게 한다 */
    TONE_DODGE(R.string.subtool_dodge, R.drawable.ic_light_mode),

    /** 번: 칠한 곳을 어둡게 한다 */
    TONE_BURN(R.string.subtool_burn, R.drawable.ic_dark_mode),

    /** 흐림: 칠한 곳을 흐리게 한다 */
    EFFECT_BLUR(R.string.subtool_blur, R.drawable.ic_water_drop),

    /** 선명: 칠한 곳의 가는 결을 또렷하게 한다 */
    EFFECT_SHARPEN(R.string.subtool_sharpen, R.drawable.ic_deblur),

    /** 스머지(손가락): 칠한 곳을 문지르듯 끌어 섞는다 */
    EFFECT_SMUDGE(R.string.subtool_smudge, R.drawable.ic_pan_tool_alt)
}

/**
 * 편집 도구. 1단계 도구(CLAUDE.md 8장)와 사용자가 요청한 도구(이동·선택·리터칭·명암·흐림·모자이크·픽셀 유동화)가 켜져 있다.
 * 개체 제거(3단계, AI)는 따로 '제거' 도구를 두지 않고 리터칭의 하위 도구 'AI 제거'로 넣었다(2026-10-20 사용자 결정).
 * 아직 만들지 않은 도구는 넣지 않는다(숨김).
 *
 * @property label 화면 읽기 기능이 읽는 이름
 * @property shortLabel 커버 화면 탭 줄에 보이는 짧은 이름. 6칸이 좁아 따로 둔다.
 * @property subTools 하위 도구. 있으면 아이콘 오른쪽 아래에 작은 삼각형을 그리고, 길게 누르면 고르는 메뉴를 띄운다(CLAUDE.md 6장).
 * @property available 기능이 있어 누를 수 있는지
 */
enum class EditTool(
    @get:DrawableRes val icon: Int,
    @get:StringRes val label: Int,
    @get:StringRes val shortLabel: Int = label,
    val subTools: List<SubTool> = emptyList(),
    val available: Boolean = false
) {
    /** 이동(포토샵의 이동 도구). 고른 레이어를 한 손가락으로 끌어 옮긴다(사용자 피드백 7차). 고를 옵션이 없다. */
    Move(R.drawable.ic_open_with, R.string.tool_move, R.string.tool_move_short, available = true),

    /** 자르기·변형. 하위 도구는 자르기 / 회전·뒤집기 / 크기 변경이다. */
    Transform(
        R.drawable.ic_crop,
        R.string.tool_transform,
        R.string.tool_transform_short,
        subTools = listOf(SubTool.CROP, SubTool.ROTATE, SubTool.RESIZE),
        available = true
    ),
    /** 조정(기본 보정). 커버 화면에서는 탭, 메인 화면에서는 패널(아래 패널의 탭, 오른쪽 패널의 칸)이다. */
    Adjust(R.drawable.ic_tune, R.string.tool_adjust, available = true),
    /** 선택. 하위 도구는 사각형 / 원형 / 올가미 / 자동 선택 / AI 선택이다. 고른 곳에만 브러시·지우개가 그려진다. */
    Select(
        R.drawable.ic_highlight_alt,
        R.string.tool_select,
        subTools = listOf(SubTool.SELECT_RECT, SubTool.SELECT_ELLIPSE, SubTool.SELECT_LASSO, SubTool.SELECT_WAND, SubTool.SELECT_AI),
        available = true
    ),
    /** 브러시. 고르면 캔버스를 한 손가락으로 끌어 고른 레이어에 그린다. */
    Brush(R.drawable.ic_brush, R.string.tool_brush, available = true),

    /** 지우개. 브러시와 같은 모양으로 고른 레이어를 지운다(투명하게). */
    Eraser(R.drawable.ic_ink_eraser, R.string.tool_eraser, available = true),

    /** 리터칭(반창고). 하위 도구는 스팟 복구 브러시 / 복구 브러시 / 복제 도장 / AI 제거다. 고른 레이어에 한 손가락으로 칠한다. */
    Retouch(
        R.drawable.ic_healing,
        R.string.tool_retouch,
        subTools = listOf(SubTool.RETOUCH_SPOT, SubTool.RETOUCH_HEAL, SubTool.RETOUCH_CLONE, SubTool.RETOUCH_REMOVE),
        available = true
    ),

    /** 명암(닷지·번). 칠한 곳을 밝게(닷지) 또는 어둡게(번) 한다. 고른 레이어에 한 손가락으로 칠한다. */
    Tone(
        R.drawable.ic_tonality,
        R.string.tool_tone,
        R.string.tool_tone_short,
        subTools = listOf(SubTool.TONE_DODGE, SubTool.TONE_BURN),
        available = true
    ),

    /** 흐림(흐림·선명·스머지). 칠한 곳을 흐리게·또렷하게 하거나 문질러 끈다(포토샵의 흐림·선명·손가락 도구). 고른 레이어에 한 손가락으로 칠한다. */
    Blur(
        R.drawable.ic_water_drop,
        R.string.tool_blur,
        R.string.tool_blur_short,
        subTools = listOf(SubTool.EFFECT_BLUR, SubTool.EFFECT_SHARPEN, SubTool.EFFECT_SMUDGE),
        available = true
    ),

    /** 모자이크. 칠한 곳을 네모 칸(칸마다 평균 색)으로 가린다(포토샵에는 필터로만 있다). 고른 레이어에 한 손가락으로 칠한다. */
    Mosaic(R.drawable.ic_grid_on, R.string.tool_mosaic, available = true),
    /** 글자. 고르면 캔버스를 눌러 그 자리에 글자 레이어를 더한다. */
    Text(R.drawable.ic_text_fields, R.string.tool_text, available = true),
    /** 픽셀 유동화. 고르면 편집 화면을 덮는 작업 모드가 열린다(도구로 남지 않는다). */
    Liquify(R.drawable.ic_waves, R.string.tool_liquify, R.string.tool_liquify_short, available = true),
    /** 레이어. 커버 화면에서는 탭, 메인 화면에서는 패널(아래 패널의 탭, 오른쪽 패널의 칸)이다. */
    Layers(R.drawable.ic_layers, R.string.tool_layers, available = true);

    /** 하위 도구가 있는지. */
    val hasSubTools: Boolean
        get() = subTools.isNotEmpty()

    companion object {
        /** 메인 화면 왼쪽 도구 막대의 도구(이동이 맨 위, 사용자 요청). 조정·레이어는 패널에 있다. */
        val railTools: List<EditTool> = listOf(Move, Transform, Select, Brush, Eraser, Retouch, Tone, Blur, Mosaic, Text, Liquify)

        /** 커버 화면 아래 탭 줄의 도구(이동이 맨 앞). 조정·레이어는 고르면 캔버스 아래 옵션 자리에 열린다. 폭이 모자라면 옆으로 밀어 본다. */
        val tabTools: List<EditTool> = listOf(Move, Transform, Adjust, Select, Brush, Eraser, Retouch, Tone, Blur, Mosaic, Text, Liquify, Layers)

        /** [subTool]이 속한 도구 */
        fun of(subTool: SubTool): EditTool = entries.first { subTool in it.subTools }
    }
}

/** 도구 아이콘. 하위 도구가 있으면 오른쪽 아래에 작은 삼각형을 겹쳐 그린다. */
@Composable
fun ToolIcon(tool: EditTool, contentDescription: String?) {
    Box {
        Icon(painter = painterResource(tool.icon), contentDescription = contentDescription)
        if (tool.hasSubTools) {
            SubToolMark(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp))
        }
    }
}

/** 하위 도구가 있다는 작은 삼각형. 아이콘과 같은 색(비활성이면 흐린 색)으로 그린다. */
@Composable
internal fun SubToolMark(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(6.dp)) {
        val triangle = Path().apply {
            moveTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(triangle, color)
    }
}

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
 * 하위 도구. 지금은 자르기·변형 도구의 것뿐이다.
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
    RESIZE(R.string.subtool_resize, R.drawable.ic_photo_size_select_large, R.string.subtool_resize_short, oneShot = true)
}

/**
 * 1단계 편집 도구(CLAUDE.md 8장). 자르기·변형, 조정, 레이어가 켜져 있고, 나머지는 기능이 생길 때까지 비활성이다.
 * 2·3단계 도구(반창고·제거 등)는 넣지 않는다(숨김).
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
    Brush(R.drawable.ic_brush, R.string.tool_brush),
    Eraser(R.drawable.ic_ink_eraser, R.string.tool_eraser),
    Text(R.drawable.ic_text_fields, R.string.tool_text),
    /** 레이어. 커버 화면에서는 탭, 메인 화면에서는 패널(아래 패널의 탭, 오른쪽 패널의 칸)이다. */
    Layers(R.drawable.ic_layers, R.string.tool_layers, available = true);

    /** 하위 도구가 있는지. */
    val hasSubTools: Boolean
        get() = subTools.isNotEmpty()

    companion object {
        /** 메인 화면 왼쪽 도구 막대의 도구. 조정·레이어는 패널에 있다. */
        val railTools: List<EditTool> = listOf(Transform, Brush, Eraser, Text)

        /** 커버 화면 아래 탭 줄의 도구. 조정·레이어는 고르면 캔버스 아래 옵션 자리에 열린다. */
        val tabTools: List<EditTool> = listOf(Transform, Adjust, Brush, Eraser, Text, Layers)
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
private fun SubToolMark(modifier: Modifier = Modifier) {
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

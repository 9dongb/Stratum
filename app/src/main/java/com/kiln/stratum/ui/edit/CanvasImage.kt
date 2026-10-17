package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R

/** 투명한 곳을 보여 주는 체크 무늬의 두 칸 색. 다른 편집 앱처럼 흰색과 밝은 회색이다. */
internal val CheckerLight = Color(0xFFFFFFFF)
internal val CheckerDark = Color(0xFFCCCCCC)

/** 체크 무늬 한 칸의 크기. */
internal val CheckerSquare = 8.dp

/** 캔버스 둘레 테두리의 두께. */
internal val CanvasOutlineWidth = 1.dp

/**
 * 엔진이 그린 축소본을 캔버스 영역에 비율 그대로 맞춰 보여 준다. 화면 코드는 이미지 픽셀을 계산하지 않는다(CLAUDE.md 3장).
 *
 * 투명한 곳이 보이도록 축소본 아래에 체크 무늬를 깔고, 검은 캔버스도 어두운 배경과 구분되도록 바깥에 테두리를 두른다.
 * 축소본을 키워 보일 때는 픽셀이 또렷하게 가장 가까운 픽셀로, 줄여 보일 때는 부드럽게 그린다.
 * 모두 GPU가 그린다. 체크 무늬는 2×2 비트맵을 반복하는 셰이더라 CPU로 픽셀을 돌지 않는다.
 */
@Composable
fun CanvasImage(state: EditorState.Ready, modifier: Modifier = Modifier) {
    // 축소본이 바뀔 때만 Compose용 이미지로 감싼다
    val image = remember(state.preview) { state.preview.asImageBitmap() }
    val checker = remember { checkerBitmap() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.edit_canvas_description)
    Spacer(
        modifier
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .drawWithCache {
                val fit = fitRect(size.width.toInt(), size.height.toInt(), state.documentSize)
                val topLeft = Offset(fit.left.toFloat(), fit.top.toFloat())
                val fitSize = Size(fit.width.toFloat(), fit.height.toFloat())
                val square = CheckerSquare.toPx()
                val checkerBrush = ShaderBrush(
                    BitmapShader(checker, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                        filterMode = BitmapShader.FILTER_MODE_NEAREST
                        // 한 픽셀을 한 칸 크기로 늘이고, 캔버스 왼쪽 위에서 무늬가 시작하게 맞춘다
                        setLocalMatrix(Matrix().apply {
                            setScale(square, square)
                            postTranslate(topLeft.x, topLeft.y)
                        })
                    }
                )
                val filter = if (fit.width > image.width) FilterQuality.None else FilterQuality.Low
                val stroke = CanvasOutlineWidth.toPx()
                onDrawBehind {
                    drawRect(checkerBrush, topLeft, fitSize)
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(fit.left, fit.top),
                        dstSize = IntSize(fit.width, fit.height),
                        filterQuality = filter
                    )
                    // 선은 가운데를 기준으로 그려지므로, 두께의 반만큼 바깥으로 옮겨 캔버스를 덮지 않게 한다
                    drawRect(
                        color = outline,
                        topLeft = Offset(topLeft.x - stroke / 2, topLeft.y - stroke / 2),
                        size = Size(fitSize.width + stroke, fitSize.height + stroke),
                        style = Stroke(stroke)
                    )
                }
            }
    )
}

/** 체크 무늬 한 벌(2×2칸) 비트맵. 셰이더가 칸 크기로 늘여 반복해 그린다. */
private fun checkerBitmap(): Bitmap {
    val light = CheckerLight.toArgb()
    val dark = CheckerDark.toArgb()
    return Bitmap.createBitmap(intArrayOf(light, dark, dark, light), 2, 2, Bitmap.Config.ARGB_8888)
}

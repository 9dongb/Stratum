package com.kiln.stratum.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.CanvasOverlayColors
import kotlin.math.roundToInt

/**
 * 크기·단단함 슬라이더를 끄거나 세 손가락으로 미는 동안 캔버스에 브러시 크기를 보일지. 값이 바뀌면 보이고, 손을 떼면 바로 사라진다.
 */
@Stable
class SizePreviewState {
    /** 슬라이더를 끄는(세 손가락으로 미는) 중 */
    var visible: Boolean by mutableStateOf(false)
        private set

    /** 값이 바뀌었다 */
    fun onChange() {
        visible = true
    }

    /** 손을 뗐다 */
    fun onFinished() {
        visible = false
    }
}

/** 크기 미리 보기 상태 */
@Composable
fun rememberSizePreviewState(): SizePreviewState = remember { SizePreviewState() }

/**
 * 캔버스 가운데에 지름 [size](문서 픽셀)의 원을 지금 보기 배율로 그린다. 그 크기로 칠하면 얼마나 덮는지 보인다.
 * [hardness]가 있으면(0과 1 사이) 안쪽에 단단함 자리(이 안은 다 칠해지고 바깥으로 옅어짐)를 옅은 원으로 함께 그린다.
 * 단단함 슬라이더를 끄는 동안에도 같은 원을 보여, 단단함 자리가 어떻게 바뀌는지 보인다.
 * 캔버스 밖은 자른다. 터치를 받지 않아 아래 도구 터치 자리에 그대로 간다.
 * [bottomInset]은 캔버스 아래쪽을 덮은 것(닫힌 화면의 옵션 칸)의 높이로, 원은 그 위 보이는 자리의 가운데에 둔다(크기 배율은 캔버스 전체로 잰다).
 */
@Composable
fun SizePreviewOverlay(view: () -> CanvasView, document: PixelSize, size: Float, hardness: Float?, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    // 문서 자리가 아니라 캔버스 가운데(옵션 칸 위)에 두는 표시라, 화면 반전을 되돌려 늘 같은 자리에 둔다
    Canvas(modifier.undoViewFlip(LocalViewFlip.current).clipToBounds().testTag(EditScreenTags.SIZE_PREVIEW)) {
        val area = PixelSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        if (area.width <= 0 || area.height <= 0) return@Canvas
        val scale = view().transform(area, document).scaleX.toFloat()
        val visibleHeight = (this.size.height - bottomInset.toPx()).coerceAtLeast(0f)
        drawBrushSize(Offset(this.size.width / 2f, visibleHeight / 2f), size / 2f * scale, hardness)
    }
}

/** 브러시 크기 원: 어떤 사진 위에서도 보이게 검은 테두리 위에 흰 선이다(리터칭의 가져올 곳 표시와 같다). 아주 작아도 1dp는 보인다. */
private fun DrawScope.drawBrushSize(center: Offset, radius: Float, hardness: Float?) {
    val ring = radius.coerceAtLeast(1.dp.toPx())
    drawCircle(CanvasOverlayColors.Dark, ring, center, style = Stroke(3.dp.toPx()))
    drawCircle(CanvasOverlayColors.Light, ring, center, style = Stroke(1.5.dp.toPx()))
    if (hardness != null && hardness > 0f && hardness < 1f) {
        val core = ring * hardness
        drawCircle(CanvasOverlayColors.DarkHalo, core, center, style = Stroke(2.dp.toPx()))
        drawCircle(CanvasOverlayColors.LightHalo, core, center, style = Stroke(1.dp.toPx()))
    }
}

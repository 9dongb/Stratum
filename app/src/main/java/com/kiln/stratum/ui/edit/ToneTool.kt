package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.tone.ToneRange

/**
 * 명암 도구(닷지·번) 설정. 브러시 모양(크기·단단함), 바꿀 밝기 범위, 노출(0..1). 닷지·번 모두 같이 쓴다(포토샵의 범위·노출과 같다).
 * 브러시 불투명도는 쓰지 않는다(세기는 노출이 정한다).
 */
data class ToneOptions(
    val brush: BrushSettings = BrushSettings(size = 100f, hardness = 0.3f),
    val range: ToneRange = ToneRange.MIDTONES,
    val exposure: Float = 0.5f
)

/** 캔버스의 명암 손가락이 엔진 쪽(ViewModel)에 보내는 일. 좌표는 문서 픽셀이다. */
interface ToneActions {

    /** 한 손가락이 닿아 획을 시작한다([dodge]면 밝게, 아니면 어둡게) */
    fun begin(x: Float, y: Float, dodge: Boolean)

    /** 손가락이 움직였다 */
    fun extend(x: Float, y: Float)

    /** 손가락을 뗐다. 획을 기록한다. */
    fun end()

    /** 두 손가락 조작이 시작돼 획을 버린다 */
    fun cancel()

    /** 아무것도 하지 않는다(미리보기·화면 테스트) */
    object None : ToneActions {
        override fun begin(x: Float, y: Float, dodge: Boolean) = Unit
        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }
}

/**
 * 명암 옵션이 쓰는 값과 할 일. 설정은 ViewModel이 들고(앱을 끌 때까지 남음), 바꾸면 [onChange]로 알린다.
 * [sizePreview]는 크기·단단함 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태다(없으면 보이지 않음).
 */
@Stable
class ToneControls(val options: ToneOptions, val sizePreview: SizePreviewState? = null, val onChange: (ToneOptions) -> Unit)

internal fun toneRangeLabel(range: ToneRange): Int =
    when (range) {
        ToneRange.SHADOWS -> R.string.tone_shadows
        ToneRange.MIDTONES -> R.string.tone_midtones
        ToneRange.HIGHLIGHTS -> R.string.tone_highlights
    }

/** 범위의 아이콘(메인 화면 띠의 범위 칸): 어두운 영역은 밝기 낮음, 중간은 반, 밝은 영역은 밝기 높음(Material Icons) */
internal fun toneRangeIcon(range: ToneRange): Int =
    when (range) {
        ToneRange.SHADOWS -> R.drawable.ic_brightness_low
        ToneRange.MIDTONES -> R.drawable.ic_brightness_medium
        ToneRange.HIGHLIGHTS -> R.drawable.ic_brightness_high
    }

/**
 * 명암 도구를 고르면 캔버스 위에 겹치는 터치 자리. 한 손가락으로 끌어 닷지([dodge]) 또는 번 획을 그린다(브러시와 같은 손가락 처리).
 * [cursorSize](문서 픽셀 지름)가 있으면 칠하는 손가락 자리에 브러시 커서를 보인다.
 */
@Composable
fun ToneOverlay(view: () -> CanvasView, document: PixelSize, dodge: Boolean, actions: ToneActions, modifier: Modifier = Modifier, cursorSize: Float? = null) {
    PaintGestureOverlay(
        view = view,
        document = document,
        tag = EditScreenTags.TONE_OVERLAY,
        onBegin = { x, y -> actions.begin(x, y, dodge) },
        onExtend = actions::extend,
        onEnd = actions::end,
        onCancel = actions::cancel,
        modifier = modifier,
        cursorSize = cursorSize
    )
}

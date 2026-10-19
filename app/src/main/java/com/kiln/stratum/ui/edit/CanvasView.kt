package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 확대할 수 있는 가장 큰 배율. 문서 픽셀 하나가 화면 픽셀 32개(3200%)다. */
const val MAX_VIEW_SCALE = 32.0

/** 줄일 수 있는 가장 작은 배율. 화면에 맞춘 크기의 절반이다. */
const val MIN_VIEW_SCALE_OF_FIT = 0.5

/**
 * 캔버스를 어떻게 보는지(보기 상태). 편집 상태가 아니라 보는 방법이라 화면 쪽이 들고 있다.
 *
 * - [Fit]: 캔버스 영역에 문서를 비율 그대로 맞춰 본다.
 * - [Free]: 배율과, 캔버스 영역 가운데에 오는 문서 위치로 본다.
 *
 * 가운데 위치를 기준으로 두어서, 화면 크기가 바뀌어도(접기·펼치기·회전) 배율과 보던 곳이 그대로 남는다.
 * 나중에 화면 회전을 더할 때도 이 기준을 그대로 쓸 수 있다.
 */
sealed interface CanvasView {

    data object Fit : CanvasView

    /**
     * @property scale 문서 픽셀 하나가 화면 픽셀 몇 개인지. 1이면 100%다.
     * @property centerX 캔버스 영역 가운데에 오는 문서 위치(문서 픽셀). 늘 문서 안이다.
     */
    data class Free(val scale: Double, val centerX: Double, val centerY: Double) : CanvasView
}

/** 문서 좌표를 캔버스 영역 좌표(픽셀)로 바꾸는 변환. 화면 x = [originX] + 문서 x × [scaleX]. */
data class ViewTransform(val scaleX: Double, val scaleY: Double, val originX: Double, val originY: Double) {

    fun toScreenX(documentX: Double): Double = originX + documentX * scaleX

    fun toScreenY(documentY: Double): Double = originY + documentY * scaleY

    fun toDocumentX(screenX: Double): Double = (screenX - originX) / scaleX

    fun toDocumentY(screenY: Double): Double = (screenY - originY) / scaleY
}

/** [area] 크기 캔버스 영역에 [document]를 맞췄을 때의 배율. */
fun fitScale(area: PixelSize, document: PixelSize): Double =
    minOf(area.width.toDouble() / document.width, area.height.toDouble() / document.height)

/**
 * [area]에서 왼쪽 [insetStart]·오른쪽 [insetEnd]·위 [insetTop]·아래 [insetBottom](픽셀)을 뺀 자리에 [document]를 비율 그대로 맞춘 보기.
 * 메인 화면에서 자르기를 시작할 때 테두리 손잡이가 왼쪽 도구 옵션 띠에 가리지 않게 띠를 뺀 자리에 맞춘다(결정 기록 '편집 화면 도구 배치').
 * 남는 자리가 없으면 그냥 맞춘 보기와 같은 배율·가운데다.
 */
fun fitInside(area: PixelSize, document: PixelSize, insetStart: Int, insetEnd: Int = 0, insetTop: Int = 0, insetBottom: Int = 0): CanvasView.Free {
    val width = area.width - insetStart - insetEnd
    val height = area.height - insetTop - insetBottom
    if (width <= 0 || height <= 0) {
        return CanvasView.Free(fitScale(area, document), document.width / 2.0, document.height / 2.0)
    }
    val scale = minOf(width.toDouble() / document.width, height.toDouble() / document.height)
    // 문서 가운데가 남은 자리의 가운데에 오도록, 캔버스 영역 가운데에 오는 문서 위치를 정한다
    val centerX = document.width / 2.0 - (insetStart - insetEnd) / (2 * scale)
    val centerY = document.height / 2.0 - (insetTop - insetBottom) / (2 * scale)
    return CanvasView.Free(
        scale = scale,
        centerX = centerX.coerceIn(0.0, document.width.toDouble()),
        centerY = centerY.coerceIn(0.0, document.height.toDouble())
    )
}

/**
 * 메인 화면에서 자르기를 시작할 때의 보기: 왼쪽 도구 옵션 띠 자리([strip], 픽셀)와 둘레 [margin]을 왼쪽·오른쪽에 똑같이 비우고 사진 전체를 맞춘다.
 * 그래서 테두리 손잡이가 띠에 가리지 않으면서 사진이 캔버스 정중앙에 온다(2026-10-20 사용자 요청, 전에는 띠를 뺀 오른쪽 자리의 가운데라 살짝 오른쪽이었다).
 */
fun cropStartView(area: PixelSize, document: PixelSize, strip: Int, margin: Int): CanvasView.Free =
    fitInside(area, document, insetStart = strip + margin, insetEnd = strip + margin, insetTop = margin, insetBottom = margin)

/**
 * 이 보기로 그릴 때의 변환.
 * 맞춤은 [fitRect]의 정수 사각형을 그대로 써서, 지금까지의 표시(테두리·1px 최소)와 똑같다.
 */
fun CanvasView.transform(area: PixelSize, document: PixelSize): ViewTransform =
    when (this) {
        CanvasView.Fit -> {
            val fit = fitRect(area.width, area.height, document)
            ViewTransform(
                scaleX = fit.width.toDouble() / document.width,
                scaleY = fit.height.toDouble() / document.height,
                originX = fit.left.toDouble(),
                originY = fit.top.toDouble()
            )
        }
        is CanvasView.Free -> ViewTransform(
            scaleX = scale,
            scaleY = scale,
            originX = area.width / 2.0 - centerX * scale,
            originY = area.height / 2.0 - centerY * scale
        )
    }

/** 이 보기의 배율. 맞춤이면 맞춘 배율이다. */
fun CanvasView.scale(area: PixelSize, document: PixelSize): Double =
    when (this) {
        CanvasView.Fit -> fitScale(area, document)
        is CanvasView.Free -> scale
    }

/**
 * 두 손가락 조작 한 번을 적용한 보기. 손가락 가운데가 [panX]·[panY]만큼 옮겨 [centroidX]·[centroidY]에 왔고,
 * 손가락 사이가 [zoom]배가 됐다. 손가락 아래 있던 문서 위치가 손가락을 따라간다.
 *
 * 배율은 [맞춤의 절반, max(3200%, 맞춤)] 안으로 막되, 접기·펼치기로 범위가 바뀌어 이미 밖에 있으면 그 자리에서
 * 튀지 않게 지금 배율까지는 허용한다. 가운데 위치는 문서 밖으로 나가지 않아, 화면 가운데에는 늘 캔버스가 있다.
 */
fun CanvasView.transformedBy(
    panX: Double,
    panY: Double,
    zoom: Double,
    centroidX: Double,
    centroidY: Double,
    area: PixelSize,
    document: PixelSize
): CanvasView.Free {
    val current = transform(area, document)
    val scale = scale(area, document)
    val fit = fitScale(area, document)
    val minScale = minOf(fit * MIN_VIEW_SCALE_OF_FIT, scale)
    val maxScale = maxOf(MAX_VIEW_SCALE, fit, scale)
    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
    // 조작 전 손가락 가운데 아래의 문서 위치. 맞춤은 가로·세로 배율이 조금 다를 수 있어 축마다 구한다.
    val documentX = current.toDocumentX(centroidX - panX)
    val documentY = current.toDocumentY(centroidY - panY)
    // 그 문서 위치가 새 손가락 가운데에 오도록 화면 가운데의 문서 위치를 정한다
    val centerX = documentX + (area.width / 2.0 - centroidX) / newScale
    val centerY = documentY + (area.height / 2.0 - centroidY) / newScale
    return CanvasView.Free(
        scale = newScale,
        centerX = centerX.coerceIn(0.0, document.width.toDouble()),
        centerY = centerY.coerceIn(0.0, document.height.toDouble())
    )
}

/** 캔버스 영역에 보이는 문서 영역(문서 픽셀). 문서 밖으로 나갈 수 있고, 엔진이 문서 안만 쓴다. */
fun CanvasView.visibleRect(area: PixelSize, document: PixelSize): PixelRect {
    val transform = transform(area, document)
    return PixelRect(
        left = floor(transform.toDocumentX(0.0)).toInt(),
        top = floor(transform.toDocumentY(0.0)).toInt(),
        right = ceil(transform.toDocumentX(area.width.toDouble())).toInt(),
        bottom = ceil(transform.toDocumentY(area.height.toDouble())).toInt()
    )
}

/**
 * 화면이 엔진 쪽(ViewModel)에 알리는 지금 보기. [documentSize] 문서를 배율 [scale]로 볼 때,
 * 캔버스 영역에 보이는 문서 영역이 [visible]이다(문서 밖으로 나갈 수 있다). 엔진 쪽이 보이는 곳을 선명하게 다시 그리는 데 쓴다.
 * [fitScale]은 같은 캔버스 영역에서 화면에 맞춤의 배율이다(확대했는지 보는 데 쓴다).
 */
data class Viewport(val documentSize: PixelSize, val scale: Double, val visible: PixelRect, val fitScale: Double = scale)

/** [area] 크기 캔버스 영역에서 이 보기로 [document]를 볼 때의 [Viewport]. */
fun CanvasView.viewport(area: PixelSize, document: PixelSize): Viewport =
    Viewport(document, scale(area, document), visibleRect(area, document), fitScale(area, document))

/** 비율 표시의 숫자(배율 × 100). 10% 미만은 소수 첫째 자리까지, 그 위는 정수로 보인다. */
fun zoomPercentText(scale: Double): String {
    val percent = scale * 100
    return if (percent < 10) {
        String.format(Locale.ROOT, "%.1f", percent)
    } else {
        percent.roundToInt().toString()
    }
}

/** 접기·펼치기나 백그라운드 종료 뒤에도 남기도록 숫자 목록으로 바꾼다. 맞춤은 빈 목록이다. */
fun CanvasView.toSaved(): List<Double> =
    when (this) {
        CanvasView.Fit -> emptyList()
        is CanvasView.Free -> listOf(scale, centerX, centerY)
    }

/** [toSaved]로 바꾼 목록에서 보기 상태를 되살린다. */
fun canvasViewFromSaved(saved: List<Double>): CanvasView =
    if (saved.size == 3) CanvasView.Free(saved[0], saved[1], saved[2]) else CanvasView.Fit

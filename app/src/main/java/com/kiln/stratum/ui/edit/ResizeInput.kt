package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.kiln.stratum.engine.canvas.CanvasLimits
import com.kiln.stratum.engine.canvas.CanvasSizeCheck
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.home.sizeDigits
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** 크기 변경 창의 단위 */
enum class ResizeUnit { PIXELS, PERCENT }

/** 퍼센트 칸에 넣을 수 있는 글자 수(예: 1234.5) */
private const val PERCENT_MAX_CHARS = 6

/**
 * 크기 변경 창의 입력값. 가로·세로는 칸에 보이는 글자 그대로 둔다(고쳐 쓰는 동안 빈 칸도 된다).
 *
 * - 픽셀이면 가로·세로 픽셀, 퍼센트면 지금 문서 [document] 크기에 대한 퍼센트(소수 한 자리까지)다.
 * - 비율 유지([locked], 기본으로 켬)면 한 칸을 고칠 때 다른 칸을 지금 문서의 가로세로 비율로 맞춘다.
 * - 한도는 CanvasLimits.checkResize다(각 변 1~16383px, 전체 50MP, 지금 이미지가 더 크면 지금 크기까지).
 */
@Stable
class ResizeInput(
    val document: PixelSize,
    width: String,
    height: String,
    unit: ResizeUnit,
    locked: Boolean
) {
    var width: String by mutableStateOf(width)
        private set

    var height: String by mutableStateOf(height)
        private set

    var unit: ResizeUnit by mutableStateOf(unit)
        private set

    var locked: Boolean by mutableStateOf(locked)
        private set

    /** 가로 칸을 [text]로 고친다. 숫자만 받고, 비율 유지면 세로를 맞춘다. */
    fun changeWidth(text: String) {
        width = clean(text)
        if (locked) matchedFrom(width, fromWidth = true)?.let { height = it }
    }

    /** 세로 칸을 [text]로 고친다. 숫자만 받고, 비율 유지면 가로를 맞춘다. */
    fun changeHeight(text: String) {
        height = clean(text)
        if (locked) matchedFrom(height, fromWidth = false)?.let { width = it }
    }

    /** 비율 유지를 켜거나 끈다. 켜면 가로에 맞춰 세로를 고친다. */
    fun changeLocked(value: Boolean) {
        locked = value
        if (value) matchedFrom(width, fromWidth = true)?.let { height = it }
    }

    /** 단위를 바꾼다. 지금 입력한 크기를 새 단위로 옮겨 적는다(숫자가 아니면 그대로 둔다). */
    fun changeUnit(value: ResizeUnit) {
        if (value == unit) return
        val targetWidth = targetWidth()
        val targetHeight = targetHeight()
        unit = value
        if (targetWidth != null) width = toText(targetWidth, document.width)
        if (targetHeight != null) height = toText(targetHeight, document.height)
    }

    /** 바꿀 가로 픽셀. 숫자가 아니면 null. */
    fun targetWidth(): Int? = toPixels(width, document.width)

    /** 바꿀 세로 픽셀. 숫자가 아니면 null. */
    fun targetHeight(): Int? = toPixels(height, document.height)

    /** 지금 입력으로 바꿀 수 있는지. 칸이 비었거나 숫자가 아니면 범위 밖으로 본다. */
    val check: CanvasSizeCheck
        get() {
            val w = targetWidth() ?: return CanvasSizeCheck.SIDE_OUT_OF_RANGE
            val h = targetHeight() ?: return CanvasSizeCheck.SIDE_OUT_OF_RANGE
            return CanvasLimits.checkResize(document, w, h)
        }

    /** 적용할 수 있는지. 한도 안이고 지금 크기와 다르다. */
    val canApply: Boolean
        get() = check == CanvasSizeCheck.OK && (targetWidth() != document.width || targetHeight() != document.height)

    /** 퍼센트는 숫자와 점 하나만, 픽셀은 숫자만 받는다. */
    private fun clean(text: String): String =
        when (unit) {
            ResizeUnit.PIXELS -> sizeDigits(text)
            ResizeUnit.PERCENT -> {
                val kept = text.filter { it in '0'..'9' || it == '.' }
                val firstDot = kept.indexOf('.')
                (if (firstDot < 0) kept else kept.substring(0, firstDot + 1) + kept.substring(firstDot + 1).replace(".", ""))
                    .take(PERCENT_MAX_CHARS)
            }
        }

    /** 한 칸의 글자 [text]에 비율을 맞춘 다른 칸의 글자. 숫자가 아니면 null. */
    private fun matchedFrom(text: String, fromWidth: Boolean): String? =
        when (unit) {
            // 퍼센트는 가로·세로가 같은 퍼센트다
            ResizeUnit.PERCENT -> text.toDoubleOrNull()?.let { text }
            ResizeUnit.PIXELS -> {
                val value = text.toIntOrNull() ?: return null
                val matched = if (fromWidth) {
                    value.toLong() * document.height / document.width.toDouble()
                } else {
                    value.toLong() * document.width / document.height.toDouble()
                }
                max(1, matched.roundToInt()).toString()
            }
        }

    /** 칸의 글자를 픽셀로. 퍼센트는 문서 한 변 [side]에 대한 비율이다. */
    private fun toPixels(text: String, side: Int): Int? =
        when (unit) {
            ResizeUnit.PIXELS -> text.toIntOrNull()
            ResizeUnit.PERCENT -> text.toDoubleOrNull()?.let { (side * it / 100).roundToInt() }
        }

    /** 픽셀 [pixels]를 지금 단위의 글자로. 퍼센트는 소수 한 자리까지 쓰고 끝의 .0은 뗀다. */
    private fun toText(pixels: Int, side: Int): String =
        when (unit) {
            ResizeUnit.PIXELS -> pixels.toString()
            ResizeUnit.PERCENT -> String.format(Locale.ROOT, "%.1f", pixels * 100.0 / side).removeSuffix(".0")
        }

    companion object {
        /** [document] 크기에서 시작하는 입력(픽셀, 비율 유지). */
        fun start(document: PixelSize): ResizeInput =
            ResizeInput(document, document.width.toString(), document.height.toString(), ResizeUnit.PIXELS, locked = true)

        /** 창이 열려 있는 동안 접기·펼치기에도 남게 글자로 저장한다. 창이 닫혀 있으면(null) 저장하지 않는다. */
        val Saver: Saver<ResizeInput?, List<String>> = Saver(
            save = { input ->
                input?.let {
                    listOf(
                        it.document.width.toString(),
                        it.document.height.toString(),
                        it.width,
                        it.height,
                        it.unit.name,
                        it.locked.toString()
                    )
                }
            },
            restore = {
                ResizeInput(
                    document = PixelSize(it[0].toInt(), it[1].toInt()),
                    width = it[2],
                    height = it[3],
                    unit = ResizeUnit.valueOf(it[4]),
                    locked = it[5].toBoolean()
                )
            }
        )
    }
}

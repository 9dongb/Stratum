package com.kiln.stratum.engine.platform

import android.graphics.Paint
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Layer
import kotlin.math.roundToInt
import android.graphics.BlendMode as AndroidBlendMode

/**
 * 혼합 모드를 안드로이드 그리기(Skia)의 혼합 모드로. 계산은 [com.kiln.stratum.engine.document.BlendMath]와 같다.
 * 곱하기는 `BlendMode.MULTIPLY`다(`PorterDuff.Mode.MULTIPLY`는 알파까지 곱하는 다른 계산이라 쓰지 않는다).
 */
internal fun BlendMode.toAndroid(): AndroidBlendMode =
    when (this) {
        BlendMode.NORMAL -> AndroidBlendMode.SRC_OVER
        BlendMode.MULTIPLY -> AndroidBlendMode.MULTIPLY
        BlendMode.SCREEN -> AndroidBlendMode.SCREEN
        BlendMode.OVERLAY -> AndroidBlendMode.OVERLAY
    }

/** [layer]를 아래에 얹는 물감: 불투명도와 혼합 모드. 따로 그린 레이어(saveLayer)나 레이어 축소본을 얹을 때 쓴다. */
internal fun layerPaint(layer: Layer): Paint =
    Paint().apply {
        alpha = (layer.opacity * 255).roundToInt()
        blendMode = layer.blendMode.toAndroid()
    }

/** 타일을 바로 그리지 않고 따로 그린 뒤 얹어야 하는지(불투명도가 1보다 작거나 표준이 아니면) */
internal val Layer.needsOwnLayer: Boolean
    get() = opacity < 1f || blendMode != BlendMode.NORMAL

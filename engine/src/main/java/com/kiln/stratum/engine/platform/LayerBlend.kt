package com.kiln.stratum.engine.platform

import android.graphics.Paint
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.CompositeStep
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

/**
 * 합성 단계 [step]의 레이어를 아래에 얹는 물감: 얹는 불투명도와 혼합 모드. 따로 그린 레이어(saveLayer)나 레이어 축소본을 얹을 때 쓴다.
 * 따로 그려야 하는지는 [CompositeStep.Draw.needsOwnLayer]다.
 */
internal fun layerPaint(step: CompositeStep.Draw): Paint =
    Paint().apply {
        alpha = (step.opacity * 255).roundToInt()
        blendMode = step.blendMode.toAndroid()
    }

package com.kiln.stratum.engine.canvas

/** 새 캔버스의 배경을 무엇으로 채울지. */
sealed interface CanvasFill {

    /** 투명. 타일을 하나도 두지 않아서 메모리를 쓰지 않는다. */
    data object Transparent : CanvasFill

    /**
     * 한 가지 색. [argb]는 `0xAARRGGBB`이고 알파를 미리 곱하지 않은 값이다.
     * 그 색으로 칠한 타일 한 장을 캔버스의 모든 자리가 함께 쓴다.
     */
    data class Solid(val argb: Int) : CanvasFill
}

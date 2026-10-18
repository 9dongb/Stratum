package com.kiln.stratum.engine.retouch

/** 리터칭 획의 종류(포토샵의 스팟 복구 브러시·복구 브러시·복제 도장) */
enum class RetouchMode {
    /** 스팟 복구 브러시: 가져올 곳을 칠한 곳 둘레에서 찾아 복구 브러시처럼 그린다 */
    SPOT_HEAL,

    /** 복구 브러시: 정한 곳의 무늬를 가져오고, 밝기·색은 칠한 곳 둘레에 맞춘다 */
    HEAL,

    /** 복제 도장: 정한 곳의 픽셀을 그대로 베낀다 */
    CLONE
}

/** 칠한 자리에서 가져올 자리까지(문서 픽셀). 가져올 곳 = 칠한 곳 - ([x], [y]). */
data class SourceOffset(val x: Int, val y: Int)

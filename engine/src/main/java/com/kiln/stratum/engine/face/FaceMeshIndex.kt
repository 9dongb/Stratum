package com.kiln.stratum.engine.face

/**
 * 미디어파이프 FaceMesh 특징점 번호(478개: 얼굴 468 + 눈동자 10). 얼굴 보정 계산과 확인 화면이 쓰는 점만 이름을 둔다.
 * '오른쪽'·'왼쪽'은 사람의 쪽이다(정면 사진에서 오른쪽 눈은 그림의 왼쪽에 있다). 번호는 시험 사진에서 맞는 자리인지 확인했다(2026-10-22).
 */
object FaceMeshIndex {

    /** 오른쪽 눈동자 가운데(눈동자 점 468~472의 첫째) */
    const val RIGHT_IRIS = 468

    /** 왼쪽 눈동자 가운데(473~477의 첫째) */
    const val LEFT_IRIS = 473

    /** 오른쪽·왼쪽 눈동자 둘레 4점(가운데 다음 넷, 피부 보정이 눈동자를 뺀다) */
    val RIGHT_IRIS_RING = intArrayOf(469, 470, 471, 472)
    val LEFT_IRIS_RING = intArrayOf(474, 475, 476, 477)

    /** 오른쪽 눈꼬리(바깥) */
    const val RIGHT_EYE_OUTER = 33

    /** 오른쪽 눈머리(안쪽) */
    const val RIGHT_EYE_INNER = 133

    /** 왼쪽 눈꼬리(바깥) */
    const val LEFT_EYE_OUTER = 263

    /** 왼쪽 눈머리(안쪽) */
    const val LEFT_EYE_INNER = 362

    /** 오른쪽 눈 윗꺼풀·아랫꺼풀 가운데 */
    const val RIGHT_EYE_TOP = 159
    const val RIGHT_EYE_BOTTOM = 145

    /** 왼쪽 눈 윗꺼풀·아랫꺼풀 가운데 */
    const val LEFT_EYE_TOP = 386
    const val LEFT_EYE_BOTTOM = 374

    /** 코끝 */
    const val NOSE_TIP = 1

    /** 코끝 바로 위(콧등 줄과 코끝 사이) */
    const val NOSE_ABOVE_TIP = 4

    /** 코밑(콧기둥 아래 끝, 윗입술과 만나는 곳) */
    const val NOSE_BOTTOM = 2

    /** 콧방울(오른쪽·왼쪽) */
    const val NOSE_WING_RIGHT = 129
    const val NOSE_WING_LEFT = 358

    /** 콧방울 한 덩어리를 둘러싼 점(오른쪽·왼쪽 10점, 콧볼이 이 덩어리를 함께 옮긴다, 시험 사진에서 확인 2026-10-24) */
    val RIGHT_NOSE_LOBE = intArrayOf(129, 49, 48, 64, 219, 235, 98, 115, 220, 237)
    val LEFT_NOSE_LOBE = intArrayOf(358, 279, 278, 294, 439, 455, 327, 344, 440, 457)

    /** 입꼬리(오른쪽·왼쪽) */
    const val MOUTH_RIGHT = 61
    const val MOUTH_LEFT = 291

    /** 윗입술 위 끝·아랫입술 아래 끝(가운데) */
    const val UPPER_LIP_TOP = 0
    const val LOWER_LIP_BOTTOM = 17

    /** 입술 사이 선의 위·아래(가운데) */
    const val UPPER_LIP_INNER = 13
    const val LOWER_LIP_INNER = 14

    /** 이마 위 끝(윤곽 맨 위) */
    const val FOREHEAD = 10

    /** 턱 끝 */
    const val CHIN = 152

    /** 턱 모서리(오른쪽·왼쪽) */
    const val JAW_RIGHT = 172
    const val JAW_LEFT = 397

    /** 콧등 가운데 줄(위 → 아래) */
    val NOSE_BRIDGE = intArrayOf(168, 6, 197, 195, 5)

    /** 콧등 아래 끝(코끝 위, [NOSE_BRIDGE]의 마지막) */
    const val NOSE_BRIDGE_BOTTOM = 5

    /** 콧등에서 코끝까지 코 가운데 줄(위 → 아래, 콧등 너비가 이 줄 둘레를 좁히고 넓힌다) */
    val NOSE_LINE = intArrayOf(168, 6, 197, 195, 5, 4, 1)

    /** 광대 높이를 어림하는 윤곽 점(귀 앞, 오른쪽·왼쪽 둘씩): 얼굴 인식 점에는 광대뼈 자리가 따로 없다 */
    val RIGHT_CHEEK = intArrayOf(234, 93)
    val LEFT_CHEEK = intArrayOf(454, 323)

    /** 얼굴 윤곽(이마 위 끝부터 사진에서 시계 방향으로 한 바퀴) */
    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
        152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
    )

    /** 오른쪽 눈 윤곽(눈꼬리부터 아래 → 눈머리 → 위로 한 바퀴, 16점) */
    val RIGHT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)

    /** 왼쪽 눈 윤곽(16점) */
    val LEFT_EYE = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)

    /** 입술 바깥 윤곽(오른쪽 입꼬리부터 아래 → 위로 한 바퀴) */
    val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)

    /** 입술 안쪽 윤곽 */
    val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)

    /** 눈썹(오른쪽·왼쪽, 위 줄) */
    val RIGHT_BROW = intArrayOf(70, 63, 105, 66, 107)
    val LEFT_BROW = intArrayOf(300, 293, 334, 296, 336)

    /** 눈썹 윤곽(위 줄 바깥 → 안쪽, 아래 줄 안쪽 → 바깥, 10점, 피부 마스크가 뺀다) */
    val RIGHT_BROW_OUTLINE = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)
    val LEFT_BROW_OUTLINE = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)

    /** 콧구멍 둘레(오른쪽·왼쪽, 7점, 피부 마스크가 뺀다) */
    val RIGHT_NOSTRIL = intArrayOf(166, 79, 239, 238, 20, 60, 75)
    val LEFT_NOSTRIL = intArrayOf(392, 309, 459, 458, 250, 290, 305)
}

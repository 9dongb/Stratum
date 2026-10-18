package com.kiln.stratum.engine.document

/**
 * 문서 픽셀 값이 뜻하는 색 공간(결정 기록 '넓은 색').
 *
 * 엔진 안의 비트맵은 색 공간과 상관없이 모두 sRGB로 표시해 두고, 값만 이 색 공간의 값이다.
 * 그래서 복사·GPU 계산(보정·혼합·유동화·리터칭·명암)이 값을 그대로 지나간다.
 * 색 공간은 사진을 들여올 때, 화면에 보일 때, 내보낼 때, sRGB로 고른 색(브러시·글자)을 칠할 때만 다룬다(엔진 플랫폼의 DocumentColors).
 */
enum class DocumentColorSpace {
    /** 보통 사진과 새 캔버스 */
    SRGB,

    /** sRGB보다 넓은 색 영역의 사진(Display P3). 전달 곡선은 sRGB와 같다. */
    DISPLAY_P3
}

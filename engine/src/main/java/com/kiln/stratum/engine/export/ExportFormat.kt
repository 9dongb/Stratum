package com.kiln.stratum.engine.export

/**
 * 내보낼 파일 형식. 안드로이드 압축기가 지원하는 세 가지다.
 *
 * @property mimeType 갤러리·공유에 알리는 형식 이름
 * @property extension 파일 이름 끝(점 없이)
 * @property maxSide 한 변의 최대 픽셀. 넘으면 압축기가 실패한다(JPEG 65500, WebP 16383, 소스 확인).
 * @property hasQuality 품질(1~100)을 쓰는지. PNG는 손실이 없어 쓰지 않는다.
 * @property keepsTransparency 투명을 담는지. JPEG는 못 담아 흰 바탕 위에 합성한다.
 * @property encoderBytesPerPixel 압축하는 동안 압축기가 비트맵 말고 더 쓰는 메모리(픽셀당 바이트, 어림).
 *   JPEG는 허프만 최적화로 이미지 전체의 계수(약 3B)를 담고,
 *   WebP는 알파를 미리 곱하지 않은 복사본(4B)과 YUV 평면(약 2.5B)을 더 만든다(소스 확인). PNG는 줄 단위로 쓴다.
 */
enum class ExportFormat(
    val mimeType: String,
    val extension: String,
    val maxSide: Int,
    val hasQuality: Boolean,
    val keepsTransparency: Boolean,
    val encoderBytesPerPixel: Double
) {
    JPEG(
        mimeType = "image/jpeg",
        extension = "jpg",
        maxSide = 65_500,
        hasQuality = true,
        keepsTransparency = false,
        encoderBytesPerPixel = 3.0
    ),
    PNG(
        mimeType = "image/png",
        extension = "png",
        maxSide = Int.MAX_VALUE,
        hasQuality = false,
        keepsTransparency = true,
        encoderBytesPerPixel = 0.0
    ),
    WEBP(
        mimeType = "image/webp",
        extension = "webp",
        maxSide = 16_383,
        hasQuality = true,
        keepsTransparency = true,
        encoderBytesPerPixel = 6.5
    )
}

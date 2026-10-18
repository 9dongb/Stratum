package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 문서 색 공간(결정 기록 '넓은 색')을 안드로이드 그림에 맞춘다.
 *
 * 엔진 안의 비트맵은 모두 sRGB로 표시해 두고 값만 문서 색 공간의 값이다([DocumentColorSpace]).
 * 앱의 GPU 그리기(`Bitmap.createBitmap(picture, …, HARDWARE)`)는 색 공간을 고를 수 없어 늘 sRGB이고(SDK 소스로 확인),
 * 꼬리표가 같으면 그리기가 색을 바꾸지 않아 P3 값이 그대로 지나가기 때문이다.
 * 그래서 화면에 보일 때와 내보낼 때만 꼬리표를 바꾸고, sRGB로 고른 색(브러시·글자)은 칠하기 전에 문서 색 공간 값으로 바꾼다.
 */
object DocumentColors {

    /**
     * 사진의 색 공간 [space]가 sRGB보다 넓은 색 영역인지. 원색(빨강·초록·파랑)의 삼각형이 sRGB보다 넓을 때다(P3·Adobe RGB·BT.2020 등).
     * 안드로이드의 `isWideGamut`은 값의 범위만 넓은 extended sRGB(16비트 PNG 등)도 넓다고 하므로 쓰지 않는다.
     */
    fun isWiderThanSrgb(space: ColorSpace?): Boolean {
        val rgb = space as? ColorSpace.Rgb ?: return false
        return area(rgb.primaries) > area(SRGB_PRIMARIES) * WIDER_AREA
    }

    /** [space]의 안드로이드 색 공간 */
    fun androidColorSpace(space: DocumentColorSpace): ColorSpace =
        when (space) {
            DocumentColorSpace.SRGB -> ColorSpace.get(ColorSpace.Named.SRGB)
            DocumentColorSpace.DISPLAY_P3 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        }

    /**
     * sRGB로 고른 [argb](알파를 곱하지 않음)를 [space] 문서에 칠할 값으로 바꾼다. 같은 색으로 보인다. 알파는 그대로, sRGB 문서는 그대로다.
     */
    fun paintColor(argb: Int, space: DocumentColorSpace): Int {
        if (space == DocumentColorSpace.SRGB) return argb
        val rgb = toDisplayP3.transform(Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f)
        return Color.argb(Color.alpha(argb), channel(rgb[0]), channel(rgb[1]), channel(rgb[2]))
    }

    /**
     * [space] 문서의 값 [argb](알파를 곱하지 않음)를 sRGB로 고른 색으로 바꾼다([paintColor]의 반대, 스포이드). 같은 색으로 보인다.
     * sRGB에 없는 아주 진한 P3 색은 sRGB 안으로 조금 준다. 알파는 그대로, sRGB 문서는 그대로다.
     */
    fun pickedColor(argb: Int, space: DocumentColorSpace): Int {
        if (space == DocumentColorSpace.SRGB) return argb
        val rgb = toSrgb.transform(Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f)
        return Color.argb(Color.alpha(argb), channel(rgb[0]), channel(rgb[1]), channel(rgb[2]))
    }

    /**
     * 화면에 그릴 그림. P3 문서면 같은 GPU 메모리를 P3 꼬리표로 감싼 새 비트맵이고(복사 없음), sRGB 문서면 [bitmap] 그대로다.
     * [gainmap]이 있으면(HDR 문서, 안드로이드 14 이상) 새 비트맵에 그림이 보이는 문서 자리 [shown]만큼의 게인맵을 붙여,
     * HDR 창에서 HDR 화면이면 밝게 보인다(문서 크기 [document]).
     *
     * 엔진이 캐시해 다시 쓰는 그림의 꼬리표·게인맵은 바꾸지 않는다. 바꾸면 다음 합성에서 P3 → sRGB로 색이 변환된다.
     * 그래서 화면에 그리기 바로 전에만 부른다. 소프트웨어 그림(테스트 등)은 꼬리표를 바꾼 복사본이다.
     */
    fun forScreen(
        bitmap: Bitmap,
        space: DocumentColorSpace,
        gainmap: ScreenGainmap? = null,
        shown: PixelRect? = null,
        document: PixelSize? = null
    ): Bitmap {
        val attach = gainmap != null && shown != null && document != null && Gainmaps.supported
        if (space == DocumentColorSpace.SRGB && !attach) return bitmap
        val target = androidColorSpace(space)
        val wrapped = try {
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.hardwareBuffer.use { buffer -> Bitmap.wrapHardwareBuffer(buffer, target) }
            } else {
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)?.also { it.setColorSpace(target) }
            }
        } catch (e: IllegalArgumentException) {
            null
        }
        // 감쌀 수 없는 그림이면 sRGB 보통 화면으로 보인다(색이 조금 옅을 뿐 그대로 쓸 수 있다)
        if (wrapped == null) return bitmap
        if (attach) {
            val contents = checkNotNull(gainmap).crop(checkNotNull(shown), checkNotNull(document))
            if (contents != null) wrapped.gainmap = Gainmaps.toAndroid(gainmap.info, contents)
        }
        return wrapped
    }

    /** 압축할 그림 [bitmap]의 꼬리표를 문서 색 공간으로 바꾼다. 값은 그대로다. 압축하면 그 색 정보가 파일에 붙는다. */
    fun tagForEncoding(bitmap: Bitmap, space: DocumentColorSpace) {
        if (space != DocumentColorSpace.SRGB) bitmap.setColorSpace(androidColorSpace(space))
    }

    private val toSrgb: ColorSpace.Connector by lazy {
        ColorSpace.connect(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private val toDisplayP3: ColorSpace.Connector by lazy {
        ColorSpace.connect(ColorSpace.get(ColorSpace.Named.SRGB), ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    }

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()

    /** 원색 세 개(x, y 여섯 개)가 이루는 삼각형의 넓이 */
    private fun area(primaries: FloatArray): Float =
        abs((primaries[2] - primaries[0]) * (primaries[5] - primaries[1]) - (primaries[4] - primaries[0]) * (primaries[3] - primaries[1])) / 2f

    private val SRGB_PRIMARIES: FloatArray = (ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb).primaries

    /** sRGB보다 이만큼(넓이 비) 넘게 넓으면 넓은 색 영역으로 본다. P3는 약 1.36배, Adobe RGB는 약 1.35배다. */
    private const val WIDER_AREA = 1.05f
}

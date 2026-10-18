package com.kiln.stratum.engine.platform

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Paint
import android.os.Build
import com.kiln.stratum.engine.document.GainRgb
import com.kiln.stratum.engine.document.HdrInfo
import com.kiln.stratum.engine.document.HdrPrimaries
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.roundToInt

/**
 * 안드로이드 게인맵(`android.graphics.Gainmap`, 안드로이드 14 이상)과 엔진의 HDR 값([HdrInfo])·게인맵 타일을 오간다(결정 기록 'HDR').
 * 안드로이드 13에는 게인맵이 없어, 부르는 쪽이 [supported]로 먼저 확인한다(그러면 이 클래스의 14 전용 부분을 읽지 않는다).
 */
internal object Gainmaps {

    /** 이 기기가 게인맵을 다루는지(안드로이드 14 이상) */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * 풀린 사진 [decoded]의 게인맵: HDR 값과 게인맵 그림(색 공간 없음, 1채널은 ALPHA_8). 없거나 다룰 수 없으면 null.
     * HDR 사진을 밑에 둔 형식(방향 'HDR→SDR', 안드로이드 16)은 다루지 않는다(보통 사진으로 연다).
     */
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun read(decoded: Bitmap): Pair<HdrInfo, Bitmap>? {
        if (!decoded.hasGainmap()) return null
        val gainmap = decoded.gainmap ?: return null
        var primaries: HdrPrimaries? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            if (gainmap.gainmapDirection == Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR) return null
            primaries = primariesOf(gainmap.alternativeImagePrimaries)
        }
        val contents = gainmap.gainmapContents
        val info = HdrInfo(
            ratioMin = rgbOf(gainmap.ratioMin),
            ratioMax = rgbOf(gainmap.ratioMax),
            gamma = rgbOf(gainmap.gamma),
            epsilonSdr = rgbOf(gainmap.epsilonSdr),
            epsilonHdr = rgbOf(gainmap.epsilonHdr),
            displayRatioForFullHdr = gainmap.displayRatioForFullHdr,
            minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition,
            singleChannel = contents.config == Bitmap.Config.ALPHA_8,
            alternatePrimaries = primaries
        )
        return info to contents
    }

    /** [info]와 게인맵 그림 [contents]로 안드로이드 게인맵을 만든다(내보내기·화면) */
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun toAndroid(info: HdrInfo, contents: Bitmap): Gainmap =
        Gainmap(contents).apply {
            setRatioMin(info.ratioMin.r, info.ratioMin.g, info.ratioMin.b)
            setRatioMax(info.ratioMax.r, info.ratioMax.g, info.ratioMax.b)
            setGamma(info.gamma.r, info.gamma.g, info.gamma.b)
            setEpsilonSdr(info.epsilonSdr.r, info.epsilonSdr.g, info.epsilonSdr.b)
            setEpsilonHdr(info.epsilonHdr.r, info.epsilonHdr.g, info.epsilonHdr.b)
            displayRatioForFullHdr = info.displayRatioForFullHdr
            minDisplayRatioForHdrTransition = info.minDisplayRatioForHdrTransition
            val primaries = info.alternatePrimaries
            if (primaries != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                setAlternativeImagePrimaries(colorSpaceOf(primaries))
            }
        }

    /**
     * 게인맵 그림 [contents]를 불투명한 ARGB 타일로 나눈다. 1채널(ALPHA_8)은 R=G=B인 회색으로 담는다.
     * 값은 색 변환 없이 그대로 옮긴다(게인맵 그림에는 색 공간이 없다). 메모리가 모자라면 null.
     */
    fun split(contents: Bitmap, checkCanceled: () -> Unit): Raster? {
        val grid = TileGrid(contents.width, contents.height)
        val scratch = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        // 1채널은 마스크로 그려진다(붓 색 × 알파). 검은 바탕에 흰색으로 얹으면 회색 = 알파다.
        val mask = Paint().apply {
            color = Color.WHITE
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        try {
            val canvas = Canvas(scratch)
            val tiles = HashMap<TileKey, Tile?>()
            for (key in grid.keys()) {
                checkCanceled()
                val bounds = grid.bounds(key)
                canvas.drawColor(Color.BLACK, BlendMode.SRC)
                val left = -bounds.left.toFloat()
                val top = -bounds.top.toFloat()
                if (contents.config == Bitmap.Config.ALPHA_8) canvas.drawBitmap(contents, left, top, mask) else canvas.drawBitmap(contents, left, top, CanvasTileComposer.CopyPaint)
                val full = bounds.width == TILE_SIZE && bounds.height == TILE_SIZE
                val part = if (full) scratch else Bitmap.createBitmap(scratch, 0, 0, bounds.width, bounds.height)
                val tile = part.copy(Bitmap.Config.ARGB_8888, false)
                if (part !== scratch) part.recycle()
                if (tile == null) return null
                tiles[key] = Tile.of(BitmapTilePixels(tile))
            }
            return Raster.EMPTY.withTiles(tiles)
        } finally {
            scratch.recycle()
        }
    }

    /** 1채널 게인맵으로: 회색 그림 [gray](불투명)의 빨강 값을 알파로 옮긴 ALPHA_8 그림(내보내기는 1채널로 쓴다) */
    fun toSingleChannel(gray: Bitmap): Bitmap {
        val alpha = Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ALPHA_8)
        Canvas(alpha).drawBitmap(gray, 0f, 0f, Paint().apply {
            // 알파 = 빨강(회색이라 세 채널이 같다)
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)))
            blendMode = BlendMode.SRC
            isFilterBitmap = false
        })
        return alpha
    }

    /** [info]의 '밝히지 않음' 값을 불투명한 ARGB로(1채널은 회색) */
    fun neutralColor(info: HdrInfo): Int {
        val neutral = info.neutral
        fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
        return if (info.singleChannel) {
            val gray = channel(neutral.r)
            Color.rgb(gray, gray, gray)
        } else {
            Color.rgb(channel(neutral.r), channel(neutral.g), channel(neutral.b))
        }
    }

    private fun rgbOf(values: FloatArray): GainRgb = GainRgb(values[0], values[1], values[2])

    private fun primariesOf(space: ColorSpace?): HdrPrimaries? =
        when (space) {
            ColorSpace.get(ColorSpace.Named.SRGB) -> HdrPrimaries.SRGB
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> HdrPrimaries.DISPLAY_P3
            ColorSpace.get(ColorSpace.Named.BT2020) -> HdrPrimaries.BT2020
            else -> null
        }

    private fun colorSpaceOf(primaries: HdrPrimaries): ColorSpace =
        when (primaries) {
            HdrPrimaries.SRGB -> ColorSpace.get(ColorSpace.Named.SRGB)
            HdrPrimaries.DISPLAY_P3 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            HdrPrimaries.BT2020 -> ColorSpace.get(ColorSpace.Named.BT2020)
        }
}

package com.kiln.stratum.engine.importing

import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.transform.PixelPoint
import kotlin.math.roundToInt

/**
 * 편집 중인 문서에 사진을 새 레이어로 넣을 때의 크기와 자리(사용자 피드백 12차: 레이어 패널의 사진 넣기·끌어 놓기).
 * 문서보다 크면 비율을 지키며 문서 안에 들어가게 줄이고, 작으면 그대로 둔다. 자리는 문서 가운데다.
 */
object InsertSizing {

    /** [image] 사진을 [document] 안에 넣을 크기. 문서 안에 들어가면 그대로이고, 아니면 비율을 지키며 줄인다(각 변 1px 이상). */
    fun fit(image: PixelSize, document: PixelSize): PixelSize {
        if (image.width <= document.width && image.height <= document.height) return image
        val scale = minOf(document.width.toDouble() / image.width, document.height.toDouble() / image.height)
        return PixelSize(
            (image.width * scale).roundToInt().coerceIn(1, document.width),
            (image.height * scale).roundToInt().coerceIn(1, document.height)
        )
    }

    /**
     * 해독하면서 줄일 단계(1, 2, 4…): 그만큼 줄여도 [fit]보다 작아지지 않는 가장 큰 단계다. 남은 배율은 GPU로 줄인다.
     * 해독기에 따라 줄인 크기를 내림하거나 올림하므로, 내림으로 보아 해독한 그림이 늘 [fit] 이상이 되게 한다.
     */
    fun sampleSize(image: PixelSize, fit: PixelSize): Int {
        var sample = 1
        while (sample < MAX_SAMPLE_SIZE && image.width / (sample * 2) >= fit.width && image.height / (sample * 2) >= fit.height) {
            sample *= 2
        }
        return sample
    }

    /** [size] 그림을 [document] 가운데에 놓을 왼쪽 위 자리. 남는 1px은 오른쪽·아래로 간다. */
    fun centered(size: PixelSize, document: PixelSize): PixelPoint =
        PixelPoint((document.width - size.width) / 2, (document.height - size.height) / 2)

    /** [size] 그림을 [document] 가운데에 놓으면 걸치는 타일의 바이트(새 레이어 메모리의 어림, 픽셀당 4바이트) */
    fun placedTileBytes(size: PixelSize, document: PixelSize): Long {
        val origin = centered(size, document)
        val columns = (origin.x + size.width - 1) / TILE_SIZE - origin.x / TILE_SIZE + 1
        val rows = (origin.y + size.height - 1) / TILE_SIZE - origin.y / TILE_SIZE + 1
        return columns.toLong() * rows * TILE_SIZE * TILE_SIZE * Int.SIZE_BYTES
    }

    /** 가장 많이 줄이는 단계(1/1024, [ImportSizing]과 같다) */
    private const val MAX_SAMPLE_SIZE = 1024
}

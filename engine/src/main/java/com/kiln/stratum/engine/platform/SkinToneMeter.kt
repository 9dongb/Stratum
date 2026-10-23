package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColorMath
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinTone
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 얼굴마다의 피부 색 통계를 지금 그림에서 다시 잰다(피부 보정의 '다시 만들기', 결정 기록 '피부 보정'의 4단계).
 * 원본의 색온도·틴트를 고쳤거나 잡티를 지운 뒤, 색 보정의 기준이 지금 피부에 맞게 한다. 처음 잴 때([SkinMasker])와 같은 통계([SkinTone.measure])다.
 *
 * 얼굴마다 그 얼굴 둘레(가운데에서 반지름의 [WINDOW_RADII]배)를 긴 변 [MAX_EDGE]px 이하의 단계로 그려 OKLab으로 바꾸고, 피부 마스크 무게로 잰다.
 * 그 자리에서 다른 얼굴이 더 가까운 픽셀은 빼서(단체 사진) 옆 사람 피부가 섞이지 않게 한다.
 */
internal object SkinToneMeter {

    /** 얼굴 둘레: 가운데에서 반지름의 이 배수까지(피부 마스크를 만든 정사각형과 비슷하게 목까지 담음) */
    private const val WINDOW_RADII = 2.2f

    /** 잴 그림의 긴 변 최대 픽셀 */
    private const val MAX_EDGE = 384

    /** 잴 때 잠깐 쓰는 메모리의 어림(작은 그림과 OKLab 배열) */
    const val WORK_BYTES = 16L * 1024 * 1024

    /**
     * [document](색 보정 바로 아래까지 합친 문서)에서 [faces]마다 피부 색 통계를 잰다. [mask]는 피부 마스크다.
     * 잴 피부가 너무 적은 얼굴은 null이다. GPU가 그리지 못하면 [NotEnoughMemoryException].
     */
    fun measure(document: Document, mask: Selection, faces: List<SkinFaceArea>, checkCanceled: () -> Unit = {}): List<SkinTone?> {
        val size = PixelSize(document.width, document.height)
        val space = OkLab.Space.of(document.colorSpace)
        return faces.mapIndexed { index, face ->
            checkCanceled()
            val reach = face.radius * WINDOW_RADII
            val wanted = PixelRect((face.centerX - reach).toInt(), (face.centerY - reach).toInt(), (face.centerX + reach).toInt() + 1, (face.centerY + reach).toInt() + 1)
            var level = 0
            while ((max(wanted.width, wanted.height) shr level) > MAX_EDGE) level++
            val region = PreviewLevels.region(size, level, wanted) ?: return@mapIndexed null
            val width = region.previewWidth
            val height = region.previewHeight
            val drawn = PreviewRenderer.renderRegion(document, region, checkCanceled) ?: throw NotEnoughMemoryException()
            val pixels = IntArray(width * height)
            try {
                val software = drawn.copy(Bitmap.Config.ARGB_8888, false) ?: throw NotEnoughMemoryException()
                software.getPixels(pixels, 0, width, 0, 0, width, height)
                software.recycle()
            } finally {
                drawn.recycle()
            }
            val scale = 1 shl level
            val maskBitmap = SelectionPreview.maskAt(mask, region.rect.left.toFloat(), region.rect.top.toFloat(), 1f / scale, width, height)
            val alphas = ByteArray(width * height)
            try {
                val software = maskBitmap.copy(Bitmap.Config.ALPHA_8, false) ?: throw NotEnoughMemoryException()
                software.copyPixelsToBuffer(ByteBuffer.wrap(alphas))
                software.recycle()
            } finally {
                maskBitmap.recycle()
            }
            val weights = FloatArray(width * height) { i ->
                val x = region.rect.left + (i % width + 0.5f) * scale
                val y = region.rect.top + (i / width + 0.5f) * scale
                // 다른 얼굴이 더 가까운 픽셀은 이 얼굴의 피부가 아니다
                val own = faces.indices.maxBy { SkinColorMath.weight(faces[it], x, y) } == index
                if (own) (alphas[i].toInt() and 0xFF) / 255f else 0f
            }
            val (lightness, a, b) = OkLab.fromPixels(pixels, space)
            SkinTone.measure(lightness, a, b, weights)
        }
    }
}

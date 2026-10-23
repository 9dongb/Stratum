package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.CompositePlan
import com.kiln.stratum.engine.document.CompositeStep
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.segment.MaskRefine
import com.kiln.stratum.engine.segment.SegmentFeatures
import com.kiln.stratum.engine.segment.SegmentMask
import com.kiln.stratum.engine.segment.SegmentModel
import com.kiln.stratum.engine.segment.SegmentTensor
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * AI 선택의 물체 찾기(결정 기록 'AI 제거·AI 선택'). 앱 전체에서 오래 사는 서비스이고, 분할 모델([model])을 감싼다.
 *
 * - 보이는 레이어를 합친 그림(보정·불투명도·혼합 모드 반영, 숨긴 레이어는 빠짐. 스포이드와 같은 규칙)을 모델이 읽는다.
 *   긴 변이 1024px 이상인 가장 작은 축소본 단계로 GPU가 그리고([PreviewRenderer.render]), 문서 전체를 1024²로 늘여 맞추기는 소프트웨어(쌍선형)다.
 *   투명한 곳은 흰 바탕에 합친다(JPEG 내보내기와 같다). P3 문서는 값을 그대로 넣는다.
 * - 읽은 특징은 그 그림(문서 크기, 보이는 레이어마다 픽셀 객체·불투명도·혼합 모드·보정)을 열쇠로 기억한다.
 *   선택 영역만 바뀌면 그대로 쓰고, 그림이 바뀌면 다음에 다시 읽는다. 픽셀 객체는 약하게 붙잡아, 실행 취소 기록이 버린 타일을 붙들지 않는다.
 * - 탭마다 후보를 받아 고른 모양을 긴 변 [MASK_MAX_EDGE] 이하 마스크로 만들고([SegmentMask]), 같은 크기로 그려 둔 그림을 길잡이로
 *   경계를 사진의 가장자리에 붙인다([MaskRefine], 결정 기록의 '10단계 경계 다듬기'). 원본 크기로 키우기는 부르는 쪽이 한다.
 * - 읽기와 찾기는 한 번에 하나씩 한다(미리 읽기와 탭이 겹치면 뒤의 것이 기다린다). 메인 스레드가 아닌 곳에서 부른다.
 *   [workBytes]·[release]는 기다리지 않아 메인 스레드에서 불러도 된다.
 */
class ObjectSegmenter(private val model: SegmentModel) {

    private val lock = Any()

    /** 읽어 둔 특징·길잡이 그림과 그 열쇠. 쓰기는 [lock] 안에서만 한다. */
    @Volatile
    private var cached: Cached? = null

    /** [release]가 부를 때마다 는다. 그사이 읽은 특징은 기억하지 않는다. */
    @Volatile
    private var generation = 0

    /** [document]의 그림을 아직 읽지 않았으면 읽어 둔다(AI 선택을 고를 때 미리) */
    fun prepare(document: Document, checkCanceled: () -> Unit = {}) {
        synchronized(lock) { pictureFor(document, checkCanceled) }
    }

    /**
     * 문서 좌표 ([x], [y])를 탭한 물체의 모양. 문서 밖이거나 찾은 모양이 없으면 null.
     * 모델을 이 기기에서 쓸 수 없으면 [com.kiln.stratum.engine.segment.SegmentUnavailableException]을 던진다.
     */
    fun maskAt(document: Document, x: Float, y: Float, checkCanceled: () -> Unit = {}): ObjectMask? {
        if (x < 0f || y < 0f || x >= document.width || y >= document.height) return null
        synchronized(lock) {
            val picture = pictureFor(document, checkCanceled)
            checkCanceled()
            val logits = model.decode(picture.features, SegmentTensor.toInput(x, document.width), SegmentTensor.toInput(y, document.height))
            checkCanceled()
            val size = PixelSize(document.width, document.height)
            val level = maskLevel(size)
            val mask = PreviewLevels.previewSize(size, level)
            val tapX = x.toInt() shr level
            val tapY = y.toInt() shr level
            val alphas = SegmentMask.mask(logits, mask.width, mask.height, tapX, tapY) ?: return null
            checkCanceled()
            val guide = picture.guide
            val refined = if (guide.width == mask.width && guide.height == mask.height) {
                MaskRefine.refine(alphas, guide.bytes, guide.rowBytes, mask.width, mask.height, tapX, tapY)
            } else {
                alphas
            }
            return ObjectMask(refined, mask.width, mask.height, level)
        }
    }

    /** [document]에서 탭 한 번에 잠깐 쓰는 메모리의 어림: 특징이 있으면 찾기만, 없으면 그림 그리기·읽기까지, 모델을 아직 불러오지 않았으면 그것도 */
    fun workBytes(document: Document): Long {
        val encoded = cached?.key?.matches(document) == true
        return (if (encoded) DECODE_WORK_BYTES else ENCODE_WORK_BYTES) + model.pendingLoadBytes
    }

    /** 읽어 둔 특징을 버리고 모델을 내려놓는다. 다음에 쓰면 다시 불러오고 다시 읽는다. */
    fun release() {
        generation++
        cached = null
        model.release()
    }

    /** [document]의 그림을 읽은 특징과 길잡이 그림. 기억한 것이 맞으면 그대로 쓴다. [lock] 안에서 부른다. */
    private fun pictureFor(document: Document, checkCanceled: () -> Unit): Cached {
        cached?.let { if (it.key.matches(document)) return it }
        // 앞 특징(약 17MB)을 먼저 놓아 둔다
        cached = null
        val started = generation
        val (image, guide) = composite(document, checkCanceled)
        checkCanceled()
        val picture = Cached(CompositeKey.of(document), model.encode(image), guide)
        if (generation == started) cached = picture
        return picture
    }

    /**
     * 보이는 레이어를 합친 그림을 모델 입력(1×3×1024² NCHW)과, 마스크와 같은 크기(마스크 단계)의 길잡이 그림으로.
     * 투명한 곳은 둘 다 흰 바탕에 합친다. 크기 맞추기는 안드로이드 그리기(필터)다.
     */
    private fun composite(document: Document, checkCanceled: () -> Unit): Pair<FloatArray, Guide> {
        val size = PixelSize(document.width, document.height)
        val drawn = PreviewRenderer.render(document, encodeLevel(size), checkCanceled) ?: throw NotEnoughMemoryException()
        val software = try {
            drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        } ?: throw NotEnoughMemoryException()
        try {
            val input = SegmentModel.INPUT_SIZE
            val image = drawOnWhite(software, input, input) { bytes, _ -> SegmentTensor.input(bytes) }
            checkCanceled()
            val mask = PreviewLevels.previewSize(size, maskLevel(size))
            val guide = drawOnWhite(software, mask.width, mask.height) { bytes, rowBytes -> Guide(bytes, rowBytes, mask.width, mask.height) }
            return image to guide
        } finally {
            software.recycle()
        }
    }

    /** [source] 전체를 [width]×[height] 흰 바탕에 늘여 그린 바이트(R·G·B·A, 줄마다 rowBytes)를 [use]에 넘긴다 */
    private fun <T> drawOnWhite(source: Bitmap, width: Int, height: Int, use: (ByteArray, Int) -> T): T {
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            val buffer = ByteBuffer.allocate(bitmap.rowBytes * bitmap.height)
            bitmap.copyPixelsToBuffer(buffer)
            return use(buffer.array(), bitmap.rowBytes)
        } finally {
            bitmap.recycle()
        }
    }

    /** 읽어 둔 그림: 열쇠, 특징, 길잡이 그림 */
    private class Cached(val key: CompositeKey, val features: SegmentFeatures, val guide: Guide)

    /** 마스크와 같은 크기로 그린 보이는 그림(경계 다듬기의 길잡이, R·G·B·A 바이트) */
    private class Guide(val bytes: ByteArray, val rowBytes: Int, val width: Int, val height: Int)

    /** 합친 그림을 정하는 것: 문서 크기와, 합성 계획([CompositePlan])의 단계마다 픽셀 객체·불투명도·혼합 모드·보정 */
    private class CompositeKey(private val width: Int, private val height: Int, private val steps: List<StepKey>) {

        fun matches(document: Document): Boolean {
            if (document.width != width || document.height != height) return false
            val plan = CompositePlan.of(document)
            return plan.size == steps.size && plan.indices.all { steps[it].matches(plan[it]) }
        }

        companion object {
            fun of(document: Document) =
                CompositeKey(document.width, document.height, CompositePlan.of(document).map { step -> when (step) { is CompositeStep.Draw -> StepKey(step) } })
        }
    }

    /** 그리는 단계 하나의 열쇠. 픽셀·마스크 객체는 같은 객체인지만 본다(약하게 붙잡음). */
    private class StepKey(step: CompositeStep.Draw) {
        private val pixels = WeakReference(step.raster)
        private val mask = WeakReference(step.mask)
        private val opacity = step.opacity
        private val blendMode = step.blendMode
        private val adjustments = step.adjustments

        fun matches(step: CompositeStep): Boolean =
            step is CompositeStep.Draw && pixels.get() === step.raster && mask.get() === step.mask && step.opacity == opacity &&
                step.blendMode == blendMode && step.adjustments == adjustments
    }

    companion object {
        /** 찾은 모양 마스크의 긴 변 최대 픽셀. Kotlin 반복은 이 크기 이하에서만 한다. */
        const val MASK_MAX_EDGE = 1024

        /** 그림을 읽을 때 잠깐 쓰는 메모리: 축소본 둘(2047² 이하), 1024² 그림, 입력 12.6MB, 출력과 특징 16.8MB씩, 길잡이 그림(4MB 이하) */
        private const val ENCODE_WORK_BYTES = 100L * 1024 * 1024

        /** 탭 한 번(특징이 있을 때): 후보 로짓, 1024px 이하 마스크 몇 장, 경계 다듬기의 배열(반 크기 float 23장 등, 약 25MB) */
        private const val DECODE_WORK_BYTES = 40L * 1024 * 1024

        /** 긴 변이 [SegmentModel.INPUT_SIZE] 이상인 가장 작은 축소본 단계(작은 문서는 0). 1024²로 맞출 때 많이 키우지 않게 한다. */
        fun encodeLevel(size: PixelSize): Int {
            var level = 0
            while (longSide(PreviewLevels.previewSize(size, level + 1)) >= SegmentModel.INPUT_SIZE) level++
            return level
        }

        /** 긴 변이 [MASK_MAX_EDGE] 이하가 되는 가장 덜 줄인 단계 */
        fun maskLevel(size: PixelSize): Int {
            var level = 0
            while (longSide(PreviewLevels.previewSize(size, level)) > MASK_MAX_EDGE) level++
            return level
        }

        private fun longSide(size: PixelSize): Int = maxOf(size.width, size.height)
    }
}

/** 찾은 물체 모양: 문서를 [level] 단계로 줄인 크기([width]×[height])의 알파(0..255, 경계만 부드러움, 줄마다 이어 붙임) */
class ObjectMask(val alphas: ByteArray, val width: Int, val height: Int, val level: Int) {

    /** AI 제거로 채울 곳(톡 눌러 고른 물체) */
    fun inpaintArea(): InpaintArea.Masked = (1 shl level).toFloat().let { cell -> InpaintArea.Masked(alphas, width, height, cell, cell) }
}

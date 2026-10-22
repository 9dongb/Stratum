package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.BlendMode as LayerBlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.face.FaceImage
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.FaceRoi
import com.kiln.stratum.engine.face.FaceSearch
import com.kiln.stratum.engine.preview.MAX_PREVIEW_LEVEL
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.ceil
import kotlin.math.max

/**
 * 얼굴 인식 보정의 얼굴 찾기(결정 기록 '얼굴 인식 보정'). 앱 전체에서 오래 사는 서비스이고, 얼굴 모델([model])을 감싼다.
 *
 * - 찾을 그림([render]): 레이어 하나(유동화하는 레이어)만, 불투명도·혼합 모드 없이 보정은 건 채(유동화 미리 보기와 같다),
 *   긴 변이 [IMAGE_MIN_EDGE] 이상인 가장 작은 축소본 단계로 GPU가 그린다. 그것을 반씩 줄인 단계들도 소프트웨어 그리기로 만들어 둔다.
 * - 모델 입력은 알맞은 단계에서 안드로이드 그리기(쌍선형)로 잘라 읽고, 투명한 곳은 흰 바탕에 합친다. Kotlin 반복은 모델 입력(256² 이하)에서만 한다.
 * - 찾는 순서는 [FaceSearch]다. 결과는 문서 좌표로 돌려준다. 두 눈동자 사이가 [MIN_EYE_DISTANCE](문서 px)보다 작은 얼굴은 뺀다.
 * - [render]는 GPU를 쓰므로 축소본 그리기와 겹치지 않게(앱의 그리기 잠금 안에서) 부르고, [search]는 모델 계산이라 그 밖에서 불러도 된다.
 *   메인 스레드가 아닌 곳에서 부른다. [workBytes]·[release]는 기다리지 않아 메인 스레드에서 불러도 된다.
 */
class FaceFinder(private val model: FaceModel) {

    private val search = FaceSearch(model)

    /**
     * [document]의 [layer] 하나를 얼굴을 찾을 그림으로 그린다. 다 쓰면 [FacePicture.close]로 놓는다.
     * 그리지 못하면(메모리 부족) [NotEnoughMemoryException]을 던진다.
     */
    fun render(document: Document, layer: Layer, checkCanceled: () -> Unit = {}): FacePicture {
        val alone = document.copy(layers = listOf(layer.copy(opacity = 1f, blendMode = LayerBlendMode.NORMAL)), selection = null)
        val level = imageLevel(PixelSize(document.width, document.height))
        val drawn = PreviewRenderer.render(alone, level, checkCanceled) ?: throw NotEnoughMemoryException()
        val base = try {
            drawn.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        } finally {
            drawn.recycle()
        } ?: throw NotEnoughMemoryException()
        val levels = arrayListOf(base)
        try {
            // 그림 전체를 192²로 읽을 때도 두 배 넘게 줄이지 않도록 반씩 줄인 단계를 둔다(쌍선형 반 줄이기 = 2×2 평균)
            while (max(levels.last().width, levels.last().height) > 2 * FaceModel.DETECTOR_SIZE) {
                checkCanceled()
                val last = levels.last()
                levels += Bitmap.createScaledBitmap(last, ceil(last.width / 2f).toInt(), ceil(last.height / 2f).toInt(), true)
            }
        } catch (e: OutOfMemoryError) {
            levels.forEach { it.recycle() }
            throw NotEnoughMemoryException(e)
        } catch (e: Throwable) {
            levels.forEach { it.recycle() }
            throw e
        }
        return FacePicture(levels, document.width, document.height)
    }

    /**
     * [picture]에서 찾은 얼굴(문서 좌표, 왼쪽부터). 없으면 빈 목록.
     * 모델을 이 기기에서 쓸 수 없으면 [com.kiln.stratum.engine.face.FaceUnavailableException], 메모리가 모자라면 [NotEnoughMemoryException]을 던진다.
     */
    fun search(picture: FacePicture, checkCanceled: () -> Unit = {}): List<FaceLandmarks> {
        val scaleX = picture.documentWidth / picture.width.toFloat()
        val scaleY = picture.documentHeight / picture.height.toFloat()
        val started = SystemClock.elapsedRealtime()
        val faces = search.find(picture, MIN_EYE_DISTANCE * 2f / (scaleX + scaleY), checkCanceled)
        Log.i(TAG, "Found ${faces.size} faces in ${SystemClock.elapsedRealtime() - started} ms (${picture.width}x${picture.height}, presence ${faces.map { it.presence }})")
        return faces.map { it.scaled(scaleX, scaleY) }
    }

    /** [document]에서 얼굴을 한 번 찾을 때 잠깐 쓰는 메모리의 어림: 그린 그림(GPU 하나, 소프트웨어 하나와 줄인 단계들), 입력, 모델을 아직 불러오지 않았으면 그것도 */
    fun workBytes(document: Document): Long {
        val image = PreviewLevels.previewSize(PixelSize(document.width, document.height), imageLevel(PixelSize(document.width, document.height)))
        return image.width.toLong() * image.height * 4 * 5 / 2 + INPUT_WORK_BYTES + model.pendingLoadBytes
    }

    /** 모델을 미리 불러 둔다(기다리지 않는다) */
    fun prepare() = model.prepare()

    /** 모델을 내려놓는다. 다음에 쓰면 다시 불러온다. */
    fun release() = model.release()

    companion object {
        /** 얼굴을 찾을 그림의 긴 변(축소본 단계로 정하므로 이것 이상, 두 배 미만). 단체 사진의 작은 얼굴도 조각으로 찾을 만한 크기다. */
        const val IMAGE_MIN_EDGE = 1536

        /** 두 눈동자 사이가 이것(문서 픽셀)보다 작은 얼굴은 뺀다(고칠 만한 크기가 아님) */
        const val MIN_EYE_DISTANCE = 24f

        /** 모델 입력·자른 그림 몇 장(1MB 안팎)과 특징점 */
        private const val INPUT_WORK_BYTES = 8L * 1024 * 1024

        private const val TAG = "FaceFinder"

        /** 긴 변이 [IMAGE_MIN_EDGE] 이상인 가장 작은 축소본 단계(작은 문서는 0) */
        fun imageLevel(size: PixelSize): Int {
            var level = 0
            while (level < MAX_PREVIEW_LEVEL && longSide(PreviewLevels.previewSize(size, level + 1)) >= IMAGE_MIN_EDGE) level++
            return level
        }

        private fun longSide(size: PixelSize): Int = max(size.width, size.height)
    }
}

/**
 * 얼굴을 찾을 그림: 레이어 하나를 그린 것([levels]의 첫째)과 반씩 줄인 단계들. [documentWidth]×[documentHeight] 문서 전체를 덮는다.
 * 다 쓰면 [close]로 비트맵을 놓는다. 한 스레드에서만 쓴다.
 */
class FacePicture internal constructor(
    private val levels: List<Bitmap>,
    val documentWidth: Int,
    val documentHeight: Int
) : FaceImage, AutoCloseable {

    override val width: Int get() = levels[0].width
    override val height: Int get() = levels[0].height

    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private val white = Paint().apply { color = Color.WHITE }

    override fun read(roi: FaceRoi, size: Int, outside: FaceImage.Outside): IntArray {
        // 한 픽셀에 그림 몇 픽셀이 들어오는지: 두 배 넘게 줄이지 않는 가장 작은 단계에서 읽는다
        val zoom = size / roi.side
        var index = 0
        while (index + 1 < levels.size && zoom <= 1f / (1 shl (index + 1))) index++
        val level = levels[index]
        // 단계 그림 좌표 → 그림 좌표 → 정사각형 가운데로 → 돌림을 되돌림 → 출력 크기로
        val matrix = Matrix().apply {
            setScale(width / level.width.toFloat(), height / level.height.toFloat())
            postTranslate(-roi.centerX, -roi.centerY)
            postRotate(-Math.toDegrees(roi.angle.toDouble()).toFloat())
            postScale(size / roi.side, size / roi.side)
            postTranslate(size / 2f, size / 2f)
        }
        val target = try {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw NotEnoughMemoryException(e)
        }
        try {
            val canvas = Canvas(target)
            when (outside) {
                FaceImage.Outside.BLACK -> {
                    // 그림 밖은 검정, 그림 안의 투명한 곳은 흰 바탕
                    canvas.drawColor(Color.BLACK)
                    canvas.save()
                    canvas.concat(matrix)
                    canvas.drawRect(0f, 0f, level.width.toFloat(), level.height.toFloat(), white)
                    canvas.drawBitmap(level, 0f, 0f, filter)
                    canvas.restore()
                }
                FaceImage.Outside.EDGE -> {
                    canvas.drawColor(Color.WHITE)
                    val shader = BitmapShader(level, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        filterMode = BitmapShader.FILTER_MODE_LINEAR
                        setLocalMatrix(matrix)
                    }
                    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { this.shader = shader })
                }
            }
            val pixels = IntArray(size * size)
            target.getPixels(pixels, 0, size, 0, 0, size, size)
            return pixels
        } finally {
            target.recycle()
        }
    }

    override fun close() {
        levels.forEach { it.recycle() }
    }
}

package com.kiln.stratum.engine.platform

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.segment.SegmentFeatures
import com.kiln.stratum.engine.segment.SegmentModel
import com.kiln.stratum.engine.segment.SegmentTensor
import com.kiln.stratum.engine.segment.SegmentUnavailableException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.math.hypot

/**
 * SAM 2.1 Tiny 분할 모델을 LiteRT로 돌린다(결정 기록 'AI 제거·AI 선택'의 '분할 모델').
 *
 * - 모델 파일은 APK 안의 [ENCODER_ASSET]·[DECODER_ASSET]과 점 계산 상수 [PROMPT_ASSET]이다. 엔진에는 Context가 없어서 앱이 [assets]와 [cacheDir]만 넘긴다.
 * - LiteRT 객체는 [AiThread]에서만 만들고, 쓰고, 닫는다(채우기 모델과 같은 스레드라 서로 겹치지 않는다). 부르는 쪽은 결과를 기다리기만 한다.
 * - 처음 쓸 때 불러온다. [backends] 순서대로(기본은 GPU 다음 CPU) 불러서 시험 그림(밝은 바탕의 어두운 원)을 읽고,
 *   어느 후보도 원을 고르지 못하면 다음 장치로 한다. GPU에 다 올라가도 결과가 틀릴 수 있어서다(6단계 MobileSAM).
 *   모두 안 되면 앱을 다시 켤 때까지 쓸 수 없다고 기억한다.
 * - GPU는 컴파일한 프로그램을 [cacheDir]에 남긴다. 처음 불러오기는 폴드8에서 4~8초, 남긴 뒤에는 1초 안이다.
 * - 디코더 입력은 특징 사이에 점 둘을 끼운 한 줄이다. 특징([Features])이 그 한 줄을 들고, 탭마다 점 자리만 바꿔 통째로 쓴다.
 */
class LiteRtSegmentModel(
    private val assets: AssetManager,
    private val cacheDir: File?,
    private val backends: List<Backend> = listOf(Backend.GPU, Backend.CPU)
) : SegmentModel {

    /** 계산할 장치 */
    enum class Backend { GPU, CPU }

    override val pendingLoadBytes: Long get() = if (activeBackend == null) LOAD_BYTES_ESTIMATE else 0L

    /** 지금 불러 둔 장치. 불러오지 않았으면 null이다(측정·로그용). */
    @Volatile
    var activeBackend: Backend? = null
        private set

    // 아래 둘은 AiThread에서만 읽고 쓴다
    private var session: Session? = null
    private var failure: SegmentUnavailableException? = null

    /** 디코더에 그대로 쓰는 한 줄: 이미지 특징 | 점 둘 | 고해상 특징 둘. 점 자리는 탭마다 바꾼다. */
    private class Features(val decoderInput: FloatArray) : SegmentFeatures

    override fun encode(image: FloatArray): SegmentFeatures {
        require(image.size == 3 * INPUT_PLANE) { "Input must be 1x3x${SegmentModel.INPUT_SIZE}²" }
        return onAiThread { it.encode(image) }
    }

    override fun decode(features: SegmentFeatures, x: Float, y: Float): FloatArray {
        val input = (features as Features).decoderInput
        return onAiThread { it.decode(input, x, y) }
    }

    override fun prepare() {
        AiThread.executor.execute {
            try {
                open()
            } catch (e: Exception) {
                // 쓸 때 다시 알린다
            } catch (e: OutOfMemoryError) {
                // 쓸 때 다시 불러 본다
            }
        }
    }

    override fun release() {
        AiThread.executor.execute { closeSession() }
    }

    /** [AiThread]에서 불러 둔 모델로 [work]를 하고 기다린다 */
    private fun <T> onAiThread(work: (Session) -> T): T = await(AiThread.executor.submit<T> {
        val opened = open()
        try {
            work(opened)
        } catch (e: Exception) {
            // 실행이 깨지면(GPU를 잃는 등) 내려놓고, 다음에 쓸 때 다시 불러온다
            val backend = activeBackend
            closeSession()
            throw SegmentUnavailableException("Segmentation failed on $backend", e)
        }
    })

    /** 불러 둔 모델. 없으면 불러온다. [AiThread]에서만 부른다. */
    private fun open(): Session {
        session?.let { return it }
        failure?.let { throw it }
        val constants = readConstants()
        var cause: Throwable? = null
        for (backend in backends) {
            val started = SystemClock.elapsedRealtime()
            val opened = try {
                Session.load(assets, backend, cacheDir, constants)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load the segmentation model on $backend", e)
                cause = e
                continue
            }
            val loadedAt = SystemClock.elapsedRealtime()
            val valid = try {
                findsTheProbeDisc(opened)
            } catch (e: OutOfMemoryError) {
                opened.close()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The segmentation model failed its first run on $backend", e)
                cause = e
                false
            }
            if (valid) {
                Log.i(TAG, "Segmentation model on $backend: load ${loadedAt - started} ms, probe ${SystemClock.elapsedRealtime() - loadedAt} ms")
                session = opened
                activeBackend = backend
                return opened
            }
            Log.w(TAG, "The segmentation model gave wrong shapes on $backend")
            opened.close()
        }
        throw SegmentUnavailableException("No device could run the segmentation model", cause).also { failure = it }
    }

    /** 불러 둔 모델을 닫는다. [AiThread]에서만 부른다. */
    private fun closeSession() {
        session?.close()
        session = null
        activeBackend = null
    }

    private fun readConstants(): FloatArray {
        val bytes = assets.open(PROMPT_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** 불러 둔 인코더·디코더와 입출력 자리 */
    private class Session(
        private val env: Environment,
        private val encoder: CompiledModel,
        private val decoder: CompiledModel,
        private val encoderIn: List<TensorBuffer>,
        private val encoderOut: List<TensorBuffer>,
        private val decoderIn: List<TensorBuffer>,
        private val decoderOut: List<TensorBuffer>,
        private val constants: FloatArray
    ) : AutoCloseable {

        fun encode(image: FloatArray): Features {
            encoderIn[0].writeFloat(image)
            encoder.run(encoderIn, encoderOut)
            // GPU 실행은 비동기라 읽을 때 끝난다
            val flat = encoderOut[0].readFloat()
            check(flat.size == FEATURES_LENGTH) { "Unexpected encoder output: ${flat.size}" }
            val input = FloatArray(FEATURES_LENGTH + POINTS_LENGTH)
            flat.copyInto(input, 0, 0, IMAGE_FEATURES)
            flat.copyInto(input, IMAGE_FEATURES + POINTS_LENGTH, IMAGE_FEATURES)
            return Features(input)
        }

        fun decode(input: FloatArray, x: Float, y: Float): FloatArray {
            SegmentTensor.points(constants, x, y).copyInto(input, IMAGE_FEATURES)
            decoderIn[0].writeFloat(input)
            decoder.run(decoderIn, decoderOut)
            val masks = decoderOut[0].readFloat()
            check(masks.size == SegmentModel.MASK_COUNT * MASK_PLANE) { "Unexpected decoder output: ${masks.size}" }
            return masks
        }

        override fun close() {
            (encoderIn + encoderOut + decoderIn + decoderOut).forEach { it.close() }
            decoder.close()
            encoder.close()
            env.close()
        }

        companion object {
            /** [backend]로 인코더·디코더를 불러 입출력 자리까지 만든다. 실패하면 만든 것을 모두 닫고 예외를 던진다. */
            fun load(assets: AssetManager, backend: Backend, cacheDir: File?, constants: FloatArray): Session {
                val opened = ArrayList<AutoCloseable>()
                try {
                    val env = Environment.create().also { opened += it }
                    val encoder = CompiledModel.create(assets, ENCODER_ASSET, options(backend, cacheDir, ENCODER_CACHE_KEY), env).also { opened += it }
                    val decoder = CompiledModel.create(assets, DECODER_ASSET, options(backend, cacheDir, DECODER_CACHE_KEY), env).also { opened += it }
                    val encoderIn = encoder.createInputBuffers().also { opened += it }
                    val encoderOut = encoder.createOutputBuffers().also { opened += it }
                    val decoderIn = decoder.createInputBuffers().also { opened += it }
                    val decoderOut = decoder.createOutputBuffers().also { opened += it }
                    return Session(env, encoder, decoder, encoderIn, encoderOut, decoderIn, decoderOut, constants)
                } catch (e: Throwable) {
                    opened.asReversed().forEach { it.close() }
                    throw e
                }
            }

            private fun options(backend: Backend, cacheDir: File?, cacheKey: String): CompiledModel.Options {
                val cache = cacheDir?.absolutePath
                return when (backend) {
                    Backend.GPU -> CompiledModel.Options(Accelerator.GPU).apply {
                        gpuOptions = CompiledModel.GpuOptions(
                            serializationDir = cache,
                            modelCacheKey = cache?.let { cacheKey },
                            serializeProgramCache = cache?.let { true }
                        )
                    }
                    Backend.CPU -> CompiledModel.Options(Accelerator.CPU).apply {
                        cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS)
                    }
                }
            }
        }
    }

    companion object {
        /** APK 안의 인코더(SAM 2.1 Hiera-Tiny, 79,847,760바이트, SHA-256 433d488b…73b101b1d) */
        const val ENCODER_ASSET = "models/sam2_encoder.tflite"

        /** APK 안의 디코더(16,866,608바이트, SHA-256 f00e1554…de56902d) */
        const val DECODER_ASSET = "models/sam2_decoder.tflite"

        /** 점 계산 상수(3,072바이트, [SegmentTensor.points]) */
        const val PROMPT_ASSET = "models/sam2_prompt.bin"

        /** GPU 프로그램 캐시의 이름. 모델 파일을 바꾸면 함께 바꾼다(지문 앞자리). */
        private const val ENCODER_CACHE_KEY = "sam2_encoder_433d488b"
        private const val DECODER_CACHE_KEY = "sam2_decoder_f00e1554"

        private const val CPU_THREADS = 4

        private const val INPUT_PLANE = SegmentModel.INPUT_SIZE * SegmentModel.INPUT_SIZE
        private const val MASK_PLANE = SegmentModel.MASK_SIZE * SegmentModel.MASK_SIZE

        /** 인코더 출력의 이미지 특징(256×64²). 그 뒤는 고해상 특징 둘(32×256², 64×128²)이다. */
        private const val IMAGE_FEATURES = 256 * 64 * 64

        /** 인코더 출력 전체 */
        private const val FEATURES_LENGTH = IMAGE_FEATURES + 32 * 256 * 256 + 64 * 128 * 128

        /** 점 둘 */
        private const val POINTS_LENGTH = 2 * SegmentTensor.POINT_LENGTH

        /**
         * 처음 불러올 때 더 쓰는 메모리의 어림(바이트). 폴드8 GPU +830MB(그래픽 638MB), CPU +1,004MB, 에뮬레이터 CPU +707MB였다(2026-10-21,
         * 시험 그림 하나를 읽은 뒤). 폴드8은 GPU로 돈다.
         */
        private const val LOAD_BYTES_ESTIMATE = 900L * 1024 * 1024

        /** 시험 그림에서 원을 찾았다고 볼 겹침(IoU). 맥 CPU에서 고른 후보는 0.99를 넘었다. */
        private const val PROBE_MIN_OVERLAP = 0.8

        private const val TAG = "LiteRtSegment"

        /**
         * 불러온 장치가 제대로 계산하는지: 밝은 회색 바탕(220)의 어두운 원(40, 반지름 160, 가운데 (400, 520))을 읽고 원 가운데를 눌러,
         * 어느 후보든 원과 [PROBE_MIN_OVERLAP] 이상 겹치는지 본다. 입력 크기(1024²) 배열이라 Kotlin으로 돈다(결정 기록 'AI 제거·AI 선택').
         */
        private fun findsTheProbeDisc(session: Session): Boolean {
            val size = SegmentModel.INPUT_SIZE
            val inDisc = { x: Float, y: Float -> hypot(x - PROBE_X, y - PROBE_Y) <= PROBE_RADIUS }
            val rgba = ByteArray(size * size * 4)
            for (p in 0 until size * size) {
                val value = if (inDisc(p % size + 0.5f, p / size + 0.5f)) 40 else 220
                for (c in 0 until 3) rgba[p * 4 + c] = value.toByte()
                rgba[p * 4 + 3] = 0xFF.toByte()
            }
            val masks = session.decode(session.encode(SegmentTensor.input(rgba)).decoderInput, PROBE_X, PROBE_Y)
            if (!masks.all { it.isFinite() }) return false
            val cell = size.toFloat() / SegmentModel.MASK_SIZE
            return (0 until SegmentModel.MASK_COUNT).any { k ->
                var both = 0
                var either = 0
                for (q in 0 until MASK_PLANE) {
                    val found = masks[k * MASK_PLANE + q] > 0f
                    val truth = inDisc((q % SegmentModel.MASK_SIZE + 0.5f) * cell, (q / SegmentModel.MASK_SIZE + 0.5f) * cell)
                    if (found && truth) both++
                    if (found || truth) either++
                }
                either > 0 && both.toDouble() / either >= PROBE_MIN_OVERLAP
            }
        }

        private const val PROBE_X = 400f
        private const val PROBE_Y = 520f
        private const val PROBE_RADIUS = 160f

        /** [AiThread]의 결과를 기다린다. 메모리 부족은 [NotEnoughMemoryException], 그 밖의 실패는 받은 예외 그대로 던진다. */
        private fun <T> await(future: Future<T>): T = try {
            future.get()
        } catch (e: ExecutionException) {
            throw when (val cause = e.cause) {
                is OutOfMemoryError -> NotEnoughMemoryException(cause)
                is SegmentUnavailableException -> cause
                is RuntimeException -> cause
                else -> SegmentUnavailableException("Segmentation failed", cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SegmentUnavailableException("Interrupted while segmenting", e)
        }
    }
}

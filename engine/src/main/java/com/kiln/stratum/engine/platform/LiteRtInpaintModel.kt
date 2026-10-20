package com.kiln.stratum.engine.platform

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.inpaint.InpaintModel
import com.kiln.stratum.engine.inpaint.InpaintUnavailableException
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.math.abs

/**
 * MI-GAN 채우기 모델(512px)을 LiteRT로 돌린다(결정 기록 'AI 제거·AI 선택').
 *
 * - 모델 파일은 APK 안의 [MODEL_ASSET]이다(압축하지 않는다). 엔진에는 Context가 없어서 앱이 [assets]와 [cacheDir]만 넘긴다.
 * - LiteRT 객체는 [AiThread]에서만 만들고, 쓰고, 닫는다. 부르는 쪽은 결과를 기다리기만 한다.
 * - 처음 쓸 때 불러온다. [backends] 순서대로(기본은 GPU 다음 CPU) 불러서 시험 그림으로 한 번 돌려 보고,
 *   실패하거나 수가 아닌 값(NaN·무한대)이 나오면 다음 장치로 한다. 모두 안 되면 앱을 다시 켤 때까지 쓸 수 없다고 기억한다.
 * - GPU는 컴파일한 프로그램을 [cacheDir]에 남겨, 다음에 불러올 때 빨라지게 한다.
 * - [release]로 내려놓으면 다음에 쓸 때 다시 불러온다.
 */
class LiteRtInpaintModel(
    private val assets: AssetManager,
    private val cacheDir: File?,
    private val backends: List<Backend> = listOf(Backend.GPU, Backend.CPU)
) : InpaintModel {

    /** 계산할 장치 */
    enum class Backend { GPU, CPU }

    override val size: Int get() = MODEL_SIZE

    override val pendingLoadBytes: Long get() = if (activeBackend == null) LOAD_BYTES_ESTIMATE else 0L

    /** 지금 불러 둔 장치. 불러오지 않았으면 null이다(측정·로그용). */
    @Volatile
    var activeBackend: Backend? = null
        private set

    // 아래 둘은 AiThread에서만 읽고 쓴다
    private var session: Session? = null
    private var failure: InpaintUnavailableException? = null

    override fun fill(input: FloatArray): FloatArray {
        require(input.size == InpaintModel.INPUT_CHANNELS * MODEL_SIZE * MODEL_SIZE) { "Input must be 1x4x${MODEL_SIZE}x$MODEL_SIZE" }
        return await(AiThread.executor.submit<FloatArray> {
            val opened = open()
            try {
                opened.run(input)
            } catch (e: Exception) {
                // 실행이 깨지면(GPU를 잃는 등) 내려놓고, 다음에 쓸 때 다시 불러온다
                val backend = activeBackend
                closeSession()
                throw InpaintUnavailableException("Inpainting failed on $backend", e)
            }
        })
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

    /** 불러 둔 모델. 없으면 불러온다. [AiThread]에서만 부른다. */
    private fun open(): Session {
        session?.let { return it }
        failure?.let { throw it }
        var cause: Throwable? = null
        for (backend in backends) {
            val started = SystemClock.elapsedRealtime()
            val opened = try {
                Session.load(assets, backend, cacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load the inpainting model on $backend", e)
                cause = e
                continue
            }
            val loadedAt = SystemClock.elapsedRealtime()
            val valid = try {
                looksValid(opened.run(probeInput()))
            } catch (e: OutOfMemoryError) {
                opened.close()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The inpainting model failed its first run on $backend", e)
                cause = e
                false
            }
            if (valid) {
                val ranAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "Inpainting model on $backend: load ${loadedAt - started} ms, first run ${ranAt - loadedAt} ms")
                session = opened
                activeBackend = backend
                return opened
            }
            Log.w(TAG, "The inpainting model gave unusable values on $backend")
            opened.close()
        }
        throw InpaintUnavailableException("No device could run the inpainting model", cause).also { failure = it }
    }

    /** 불러 둔 모델을 닫는다. [AiThread]에서만 부른다. */
    private fun closeSession() {
        session?.close()
        session = null
        activeBackend = null
    }

    /** 불러 둔 모델과 입출력 자리 */
    private class Session(
        private val env: Environment,
        private val model: CompiledModel,
        private val inputs: List<TensorBuffer>,
        private val outputs: List<TensorBuffer>
    ) : AutoCloseable {

        fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        override fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
            env.close()
        }

        companion object {
            /** [backend]로 모델을 불러 입출력 자리까지 만든다. 실패하면 만든 것을 모두 닫고 예외를 던진다. */
            fun load(assets: AssetManager, backend: Backend, cacheDir: File?): Session {
                val env = Environment.create()
                try {
                    val model = CompiledModel.create(assets, MODEL_ASSET, options(backend, cacheDir), env)
                    try {
                        val inputs = model.createInputBuffers()
                        try {
                            return Session(env, model, inputs, model.createOutputBuffers())
                        } catch (e: Throwable) {
                            inputs.forEach { it.close() }
                            throw e
                        }
                    } catch (e: Throwable) {
                        model.close()
                        throw e
                    }
                } catch (e: Throwable) {
                    env.close()
                    throw e
                }
            }

            private fun options(backend: Backend, cacheDir: File?): CompiledModel.Options {
                val cache = cacheDir?.absolutePath
                return when (backend) {
                    Backend.GPU -> CompiledModel.Options(Accelerator.GPU).apply {
                        gpuOptions = CompiledModel.GpuOptions(
                            serializationDir = cache,
                            modelCacheKey = cache?.let { MODEL_CACHE_KEY },
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
        /** APK 안의 모델 파일(MI-GAN 512 Places2, LiteRT fp16, 16,312,640바이트, SHA-256 ef53f8dc…2463c25f) */
        const val MODEL_ASSET = "models/migan_fp16.tflite"

        /** 입력·출력 한 변 */
        const val MODEL_SIZE = 512

        /** GPU 프로그램 캐시의 이름. 모델 파일을 바꾸면 함께 바꾼다(지문 앞자리). */
        private const val MODEL_CACHE_KEY = "migan_fp16_ef53f8dc"

        private const val CPU_THREADS = 4

        /** 출력이 이보다 크게 벗어나면 계산이 깨진 것으로 본다(정상은 -1..1) */
        private const val VALID_LIMIT = 4f

        /**
         * 처음 불러올 때 더 쓰는 메모리의 어림(바이트). 에뮬레이터 CPU에서 앱 메모리(PSS)가 약 386MB 늘었다(2026-10-20).
         * 폴드8(2026-10-21): GPU는 불러온 뒤 +249MB, 스무 번 채운 뒤 +307MB이고, CPU는 +388MB·+448MB다. 폴드8은 GPU로 돌아 그대로 둔다.
         */
        private const val LOAD_BYTES_ESTIMATE = 400L * 1024 * 1024

        private const val TAG = "LiteRtInpaint"

        /**
         * 시험 그림: 가운데에 네모 구멍이 있는 회색(RGB 0). 불러온 장치가 제대로 계산하는지 본다.
         * 모델 크기(512²) 배열이라 Kotlin으로 돈다(결정 기록 'AI 제거·AI 선택').
         */
        private fun probeInput(): FloatArray {
            val input = FloatArray(InpaintModel.INPUT_CHANNELS * MODEL_SIZE * MODEL_SIZE)
            val hole = MODEL_SIZE / 4 until MODEL_SIZE * 3 / 4
            for (y in 0 until MODEL_SIZE) {
                for (x in 0 until MODEL_SIZE) {
                    val keep = if (x in hole && y in hole) 0f else 1f
                    input[y * MODEL_SIZE + x] = keep - 0.5f
                }
            }
            return input
        }

        /** 출력이 제 크기이고, 모두 수이며, 크게 벗어나지 않는지 */
        private fun looksValid(output: FloatArray): Boolean =
            output.size == InpaintModel.OUTPUT_CHANNELS * MODEL_SIZE * MODEL_SIZE &&
                output.all { it.isFinite() && abs(it) <= VALID_LIMIT }

        /** [AiThread]의 결과를 기다린다. 메모리 부족은 [NotEnoughMemoryException], 그 밖의 실패는 받은 예외 그대로 던진다. */
        private fun <T> await(future: Future<T>): T = try {
            future.get()
        } catch (e: ExecutionException) {
            throw when (val cause = e.cause) {
                is OutOfMemoryError -> NotEnoughMemoryException(cause)
                is InpaintUnavailableException -> cause
                is RuntimeException -> cause
                else -> InpaintUnavailableException("Inpainting failed", cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InpaintUnavailableException("Interrupted while inpainting", e)
        }
    }
}

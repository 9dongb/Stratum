package com.kiln.stratum.engine.platform

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.skin.SkinModel
import com.kiln.stratum.engine.skin.SkinUnavailableException
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * 미디어파이프 셀피 멀티클래스 분할 모델을 LiteRT로 돌린다(결정 기록 '피부 보정'의 2a). 얼굴 모델([LiteRtFaceModel])과 같은 방식이다.
 *
 * - 모델 파일은 APK 안의 [MODEL_ASSET]이다. 엔진에는 Context가 없어서 앱이 [assets]와 [cacheDir]만 넘긴다.
 * - LiteRT 객체는 [AiThread]에서만 만들고, 쓰고, 닫는다. 부르는 쪽은 결과를 기다리기만 한다.
 * - 처음 쓸 때 불러온다. CPU로 먼저 하고([backends] 기본 순서), 안 되면 GPU로 한다. 얼굴 하나에 한 번 도는 작은 입력(256²)이라
 *   GPU 준비 시간이 아깝고, CPU 결과가 기기마다 같다.
 *   불러온 장치마다 회색 그림을 읽어 결과가 모두 숫자일 때만 쓴다. 모두 안 되면 앱을 다시 켤 때까지 쓸 수 없다고 기억한다.
 */
class LiteRtSkinModel(
    private val assets: AssetManager,
    private val cacheDir: File?,
    private val backends: List<Backend> = listOf(Backend.CPU, Backend.GPU)
) : SkinModel {

    /** 계산할 장치 */
    enum class Backend { CPU, GPU }

    override val pendingLoadBytes: Long get() = if (activeBackend == null) LOAD_BYTES_ESTIMATE else 0L

    /** 지금 불러 둔 장치. 불러오지 않았으면 null이다(측정·로그용). */
    @Volatile
    var activeBackend: Backend? = null
        private set

    // 아래 둘은 AiThread에서만 읽고 쓴다
    private var session: Session? = null
    private var failure: SkinUnavailableException? = null

    override fun segment(input: FloatArray): FloatArray {
        require(input.size == SkinModel.INPUT_VALUES) { "Input must be 1x${SkinModel.INPUT_SIZE}²x3" }
        return onAiThread { it.segment(input) }
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
            throw SkinUnavailableException("Skin model failed on $backend", e)
        }
    })

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
                Log.w(TAG, "Could not load the skin model on $backend", e)
                cause = e
                continue
            }
            val loadedAt = SystemClock.elapsedRealtime()
            val valid = try {
                answersSensibly(opened)
            } catch (e: OutOfMemoryError) {
                opened.close()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The skin model failed its first run on $backend", e)
                cause = e
                false
            }
            if (valid) {
                Log.i(TAG, "Skin model on $backend: load ${loadedAt - started} ms, probe ${SystemClock.elapsedRealtime() - loadedAt} ms")
                session = opened
                activeBackend = backend
                return opened
            }
            Log.w(TAG, "The skin model gave wrong results on $backend")
            opened.close()
        }
        throw SkinUnavailableException("No device could run the skin model", cause).also { failure = it }
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

        fun segment(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            // GPU 실행은 비동기라 읽을 때 끝난다
            val logits = outputs[0].readFloat()
            check(logits.size == SkinModel.OUTPUT_VALUES) { "Unexpected output: ${logits.size}" }
            return logits
        }

        override fun close() {
            (inputs + outputs).forEach { it.close() }
            model.close()
            env.close()
        }

        companion object {
            /** [backend]로 모델을 불러 입출력 자리까지 만든다. 실패하면 만든 것을 모두 닫고 예외를 던진다. */
            fun load(assets: AssetManager, backend: Backend, cacheDir: File?): Session {
                val opened = ArrayList<AutoCloseable>()
                try {
                    val env = Environment.create().also { opened += it }
                    val model = CompiledModel.create(assets, MODEL_ASSET, options(backend, cacheDir), env).also { opened += it }
                    val inputs = model.createInputBuffers().also { opened += it }
                    val outputs = model.createOutputBuffers().also { opened += it }
                    check(inputs.isNotEmpty() && outputs.isNotEmpty()) { "Unexpected buffers: ${inputs.size}, ${outputs.size}" }
                    return Session(env, model, inputs, outputs)
                } catch (e: Throwable) {
                    opened.asReversed().forEach { it.close() }
                    throw e
                }
            }

            private fun options(backend: Backend, cacheDir: File?): CompiledModel.Options {
                val cache = cacheDir?.absolutePath
                return when (backend) {
                    Backend.GPU -> CompiledModel.Options(Accelerator.GPU).apply {
                        gpuOptions = CompiledModel.GpuOptions(
                            serializationDir = cache,
                            modelCacheKey = cache?.let { CACHE_KEY },
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
        /** APK 안의 분할 모델(16,371,837바이트, SHA-256 c6748b12…507e0e0) */
        const val MODEL_ASSET = "models/selfie_multiclass_256x256.tflite"

        /** GPU 프로그램 캐시의 이름. 모델 파일을 바꾸면 함께 바꾼다(지문 앞자리). */
        private const val CACHE_KEY = "skin_selfie_c6748b12"

        private const val CPU_THREADS = 4

        /** 처음 불러올 때 더 쓰는 메모리의 어림(바이트). 에뮬레이터 CPU 실측 +104MB(SkinModelProbeTest)보다 넉넉히 잡는다. */
        private const val LOAD_BYTES_ESTIMATE = 128L * 1024 * 1024

        private const val TAG = "LiteRtSkin"

        /** 불러온 장치가 제대로 계산하는지: 가운데 회색 그림(값 0.5)을 넣어 결과가 모두 숫자인지 본다(GPU가 깨진 값을 내면 여기서 걸린다). */
        private fun answersSensibly(session: Session): Boolean =
            session.segment(FloatArray(SkinModel.INPUT_VALUES) { 0.5f }).all { it.isFinite() }

        /** [AiThread]의 결과를 기다린다. 메모리 부족은 [NotEnoughMemoryException], 그 밖의 실패는 받은 예외 그대로 던진다. */
        private fun <T> await(future: Future<T>): T = try {
            future.get()
        } catch (e: ExecutionException) {
            throw when (val cause = e.cause) {
                is OutOfMemoryError -> NotEnoughMemoryException(cause)
                is SkinUnavailableException -> cause
                is RuntimeException -> cause
                else -> SkinUnavailableException("Skin segmentation failed", cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SkinUnavailableException("Interrupted while segmenting skin", e)
        }
    }
}

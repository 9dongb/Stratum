package com.kiln.stratum.engine.platform

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.face.DetectorOutput
import com.kiln.stratum.engine.face.FaceDetectionDecoder
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.FaceRoi
import com.kiln.stratum.engine.face.FaceUnavailableException
import com.kiln.stratum.engine.face.LandmarkOutput
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * 미디어파이프 얼굴 모델 둘(BlazeFace 먼 거리용·FaceMesh V2)을 LiteRT로 돌린다(결정 기록 '얼굴 인식 보정').
 *
 * - 모델 파일은 APK 안의 [DETECTOR_ASSET]·[LANDMARK_ASSET]이다. 엔진에는 Context가 없어서 앱이 [assets]와 [cacheDir]만 넘긴다.
 * - LiteRT 객체는 [AiThread]에서만 만들고, 쓰고, 닫는다(다른 AI 모델과 같은 스레드라 서로 겹치지 않는다). 부르는 쪽은 결과를 기다리기만 한다.
 * - 처음 쓸 때 불러온다. 두 모델 모두 작아(1MB·2.5MB) CPU로 먼저 하고([backends] 기본 순서), 안 되면 GPU로 한다.
 *   불러온 장치마다 회색 그림을 읽어, 결과가 숫자이고 회색에서 얼굴을 찾지 않을 때만 쓴다. 모두 안 되면 앱을 다시 켤 때까지 쓸 수 없다고 기억한다.
 * - 출력 순서는 모델 파일 그대로다(서명 없음): 얼굴 찾기는 상자(2304×16)·점수(2304), 특징점은 점(1434)·얼굴 있음·혀(쓰지 않음).
 */
class LiteRtFaceModel(
    private val assets: AssetManager,
    private val cacheDir: File?,
    private val backends: List<Backend> = listOf(Backend.CPU, Backend.GPU)
) : FaceModel {

    /** 계산할 장치 */
    enum class Backend { CPU, GPU }

    override val pendingLoadBytes: Long get() = if (activeBackend == null) LOAD_BYTES_ESTIMATE else 0L

    /** 지금 불러 둔 장치. 불러오지 않았으면 null이다(측정·로그용). */
    @Volatile
    var activeBackend: Backend? = null
        private set

    // 아래 둘은 AiThread에서만 읽고 쓴다
    private var session: Session? = null
    private var failure: FaceUnavailableException? = null

    override fun detect(input: FloatArray): DetectorOutput {
        require(input.size == DETECTOR_INPUT) { "Input must be 1x${FaceModel.DETECTOR_SIZE}²x3" }
        return onAiThread { it.detect(input) }
    }

    override fun landmarks(input: FloatArray): LandmarkOutput {
        require(input.size == LANDMARK_INPUT) { "Input must be 1x${FaceModel.LANDMARK_SIZE}²x3" }
        return onAiThread { it.landmarks(input) }
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
            throw FaceUnavailableException("Face model failed on $backend", e)
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
                Log.w(TAG, "Could not load the face models on $backend", e)
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
                Log.w(TAG, "The face models failed their first run on $backend", e)
                cause = e
                false
            }
            if (valid) {
                Log.i(TAG, "Face models on $backend: load ${loadedAt - started} ms, probe ${SystemClock.elapsedRealtime() - loadedAt} ms")
                session = opened
                activeBackend = backend
                return opened
            }
            Log.w(TAG, "The face models gave wrong results on $backend")
            opened.close()
        }
        throw FaceUnavailableException("No device could run the face models", cause).also { failure = it }
    }

    /** 불러 둔 모델을 닫는다. [AiThread]에서만 부른다. */
    private fun closeSession() {
        session?.close()
        session = null
        activeBackend = null
    }

    /** 불러 둔 두 모델과 입출력 자리 */
    private class Session(
        private val env: Environment,
        private val detector: CompiledModel,
        private val landmarker: CompiledModel,
        private val detectorIn: List<TensorBuffer>,
        private val detectorOut: List<TensorBuffer>,
        private val landmarkIn: List<TensorBuffer>,
        private val landmarkOut: List<TensorBuffer>
    ) : AutoCloseable {

        fun detect(input: FloatArray): DetectorOutput {
            detectorIn[0].writeFloat(input)
            detector.run(detectorIn, detectorOut)
            // GPU 실행은 비동기라 읽을 때 끝난다. 상자·점수는 길이로 가린다.
            val first = detectorOut[0].readFloat()
            val second = detectorOut[1].readFloat()
            val boxesFirst = first.size == FaceModel.ANCHOR_COUNT * FaceModel.ANCHOR_VALUES
            return DetectorOutput(if (boxesFirst) first else second, if (boxesFirst) second else first)
        }

        fun landmarks(input: FloatArray): LandmarkOutput {
            landmarkIn[0].writeFloat(input)
            landmarker.run(landmarkIn, landmarkOut)
            val points = landmarkOut[0].readFloat()
            val presence = landmarkOut[1].readFloat()
            check(presence.size == 1) { "Unexpected presence output: ${presence.size}" }
            return LandmarkOutput(points, presence[0])
        }

        override fun close() {
            (detectorIn + detectorOut + landmarkIn + landmarkOut).forEach { it.close() }
            landmarker.close()
            detector.close()
            env.close()
        }

        companion object {
            /** [backend]로 두 모델을 불러 입출력 자리까지 만든다. 실패하면 만든 것을 모두 닫고 예외를 던진다. */
            fun load(assets: AssetManager, backend: Backend, cacheDir: File?): Session {
                val opened = ArrayList<AutoCloseable>()
                try {
                    val env = Environment.create().also { opened += it }
                    val detector = CompiledModel.create(assets, DETECTOR_ASSET, options(backend, cacheDir, DETECTOR_CACHE_KEY), env).also { opened += it }
                    val landmarker = CompiledModel.create(assets, LANDMARK_ASSET, options(backend, cacheDir, LANDMARK_CACHE_KEY), env).also { opened += it }
                    val detectorIn = detector.createInputBuffers().also { opened += it }
                    val detectorOut = detector.createOutputBuffers().also { opened += it }
                    val landmarkIn = landmarker.createInputBuffers().also { opened += it }
                    val landmarkOut = landmarker.createOutputBuffers().also { opened += it }
                    check(detectorOut.size >= 2 && landmarkOut.size >= 2) { "Unexpected outputs: ${detectorOut.size}, ${landmarkOut.size}" }
                    return Session(env, detector, landmarker, detectorIn, detectorOut, landmarkIn, landmarkOut)
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
        /** APK 안의 얼굴 찾기 모델(BlazeFace 먼 거리용, 1,083,786바이트, SHA-256 3698b18f…aed2b181b) */
        const val DETECTOR_ASSET = "models/blaze_face_full_range.tflite"

        /** APK 안의 특징점 모델(FaceMesh V2, 2,553,590바이트, SHA-256 c7d54204…43d5) */
        const val LANDMARK_ASSET = "models/face_landmarks_detector.tflite"

        /** GPU 프로그램 캐시의 이름. 모델 파일을 바꾸면 함께 바꾼다(지문 앞자리). */
        private const val DETECTOR_CACHE_KEY = "face_detector_3698b18f"
        private const val LANDMARK_CACHE_KEY = "face_landmarks_c7d54204"

        private const val CPU_THREADS = 4

        private const val DETECTOR_INPUT = FaceModel.DETECTOR_SIZE * FaceModel.DETECTOR_SIZE * 3
        private const val LANDMARK_INPUT = FaceModel.LANDMARK_SIZE * FaceModel.LANDMARK_SIZE * 3

        /** 처음 불러올 때 더 쓰는 메모리의 어림(바이트). 두 모델이 작아 넉넉히 잡는다(에뮬레이터 CPU 실측은 FaceModelProbeTest). */
        private const val LOAD_BYTES_ESTIMATE = 64L * 1024 * 1024

        private const val TAG = "LiteRtFace"

        /**
         * 불러온 장치가 제대로 계산하는지: 가운데 회색 그림(값 0)을 두 모델에 넣어, 결과가 모두 숫자이고 얼굴 찾기가 아무것도 찾지 않는지 본다.
         * GPU가 깨진 값을 내면 여기서 걸린다.
         */
        private fun answersSensibly(session: Session): Boolean {
            val detected = session.detect(FloatArray(DETECTOR_INPUT))
            if (!detected.boxes.all { it.isFinite() } || !detected.scores.all { it.isFinite() }) return false
            if (FaceDetectionDecoder.decode(detected, FaceRoi(96f, 96f, 192f)).isNotEmpty()) return false
            val landmarks = session.landmarks(FloatArray(LANDMARK_INPUT) { 0.5f })
            return landmarks.points.all { it.isFinite() } && landmarks.presence.isFinite()
        }

        /** [AiThread]의 결과를 기다린다. 메모리 부족은 [NotEnoughMemoryException], 그 밖의 실패는 받은 예외 그대로 던진다. */
        private fun <T> await(future: Future<T>): T = try {
            future.get()
        } catch (e: ExecutionException) {
            throw when (val cause = e.cause) {
                is OutOfMemoryError -> NotEnoughMemoryException(cause)
                is FaceUnavailableException -> cause
                is RuntimeException -> cause
                else -> FaceUnavailableException("Face detection failed", cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FaceUnavailableException("Interrupted while finding faces", e)
        }
    }
}

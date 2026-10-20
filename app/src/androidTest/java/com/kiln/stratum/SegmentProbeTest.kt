package com.kiln.stratum

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AI 선택 6단계: 분할 모델 두 후보(SAM 2.1 Tiny·MobileSAM)를 이 기기의 CPU·GPU에서 돌려 시간·메모리를 재고,
 * 맥(LiteRT 파이썬, CPU)에서 같은 입력으로 낸 마스크와 같은지 본다(결정 기록 'AI 제거·AI 선택').
 *
 * 모델은 아직 고르지 않아 저장소에 없다. 앱 내부 저장소 files/seg/에 넣어 둔 때만 돌고, 없으면 건너뛴다(넣는 법은 docs/emulator.md).
 * 그 장치로 모델을 돌리지 못해도 건너뛴다. 잰 값은 logcat의 "SegmentProbe" 줄로 본다.
 */
@RunWith(AndroidJUnit4::class)
class SegmentProbeTest {

    @Test
    fun sam2_cpu() = probe(Candidate.SAM2, Accelerator.CPU)

    @Test
    fun sam2_gpu() = probe(Candidate.SAM2, Accelerator.GPU)

    @Test
    fun mobileSam_cpu() = probe(Candidate.MOBILE_SAM, Accelerator.CPU)

    @Test
    fun mobileSam_gpu() = probe(Candidate.MOBILE_SAM, Accelerator.GPU)

    /** 후보 모델. 파일 이름 앞부분([prefix])과 디코더의 입력·출력 모양이 다르다. */
    private enum class Candidate(val prefix: String) {
        /** SAM 2.1 Tiny 변환본: 인코더가 특징 셋을 한 줄로 내고, 디코더는 그 사이에 점 둘을 끼운 한 줄을 받아 마스크 셋을 낸다(0번을 씀). */
        SAM2("sam2"),

        /**
         * MobileSAM(직접 변환): 디코더가 특징과 점 둘을 따로 받아 마스크 넷과 점수 넷을 낸다(1~3번 중 점수가 가장 높은 것).
         * 폴드8 GPU에서는 인코더 결과가 틀려 마스크가 맞지 않는다(2026-10-21, 보통 정밀도로도 IoU 0.62). CPU는 맞다.
         */
        MOBILE_SAM("mobilesam")
    }

    private fun probe(candidate: Candidate, accelerator: Accelerator) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "seg")
        fun file(suffix: String) = File(dir, candidate.prefix + suffix)
        val suffixes = listOf("_encoder.tflite", "_decoder.tflite", "_prompt.bin", "_input.f32", "_tap.txt", "_mask.f32")
        assumeTrue("The model files are not in ${dir.path}", suffixes.all { file(it).isFile })

        val image = readFloats(file("_input.f32"))
        val reference = readFloats(file("_mask.f32"))
        val (tapX, tapY) = file("_tap.txt").readText().trim().split(" ").map { it.toFloat() }
        val points = sparse(readFloats(file("_prompt.bin")), tapX, tapY)
        // GPU는 컴파일한 프로그램을 남겨 두 번째 불러오기부터 빨라진다(앱의 채우기 모델과 같다). 처음인지 로그에 남긴다.
        val gpuCache = File(context.cacheDir, "seg-gpu").apply { mkdirs() }
        val cachedFiles = gpuCache.list()?.size ?: 0

        val before = ProcessMemory.now()
        Environment.create().use { env ->
            val loadStarted = SystemClock.elapsedRealtime()
            val encoder = load(file("_encoder.tflite"), accelerator, env, gpuCache)
            encoder.use {
                val decoder = load(file("_decoder.tflite"), accelerator, env, gpuCache)
                decoder.use {
                    val loaded = SystemClock.elapsedRealtime() - loadStarted
                    val encoderIn = encoder.createInputBuffers()
                    val encoderOut = encoder.createOutputBuffers()
                    val decoderIn = decoder.createInputBuffers()
                    val decoderOut = decoder.createOutputBuffers()
                    try {
                        // 사진 한 장: 입력 쓰기·실행·특징 읽기까지 잰다(GPU는 실행이 비동기라 결과를 읽어야 끝난다)
                        lateinit var features: FloatArray
                        fun encode() {
                            encoderIn[0].writeFloat(image)
                            encoder.run(encoderIn, encoderOut)
                            features = encoderOut[0].readFloat()
                        }
                        val encodeCold = timed { firstRun(accelerator) { encode() } }
                        val encodeTimes = LongArray(ENCODER_RUNS) { timed { encode() } }.sorted()

                        // 탭 한 번: 디코더 입력 쓰기·실행·마스크 읽기까지 잰다. SAM 2.1 Tiny는 특징 사이에 점을 끼운 한 줄을 사진마다 한 번 만든다.
                        val sam2Input = if (candidate == Candidate.SAM2) sam2DecoderInput(features, points) else null
                        lateinit var mask: FloatArray
                        fun decode() {
                            when (candidate) {
                                Candidate.SAM2 -> decoderIn[0].writeFloat(checkNotNull(sam2Input))
                                Candidate.MOBILE_SAM -> {
                                    decoderIn[0].writeFloat(features)
                                    decoderIn[1].writeFloat(points)
                                }
                            }
                            decoder.run(decoderIn, decoderOut)
                            mask = when (candidate) {
                                Candidate.SAM2 -> decoderOut[0].readFloat().copyOfRange(0, MASK_PLANE)
                                Candidate.MOBILE_SAM -> bestMobileSamMask(decoderOut[0].readFloat(), decoderOut[1].readFloat())
                            }
                        }
                        val decodeCold = timed { firstRun(accelerator) { decode() } }
                        val decodeTimes = LongArray(DECODER_RUNS) { timed { decode() } }.sorted()
                        val after = ProcessMemory.now()

                        assertEquals(MASK_PLANE, mask.size)
                        assertTrue("Every value is a number", mask.all { it.isFinite() })
                        val agreement = intersectionOverUnion(mask, reference)
                        val maxDiff = mask.indices.maxOf { abs(mask[it] - reference[it]) }
                        Log.i(
                            TAG,
                            "${candidate.name} on $accelerator: load $loaded ms " +
                                "(encoder ${file("_encoder.tflite").length() / 1_000_000} MB + decoder ${file("_decoder.tflite").length() / 1_000_000} MB" +
                                (if (accelerator == Accelerator.GPU) ", GPU cache files before: $cachedFiles" else "") + "); " +
                                "encode cold $encodeCold ms, median ${encodeTimes[ENCODER_RUNS / 2]} ms (min ${encodeTimes.first()}, max ${encodeTimes.last()}); " +
                                "tap cold $decodeCold ms, median ${decodeTimes[DECODER_RUNS / 2]} ms (min ${decodeTimes.first()}, max ${decodeTimes.last()}); " +
                                "PSS +${(after.pssKb - before.pssKb) / 1024} MB, graphics +${(after.graphicsKb - before.graphicsKb) / 1024} MB, " +
                                "native heap +${(after.nativeKb - before.nativeKb) / 1024} MB; " +
                                "mask IoU with the Mac ${"%.4f".format(agreement)}, max logit diff ${"%.3f".format(maxDiff)}"
                        )
                        assertTrue("The mask matches the Mac's (IoU $agreement)", agreement >= MIN_AGREEMENT)
                    } finally {
                        (encoderIn + encoderOut + decoderIn + decoderOut).forEach { it.close() }
                    }
                }
            }
        }
    }

    /** [accelerator]로 모델을 불러온다. GPU는 [gpuCache]에 컴파일한 프로그램을 남긴다. 이 기기에서 그 장치를 쓸 수 없으면 건너뛴다. */
    private fun load(file: File, accelerator: Accelerator, env: Environment, gpuCache: File): CompiledModel = try {
        val options = CompiledModel.Options(accelerator)
        when (accelerator) {
            Accelerator.CPU -> options.cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS)
            Accelerator.GPU -> options.gpuOptions = CompiledModel.GpuOptions(
                serializationDir = gpuCache.absolutePath,
                modelCacheKey = file.nameWithoutExtension,
                serializeProgramCache = true
            )
            else -> {}
        }
        CompiledModel.create(file.absolutePath, options, env)
    } catch (e: Exception) {
        Log.i(TAG, "$accelerator cannot load ${file.name} on this device: $e")
        assumeNoException("$accelerator cannot load ${file.name} on this device", e)
        throw e
    }

    /** 첫 실행. 이 기기에서 그 장치로 돌리지 못하면 건너뛴다. */
    private inline fun firstRun(accelerator: Accelerator, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.i(TAG, "$accelerator cannot run the model on this device: $e")
            assumeNoException("$accelerator cannot run the model on this device", e)
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val started = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
    }

    /** 리틀엔디언 float32 파일 */
    private fun readFloats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * 점 둘(탭한 곳과 빈 점, 2×256)의 프롬프트. [constants]는 위치 무늬 행렬(x 128 | y 128), 양의 점 256, 빈 점 256이다.
     * 탭 자리([x], [y])는 모델의 1024 틀 픽셀이다(SAM의 PromptEncoder와 같은 계산).
     */
    private fun sparse(constants: FloatArray, x: Float, y: Float): FloatArray {
        val cx = 2f * ((x + 0.5f) / FRAME) - 1f
        val cy = 2f * ((y + 0.5f) / FRAME) - 1f
        val points = FloatArray(2 * EMBED)
        for (k in 0 until EMBED / 2) {
            val projection = 2.0 * PI * (cx * constants[k] + cy * constants[EMBED / 2 + k])
            points[k] = sin(projection).toFloat() + constants[EMBED + k]
            points[EMBED / 2 + k] = cos(projection).toFloat() + constants[EMBED + EMBED / 2 + k]
        }
        for (k in 0 until EMBED) points[EMBED + k] = constants[2 * EMBED + k]
        return points
    }

    /** SAM 2.1 Tiny 디코더 입력: 이미지 특징(256×64²) | 점 둘(512) | 고해상 특징 둘 */
    private fun sam2DecoderInput(features: FloatArray, points: FloatArray): FloatArray {
        val imageFeatures = EMBED * 64 * 64
        val input = FloatArray(features.size + points.size)
        features.copyInto(input, 0, 0, imageFeatures)
        points.copyInto(input, imageFeatures)
        features.copyInto(input, imageFeatures + points.size, imageFeatures)
        return input
    }

    /** MobileSAM 마스크 넷(4×256²) 가운데 점 하나의 후보(1~3번)에서 점수가 가장 높은 것 */
    private fun bestMobileSamMask(masks: FloatArray, scores: FloatArray): FloatArray {
        val best = (1..3).maxBy { scores[it] }
        return masks.copyOfRange(best * MASK_PLANE, (best + 1) * MASK_PLANE)
    }

    /** 두 로짓 마스크(0보다 크면 물체)의 겹친 정도 */
    private fun intersectionOverUnion(a: FloatArray, b: FloatArray): Double {
        var both = 0
        var either = 0
        for (i in a.indices) {
            val inA = a[i] > 0f
            val inB = b[i] > 0f
            if (inA && inB) both++
            if (inA || inB) either++
        }
        return if (either == 0) 1.0 else both.toDouble() / either
    }

    private companion object {
        const val TAG = "SegmentProbe"

        /** 모델 입력 틀의 한 변(px) */
        const val FRAME = 1024f

        /** 점 하나의 길이 */
        const val EMBED = 256

        /** 마스크 한 장(256²) */
        const val MASK_PLANE = 256 * 256

        const val ENCODER_RUNS = 5
        const val DECODER_RUNS = 20
        const val CPU_THREADS = 4

        /** 맥 CPU와 이 기기의 마스크가 이만큼은 겹쳐야 한다. GPU(fp16)는 경계 몇 픽셀이 다를 수 있어 여유를 둔다. */
        const val MIN_AGREEMENT = 0.95
    }
}

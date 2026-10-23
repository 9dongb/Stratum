package com.kiln.stratum.engine.skin

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 잡티 찾기(결정 기록 '피부 보정'의 5단계, 식·상수는 0단계 맥 시험 spot_proto). 얼굴 하나를 두 눈꼬리 사이가 [FRAME_UNIT]px가 되게 맞춘
 * 작은 그림(틀)에서 찾는다. 그래서 크기는 모두 얼굴 크기 단위다.
 *
 * - 신호 = 어두움 + 붉은기: 배경(피부 안쪽만으로 낸 넓은 평균, σ = U/6)보다 어두운 만큼(OKLab L)과 붉은 만큼(a)을 각자의 퍼짐
 *   (1.4826 × 가운데 절대 편차)으로 나눠 더한다. 그래서 피부 결이 거친 사람은 그만큼 덜 찾는다.
 * - 여러 크기의 DoG(σ 1.2·2·3.2·5, 바깥 σ는 2.2배)에 σ를 곱한 응답이 [THRESHOLD]를 넘는 지역 최대가 점이다. 둘레가 겹치면(가운데 사이가
 *   두 반지름의 합보다 가까움) 센 것 하나만 남긴다. 그래서 큰 점 안·가장자리의 작은 결은 따로 찾지 않는다(맥 시험은 큰 반지름만 보아 점 가장자리를 지웠다).
 * - 둥근 점만: 응답의 굽음(헤세 행렬)이 한쪽으로만 큰 곳(주름·귀 주름·머리카락 같은 선)은 뺀다(SIFT의 가장자리 거르기와 같은 비율 시험, [ELONGATION]).
 * - 지우는 붓 자리([healRadius] + 1px)가 모두 피부 안쪽인 점만 찾는다. 눈·눈썹·입술·콧구멍·귀·머리선처럼 마스크가 빠진 곳 둘레의
 *   그늘(눈 밑, 콧방울, 입꼬리, 귀 안쪽)은 얼굴 생김새라 지우지 않는다. 그 둘레의 잡티는 손으로 지운다.
 *   두 가지 모두 맥 시험에는 없었고, 실제 사진에서 눈가 주름·귀 안쪽·콧방울 그늘을 잡티로 지웠다.
 * - 점(모반)은 남긴다(사용자 결정 "바로 지우기, 점은 남김"): 어두움이 퍼짐의 [MOLE_DARKNESS]배를 넘고, 붉은기가 [MOLE_REDNESS]배 아래이고,
 *   반지름이 [MOLE_RADIUS]px(틀)보다 큰 점. 붉은 뾰루지와 작은 점은 지운다. 어두움·붉은기는 점의 원 안 피부 픽셀의 평균이다(맥 시험의 가운데 한 픽셀은 잡음에 흔들렸다).
 * - 틀(얼굴 하나, 수십만 픽셀)에서만 돈다(Kotlin 반복, CLAUDE.md 2장).
 */
object SkinSpots {

    /** 틀에서 두 눈꼬리 사이(길이 단위 U)의 픽셀 수 */
    const val FRAME_UNIT = 160f

    /** DoG의 안쪽 σ(틀 픽셀) */
    private val SIGMAS = floatArrayOf(1.2f, 2f, 3.2f, 5f)

    /** DoG의 바깥 σ = 안쪽 σ × 이 배수 */
    private const val OUTER = 2.2f

    /** 점으로 볼 응답(DoG × σ)의 바닥 */
    const val THRESHOLD = 2.2f

    /** 찾을 피부: 마스크가 이보다 큰 곳(경계·눈썹 둘레처럼 피부가 아닌 것이 섞인 곳은 보지 않음) */
    const val INNER = 0.8f

    /** 찾은 점의 반지름 = 안쪽 σ × 이 배수(틀 픽셀) */
    private const val RADIUS_PER_SIGMA = 1.6f

    /** 지우는 붓의 반지름 = 찾은 반지름 × 이 배수 + [HEAL_MARGIN](잡티 둘레의 피부까지 조금 넉넉하게, [healRadius]) */
    const val HEAL_RADIUS = 1.5f

    /** 지우는 붓 반지름에 더하는 틀 픽셀(작은 점은 찾은 반지름이 실제보다 작게 나와 덜 덮지 않게) */
    const val HEAL_MARGIN = 1.5f

    /** 찾은 반지름 [radius](틀 픽셀)의 잡티를 지우는 붓의 반지름(틀 픽셀). 이 자리가 모두 피부 안쪽이어야 찾는다. */
    fun healRadius(radius: Float): Float = radius * HEAL_RADIUS + HEAL_MARGIN

    /** 둥근 정도의 한도: 헤세 행렬 두 고유값의 비가 이보다 크면 선으로 본다(SIFT는 10, 주름을 더 거르려고 낮춤) */
    const val ELONGATION = 4f

    /** 배경의 σ = U × 이 몫 */
    private const val BACKGROUND = 1f / 6f

    /** 점(모반)의 어두움 바닥(퍼짐의 배수) */
    const val MOLE_DARKNESS = 6f

    /** 점(모반)의 붉은기 천장(퍼짐의 배수): 이보다 붉으면 뾰루지다 */
    const val MOLE_REDNESS = 1f

    /** 점(모반)의 반지름 바닥(틀 픽셀): 이보다 작은 점은 지운다 */
    const val MOLE_RADIUS = 3f

    /**
     * 찾은 점 하나(틀 좌표, 픽셀 가운데가 x + 0.5). [radius]는 틀 픽셀, [strength]는 응답, [darkness]·[redness]는 점의 원 안이 배경보다
     * 어둡고 붉은 정도(퍼짐의 배수, 원 안 피부 픽셀의 평균)다.
     */
    data class Spot(val x: Float, val y: Float, val radius: Float, val strength: Float, val darkness: Float, val redness: Float) {

        /** 점(모반)이라 남길지 */
        val isMole: Boolean
            get() = darkness > MOLE_DARKNESS && redness < MOLE_REDNESS && radius > MOLE_RADIUS
    }

    /**
     * 틀 그림(OKLab [lightness]·[a], 마스크 [mask] 0..1, [width]×[height])에서 점을 찾는다(센 것부터). 남길 점(모반)도 들어 있다([Spot.isMole]).
     * 마스크 안쪽([INNER] 넘게)이 없으면 빈 목록이다.
     */
    fun detect(lightness: FloatArray, a: FloatArray, mask: FloatArray, width: Int, height: Int): List<Spot> {
        require(lightness.size == width * height && a.size == width * height && mask.size == width * height) { "Frame arrays must be ${width}x$height" }
        // 피부 안쪽을 덮는 사각형과 둘레(가장 큰 DoG가 읽는 거리)만 계산한다. 그 밖은 무게가 0이라 배경도 같다.
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (mask[y * width + x] > INNER) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return emptyList()
        val margin = MARGIN
        left = maxOf(0, left - margin)
        top = maxOf(0, top - margin)
        right = minOf(width - 1, right + margin)
        bottom = minOf(height - 1, bottom + margin)
        val w = right - left + 1
        val h = bottom - top + 1
        fun crop(values: FloatArray) = FloatArray(w * h) { values[(top + it / w) * width + left + it % w] }
        return detectIn(crop(lightness), crop(a), crop(mask), w, h).map { it.copy(x = it.x + left, y = it.y + top) }
    }

    /** 가장 큰 DoG(바깥 σ = 5 × 2.2)가 읽는 거리 + 한 칸 */
    private const val MARGIN = 35

    private fun detectIn(lightness: FloatArray, a: FloatArray, mask: FloatArray, width: Int, height: Int): List<Spot> {
        val count = width * height
        val inner = BooleanArray(count) { mask[it] > INNER }
        // 배경: 피부 안쪽만으로 낸 넓은 평균
        val weight = FloatArray(count) { if (inner[it]) 1f else 0f }
        val sigma = FRAME_UNIT * BACKGROUND
        val total = wideBlur(weight, width, height, sigma)
        val lightSum = wideBlur(FloatArray(count) { lightness[it] * weight[it] }, width, height, sigma)
        val redSum = wideBlur(FloatArray(count) { a[it] * weight[it] }, width, height, sigma)
        val dark = FloatArray(count) { lightSum[it] / max(total[it], 1e-3f) - lightness[it] }
        val red = FloatArray(count) { a[it] - redSum[it] / max(total[it], 1e-3f) }
        val darkSpread = spread(dark, inner)
        val redSpread = spread(red, inner)
        val signal = FloatArray(count) { max(dark[it] / darkSpread, 0f) + max(red[it] / redSpread, 0f) }

        val found = ArrayList<Spot>()
        for (scale in SIGMAS) {
            val near = SkinMaps.gaussian(signal, width, height, scale)
            val far = SkinMaps.gaussian(signal, width, height, scale * OUTER)
            val response = FloatArray(count) { (near[it] - far[it]) * scale }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    if (!inner[i] || response[i] <= THRESHOLD || !isPeak(response, x, y, width, height) || isLine(response, x, y, width, height)) continue
                    val radius = scale * RADIUS_PER_SIGMA
                    if (!insideSkin(inner, x, y, healRadius(radius) + 1f, width, height)) continue
                    val (darkness, redness) = discMean(dark, red, inner, x, y, radius, width, height)
                    found += Spot(x + 0.5f, y + 0.5f, radius, response[i], darkness / darkSpread, redness / redSpread)
                }
            }
        }
        // 둘레가 겹치는 것은 센 것 하나만
        found.sortByDescending { it.strength }
        val kept = ArrayList<Spot>()
        for (spot in found) {
            if (kept.all { hypot(spot.x - it.x, spot.y - it.y) > spot.radius + it.radius }) kept += spot
        }
        return kept
    }

    /** ([x], [y]) 가운데 반지름 [radius] 원 안 피부 픽셀의 [dark]·[red] 평균(피부 픽셀이 없으면 가운데 값) */
    private fun discMean(dark: FloatArray, red: FloatArray, inner: BooleanArray, x: Int, y: Int, radius: Float, width: Int, height: Int): Pair<Float, Float> {
        val reach = radius.toInt()
        var sumDark = 0f
        var sumRed = 0f
        var count = 0
        for (dy in -reach..reach) {
            val row = y + dy
            if (row < 0 || row >= height) continue
            for (dx in -reach..reach) {
                val column = x + dx
                if (column < 0 || column >= width || dx * dx + dy * dy > radius * radius) continue
                val i = row * width + column
                if (!inner[i]) continue
                sumDark += dark[i]
                sumRed += red[i]
                count++
            }
        }
        val center = y * width + x
        return if (count == 0) dark[center] to red[center] else sumDark / count to sumRed / count
    }

    /** 1.4826 × 가운데 절대 편차(피부 안쪽만), 0이 되지 않게 아주 작은 값을 더한다 */
    private fun spread(values: FloatArray, inner: BooleanArray): Float {
        var count = 0
        for (flag in inner) if (flag) count++
        val magnitudes = FloatArray(count)
        var next = 0
        for (i in values.indices) if (inner[i]) magnitudes[next++] = abs(values[i])
        magnitudes.sort()
        val median = if (count % 2 == 1) magnitudes[count / 2] else (magnitudes[count / 2 - 1] + magnitudes[count / 2]) / 2f
        return 1.4826f * median + 1e-4f
    }

    /**
     * ([x], [y])의 봉우리가 선 모양인지: 응답의 헤세 행렬(가운데 차분)의 고유값 비가 [ELONGATION]보다 크다(tr²/det ≥ (r + 1)²/r),
     * 또는 한쪽으로는 오목하지 않다(det ≤ 0). 그림 끝 칸은 선으로 보지 않는다(이웃이 없음).
     */
    private fun isLine(values: FloatArray, x: Int, y: Int, width: Int, height: Int): Boolean {
        if (x < 1 || y < 1 || x >= width - 1 || y >= height - 1) return false
        val i = y * width + x
        val dxx = values[i + 1] + values[i - 1] - 2f * values[i]
        val dyy = values[i + width] + values[i - width] - 2f * values[i]
        val dxy = (values[i + width + 1] - values[i + width - 1] - values[i - width + 1] + values[i - width - 1]) / 4f
        val trace = dxx + dyy
        val det = dxx * dyy - dxy * dxy
        if (det <= 0f) return true
        return trace * trace / det >= (ELONGATION + 1f) * (ELONGATION + 1f) / ELONGATION
    }

    /** ([x], [y]) 가운데 반지름 [reach] 원이 모두 피부 안쪽인지(그림 밖은 아님) */
    private fun insideSkin(inner: BooleanArray, x: Int, y: Int, reach: Float, width: Int, height: Int): Boolean {
        val r = reach.toInt() + 1
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > reach * reach) continue
                val column = x + dx
                val row = y + dy
                if (column < 0 || row < 0 || column >= width || row >= height || !inner[row * width + column]) return false
            }
        }
        return true
    }

    /** 이웃 여덟 칸보다 작지 않은지(그림 밖 이웃은 보지 않음) */
    private fun isPeak(values: FloatArray, x: Int, y: Int, width: Int, height: Int): Boolean {
        val value = values[y * width + x]
        for (dy in -1..1) {
            val row = y + dy
            if (row < 0 || row >= height) continue
            for (dx in -1..1) {
                val column = x + dx
                if ((dx == 0 && dy == 0) || column < 0 || column >= width) continue
                if (values[row * width + column] > value) return false
            }
        }
        return true
    }

    /** 넓은 가우스 흐림의 어림: 상자 평균 세 번(분산이 같은 상자, 가장자리 값을 늘임). 큰 σ에서 가우스 그대로보다 훨씬 빠르다. */
    private fun wideBlur(values: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val radius = ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt()
        var out = values
        repeat(3) { out = SkinMaps.box(out, width, height, radius) }
        return out
    }
}

/**
 * 자동으로 지운 잡티 하나의 자리(문서 좌표, 결정 기록 '피부 보정'의 5단계)와 지운 붓의 반지름([radius], 문서 픽셀). 피부 보정 그룹의 기록
 * ([com.kiln.stratum.engine.document.SkinRecord.healed])에 남아 [다시 만들기]가 지금 그림으로 다시 지운다. 자르기·회전·크기 변경·옮기기를 따라간다([mapped]).
 */
data class SkinSpot(val x: Float, val y: Float, val radius: Float) {

    init {
        require(radius > 0f && x.isFinite() && y.isFinite() && radius.isFinite()) { "Broken spot: $this" }
    }

    /** 자리를 [map](문서 좌표 → 새 문서 좌표)으로 옮기고 반지름을 [scale]배 한 것 */
    fun mapped(scale: Float, map: (Float, Float) -> Pair<Float, Float>): SkinSpot {
        val (x, y) = map(x, y)
        return SkinSpot(x, y, radius * scale)
    }
}

/** 잡티 자동 제거 한 번의 결과: 지운 잡티 수와 남긴 점(모반) 수. 화면이 지운 뒤 한 번 알리는 데만 쓰고 저장하지 않는다. */
data class SkinHealReport(val healed: Int, val moles: Int)

/** 지울 잡티를 찾지 못했다(점(모반)만 있거나 고른 피부). [moles]는 남긴 점 수다. 문서는 그대로다. */
class NoSpotsException(val moles: Int) : Exception("No spots to heal")

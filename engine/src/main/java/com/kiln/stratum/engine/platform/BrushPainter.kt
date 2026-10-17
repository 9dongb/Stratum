package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import kotlin.math.roundToInt

/**
 * 브러시 자국을 안드로이드 캔버스에 그린다. 기록(타일, 소프트웨어 그리기)과 끄는 동안의 미리 보기(축소본, GPU)가 같은 그리기를 쓴다.
 *
 * - 자국은 둥글다. 단단하면([HARD] 이상) 경계가 또렷한 원을 그리고, 아니면 가운데부터 부드럽게 옅어지는 모양(알파 마스크)을 늘여 찍는다.
 * - 한 획의 자국들은 따로 그린 뒤(saveLayer) 불투명도로 한 번 얹는다. 그래서 자국이 겹쳐도 획은 불투명도보다 짙어지지 않는다.
 * - 지우개는 같은 자국으로, 얹을 때 아래를 지운다(DST_OUT). 브러시는 그 위에 칠한다(SRC_OVER).
 * - 셰이더 프로그램(RuntimeShader)이나 하드웨어 비트맵을 쓰지 않아 소프트웨어 캔버스에서도 그려진다.
 */
internal object BrushPainter {

    /** 이 단단함부터는 마스크 대신 또렷한 원을 그린다(마스크를 크게 늘이면 경계가 흐려진다) */
    const val HARD = 0.98f

    /** 부드러운 자국 마스크의 지름(픽셀). 이보다 큰 자국은 늘여 찍는데, 부드러운 가장자리라 늘여도 티가 나지 않는다. */
    private const val MASK_SIZE = 256

    /** 단단함(1/100 단위)마다의 마스크 */
    private val masks = HashMap<Int, Bitmap>()

    /**
     * [canvas]에 [dabs]를 [settings]로 그린다([erase]면 지운다). 자국은 문서 좌표이고,
     * 캔버스 좌표는 (문서 - ([left], [top])) × [scale]이다. 캔버스의 그릴 자리는 [width]×[height]이고, 그 밖의 자국은 건너뛴다.
     */
    fun drawStroke(
        canvas: Canvas,
        dabs: List<StrokePoint>,
        settings: BrushSettings,
        erase: Boolean,
        scale: Float,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ) {
        if (dabs.isEmpty()) return
        val stroke = Paint().apply {
            alpha = (settings.opacity * 255).roundToInt()
            blendMode = if (erase) BlendMode.DST_OUT else BlendMode.SRC_OVER
        }
        val saved = canvas.saveLayer(0f, 0f, width, height, stroke)
        val radius = settings.radius * scale
        val dab = Paint().apply {
            // 지우개는 지울 몫(알파)만 쓴다
            color = if (erase) Color.WHITE else settings.opaqueColor
            isAntiAlias = true
            isFilterBitmap = true
        }
        val mask = if (settings.hardness >= HARD) null else maskFor(settings.hardness)
        val target = RectF()
        for (point in dabs) {
            val x = (point.x - left) * scale
            val y = (point.y - top) * scale
            if (x + radius < 0f || y + radius < 0f || x - radius > width || y - radius > height) continue
            if (mask == null) {
                canvas.drawCircle(x, y, radius, dab)
            } else {
                target.set(x - radius, y - radius, x + radius, y + radius)
                canvas.drawBitmap(mask, null, target, dab)
            }
        }
        canvas.restoreToCount(saved)
    }

    /**
     * 단단함 [hardness]의 부드러운 자국 마스크(알파만). 단단함 자리까지는 짙고, 그 밖은 smoothstep 모양으로 옅어진다.
     * 안드로이드 그리기(방사형 그러데이션)로 만들고 픽셀을 하나씩 돌지 않는다.
     */
    @Synchronized
    private fun maskFor(hardness: Float): Bitmap =
        masks.getOrPut((hardness * 100).roundToInt()) {
            val core = (hardness * 100).roundToInt() / 100f
            val steps = 8
            val stops = FloatArray(steps + 2)
            val colors = IntArray(steps + 2)
            stops[0] = 0f
            colors[0] = Color.WHITE
            for (step in 0..steps) {
                val t = step / steps.toFloat()
                stops[step + 1] = core + (1f - core) * t
                val alpha = 1f - t * t * (3f - 2f * t)
                colors[step + 1] = Color.argb((alpha * 255).roundToInt(), 255, 255, 255)
            }
            val drawn = Bitmap.createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ALPHA_8)
            val center = MASK_SIZE / 2f
            Canvas(drawn).drawCircle(center, center, center, Paint().apply {
                isAntiAlias = true
                shader = RadialGradient(center, center, center, colors, stops, Shader.TileMode.CLAMP)
            })
            // 여러 그리기가 함께 쓰므로 바꿀 수 없는 복사본으로 둔다(공개 SDK에는 비트맵을 바꿀 수 없게 하는 함수가 없다)
            drawn.copy(Bitmap.Config.ALPHA_8, false).also { drawn.recycle() }
        }
}

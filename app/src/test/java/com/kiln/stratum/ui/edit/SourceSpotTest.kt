package com.kiln.stratum.ui.edit

import androidx.compose.ui.unit.IntSize
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 복구 브러시·복제 도장을 고를 때 가져올 곳 표시를 두는 자리(사용자 피드백 10차 4: 정하는 버튼 없이 표시가 늘 보임) */
class SourceSpotTest {

    /** 1000×1000 캔버스에 맞춘 1000×1000 문서: 화면 1px = 문서 1px */
    private val area = IntSize(1000, 1000)
    private val document = PixelSize(1000, 1000)

    @Test
    fun withoutASource_goesToTheMiddleOfTheView() {
        assertEquals(500f to 500f, sourceSpot(CanvasView.Fit, area, document, hiddenBottom = 0, current = null))
    }

    @Test
    fun theCoverSheet_movesTheMiddleUp() {
        // 아래 400px를 옵션 칸이 덮으면 보이는 곳(0~600)의 가운데
        assertEquals(500f to 300f, sourceSpot(CanvasView.Fit, area, document, hiddenBottom = 400, current = null))
    }

    @Test
    fun aVisibleSource_staysPut() {
        assertNull(sourceSpot(CanvasView.Fit, area, document, hiddenBottom = 0, current = CloneSource(100f, 900f)))
    }

    @Test
    fun aSourceUnderTheSheetOrOffTheView_movesToTheMiddle() {
        // 옵션 칸 밑(y 900)이라 보이지 않는다
        assertEquals(500f to 300f, sourceSpot(CanvasView.Fit, area, document, hiddenBottom = 400, current = CloneSource(100f, 900f)))
        // 두 배로 확대해 문서 (250~750)만 보이면, 문서 (100, 100)은 화면 밖이라 보이는 가운데(문서 500, 500)로 옮긴다
        val zoomed = CanvasView.Free(scale = 2.0, centerX = 500.0, centerY = 500.0)
        assertEquals(500f to 500f, sourceSpot(zoomed, area, document, hiddenBottom = 0, current = CloneSource(100f, 100f)))
    }

    @Test
    fun theSpotStaysInsideTheDocument() {
        // 납작한 문서(1000×200)를 맞추면 위아래가 비어, 옵션 칸 위 가운데(y 300)가 문서 위쪽 밖이면 문서 위 끝에 둔다
        val wide = PixelSize(1000, 200)
        val (x, y) = checkNotNull(sourceSpot(CanvasView.Fit, area, wide, hiddenBottom = 400, current = null))
        assertEquals(500f, x)
        assertEquals(0f, y)
    }
}

package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey

/**
 * 편집이 레이어 타일 한 장을 다시 만든다(결정 기록 '캔버스 밖 픽셀'). 칠하기·효과·명암·리터칭·AI 채우기·유동화·선택 지우기가 쓴다.
 *
 * 새 타일은 [TileGrid.rebuildSize] 크기(캔버스 안쪽과 옛 타일 중 큰 쪽)이고, 옛 타일을 먼저 값 그대로 베낀 뒤 편집을 캔버스 안쪽으로 잘라 그린다.
 * 그래서 이동 도구가 끝 타일의 캔버스 밖으로 밀어낸 픽셀이 편집에 지워지지 않는다. 소프트웨어로 그려 바꿀 수 없는 비트맵 하나로 돌려준다
 * (편집이 닿지 않은 픽셀은 값이 그대로다). Kotlin으로 픽셀을 하나씩 돌지 않는다.
 */
internal object TileRebuild {

    /**
     * [key] 자리(캔버스 안)의 옛 타일 [old](없으면 투명)에 [edit]를 그린 새 타일. [edit]의 캔버스 (0, 0)은 타일 왼쪽 위이고,
     * 캔버스 안쪽([TileGrid.bounds] 크기) 밖은 잘린다. 힙 타일(테스트)을 그리는 데 만든 비트맵은 [temporary]에 넣는다.
     * 메모리가 모자라면 [OutOfMemoryError]를 던진다.
     */
    fun pixels(key: TileKey, old: Tile?, grid: TileGrid, temporary: MutableList<Bitmap>, edit: (Canvas) -> Unit): Tile {
        val inside = grid.bounds(key)
        val size = grid.rebuildSize(key, old)
        val picture = Picture()
        val canvas = picture.beginRecording(size.width, size.height)
        if (old != null) canvas.drawBitmap(CanvasTileComposer.bitmapOf(old, temporary), 0f, 0f, CanvasTileComposer.CopyPaint)
        canvas.save()
        canvas.clipRect(0f, 0f, inside.width.toFloat(), inside.height.toFloat())
        edit(canvas)
        canvas.restore()
        picture.endRecording()
        // GPU 전용 내용이 없어 소프트웨어로 그리고, 바꿀 수 없는 비트맵 하나로 돌려준다(CanvasTileComposer와 같다)
        return Tile.of(BitmapTilePixels(Bitmap.createBitmap(picture, size.width, size.height, Bitmap.Config.ARGB_8888)))
    }
}

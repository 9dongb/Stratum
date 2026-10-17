package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import com.kiln.stratum.engine.raster.HeapTilePixels
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.engine.transform.TileComposer
import com.kiln.stratum.engine.transform.TilePiece
import com.kiln.stratum.engine.transform.affine

/**
 * 원본 조각들을 안드로이드 복사로 옮겨 타일 한 장을 만든다(회전·뒤집기·자르기).
 *
 * 픽셀 값은 바꾸지 않고 자리만 옮겨야 해서 GPU가 아니라 소프트웨어로 그린다(결정 기록 '회전·뒤집기·자르기는 안드로이드 내부 복사').
 * GPU는 값이 1/255씩 달라질 수 있어, 여러 번 돌리면 원본과 달라진다.
 * 그리기 행렬은 정수 계수([affine])이고 필터를 꺼서, 알파를 미리 곱한 픽셀이 반올림 없이 그대로 옮겨진다.
 * Kotlin으로 픽셀을 하나씩 돌지 않는다.
 */
internal object CanvasTileComposer : TileComposer {

    override fun compose(width: Int, height: Int, pieces: List<TilePiece>, orientation: Orientation): Tile {
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        // 힙에 담긴 타일(테스트·작은 데이터)은 그리는 동안만 비트맵으로 바꿔 쓴다
        val temporary = ArrayList<Bitmap>()
        try {
            for (piece in pieces) {
                val source = bitmapOf(piece.tile, temporary)
                val target = piece.part.targetRect
                val m = piece.part.affine(orientation)
                canvas.save()
                // 조각이 덮는 대상 사각형 밖으로는 그리지 않는다
                canvas.clipRect(target.left, target.top, target.right, target.bottom)
                canvas.concat(Matrix().apply {
                    setValues(floatArrayOf(m[0].toFloat(), m[1].toFloat(), m[2].toFloat(), m[3].toFloat(), m[4].toFloat(), m[5].toFloat(), 0f, 0f, 1f))
                })
                canvas.drawBitmap(source, 0f, 0f, CopyPaint)
                canvas.restore()
            }
            picture.endRecording()
            // GPU 전용 내용이 없으면 소프트웨어로 그리고, 바꿀 수 없는 비트맵 하나로 돌려준다(SDK 소스로 확인)
            val bitmap = Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)
            return Tile.of(BitmapTilePixels(bitmap))
        } finally {
            for (bitmap in temporary) bitmap.recycle()
        }
    }

    /** 타일 픽셀을 그릴 수 있는 비트맵으로. 힙 타일은 임시 비트맵을 만들어 [temporary]에 넣는다. */
    internal fun bitmapOf(tile: Tile, temporary: MutableList<Bitmap>): Bitmap =
        when (val pixels = tile.pixels) {
            is BitmapTilePixels -> pixels.bitmap
            is HeapTilePixels ->
                Bitmap.createBitmap(pixels.toIntArray(), TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
                    .also { temporary += it }
            else -> error("Unknown tile storage: ${pixels::class.java.name}")
        }

    /**
     * 그대로 베끼는 붓. `Paint()`와 `Paint(0)`도 비트맵 필터가 켜져 있어서(SDK 소스로 확인) 직접 끈다.
     * 대상은 처음에 투명하고 조각이 겹치지 않으므로, 원본을 그대로 쓴다(SRC).
     */
    internal val CopyPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
        blendMode = BlendMode.SRC
    }
}

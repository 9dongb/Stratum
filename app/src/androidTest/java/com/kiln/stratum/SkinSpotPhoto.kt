package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 잡티 자동 제거 시험 사진(결정 기록 '피부 보정'의 5단계): 흰 캔버스 1200×900에 피부색 타원(가운데 (400, 450), 가로 반지름 100·세로 120,
 * 가짜 모델([FakeFaceModel]·[FakeSkinModel])이 얼굴·피부로 보는 붉은 색), 뾰루지 둘([PIMPLES], 반지름 3px, 조금 어둡고 더 붉음), 진한 점 하나
 * ([MOLE], 반지름 7px). 피부에는 고운 잡음(채널마다 표준 편차 4)을 늘 같은 씨앗으로 입힌다(진짜 피부처럼 퍼짐이 있어 피부 마스크가 잡티를 빼지 않게).
 */
object SkinSpotPhoto {

    const val WIDTH = 1200
    const val HEIGHT = 900

    /** 피부색 */
    val SKIN = intArrayOf(240, 60, 50)

    /** 뾰루지 둘(덩어리 가운데의 가짜 특징점을 피한 피부) */
    val PIMPLES = listOf(330f to 480f, 470f to 480f)

    /** 진한 점 하나(이마 쪽) */
    val MOLE = 400f to 365f

    private val PIMPLE = intArrayOf(233, 38, 40)
    private val DARK = intArrayOf(150, 40, 30)

    fun pixels(): IntArray {
        val random = Random(11)
        return IntArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH + 0.5f
            val y = i / WIDTH + 0.5f
            val dx = (x - 400f) / 100f
            val dy = (y - 450f) / 120f
            if (dx * dx + dy * dy > 1f) {
                Color.WHITE
            } else {
                val base = when {
                    PIMPLES.any { hypot(x - it.first, y - it.second) <= 3f } -> PIMPLE
                    hypot(x - MOLE.first, y - MOLE.second) <= 7f -> DARK
                    else -> SKIN
                }
                fun channel(value: Int) = (value + 4.0 * random.nextGaussian()).roundToInt().coerceIn(0, 255)
                Color.rgb(channel(base[0]), channel(base[1]), channel(base[2]))
            }
        }
    }

    /** 사진 레이어 하나의 문서 */
    fun document(): Document {
        val pixels = pixels()
        val tiles = TileGrid(WIDTH, HEIGHT).keys().associateWith { key ->
            Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i ->
                val x = (key.column * TILE_SIZE + i % TILE_SIZE).coerceAtMost(WIDTH - 1)
                val y = (key.row * TILE_SIZE + i / TILE_SIZE).coerceAtMost(HEIGHT - 1)
                pixels[y * WIDTH + x]
            })
        }
        return Document.create(WIDTH, HEIGHT, "Photo", Raster.EMPTY.withTiles(tiles))
    }

    /** PNG 파일([file])로 쓴다(사진 열기로 여는 시험) */
    fun writePng(file: File) {
        val bitmap = Bitmap.createBitmap(pixels(), WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

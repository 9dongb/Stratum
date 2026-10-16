package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.raster.Raster

/**
 * 편집 중인 문서 하나(캔버스와 레이어들).
 *
 * 한 번 만들면 바꾸지 않는다. 편집 명령은 바뀐 부분만 새로 만든 새 문서를 돌려주고,
 * 바뀌지 않은 레이어는 같은 객체를 함께 쓴다. 그래서 [layers]에 넘긴 목록은 나중에 고치지 않는다.
 *
 * @property width 캔버스 가로 픽셀 수
 * @property height 캔버스 세로 픽셀 수
 * @property layers 레이어 목록. 첫 번째가 맨 아래, 마지막이 맨 위이며, 항상 1개 이상이다.
 * @property nextLayerId 다음에 만들 레이어의 번호. 지금 있는 모든 레이어 번호보다 크다.
 */
data class Document(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val nextLayerId: Long
) {
    init {
        require(width > 0 && height > 0) { "Canvas size must be positive: ${width}x$height" }
        require(layers.isNotEmpty()) { "Document needs at least one layer" }
        require(layers.distinctBy { it.id }.size == layers.size) { "Layer ids must be unique" }
        require(layers.all { it.id.value in 0L until nextLayerId }) {
            "Layer ids must be within 0 until $nextLayerId"
        }
    }

    /** [id] 레이어의 위치(0이 맨 아래). 없으면 -1. */
    fun indexOfLayer(id: LayerId): Int = layers.indexOfFirst { it.id == id }

    companion object {
        /** 빈 레이어 하나로 새 문서를 만든다. 레이어 번호는 0부터 시작한다. */
        fun create(width: Int, height: Int, firstLayerName: String): Document =
            Document(
                width = width,
                height = height,
                layers = listOf(Layer(LayerId(0), firstLayerName, RasterContent(Raster.EMPTY))),
                nextLayerId = 1
            )
    }
}

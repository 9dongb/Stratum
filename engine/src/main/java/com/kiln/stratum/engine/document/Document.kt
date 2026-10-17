package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.selection.Selection

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
 * @property selection 선택 영역. 없으면 null(모든 곳을 편집한다). 비어 있는 선택 영역은 두지 않는다([withSelection]).
 *   실행 취소 기록에는 남지만 프로젝트 파일에는 저장하지 않는다(결정 기록 '선택 도구').
 */
data class Document(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val nextLayerId: Long,
    val selection: Selection? = null
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

    /**
     * 선택 영역을 [selection]으로 바꾼 문서. 비어 있으면 선택이 없는 것(null)으로 둔다.
     * 바뀌는 것이 없으면(같은 객체, 또는 없던 것을 없앰) 자기 자신을 돌려주어 실행 취소 기록에 남지 않는다.
     */
    fun withSelection(selection: Selection?): Document {
        val next = selection?.takeUnless { it.isEmpty }
        return if (next === this.selection) this else copy(selection = next)
    }

    companion object {
        /**
         * 레이어 하나로 새 문서를 만든다. 레이어 번호는 0부터 시작한다.
         * [firstLayerRaster]를 주지 않으면 빈(투명한) 레이어이고, 불러온 사진은 그 픽셀을 넘긴다.
         */
        fun create(
            width: Int,
            height: Int,
            firstLayerName: String,
            firstLayerRaster: Raster = Raster.EMPTY
        ): Document =
            Document(
                width = width,
                height = height,
                layers = listOf(Layer(LayerId(0), firstLayerName, RasterContent(firstLayerRaster))),
                nextLayerId = 1
            )
    }
}

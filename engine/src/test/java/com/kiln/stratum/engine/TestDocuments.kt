package com.kiln.stratum.engine

import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.Raster

/** 테스트용: 번호 0부터 [count]-1까지의 빈 레이어를 아래부터 차례로 가진 문서. */
fun documentWithLayers(count: Int): Document =
    Document(
        width = 100,
        height = 100,
        layers = List(count) { Layer(LayerId(it.toLong()), "L$it", RasterContent(Raster.EMPTY)) },
        nextLayerId = count.toLong()
    )

/** 테스트용: 아래부터 위까지의 레이어 번호 목록. */
fun Document.layerIds(): List<Long> = layers.map { it.id.value }

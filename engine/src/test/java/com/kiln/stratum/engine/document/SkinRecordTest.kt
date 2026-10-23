package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.SkinColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 피부 도구가 고칠 그룹 찾기와 함께 쓰는 피부 마스크(결정 기록 '피부 보정'의 2c 화면) */
class SkinRecordTest {

    private val mask = Selection.of(mapOf(TileKey(0, 0) to MaskTile.FULL))

    /** 아래부터: 원본 사진 0, 잡티 제거 4, 피부 정돈 3, 색 보정 2, [피부 보정] 그룹 1, 다른 사진 5 */
    private fun layers(smoothMask: Selection? = mask): List<Layer> {
        val record = SkinRecord(LayerId(0), color = LayerId(2), smooth = LayerId(3), spots = LayerId(4), faces = emptyList(), segmented = true)
        return listOf(
            Layer(LayerId(0), "Photo", RasterContent(Raster.EMPTY)),
            Layer(LayerId(4), "Spots", RasterContent(Raster.EMPTY), parent = LayerId(1)),
            Layer(LayerId(3), "Smooth", RasterContent(Raster.EMPTY), opacity = 0f, mask = smoothMask, parent = LayerId(1)),
            Layer(LayerId(2), "Color", AdjustmentContent(skin = SkinColor()), mask = mask, parent = LayerId(1)),
            Layer(LayerId(1), "Skin", GroupContent, skin = record),
            Layer(LayerId(5), "Other", RasterContent(Raster.EMPTY))
        )
    }

    @Test
    fun groupOf_findsTheGroup_fromItself_itsLayers_andItsSource() {
        val layers = layers()

        for (id in listOf(1L, 2L, 3L, 4L, 0L)) assertEquals("from $id", LayerId(1), SkinRecord.groupOf(layers, LayerId(id))?.id)
        // 다른 사진에는 그룹이 없다
        assertNull(SkinRecord.groupOf(layers, LayerId(5)))
        assertNull(SkinRecord.groupOf(layers, LayerId(9)))
    }

    @Test
    fun groupOf_theSourceOfTwoGroups_isFixedByTheTopOne() {
        val base = layers()
        val record = checkNotNull(base[4].skin).copy(color = LayerId(7), smooth = LayerId(7), spots = LayerId(7))
        val layers = base + Layer(LayerId(6), "Skin copy", GroupContent, skin = record)

        assertEquals(LayerId(6), SkinRecord.groupOf(layers, LayerId(0))?.id)
        // 그룹 안의 레이어는 자기 그룹이다
        assertEquals(LayerId(1), SkinRecord.groupOf(layers, LayerId(2))?.id)
    }

    @Test
    fun maskPartner_isTheOtherSkinLayer_onlyWhileTheyShareTheMask() {
        val layers = layers()

        assertEquals(LayerId(3), SkinRecord.maskPartnerOf(layers, layers[3])?.id)
        assertEquals(LayerId(2), SkinRecord.maskPartnerOf(layers, layers[2])?.id)
        // 잡티 제거·원본·다른 사진은 짝이 없다
        assertNull(SkinRecord.maskPartnerOf(layers, layers[1]))
        assertNull(SkinRecord.maskPartnerOf(layers, layers[0]))
        // 피부 정돈의 마스크를 따로 칠했으면(다른 마스크) 짝이 아니다
        val apart = layers(smoothMask = Selection.of(mapOf(TileKey(0, 0) to MaskTile.FULL)))
        assertNull(SkinRecord.maskPartnerOf(apart, apart[3]))
        val noMask = layers(smoothMask = null)
        assertNull(SkinRecord.maskPartnerOf(noMask, noMask[3]))
        assertSame(mask, layers[2].mask)
    }
}

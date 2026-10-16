package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.documentWithLayers
import com.kiln.stratum.engine.layerIds
import com.kiln.stratum.engine.raster.Raster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LayerCommandsTest {

    @Test
    fun addLayer_insertsEmptyLayerWithNextId() {
        val document = documentWithLayers(2)

        val result = AddLayer(name = "New", index = 1).applyTo(document)

        assertEquals(listOf(0L, 2L, 1L), result.layerIds())
        assertEquals(3L, result.nextLayerId)
        val added = result.layers[1]
        assertEquals("New", added.name)
        assertEquals(1f, added.opacity, 0f)
        assertEquals(BlendMode.NORMAL, added.blendMode)
        assertSame(Raster.EMPTY, (added.content as RasterContent).raster)
        // 다른 레이어는 같은 객체를 그대로 쓰고, 원래 문서는 바뀌지 않는다
        assertSame(document.layers[0], result.layers[0])
        assertSame(document.layers[1], result.layers[2])
        assertEquals(listOf(0L, 1L), document.layerIds())
    }

    @Test
    fun addLayer_worksAtBottomAndTop() {
        val document = documentWithLayers(2)

        assertEquals(listOf(2L, 0L, 1L), AddLayer("New", 0).applyTo(document).layerIds())
        assertEquals(listOf(0L, 1L, 2L), AddLayer("New", 2).applyTo(document).layerIds())
    }

    @Test
    fun addLayer_rejectsIndexOutOfRange() {
        val document = documentWithLayers(2)

        assertThrows(IllegalArgumentException::class.java) { AddLayer("New", -1).applyTo(document) }
        assertThrows(IllegalArgumentException::class.java) { AddLayer("New", 3).applyTo(document) }
    }

    @Test
    fun deleteLayer_removesLayerAndNewLayersStillGetNewIds() {
        val document = documentWithLayers(3)

        val result = DeleteLayer(LayerId(1)).applyTo(document)

        assertEquals(listOf(0L, 2L), result.layerIds())
        assertSame(document.layers[0], result.layers[0])
        // 지운 번호(1)를 다시 쓰지 않고 새 번호를 받는다
        assertEquals(listOf(0L, 2L, 3L), AddLayer("New", 2).applyTo(result).layerIds())
    }

    @Test
    fun deleteLayer_keepsLastRemainingLayer() {
        val document = documentWithLayers(1)

        assertSame(document, DeleteLayer(LayerId(0)).applyTo(document))
    }

    @Test
    fun deleteLayer_ignoresUnknownLayer() {
        val document = documentWithLayers(2)

        assertSame(document, DeleteLayer(LayerId(9)).applyTo(document))
    }

    @Test
    fun moveLayer_movesUpAndDown() {
        val document = documentWithLayers(4)

        // toIndex는 옮긴 뒤 목록에서의 위치다
        assertEquals(listOf(1L, 2L, 0L, 3L), MoveLayer(LayerId(0), 2).applyTo(document).layerIds())
        assertEquals(listOf(0L, 3L, 1L, 2L), MoveLayer(LayerId(3), 1).applyTo(document).layerIds())
    }

    @Test
    fun moveLayer_keepsDocumentWhenAlreadyThereOrUnknown() {
        val document = documentWithLayers(3)

        assertSame(document, MoveLayer(LayerId(1), 1).applyTo(document))
        assertSame(document, MoveLayer(LayerId(9), 0).applyTo(document))
    }

    @Test
    fun moveLayer_rejectsIndexOutOfRange() {
        val document = documentWithLayers(3)

        assertThrows(IllegalArgumentException::class.java) { MoveLayer(LayerId(0), 3).applyTo(document) }
        assertThrows(IllegalArgumentException::class.java) { MoveLayer(LayerId(0), -1).applyTo(document) }
    }

    @Test
    fun setLayerOpacity_changesOnlyTargetLayer() {
        val document = documentWithLayers(3)

        val result = SetLayerOpacity(LayerId(1), 0.5f).applyTo(document)

        assertEquals(0.5f, result.layers[1].opacity, 0f)
        assertSame(document.layers[0], result.layers[0])
        assertSame(document.layers[2], result.layers[2])
        assertEquals(1f, document.layers[1].opacity, 0f)
    }

    @Test
    fun setLayerOpacity_keepsDocumentWhenSameValueOrUnknown() {
        val document = documentWithLayers(2)

        assertSame(document, SetLayerOpacity(LayerId(0), 1f).applyTo(document))
        assertSame(document, SetLayerOpacity(LayerId(9), 0.5f).applyTo(document))
    }

    @Test
    fun setLayerOpacity_treatsNegativeZeroAsZero() {
        val document = documentWithLayers(1)
        val transparent = SetLayerOpacity(LayerId(0), 0f).applyTo(document)

        assertSame(transparent, SetLayerOpacity(LayerId(0), -0f).applyTo(transparent))
        assertEquals(transparent, SetLayerOpacity(LayerId(0), -0f).applyTo(document))
    }

    @Test
    fun setLayerOpacity_rejectsInvalidValue() {
        for (opacity in listOf(Float.NaN, -0.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { SetLayerOpacity(LayerId(0), opacity) }
        }
    }

    @Test
    fun setLayerBlendMode_changesOnlyTargetLayer() {
        val document = documentWithLayers(2)

        val result = SetLayerBlendMode(LayerId(1), BlendMode.MULTIPLY).applyTo(document)

        assertEquals(BlendMode.MULTIPLY, result.layers[1].blendMode)
        assertSame(document.layers[0], result.layers[0])
        assertEquals(BlendMode.NORMAL, document.layers[1].blendMode)
    }

    @Test
    fun setLayerBlendMode_keepsDocumentWhenSameModeOrUnknown() {
        val document = documentWithLayers(2)

        assertSame(document, SetLayerBlendMode(LayerId(0), BlendMode.NORMAL).applyTo(document))
        assertSame(document, SetLayerBlendMode(LayerId(9), BlendMode.SCREEN).applyTo(document))
    }
}

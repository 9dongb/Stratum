package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.adjust.Adjustments
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
    fun duplicateLayer_addsTheSameLayerRightAbove_withTheNextId_sharingThePixels() {
        val document = documentWithLayers(3)
        val styled = document.copy(
            layers = document.layers.toMutableList().apply {
                set(1, get(1).copy(opacity = 0.4f, blendMode = BlendMode.SCREEN, adjustments = Adjustments(exposure = 0.5f)))
            }
        )

        val result = DuplicateLayer(LayerId(1), "L1 copy").applyTo(styled)

        assertEquals(listOf(0L, 1L, 3L, 2L), result.layerIds())
        assertEquals(4L, result.nextLayerId)
        val copy = result.layers[2]
        assertEquals("L1 copy", copy.name)
        assertEquals(0.4f, copy.opacity, 0f)
        assertEquals(BlendMode.SCREEN, copy.blendMode)
        assertEquals(Adjustments(exposure = 0.5f), copy.adjustments)
        // 픽셀은 같은 래스터를 함께 쓰고, 다른 레이어는 같은 객체다
        assertSame((styled.layers[1].content as RasterContent).raster, (copy.content as RasterContent).raster)
        assertSame(styled.layers[1], result.layers[1])
        assertSame(styled.layers[2], result.layers[3])
    }

    @Test
    fun duplicateLayer_topLayerGoesToTheTop_andUnknownLayerKeepsTheDocument() {
        val document = documentWithLayers(2)

        assertEquals(listOf(0L, 1L, 2L), DuplicateLayer(LayerId(1), "copy").applyTo(document).layerIds())
        assertSame(document, DuplicateLayer(LayerId(9), "copy").applyTo(document))
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

    @Test
    fun setAdjustments_recordsValuesOnly() {
        val document = documentWithLayers(2)
        val warm = Adjustments(temperature = 0.5f, exposure = 0.3f)

        val adjusted = SetLayerAdjustments(LayerId(1), warm).applyTo(document)

        assertEquals(warm, adjusted.layers[1].adjustments)
        // 픽셀(래스터)은 그대로 함께 쓴다
        assertSame((document.layers[1].content as RasterContent).raster, (adjusted.layers[1].content as RasterContent).raster)
        // 같은 값(-0 포함)이면 문서를 그대로 돌려준다
        assertSame(adjusted, SetLayerAdjustments(LayerId(1), warm).applyTo(adjusted))
        assertSame(document, SetLayerAdjustments(LayerId(0), Adjustments(contrast = -0f)).applyTo(document))
        assertSame(document, SetLayerAdjustments(LayerId(9), warm).applyTo(document))
    }
}

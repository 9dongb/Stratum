package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.raster.Raster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentTest {

    private fun emptyLayer(id: Long, name: String = "Layer $id") =
        Layer(LayerId(id), name, RasterContent(Raster.EMPTY))

    @Test
    fun create_makesOneEmptyLayerWithFirstId() {
        val document = Document.create(width = 100, height = 50, firstLayerName = "Background")

        assertEquals(100, document.width)
        assertEquals(50, document.height)
        assertEquals(1L, document.nextLayerId)
        val layer = document.layers.single()
        assertEquals(LayerId(0), layer.id)
        assertEquals("Background", layer.name)
        assertEquals(1f, layer.opacity, 0f)
        assertEquals(BlendMode.NORMAL, layer.blendMode)
        assertSame(Raster.EMPTY, (layer.content as RasterContent).raster)
    }

    @Test
    fun init_rejectsInvalidSize() {
        assertThrows(IllegalArgumentException::class.java) { Document(0, 10, listOf(emptyLayer(0)), 1) }
        assertThrows(IllegalArgumentException::class.java) { Document(10, -1, listOf(emptyLayer(0)), 1) }
    }

    @Test
    fun init_rejectsDocumentWithoutLayers() {
        assertThrows(IllegalArgumentException::class.java) { Document(10, 10, emptyList(), 1) }
    }

    @Test
    fun init_rejectsDuplicateLayerIds() {
        assertThrows(IllegalArgumentException::class.java) {
            Document(10, 10, listOf(emptyLayer(0, "a"), emptyLayer(0, "b")), 1)
        }
    }

    @Test
    fun init_rejectsLayerIdsOutsideNextLayerId() {
        // 레이어 번호는 모두 nextLayerId보다 작아야 한다
        assertThrows(IllegalArgumentException::class.java) { Document(10, 10, listOf(emptyLayer(1)), 1) }
        assertThrows(IllegalArgumentException::class.java) { Document(10, 10, listOf(emptyLayer(-1)), 1) }
    }

    @Test
    fun layer_rejectsOpacityOutsideRange() {
        for (opacity in listOf(Float.NaN, -0.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) {
                Layer(LayerId(0), "a", RasterContent(Raster.EMPTY), opacity = opacity)
            }
        }
    }

    @Test
    fun indexOfLayer_findsPositionOrMinusOne() {
        val document = Document(10, 10, listOf(emptyLayer(0), emptyLayer(1)), 2)

        assertEquals(0, document.indexOfLayer(LayerId(0)))
        assertEquals(1, document.indexOfLayer(LayerId(1)))
        assertEquals(-1, document.indexOfLayer(LayerId(9)))
    }
}

package com.kiln.stratum.engine.document

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.raster.Raster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositePlanTest {

    private fun layer(id: Long, opacity: Float = 1f, visible: Boolean = true, blendMode: BlendMode = BlendMode.NORMAL, adjustments: Adjustments = Adjustments.NONE) =
        Layer(LayerId(id), "L$id", RasterContent(Raster.EMPTY), opacity = opacity, blendMode = blendMode, adjustments = adjustments, visible = visible)

    private fun document(vararg layers: Layer) = Document(100, 100, layers.toList(), layers.size.toLong() + 10)

    @Test
    fun steps_followTheLayersFromTheBottom_andSkipHiddenAndClearOnes() {
        val shown = layer(0)
        val hidden = layer(1, visible = false)
        val clear = layer(2, opacity = 0f)
        val top = layer(3, opacity = 0.5f)

        val steps = CompositePlan.of(document(shown, hidden, clear, top))

        assertEquals(listOf(0L, 3L), steps.map { it.layer.id.value })
        val drawn = steps.filterIsInstance<CompositeStep.Draw>()
        assertSame((shown.content as RasterContent).raster, drawn[0].raster)
        assertEquals(0.5f, drawn[1].opacity)
        assertEquals(listOf(shown, top), CompositePlan.shownLayers(document(shown, hidden, clear, top)))
    }

    @Test
    fun drawSteps_tellHowTheyAreLaidOn() {
        val plain = CompositePlan.of(document(layer(0))).single() as CompositeStep.Draw
        assertFalse(plain.needsOwnLayer)
        assertTrue(plain.isPlain)

        val faded = CompositePlan.of(document(layer(0, opacity = 0.4f))).single() as CompositeStep.Draw
        assertTrue(faded.needsOwnLayer)
        assertFalse(faded.isPlain)

        val multiplied = CompositePlan.of(document(layer(0, blendMode = BlendMode.MULTIPLY))).single() as CompositeStep.Draw
        assertTrue(multiplied.needsOwnLayer)
        assertTrue(multiplied.isPlain)

        val adjusted = CompositePlan.of(document(layer(0, adjustments = Adjustments(brightness = 0.2f)))).single() as CompositeStep.Draw
        assertFalse(adjusted.needsOwnLayer)
        assertFalse(adjusted.isPlain)
        assertEquals(Adjustments(brightness = 0.2f), adjusted.adjustments)
    }

    @Test
    fun aPartOfTheLayers_canBePlannedAlone() {
        val a = layer(0)
        val b = layer(1, visible = false)
        val c = layer(2)

        assertEquals(listOf(a, c), CompositePlan.of(listOf(a, b, c)).map { it.layer })
    }
}

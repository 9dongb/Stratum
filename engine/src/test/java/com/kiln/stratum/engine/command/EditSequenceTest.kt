package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.history.EditHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSequenceTest {

    private val document = Document.create(100, 80, "Background")

    @Test
    fun appliesInOrder_asOneUndoStep() {
        val history = EditHistory(document)
        val both = EditSequence(listOf(SetLayerOpacity(LayerId(0), 0.4f), SetLayerAdjustments(LayerId(0), Adjustments(contrast = 0.2f))))

        assertTrue(history.execute(both))

        val layer = history.document.layers[0]
        assertEquals(0.4f, layer.opacity, 0f)
        assertEquals(Adjustments(contrast = 0.2f), layer.adjustments)
        history.undo()
        assertSame(document, history.document)
    }

    @Test
    fun nothingChanged_recordsNothing() {
        val history = EditHistory(document)

        assertFalse(history.execute(EditSequence(listOf(SetLayerOpacity(LayerId(0), 1f), SetLayerAdjustments(LayerId(0), Adjustments.NONE)))))
        assertFalse(history.canUndo)
    }
}

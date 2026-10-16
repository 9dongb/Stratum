package com.kiln.stratum.engine.history

import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerBlendMode
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.documentWithLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private val makeHalfTransparent = SetLayerOpacity(LayerId(0), 0.5f)
    private val makeMultiply = SetLayerBlendMode(LayerId(0), BlendMode.MULTIPLY)
    private val makeScreen = SetLayerBlendMode(LayerId(0), BlendMode.SCREEN)

    @Test
    fun newHistory_hasNothingToUndoOrRedo() {
        val initial = documentWithLayers(1)
        val history = EditHistory(initial)

        assertSame(initial, history.document)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undoCommand)
        assertNull(history.redoCommand)
        assertFalse(history.undo())
        assertFalse(history.redo())
        assertSame(initial, history.document)
    }

    @Test
    fun undoAndRedo_returnToExactlyTheSameDocuments() {
        val initial = documentWithLayers(1)
        val history = EditHistory(initial)

        assertTrue(history.execute(makeHalfTransparent))
        val afterFirst = history.document
        assertTrue(history.execute(makeMultiply))
        val afterSecond = history.document

        // 복사본이 아니라 보관해 둔 바로 그 문서로 돌아간다
        assertTrue(history.undo())
        assertSame(afterFirst, history.document)
        assertTrue(history.undo())
        assertSame(initial, history.document)
        assertTrue(history.redo())
        assertSame(afterFirst, history.document)
        assertTrue(history.redo())
        assertSame(afterSecond, history.document)
        assertFalse(history.canRedo)
    }

    @Test
    fun execute_afterUndo_discardsRedo() {
        val history = EditHistory(documentWithLayers(1))
        history.execute(makeHalfTransparent)
        val afterFirst = history.document
        history.execute(makeMultiply)
        history.undo()

        assertTrue(history.execute(makeScreen))

        assertFalse(history.canRedo)
        assertEquals(BlendMode.SCREEN, history.document.layers[0].blendMode)
        history.undo()
        assertSame(afterFirst, history.document)
    }

    @Test
    fun noOpCommand_isNotRecordedAndKeepsRedo() {
        val history = EditHistory(documentWithLayers(1))
        history.execute(makeMultiply)
        history.undo()
        val before = history.document

        // 이미 불투명도가 1이므로 바뀌는 것이 없다
        assertFalse(history.execute(SetLayerOpacity(LayerId(0), 1f)))

        assertSame(before, history.document)
        assertTrue(history.canRedo)
        assertTrue(history.redo())
        assertEquals(BlendMode.MULTIPLY, history.document.layers[0].blendMode)
    }

    @Test
    fun failingCommand_leavesHistoryUnchanged() {
        val history = EditHistory(documentWithLayers(1))
        history.execute(makeHalfTransparent)
        val before = history.document

        assertThrows(IllegalArgumentException::class.java) { history.execute(AddLayer("New", 99)) }

        assertSame(before, history.document)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertSame(makeHalfTransparent, history.undoCommand)
    }

    @Test
    fun maxUndoSteps_dropsOldestSteps() {
        val history = EditHistory(documentWithLayers(1), maxUndoSteps = 2)
        history.execute(makeHalfTransparent)
        val afterFirst = history.document
        history.execute(makeMultiply)
        history.execute(makeScreen)
        val afterThird = history.document

        // 2단계까지만 되돌릴 수 있고, 가장 오래된 단계 전으로는 못 간다
        assertTrue(history.undo())
        assertTrue(history.undo())
        assertSame(afterFirst, history.document)
        assertFalse(history.canUndo)
        assertFalse(history.undo())

        assertTrue(history.redo())
        assertTrue(history.redo())
        assertSame(afterThird, history.document)
    }

    @Test
    fun undoAndRedoCommands_reportRecordedCommands() {
        val history = EditHistory(documentWithLayers(1))
        history.execute(makeHalfTransparent)
        history.execute(makeMultiply)

        assertSame(makeMultiply, history.undoCommand)
        assertNull(history.redoCommand)

        history.undo()

        assertSame(makeHalfTransparent, history.undoCommand)
        assertSame(makeMultiply, history.redoCommand)
    }

    @Test
    fun addLayerAfterUndo_reusesDiscardedLayerId() {
        val history = EditHistory(documentWithLayers(1))
        history.execute(AddLayer("First", 1))
        assertEquals(LayerId(1), history.document.layers[1].id)
        history.undo()

        history.execute(AddLayer("Second", 1))

        // 버려진 레이어의 번호를 다시 쓴다. 그래서 화면 캐시는 번호가 아니라 래스터·타일 객체를 기준으로 삼아야 한다.
        assertEquals(LayerId(1), history.document.layers[1].id)
        assertEquals("Second", history.document.layers[1].name)
    }

    @Test
    fun init_rejectsMaxUndoStepsBelowOne() {
        assertThrows(IllegalArgumentException::class.java) { EditHistory(documentWithLayers(1), maxUndoSteps = 0) }
    }
}

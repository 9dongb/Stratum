package com.kiln.stratum.engine.history

import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.command.SetLayerBlendMode
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.documentWithLayers
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
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

    /** 타일 한 장의 메모리(바이트). */
    private val tileBytes = (TILE_PIXEL_COUNT * 4).toLong()

    /** 가로 타일 4장짜리 캔버스. 처음에는 한 가지 색 타일 [solid] 한 장을 모든 자리가 함께 쓴다. */
    private val solid = Tile.solid(0xFFFFFFFF.toInt())
    private val canvas = Document.create(4 * TILE_SIZE, TILE_SIZE, "Background", Raster.filled(TileGrid(4 * TILE_SIZE, TILE_SIZE), solid))

    private fun newTile(): Tile = Tile.of(IntArray(TILE_PIXEL_COUNT))

    /** 레이어 0의 [key] 자리를 [tile]로 바꾸는 테스트용 명령. 칠하기처럼 그 자리만 새 타일이 된다. */
    private data class PutTile(val key: TileKey, val tile: Tile) : EditCommand {
        override fun applyTo(document: Document): Document {
            val layer = document.layers[0]
            val raster = (layer.content as RasterContent).raster.withTiles(mapOf(key to tile))
            return document.copy(layers = listOf(layer.copy(content = RasterContent(raster))) + document.layers.drop(1))
        }
    }

    @Test
    fun bytesInUse_countsEachTileObjectOnce() {
        val history = EditHistory(canvas)
        // 네 자리가 함께 쓰는 한 장
        assertEquals(tileBytes, history.bytesInUse)

        history.execute(PutTile(TileKey(1, 0), newTile()))
        history.execute(PutTile(TileKey(2, 0), newTile()))

        // 한 가지 색 한 장과 새로 칠한 두 장. 이전 문서들과 함께 쓰는 타일은 한 번만 센다.
        assertEquals(3 * tileBytes, history.bytesInUse)
    }

    @Test
    fun bytesInUse_countsSelectionTiles_andSharedFullTilesAreFree() {
        val history = EditHistory(canvas)
        val before = history.bytesInUse
        val partial = MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { 100 })

        history.execute(Select(Selection.of(mapOf(TileKey(0, 0) to partial, TileKey(1, 0) to MaskTile.FULL))))

        // 픽셀을 가진 알파 타일 한 장(픽셀당 1바이트)만 늘고, 다 고른 타일은 메모리를 쓰지 않는다
        assertEquals(before + TILE_PIXEL_COUNT, history.bytesInUse)
    }

    @Test
    fun bytesInUse_countsGainmapTiles() {
        val gainmapTile = newTile()
        val photo = canvas.copy(layers = listOf(canvas.layers[0].copy(gainmap = HdrGainmap(64, 64, Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to gainmapTile))))))

        val history = EditHistory(photo)

        assertEquals(2 * tileBytes, history.bytesInUse)
    }

    @Test
    fun selectionChange_isAnUndoStep() {
        val history = EditHistory(canvas)
        val selection = Selection.of(mapOf(TileKey(0, 0) to MaskTile.FULL))

        assertTrue(history.execute(Select(selection)))
        // 같은 선택 영역을 다시 고르면 바뀌지 않는다
        assertFalse(history.execute(Select(selection)))
        assertTrue(history.undo())
        assertNull(history.document.selection)
        assertTrue(history.redo())
        assertSame(selection, history.document.selection)
    }

    /** 선택 영역을 바꾸는 명령(테스트용) */
    private class Select(val selection: Selection) : EditCommand {
        override fun applyTo(document: Document): Document = document.withSelection(selection)
    }

    @Test
    fun maxBytes_dropsOldestStepsFirst() {
        val history = EditHistory(canvas, maxBytes = 3 * tileBytes)
        val first = newTile()
        val second = newTile()
        history.execute(PutTile(TileKey(0, 0), first))
        history.execute(PutTile(TileKey(0, 0), second))
        // 같은 자리를 세 번째로 칠하면 타일 네 장(한 가지 색, 첫째, 둘째, 셋째)이 되어 한도를 넘는다
        history.execute(PutTile(TileKey(0, 0), newTile()))

        // 첫째 타일을 쓰는 가장 오래된 두 단계를 버려 세 장으로 줄인다
        assertEquals(3 * tileBytes, history.bytesInUse)
        assertTrue(history.undo())
        assertSame(second, (history.document.layers[0].content as RasterContent).raster.tiles[TileKey(0, 0)])
        assertFalse(history.canUndo)
    }

    @Test
    fun maxBytes_keepsTheCurrentDocumentEvenIfTooLarge() {
        val history = EditHistory(canvas, maxBytes = 0)
        val tile = newTile()

        assertTrue(history.execute(PutTile(TileKey(3, 0), tile)))

        assertSame(tile, (history.document.layers[0].content as RasterContent).raster.tiles[TileKey(3, 0)])
        assertFalse(history.canUndo)
    }

    @Test
    fun commit_recordsResultComputedElsewhere() {
        val history = EditHistory(canvas)
        val command = PutTile(TileKey(0, 0), newTile())
        val before = history.document
        // 무거운 편집은 다른 스레드에서 계산한다
        val after = command.applyTo(before)

        assertTrue(history.commit(command, before, after))

        assertSame(after, history.document)
        assertSame(command, history.undoCommand)
    }

    @Test
    fun commit_ignoresLateResult() {
        val history = EditHistory(canvas)
        val command = PutTile(TileKey(0, 0), newTile())
        val before = history.document
        val after = command.applyTo(before)
        // 계산하는 동안 다른 편집이 먼저 기록됐다
        history.execute(makeHalfTransparent)
        val current = history.document

        assertFalse(history.commit(command, before, after))

        assertSame(current, history.document)
        assertSame(makeHalfTransparent, history.undoCommand)
    }

    @Test
    fun releaseOldSteps_dropsOldestUndoStepsButKeepsRedo() {
        val history = EditHistory(canvas)
        repeat(3) { history.execute(PutTile(TileKey(0, 0), newTile())) }
        history.undo()
        val current = history.document

        val remaining = history.releaseOldSteps(targetBytes = 3 * tileBytes)

        // 한 가지 색, 지금 문서의 타일, 다시 실행할 타일 세 장만 남는다
        assertEquals(3 * tileBytes, remaining)
        assertSame(current, history.document)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }
}

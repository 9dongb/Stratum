package com.kiln.stratum.engine.command

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.LiquifyRecord
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.liquifySource
import com.kiln.stratum.engine.documentWithLayers
import com.kiln.stratum.engine.layerIds
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.engine.document.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull

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
    fun renameLayer_changesOnlyTheName_andKeepsPixels() {
        val document = documentWithLayers(3)

        val result = RenameLayer(LayerId(1), "Sky").applyTo(document)

        assertEquals("Sky", result.layers[1].name)
        // 픽셀(같은 객체)·불투명도·혼합 모드는 그대로이고, 다른 레이어와 원래 문서도 그대로다
        assertSame(document.layers[1].content, result.layers[1].content)
        assertEquals(document.layers[1].copy(name = "Sky"), result.layers[1])
        assertSame(document.layers[0], result.layers[0])
        assertSame(document.layers[2], result.layers[2])
        assertEquals(listOf(0L, 1L, 2L), result.layerIds())
    }

    @Test
    fun renameLayer_keepsDocumentWhenSameNameOrUnknown_andRejectsBlank() {
        val document = documentWithLayers(2)
        val name = document.layers[0].name

        assertSame(document, RenameLayer(LayerId(0), name).applyTo(document))
        assertSame(document, RenameLayer(LayerId(9), "Sky").applyTo(document))
        assertThrows(IllegalArgumentException::class.java) { RenameLayer(LayerId(0), " ") }
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
    fun setLayerVisible_hidesAndShowsOnlyTargetLayer_keepingTheOpacity() {
        val document = SetLayerOpacity(LayerId(1), 0.5f).applyTo(documentWithLayers(3))

        val hidden = SetLayerVisible(LayerId(1), false).applyTo(document)

        assertEquals(false, hidden.layers[1].visible)
        assertEquals(false, hidden.layers[1].isShown)
        assertEquals(0.5f, hidden.layers[1].opacity, 0f)
        assertSame(document.layers[0], hidden.layers[0])
        assertSame(document.layers[2], hidden.layers[2])
        assertEquals(true, document.layers[1].visible)

        val shown = SetLayerVisible(LayerId(1), true).applyTo(hidden)
        assertEquals(document.layers[1], shown.layers[1])
    }

    @Test
    fun setLayerVisible_keepsDocumentWhenSameValueOrUnknown() {
        val document = documentWithLayers(2)

        assertSame(document, SetLayerVisible(LayerId(0), true).applyTo(document))
        assertSame(document, SetLayerVisible(LayerId(9), false).applyTo(document))
    }

    @Test
    fun isShown_needsVisibleAndSomeOpacity() {
        val layer = documentWithLayers(1).layers[0]

        assertEquals(true, layer.isShown)
        assertEquals(false, layer.copy(visible = false).isShown)
        assertEquals(false, layer.copy(opacity = 0f).isShown)
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

    /** [document]의 [index] 레이어를 글자 레이어로 바꾼 문서(픽셀은 비어 있다) */
    private fun withText(document: Document, index: Int, spec: TextSpec): Document =
        document.copy(layers = document.layers.toMutableList().apply { set(index, this[index].copy(text = spec)) })

    private val spec = TextSpec("Hello", x = 10f, y = 20f, style = TextStyle(size = 40f), width = 90f, height = 48f)

    @Test
    fun rasterizeLayer_dropsOnlyTheText_andKeepsOtherLayersAsTheyAre() {
        val document = withText(documentWithLayers(2), 1, spec)

        val result = RasterizeLayer(LayerId(1)).applyTo(document)

        assertNull(result.layers[1].text)
        assertSame((document.layers[1].content as RasterContent).raster, (result.layers[1].content as RasterContent).raster)
        assertSame(document.layers[0], result.layers[0])
        // 글자 레이어가 아니거나 없는 레이어면 그대로다
        assertSame(result, RasterizeLayer(LayerId(1)).applyTo(result))
        assertSame(document, RasterizeLayer(LayerId(9)).applyTo(document))
    }

    @Test
    fun duplicateLayer_keepsTheText_soTheCopyCanBeEditedToo() {
        val document = withText(documentWithLayers(1), 0, spec)

        val result = DuplicateLayer(LayerId(0), "copy").applyTo(document)

        assertEquals(spec, result.layers[1].text)
    }

    @Test
    fun withPixels_dropsTheText_andWithTextKeepsIt() {
        val layer = documentWithLayers(1).layers[0].copy(text = spec)

        // 픽셀을 바꾸면 더 이상 그 글자를 그린 것이 아니다
        assertNull(layer.withPixels(Raster.EMPTY).text)
        val moved = spec.moved(5f, -5f)
        assertEquals(moved, layer.withText(Raster.EMPTY, moved).text)
    }

    /** 유동화 기록이 있는 레이어: 원래 픽셀 [base] 위에 결과 [result]가 있다 */
    private val base = Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(0xFF336699.toInt())))
    private val result = Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(0xFF996633.toInt())))
    private val record = LiquifyRecord(base, null, null, emptyList(), emptyList())

    private fun withLiquify(document: Document, index: Int): Document =
        document.copy(layers = document.layers.toMutableList().apply { set(index, this[index].withLiquify(result, null, record)) })

    @Test
    fun rasterizeLayer_dropsTheLiquifyRecord_andKeepsThePixels() {
        val document = withLiquify(documentWithLayers(2), 1)

        val rasterized = RasterizeLayer(LayerId(1)).applyTo(document)

        assertNull(rasterized.layers[1].liquify)
        assertSame(result, (rasterized.layers[1].content as RasterContent).raster)
        assertSame(document.layers[0], rasterized.layers[0])
    }

    @Test
    fun duplicateLayer_keepsTheLiquifyRecord_soTheCopyCanBeEditedToo() {
        val document = withLiquify(documentWithLayers(1), 0)

        val result = DuplicateLayer(LayerId(0), "copy").applyTo(document)

        assertSame(record, result.layers[1].liquify)
    }

    @Test
    fun changingThePixels_bakesTheLiquify_andTheSourceIsTheOriginalPixels() {
        val layer = withLiquify(documentWithLayers(1), 0).layers[0]

        // 다른 명령이 픽셀을 바꾸면 기록을 지운다(사용자 결정 "묻지 않고 굳히기"), 글자로 바꿔도 같다
        assertNull(layer.withPixels(Raster.EMPTY).liquify)
        assertNull(layer.withText(Raster.EMPTY, spec).liquify)
        // 속성만 바꾸면 기록이 남는다
        assertSame(record, SetLayerOpacity(LayerId(0), 0.4f).applyTo(withLiquify(documentWithLayers(1), 0)).layers[0].liquify)
        // 다시 연 유동화는 원래 픽셀에서 시작한다
        assertSame(base, (layer.liquifySource.content as RasterContent).raster)
        assertNull(layer.liquifySource.liquify)
        val plain = documentWithLayers(1).layers[0]
        assertSame(plain, plain.liquifySource)
    }

    @Test
    fun addAdjustmentLayer_usesTheSelectionAsItsMask_orRevealsAll() {
        val document = documentWithLayers(2)
        val selection = Selection.of(mapOf(TileKey(0, 0) to MaskTile.FULL))

        val added = AddAdjustmentLayer("Adjust", 1).applyTo(document.copy(selection = selection))

        assertEquals(listOf(0L, 2L, 1L), added.layerIds())
        assertEquals(3L, added.nextLayerId)
        val layer = added.layers[1]
        assertEquals("Adjust", layer.name)
        assertSame(AdjustmentContent, layer.content)
        assertEquals(Adjustments.NONE, layer.adjustments)
        assertSame(selection, layer.mask)
        // 선택을 풀어 마스크 모양이 그대로 보인다(레이어 마스크 더하기와 같다)
        assertNull(added.selection)
        // 선택이 없으면 다 보이는 마스크다
        val all = checkNotNull(AddAdjustmentLayer("Adjust", 2).applyTo(document).layers[2].mask)
        assertEquals(setOf(TileKey(0, 0)), all.tiles.keys)
        assertSame(MaskTile.FULL, all.tiles[TileKey(0, 0)])
    }

    /** 아래부터 레이어 0, 1, 2와 그 위 그룹 3(안에 1, 2) */
    private fun grouped(): Document {
        val base = documentWithLayers(3)
        val layers = base.layers.mapIndexed { i, layer -> if (i == 0) layer else layer.copy(parent = LayerId(3)) } +
            Layer(LayerId(3), "G", GroupContent, opacity = 0.5f)
        return base.copy(layers = layers, nextLayerId = 4)
    }

    @Test
    fun groupLayer_wrapsTheLayerInANewGroupRightAboveIt() {
        val document = documentWithLayers(2)

        val grouped = GroupLayer(LayerId(0), "Group").applyTo(document)

        assertEquals(listOf(0L, 2L, 1L), grouped.layerIds())
        assertSame(GroupContent, grouped.layers[1].content)
        assertEquals("Group", grouped.layers[1].name)
        assertEquals(LayerId(2), grouped.layers[0].parent)
        assertNull(grouped.layers[1].parent)
        assertNull(LayerTree.problem(grouped.layers))
        // 그룹 안의 레이어를 묶으면 새 그룹은 그 그룹 안이다
        val nested = GroupLayer(LayerId(1), "Inner").applyTo(grouped())
        assertEquals(LayerId(3), nested.layers[nested.indexOfLayer(LayerId(4))].parent)
        assertNull(LayerTree.problem(nested.layers))
    }

    @Test
    fun ungroupLayer_keepsTheLook_byHandingDownOpacityAndVisibility() {
        val document = grouped()

        val ungrouped = UngroupLayer(LayerId(3)).applyTo(document)

        assertEquals(listOf(0L, 1L, 2L), ungrouped.layerIds())
        assertTrue(ungrouped.layers.all { it.parent == null })
        assertEquals(0.5f, ungrouped.layers[1].opacity, 0f)
        val hidden = UngroupLayer(LayerId(3)).applyTo(document.copy(layers = document.layers.map { if (it.id == LayerId(3)) it.copy(visible = false) else it }))
        assertTrue(hidden.layers.drop(1).none { it.visible })
        // 그룹이 아니면 그대로다
        assertSame(document, UngroupLayer(LayerId(1)).applyTo(document))
    }

    @Test
    fun duplicateAndDelete_takeTheGroupWithItsLayers() {
        val document = grouped()

        val duplicated = DuplicateLayer(LayerId(3), "G copy").applyTo(document)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L), duplicated.layerIds())
        assertEquals(7L, duplicated.nextLayerId)
        assertEquals("G copy", duplicated.layers[6].name)
        assertEquals(listOf(LayerId(6), LayerId(6)), duplicated.layers.subList(4, 6).map { it.parent })
        assertNull(LayerTree.problem(duplicated.layers))

        val deleted = DeleteLayer(LayerId(3)).applyTo(document)
        assertEquals(listOf(0L), deleted.layerIds())
        // 모두 지워지면 지우지 않는다
        val all = document.copy(layers = document.layers.map { if (it.id == LayerId(0)) it.copy(parent = LayerId(3)) else it })
        assertSame(all, DeleteLayer(LayerId(3)).applyTo(all))
    }

    @Test
    fun moveLayer_entersAndLeavesGroups_andKeepsGroupsWhole() {
        val document = grouped()

        // 레이어 0을 그룹 안 맨 위로
        val entered = MoveLayer(LayerId(0), 2, parent = LayerId(3)).applyTo(document)
        assertEquals(listOf(1L, 2L, 0L, 3L), entered.layerIds())
        assertEquals(LayerId(3), entered.layers[2].parent)
        // 그룹 안의 레이어를 그룹 밖 맨 위로
        val left = MoveLayer(LayerId(1), 3, parent = null).applyTo(document)
        assertEquals(listOf(0L, 2L, 3L, 1L), left.layerIds())
        assertNull(left.layers[3].parent)
        // 그룹은 자손과 함께 옮긴다(맨 아래로)
        val group = MoveLayer(LayerId(3), 2).applyTo(document)
        assertEquals(listOf(1L, 2L, 3L, 0L), group.layerIds())
        assertNull(LayerTree.problem(group.layers))
        // 그룹 사이를 가르는 자리는 옮기지 않는다
        assertSame(document, MoveLayer(LayerId(0), 1, parent = null).applyTo(document))
    }

    @Test
    fun addLayer_goesIntoTheGivenGroup() {
        val added = AddLayer("New", 3, parent = LayerId(3)).applyTo(grouped())

        assertEquals(listOf(0L, 1L, 2L, 4L, 3L), added.layerIds())
        assertEquals(LayerId(3), added.layers[3].parent)
        assertNull(LayerTree.problem(added.layers))
    }
}

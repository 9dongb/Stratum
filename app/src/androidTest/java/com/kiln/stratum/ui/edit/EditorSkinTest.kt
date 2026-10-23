package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.FakeFaceModel
import com.kiln.stratum.R
import com.kiln.stratum.FakeSkinModel
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.SetLayerVisible
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.SkinMasker
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.skin.SkinUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 피부 도구의 흐름(ViewModel, 결정 기록 '피부 보정'의 2c 화면): 고르면 그룹 만들기, 이미 있으면 그대로, 알림, 마스크 보기와 마스크 칠하기.
 * 빨간 덩어리를 얼굴·피부로 보는 가짜 모델([FakeFaceModel]·[FakeSkinModel])로 진짜 찾는 순서·마스크 만들기를 거친다.
 */
@RunWith(AndroidJUnit4::class)
class EditorSkinTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)
    private val skinModel = FakeSkinModel()

    init {
        viewModel.faceFinder = FaceFinder(FakeFaceModel())
        viewModel.skinMasker = SkinMasker(skinModel)
    }

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(30_000) { viewModel.messages.first() } }

    /** 흰 캔버스 1200×900. [withFace]면 빨간 덩어리(지름 200px 붓으로 위아래 40px 그음, 가운데 (400, 450))를 칠한다. */
    private fun openPhoto(withFace: Boolean = true): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val opened = awaitReady { it.documentSize == SIZE }
        if (!withFace) return opened
        val dabs = StrokeDabs.place(listOf(StrokePoint(400f, 430f), StrokePoint(400f, 470f)), 1f)
        onMain { viewModel.edit(PaintStroke(LayerId(0), dabs, BrushSettings(size = 200f, hardness = 1f, color = Color.RED), false)) }
        return awaitReady { it.revision > opened.revision }
    }

    /** 피부 도구를 골라 그룹을 만든 상태 */
    private fun created(): EditorState.Ready {
        val before = openPhoto()
        onMain { viewModel.skinActions.picked() }
        return awaitReady { it.revision > before.revision && it.layers.size == 5 }
    }

    @Test
    fun pickingTheTool_makesTheGroupAboveThePhoto_andSelectsIt() {
        val ready = created()

        // 아래부터: 원본, 잡티 제거, 피부 정돈, 색 보정, 그룹(번호는 그룹부터 차례로)
        assertEquals(listOf(0L, 4L, 3L, 2L, 1L), ready.layers.map { it.id.value })
        assertEquals(LayerId(1), ready.activeLayer)
        val group = checkNotNull(ready.layers.last().skin)
        assertEquals(SkinSummary(LayerId(0), LayerId(2), LayerId(3), LayerId(4), faceCount = 1, segmented = true, texture = 0.5f), group)
        // 이름은 앱 문구(잡티 제거, 피부 정돈, 색 보정, 피부 보정)
        val names = listOf(R.string.skin_spots_name, R.string.skin_smooth_name, R.string.skin_color_name, R.string.skin_group_name).map(context::getString)
        assertEquals(names, ready.layers.drop(1).map { it.name })
        assertTrue(ready.layers[3].adjustment && ready.layers[3].hasMask)
        assertTrue(ready.layers[2].hasMask)
        // 고칠 그룹은 그룹 자신·안의 레이어·원본에서 같다
        for (id in listOf(0L, 1L, 2L, 3L, 4L)) assertEquals(LayerId(1), skinGroupFor(ready.layers, LayerId(id))?.id)
    }

    @Test
    fun pickingAgain_orFromTheSource_doesNotMakeAnother_andUndoTakesItAway() {
        val ready = created()

        // 원본을 고르고 다시 골라도 새로 만들지 않는다
        onMain {
            viewModel.selectLayer(LayerId(0))
            viewModel.skinActions.picked()
            viewModel.skinActions.create()
        }
        val again = awaitReady { it.activeLayer == LayerId(0) }
        assertEquals(ready.revision, again.revision)
        assertEquals(5, again.layers.size)

        // 실행 취소 한 단계로 그룹이 없어진다
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > ready.revision }
        assertEquals(listOf(0L), undone.layers.map { it.id.value })
        assertNull(skinGroupFor(undone.layers, undone.activeLayer))
    }

    @Test
    fun noFace_isTold_andNothingIsMade() {
        val ready = openPhoto(withFace = false)

        onMain { viewModel.skinActions.picked() }

        assertEquals(EditorMessage.SkinNoFace, awaitMessage())
        val after = awaitReady { true }
        assertEquals(ready.revision, after.revision)
        assertEquals(1, after.layers.size)
    }

    @Test
    fun anAdjustmentLayer_orAHiddenPhoto_isTold() {
        val ready = openPhoto()

        onMain { viewModel.addAdjustmentLayer() }
        awaitReady { it.layers.size == 2 && it.active?.adjustment == true }
        onMain { viewModel.skinActions.picked() }
        assertEquals(EditorMessage.SkinPickPhoto, awaitMessage())

        onMain {
            viewModel.selectLayer(LayerId(0))
            viewModel.edit(SetLayerVisible(LayerId(0), false))
        }
        awaitReady { it.revision > ready.revision + 1 && it.layers[0].visible.not() }
        onMain { viewModel.skinActions.picked() }
        assertEquals(EditorMessage.HiddenLayer, awaitMessage())
        assertEquals(2, awaitReady { true }.layers.size)
    }

    @Test
    fun withoutTheSkinModel_theOutlineIsUsed_andItIsTold() {
        skinModel.failure = SkinUnavailableException("No skin model here")
        val before = openPhoto()

        onMain { viewModel.skinActions.picked() }

        assertEquals(EditorMessage.SkinOvalOnly, awaitMessage())
        val ready = awaitReady { it.revision > before.revision }
        assertEquals(false, ready.layers.last().skin?.segmented)
    }

    @Test
    fun maskView_selectsTheColorLayer_showsTheRedVeil_andTogglesOff() {
        created()

        onMain { viewModel.skinActions.toggleMask(LayerId(2)) }
        val shown = awaitReady { it.maskOverlay != null }
        assertEquals(LayerId(2), shown.activeLayer)

        onMain { viewModel.skinActions.toggleMask(LayerId(2)) }
        val hidden = awaitReady { it.maskOverlay == null }
        assertEquals(LayerId(2), hidden.activeLayer)
    }

    @Test
    fun paintingTheSkinMask_changesBothSkinLayers_whichKeepSharingIt() {
        val ready = created()
        onMain { viewModel.skinActions.toggleMask(LayerId(2)) }
        awaitReady { it.maskOverlay != null }

        // 덩어리 안을 지우개로 칠하면(색 보정 레이어는 늘 마스크를 칠함) 피부 정돈의 마스크도 같이 가려진다
        onMain {
            viewModel.setEraser(BrushSettings(size = 60f, hardness = 1f, opacity = 1f))
            viewModel.beginStroke(StrokePoint(330f, 470f), erase = true)
            viewModel.endStroke()
        }
        awaitReady { it.revision > ready.revision }

        val document = checkNotNull(viewModel.recordedDocument)
        val color = checkNotNull(document.layers[document.indexOfLayer(LayerId(2))].mask)
        val smooth = document.layers[document.indexOfLayer(LayerId(3))].mask
        assertSame(color, smooth)
        assertEquals(0, color.alphaAt(330, 470))
        // 칠하지 않은 피부는 그대로다
        assertTrue("skin ${color.alphaAt(470, 470)}", color.alphaAt(470, 470) > 200)
        assertNotNull(document.layers[document.indexOfLayer(LayerId(1))].skin)
    }

    private companion object {
        val SIZE = PixelSize(1200, 900)
    }
}

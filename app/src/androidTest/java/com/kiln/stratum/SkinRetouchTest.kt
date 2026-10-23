package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.CreateSkinRetouch
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.platform.SkinLayerNames
import com.kiln.stratum.engine.platform.SkinMasker
import com.kiln.stratum.engine.skin.NoFaceException
import com.kiln.stratum.engine.skin.SkinUnavailableException
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 피부 보정 만들기(결정 기록 '피부 보정'의 2c): [피부 보정] 그룹의 구조·마스크·기록과 저장. 빨간 덩어리를 얼굴·피부로 보는 가짜 모델
 * ([FakeFaceModel]·[FakeSkinModel])로 진짜 찾는 순서·그림 읽기·마스크 만들기를 거친다.
 */
@RunWith(AndroidJUnit4::class)
class SkinRetouchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val skinModel = FakeSkinModel()
    private val finder = FaceFinder(FakeFaceModel())
    private val masker = SkinMasker(skinModel)
    private val names = SkinLayerNames("Skin retouch", "Skin color", "Smoothing", "Blemishes")
    private val directory = File(context.cacheDir, "skin-retouch-test")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** 흰 캔버스 1200×900에 빨간 덩어리(지름 200px 붓으로 위아래 40px 그음: 가로 200, 세로 240, 가운데 (400, 450)) */
    private fun photo(withFace: Boolean = true): Document {
        val blank = checkNotNull(BlankCanvas.create(1200, 900, CanvasFill.Solid(Color.WHITE), "Photo"))
        if (!withFace) return blank
        val dabs = StrokeDabs.place(listOf(StrokePoint(400f, 430f), StrokePoint(400f, 470f)), 1f)
        return PaintStroke(LayerId(0), dabs, BrushSettings(size = 200f, hardness = 1f, color = Color.RED), false).applyTo(blank)
    }

    @Test
    fun create_putsTheGroupRightAboveThePhoto_withSharedMasksAndARecord() {
        val document = photo()

        val result = CreateSkinRetouch(LayerId(0), names, finder, masker).applyTo(document)

        // 아래부터: 원본, 잡티 제거, 피부 정돈, 색 보정, 그룹(번호는 그룹부터 차례로)
        assertEquals(listOf(0L, 4L, 3L, 2L, 1L), result.layers.map { it.id.value })
        assertEquals(5L, result.nextLayerId)
        assertNull(LayerTree.problem(result.layers))
        assertSame(document.layers[0], result.layers[0])
        val (spots, smooth, color, group) = result.layers.drop(1)
        assertSame(GroupContent, group.content)
        assertEquals("Skin retouch", group.name)
        assertTrue(listOf(spots, smooth, color).all { it.parent == group.id })
        // 색 보정은 슬라이더가 0이고, 얼굴마다 그 피부의 색 통계를 지닌다(빨간 덩어리라 색상 약 29°)
        val skinColor = checkNotNull((color.content as AdjustmentContent).skin)
        assertTrue(skinColor.isIdentity)
        assertEquals(1, skinColor.faces.size)
        assertEquals(29.0, Math.toDegrees(skinColor.faces[0].tone.hue.toDouble()), 3.0)
        assertEquals(400f, skinColor.faces[0].area.centerX, 10f)
        assertEquals(0f, smooth.opacity, 0f)
        assertTrue(spots.content is RasterContent && smooth.content is RasterContent)
        assertNull(spots.mask)
        // 두 마스크는 같은 타일을 함께 쓴다
        val mask = checkNotNull(color.mask)
        assertSame(mask, smooth.mask)
        // 덩어리 안(가운데 작은 타원에 모인 가짜 특징점을 피한 자리)은 피부, 흰 바탕은 아님
        assertTrue("skin ${mask.alphaAt(330, 470)}", mask.alphaAt(330, 470) > 200)
        assertEquals(0, mask.alphaAt(900, 450))
        assertEquals(0, mask.alphaAt(400, 150))
        // 기록: 원본·세 레이어·얼굴 하나·분할함
        val record = checkNotNull(group.skin)
        assertEquals(LayerId(0), record.source)
        assertEquals(listOf(color.id, smooth.id, spots.id), listOf(record.color, record.smooth, record.spots))
        assertEquals(1, record.faces.size)
        assertTrue(record.segmented)
        assertEquals(0.5f, record.texture, 0f)
        assertTrue(skinModel.calls > 0)
    }

    @Test
    fun noFace_throws_andTheDocumentStaysAsItWas() {
        val document = photo(withFace = false)

        assertThrows(NoFaceException::class.java) { CreateSkinRetouch(LayerId(0), names, finder, masker).applyTo(document) }
    }

    @Test
    fun withoutTheSkinModel_theOvalIsUsed_andTheRecordSaysSo() {
        skinModel.failure = SkinUnavailableException("No skin model here")

        val result = CreateSkinRetouch(LayerId(0), names, finder, masker).applyTo(photo())

        val group = result.layers.last()
        assertEquals(false, group.skin?.segmented)
        val mask = checkNotNull(result.layers[3].mask)
        assertTrue("skin ${mask.alphaAt(330, 470)}", mask.alphaAt(330, 470) > 200)
        assertEquals(0, mask.alphaAt(900, 450))
    }

    @Test
    fun theGroup_itsRecord_andTheSkinColor_surviveSaving() {
        val result = CreateSkinRetouch(LayerId(0), names, finder, masker).applyTo(photo())

        ProjectFolder(directory).save(result, createdMillis = 1, modifiedMillis = 2)
        val loaded = ProjectFolder(directory).load().document

        assertEquals(result.layers.map { it.id }, loaded.layers.map { it.id })
        assertEquals(result.layers.map { it.parent }, loaded.layers.map { it.parent })
        // 만들 때의 붉은 사진 표시는 저장하지 않는다(만든 뒤 한 번 알리는 데만 씀)
        assertEquals(result.layers.last().skin?.copy(redCast = false), loaded.layers.last().skin)
        assertEquals(result.layers[3].content, loaded.layers[3].content)
        assertEquals(0f, loaded.layers[2].opacity, 0f)
        val mask = checkNotNull(loaded.layers[3].mask)
        assertEquals(result.layers[3].mask!!.alphaAt(330, 470), mask.alphaAt(330, 470))
    }
}

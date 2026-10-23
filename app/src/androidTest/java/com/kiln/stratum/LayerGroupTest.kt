package com.kiln.stratum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.command.MoveLayer
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.MergeDown
import com.kiln.stratum.engine.platform.MergeVisible
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 레이어 그룹(통과, 결정 기록 '피부 보정'의 1g)을 에뮬레이터에서 확인한다: 그룹 안의 조정 레이어가 그룹 밖 아래에도 걸리는지(통과),
 * 그룹 불투명도·숨김, 레이어 축소본·내보내기, 병합, 그룹 옮기기, 저장.
 */
@RunWith(AndroidJUnit4::class)
class LayerGroupTest {

    private val width = 600
    private val height = 300
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "group-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun raster(color: (x: Int, y: Int) -> Int): Raster {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Layer")
        val document = (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
        return (document.layers[0].content as RasterContent).raster
    }

    private val orange = Color.rgb(220, 120, 40)
    private val photo: Raster by lazy { raster { _, _ -> orange } }

    /** 왼쪽 반만 파랑, 오른쪽 반은 투명 */
    private val blueLeft: Raster by lazy { raster { x, _ -> if (x < width / 2) Color.BLUE else Color.TRANSPARENT } }

    private val gray = Adjustments(saturation = -1f)

    private fun pixels(id: Long, raster: Raster, parent: Long? = null) = Layer(LayerId(id), "L$id", RasterContent(raster), parent = parent?.let(::LayerId))

    private fun group(id: Long, opacity: Float = 1f, visible: Boolean = true, parent: Long? = null) =
        Layer(LayerId(id), "G$id", GroupContent, opacity = opacity, visible = visible, parent = parent?.let(::LayerId))

    private fun document(vararg layers: Layer) = Document(width, height, layers.toList(), nextLayerId = layers.maxOf { it.id.value } + 1)

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(software.width * software.height).also { software.getPixels(it, 0, software.width, 0, 0, software.width, software.height) }
    }

    private fun look(document: Document): IntArray = pixelsOf(checkNotNull(PreviewRenderer.render(document, level = 0)))

    private fun at(pixels: IntArray, x: Int, y: Int) = pixels[y * width + x]

    private fun near(a: Int, b: Int, tolerance: Int = 3): Boolean =
        abs(Color.alpha(a) - Color.alpha(b)) <= tolerance && abs(Color.red(a) - Color.red(b)) <= tolerance &&
            abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun isGray(color: Int): Boolean = abs(Color.red(color) - Color.green(color)) <= 3 && abs(Color.green(color) - Color.blue(color)) <= 3

    private fun assertSameLook(a: IntArray, b: IntArray) {
        for (i in a.indices) assertTrue("pixel (${i % width}, ${i / width}): ${Integer.toHexString(a[i])} vs ${Integer.toHexString(b[i])}", near(a[i], b[i]))
    }

    /** 사진(맨 아래) 위에 그룹 3(안에 파랑 왼쪽 1과 채도를 뺀 조정 레이어 2) */
    private fun skinLike(groupOpacity: Float = 1f, groupVisible: Boolean = true) = document(
        pixels(0, photo),
        pixels(1, blueLeft, parent = 3),
        Layer(LayerId(2), "A", AdjustmentContent, adjustments = gray, parent = LayerId(3)),
        group(3, groupOpacity, groupVisible)
    )

    @Test
    fun passThroughGroup_letsItsAdjustmentReachLayersBelowTheGroup() {
        val pixels = look(skinLike())

        // 그룹 밖 아래 사진에도 조정이 걸려 회색이다(통과 그룹)
        assertTrue(isGray(at(pixels, 450, 100)))
        assertTrue(isGray(at(pixels, 100, 100)))
        // 숨긴 그룹은 안의 레이어가 모두 빠진다
        assertEquals(orange, at(look(skinLike(groupVisible = false)), 450, 100))
    }

    @Test
    fun groupOpacity_multipliesTheLayersInside() {
        val half = look(skinLike(groupOpacity = 0.5f))
        val direct = look(
            document(
                pixels(0, photo),
                pixels(1, blueLeft).copy(opacity = 0.5f),
                Layer(LayerId(2), "A", AdjustmentContent, opacity = 0.5f, adjustments = gray)
            )
        )

        assertSameLook(direct, half)
        assertTrue(!isGray(at(half, 450, 100)))
    }

    @Test
    fun layerCache_andExport_matchTheRenderer() {
        val document = skinLike(groupOpacity = 0.7f)
        val tiles = look(document)

        assertSameLook(tiles, pixelsOf(checkNotNull(LayerPreviewCache().render(document, level = 0))))
        val png = ByteArrayOutputStream().also { out ->
            ImageExporter.render(document, ExportSettings(ExportFormat.PNG, 100, PixelSize(width, height))).use { it.writeTo(out) }
        }.toByteArray()
        assertSameLook(tiles, pixelsOf(checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))))
    }

    @Test
    fun merges_keepTheLook_andRespectGroups() {
        val before = skinLike(groupOpacity = 0.6f)

        // 보이는 레이어 병합: 보이던 그대로, 결과는 그룹 밖이고 다 합친 그룹은 지운다
        val merged = MergeVisible.applyTo(before)
        assertEquals(listOf(LayerId(0)), merged.layers.map { it.id })
        assertNull(merged.layers.single().parent)
        assertSameLook(look(before), look(merged))
        // 숨긴 레이어가 남은 그룹은 남는다
        val withHidden = document(pixels(0, photo), pixels(1, blueLeft, parent = 3), pixels(2, photo, parent = 3).copy(visible = false), group(3))
        val kept = MergeVisible.applyTo(withHidden)
        assertEquals(listOf(LayerId(0), LayerId(2), LayerId(3)), kept.layers.map { it.id })
        assertNull(LayerTree.problem(kept.layers))
        // 아래와 병합은 같은 그룹 안의 이웃끼리만(그룹 경계·그룹 줄은 넘지 않음)
        val two = document(pixels(0, photo), pixels(1, photo, parent = 3), pixels(2, blueLeft, parent = 3), group(3))
        val down = MergeDown(LayerId(2)).applyTo(two)
        assertEquals(listOf(LayerId(0), LayerId(1), LayerId(3)), down.layers.map { it.id })
        assertEquals(LayerId(3), down.layers[1].parent)
        assertSame(two, MergeDown(LayerId(1)).applyTo(two))
        assertSame(two, MergeDown(LayerId(3)).applyTo(two))
    }

    @Test
    fun movingAGroup_movesEveryLayerInside_inOneStep() {
        val document = document(pixels(0, photo), pixels(1, blueLeft, parent = 2), group(2))

        val moved = TranslateLayer(LayerId(2), 100, 0).applyTo(document)

        val pixels = look(moved)
        assertEquals(Color.BLUE, at(pixels, 380, 100))
        assertEquals(orange, at(pixels, 50, 100))
        // 사진(그룹 밖)은 그대로다
        assertSame(document.layers[0], moved.layers[0])
        // 그룹 줄 옮기기는 자손과 함께(그룹 줄이 맨 위 자리 3으로)
        val reordered = MoveLayer(LayerId(2), 3).applyTo(document(pixels(0, photo), pixels(1, blueLeft, parent = 2), group(2), pixels(3, photo)))
        assertEquals(listOf(LayerId(0), LayerId(3), LayerId(1), LayerId(2)), reordered.layers.map { it.id })
    }

    @Test
    fun projectFolder_savesAndLoadsGroups() {
        val document = skinLike(groupOpacity = 0.6f, groupVisible = false)

        ProjectFolder(directory).save(document, createdMillis = 1, modifiedMillis = 2)
        val loaded = ProjectFolder(directory).load().document

        assertEquals(document.layers.map { it.id }, loaded.layers.map { it.id })
        assertEquals(document.layers.map { it.parent }, loaded.layers.map { it.parent })
        assertSame(GroupContent, loaded.layers[3].content)
        assertEquals(0.6f, loaded.layers[3].opacity, 0f)
        assertEquals(false, loaded.layers[3].visible)
    }
}

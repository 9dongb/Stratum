package com.kiln.stratum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerBlendMode
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.command.SetLayerVisible
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.command.RasterizeLayer
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.liquifySource
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.FaceSlider
import com.kiln.stratum.engine.face.FaceValues
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.LiquifyLayer
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.ProjectFolder
import com.kiln.stratum.engine.project.ProjectHeader
import com.kiln.stratum.engine.project.ProjectManifestCodec
import com.kiln.stratum.engine.project.RasterEntry
import com.kiln.stratum.engine.project.TileRef
import com.kiln.stratum.engine.project.UnreadableProjectException
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 프로젝트 폴더에 문서를 저장하고 불러오는 것을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class ProjectFolderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "project-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** [color]로 칠한 사진을 PNG로 만들어 앱과 같은 방법으로 불러온다. */
    private fun photo(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Document {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private fun Document.raster(index: Int = 0): Raster = (layers[index].content as RasterContent).raster

    private fun tileFiles(): Set<String> = File(directory, "tiles").list()?.toSet() ?: emptySet()

    /** 두 문서의 크기·레이어 수·타일 자리와 모든 픽셀이 같은지 */
    private fun assertSamePixels(expected: Document, actual: Document) {
        assertEquals(PixelSize(expected.width, expected.height), PixelSize(actual.width, actual.height))
        assertEquals(expected.layers.size, actual.layers.size)
        for (index in expected.layers.indices) {
            val before = expected.raster(index).tiles
            val after = actual.raster(index).tiles
            assertEquals(before.keys, after.keys)
            for ((key, tile) in before) {
                val loaded = checkNotNull(after[key])
                for (y in 0 until TILE_SIZE) {
                    for (x in 0 until TILE_SIZE) {
                        if (tile.pixelAt(x, y) != loaded.pixelAt(x, y)) {
                            fail("layer $index tile $key ($x, $y): ${Integer.toHexString(loaded.pixelAt(x, y))}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun photo_roundTripsExactly() {
        // 일곱 자리마다 반투명. 601×301은 끝 타일이 캔버스 밖으로 걸친다.
        val document = photo(601, 301) { x, y ->
            val alpha = if ((x + y) % 7 == 0) 77 else 255
            Color.argb(alpha, (x * 5) and 0xFF, (y * 3) and 0xFF, (x + y) and 0xFF)
        }

        ProjectFolder(directory).save(document, createdMillis = 1_000, modifiedMillis = 2_000)
        val loaded = ProjectFolder(directory).load()

        assertEquals(1_000, loaded.createdMillis)
        assertEquals(document.layers.map { it.name }, loaded.document.layers.map { it.name })
        assertSamePixels(document, loaded.document)
    }

    @Test
    fun solidCanvas_savesNoTileFiles_andSharesOneTilePerShapeAfterLoading() {
        val document = checkNotNull(BlankCanvas.create(3000, 2000, CanvasFill.Solid(Color.WHITE), "Background"))

        ProjectFolder(directory).save(document, 1, 1)
        val loaded = ProjectFolder(directory).load().document

        assertTrue(tileFiles().isEmpty())
        val grid = TileGrid(3000, 2000)
        val tiles = loaded.raster().tiles
        assertEquals(grid.keys().size, tiles.size)
        // 끝 자리는 캔버스 안쪽 크기로 되살린다(결정 기록 '캔버스 밖 픽셀'). 크기마다 한 장이다(꽉 찬·오른쪽 끝·아래 끝·구석).
        for ((key, tile) in tiles) {
            assertEquals(Color.WHITE, tile.solidColor)
            assertEquals(grid.solidShape(key), PixelSize(tile.width, tile.height))
        }
        assertEquals(4, tiles.values.toSet().size)
    }

    @Test
    fun nextSave_writesOnlyNewTiles_andDropsUnusedOnes() {
        // 700×300은 3×2 = 6타일이다
        val document = photo(700, 300) { x, y -> Color.rgb(x and 0xFF, y and 0xFF, 128) }
        val folder = ProjectFolder(directory)
        folder.save(document, 1, 1)
        val first = tileFiles()
        assertEquals(6, first.size)

        // 같은 문서는 새로 쓰지 않는다
        folder.save(document, 1, 2)
        assertEquals(first, tileFiles())

        // 왼쪽 위에 맞춘 자르기는 두 타일을 그대로 쓰므로 그 파일만 남는다
        val cropped = CropImage(PixelRect(0, 0, 512, 256), PixelSize(700, 300)).applyTo(document)
        folder.save(cropped, 1, 3)
        val second = tileFiles()
        assertEquals(2, second.size)
        assertTrue(first.containsAll(second))
        assertSamePixels(cropped, ProjectFolder(directory).load().document)
    }

    @Test
    fun layers_keepTheirProperties() {
        val base = photo(300, 200) { _, _ -> Color.BLUE }
        val withLayer = AddLayer("위 레이어", 1).applyTo(base)
        val document = SetLayerVisible(LayerId(0), false).applyTo(
            SetLayerBlendMode(LayerId(1), BlendMode.MULTIPLY).applyTo(SetLayerOpacity(LayerId(1), 0.4f).applyTo(withLayer))
        )

        ProjectFolder(directory).save(document, 1, 1)
        val loaded = ProjectFolder(directory).load().document

        // 숨긴 레이어(눈을 끈 아래 레이어)도 숨긴 채로 열린다
        assertEquals(
            document.layers.map { listOf(it.id, it.name, it.opacity, it.blendMode, it.visible) },
            loaded.layers.map { listOf(it.id, it.name, it.opacity, it.blendMode, it.visible) }
        )
        assertEquals(listOf(false, true), loaded.layers.map { it.visible })
        assertEquals(document.nextLayerId, loaded.nextLayerId)
        assertTrue(loaded.raster(1).tiles.isEmpty())
        assertSamePixels(document, loaded)
    }

    @Test
    fun brokenOrIncompleteProjects_cannotBeOpened() {
        directory.mkdirs()
        File(directory, ProjectFolder.MANIFEST).writeBytes(byteArrayOf(1, 2, 3, 4))
        assertThrows(UnreadableProjectException::class.java) { ProjectFolder(directory).load() }
        assertNull(ProjectFolder.readHeader(directory))

        // 타일 파일 하나가 없으면 읽지 못한다
        val document = photo(300, 200) { x, _ -> Color.rgb(x and 0xFF, 0, 0) }
        ProjectFolder(directory).save(document, 1, 1)
        File(File(directory, "tiles"), tileFiles().first()).delete()
        assertThrows(IOException::class.java) { ProjectFolder(directory).load() }
    }

    @Test
    fun header_andThumbnail() {
        val document = photo(300, 200) { _, _ -> Color.GREEN }
        val folder = ProjectFolder(directory)

        folder.save(document, createdMillis = 10, modifiedMillis = 20)
        folder.writeThumbnail(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) })

        assertEquals(ProjectHeader(300, 200, 10, 20), ProjectFolder.readHeader(directory))
        val thumbnail = checkNotNull(BitmapFactory.decodeFile(folder.thumbnailFile.path))
        assertEquals(PixelSize(64, 48), PixelSize(thumbnail.width, thumbnail.height))
    }

    @Test
    fun name_roundTrips_andAMissingNameIsNull() {
        assertEquals(null, ProjectFolder.readName(directory))
        val folder = ProjectFolder(directory)

        // 첫 저장 전(폴더가 없을 때)에도 쓸 수 있다
        folder.writeName("바다 노을")
        assertEquals("바다 노을", ProjectFolder.readName(directory))
        folder.save(photo(40, 30) { _, _ -> Color.BLUE }, createdMillis = 1, modifiedMillis = 2)
        folder.writeName("")
        assertEquals("", ProjectFolder.readName(directory))
        // 이름을 바꿔도 목차(고친 시각)는 그대로다
        assertEquals(ProjectHeader(40, 30, 1, 2), ProjectFolder.readHeader(directory))
    }

    private fun meshFiles(): Set<String> = File(directory, "liquify").list()?.toSet() ?: emptySet()

    /** 700×300 사진(6타일)의 가운데를 오른쪽으로 민 유동화(얼굴 하나와 값을 지님) */
    private fun liquified(): Pair<Document, Document> {
        val document = photo(700, 300) { x, y -> Color.rgb(x and 0xFF, y and 0xFF, 128) }
        val mesh = LiquifyMesh.identity(PixelSize(700, 300))
        repeat(6) { step -> mesh.brush(LiquifyTool.PUSH, 350f + step * 5f, 150f, radius = 60f, strength = 1f, moveX = 5f, moveY = 0f) }
        val face = FaceLandmarks(FloatArray(FaceModel.LANDMARK_COUNT * 2) { (it % 600).toFloat() }, 18f)
        val values = FaceValues.ZERO.with(FaceSlider.EYE_SIZE, 0.25f).with(FaceSlider.FACE_WIDTH, -0.5f)
        return document to LiquifyLayer(LayerId(0), mesh.copy(), manual = mesh, faces = listOf(face), faceValues = listOf(values)).applyTo(document)
    }

    @Test
    fun liquifyRecord_roundTrips_sharingUnchangedTiles() {
        val (original, document) = liquified()
        val record = checkNotNull(document.layers[0].liquify)
        val folder = ProjectFolder(directory)

        folder.save(document, 1, 1)
        val loaded = ProjectFolder(directory).load().document
        val read = checkNotNull(loaded.layers[0].liquify)

        // 원래 픽셀과 결과가 함께 쓰는 타일은 한 번만 쓴다(6타일 + 다시 그린 타일)
        val changed = document.raster().tiles.count { (key, tile) -> original.raster().tiles[key] !== tile }
        assertTrue(changed in 1..4)
        assertEquals(6 + changed, tileFiles().size)
        assertEquals(1, meshFiles().size)
        assertSamePixels(document, loaded)
        assertSamePixels(original, loaded.copy(layers = listOf(loaded.layers[0].liquifySource)))
        assertEquals(record.faces, read.faces)
        assertEquals(record.faceValues, read.faceValues)
        val mesh = checkNotNull(record.mesh)
        val readMesh = checkNotNull(read.mesh)
        for (row in 0 until mesh.rows) for (column in 0 until mesh.columns) {
            assertEquals(mesh.vertexX(column, row), readMesh.vertexX(column, row), 0f)
            assertEquals(mesh.vertexY(column, row), readMesh.vertexY(column, row), 0f)
        }

        // 같은 문서를 다시 저장하면 새로 쓰지 않는다. 굳히면(픽셀로 바꾸기) 원래 픽셀만 쓰던 타일과 격자 파일을 지운다.
        val files = tileFiles()
        folder.save(document, 1, 2)
        assertEquals(files, tileFiles())
        folder.save(RasterizeLayer(LayerId(0)).applyTo(document), 1, 3)
        assertEquals(6, tileFiles().size)
        assertTrue(meshFiles().isEmpty())
        assertNull(ProjectFolder(directory).load().document.layers[0].liquify)
    }

    @Test
    fun missingLiquifyFiles_dropOnlyTheRecord() {
        val (_, document) = liquified()
        ProjectFolder(directory).save(document, 1, 1)
        // 원래 픽셀만 쓰는 타일 파일(결과에 없는 것). 새 폴더 객체로 저장하면 파일 이름이 새로 생기므로 그때마다 목차에서 읽는다.
        fun baseOnly(): TileRef.File {
            val manifest = File(directory, ProjectFolder.MANIFEST).inputStream().use { ProjectManifestCodec.read(it) }
            val used = (manifest.layers[0].content as RasterEntry).tiles.map { it.ref }.toSet()
            return checkNotNull(manifest.layers[0].liquify).base.map { it.ref }.filterIsInstance<TileRef.File>().first { it !in used }
        }

        // 격자 파일이 없으면 기록만 버리고 결과 픽셀로 연다
        File(File(directory, "liquify"), meshFiles().first()).delete()
        val withoutMesh = ProjectFolder(directory).load().document
        assertNull(withoutMesh.layers[0].liquify)
        assertSamePixels(document, withoutMesh)

        // 원래 픽셀의 타일 파일이 없어도 같다
        ProjectFolder(directory).save(document, 1, 2)
        assertTrue(File(File(directory, "tiles"), baseOnly().name).delete())
        val withoutBase = ProjectFolder(directory).load().document
        assertNull(withoutBase.layers[0].liquify)
        assertSamePixels(document, withoutBase)
    }

    @Test
    fun estimatedBytes_countsTheLiquifyRecord() {
        val (original, document) = liquified()
        val folder = ProjectFolder(directory)
        folder.save(original, 1, 1)
        val plain = folder.estimatedBytes()

        folder.save(document, 1, 2)

        val tileBytes = TILE_SIZE.toLong() * TILE_SIZE * 4
        assertEquals(plain + (tileFiles().size - 6) * tileBytes + LiquifyMesh.byteCountFor(PixelSize(700, 300)), folder.estimatedBytes())
        assertNotNull(document.layers[0].liquify)
    }
}

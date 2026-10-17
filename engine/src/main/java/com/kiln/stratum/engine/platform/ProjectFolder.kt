package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.project.LayerEntry
import com.kiln.stratum.engine.project.ProjectHeader
import com.kiln.stratum.engine.project.ProjectManifest
import com.kiln.stratum.engine.project.ProjectManifestCodec
import com.kiln.stratum.engine.project.RasterEntry
import com.kiln.stratum.engine.project.TileEntry
import com.kiln.stratum.engine.project.TileRef
import com.kiln.stratum.engine.project.UnreadableProjectException
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.IdentityHashMap
import java.util.UUID

/** 불러온 프로젝트: 문서와 처음 만든 시각 */
class LoadedProject(val document: Document, val createdMillis: Long)

/**
 * 프로젝트 하나를 담는 폴더(앱 내부 저장소). 문서를 저장하고([save]) 불러온다([load]). 실행 취소 기록은 저장하지 않는다.
 *
 * 폴더 안:
 * - project.bin: 목차(ProjectManifest)
 * - tiles/: 타일 파일. 타일 객체마다 하나이고, 같은 객체를 여러 자리가 함께 쓰면 한 번만 쓴다.
 * - thumbnail.webp: 홈 목록용 작은 그림(앱이 그려서 넘긴다)
 *
 * - 타일은 한 번 만들면 바뀌지 않으므로, 이 폴더에 이미 쓴 타일 객체는 다시 쓰지 않는다. 그래서 바뀐 타일만 새로 쓴다.
 * - 한 가지 색 타일(새 캔버스)은 파일 없이 색만 적는다.
 * - 목차는 임시 파일에 다 쓴 뒤 이름을 바꿔 한 번에 바꾼다. 저장하다 앱이 꺼져도 앞 저장본이 그대로 남는다.
 * - 목차가 더 쓰지 않는 타일 파일은 목차를 바꾼 뒤 지운다.
 *
 * 한 폴더를 여러 스레드에서 동시에 저장·불러오지 않는다(부르는 쪽이 한 번에 하나씩 한다). 파일을 다루므로 메인 스레드가 아닌 곳에서 부른다.
 */
class ProjectFolder(val directory: File) {

    private val tilesDirectory = File(directory, TILES)

    /** 이 폴더에 이미 쓴(또는 여기서 불러온) 타일 객체와 그 파일 이름 */
    private val savedTiles = IdentityHashMap<Tile, String>()

    /** 목차 파일 */
    val manifestFile: File
        get() = File(directory, MANIFEST)

    /** 홈 목록용 작은 그림 파일 */
    val thumbnailFile: File
        get() = File(directory, THUMBNAIL)

    /**
     * [document]를 저장한다. 바뀐 타일만 새로 쓰고, 목차를 한 번에 바꾼 뒤 더 쓰지 않는 타일 파일을 지운다.
     * [createdMillis]는 프로젝트를 처음 만든 시각, [modifiedMillis]는 이번에 저장하는 시각이다.
     * [checkCanceled]는 타일 사이마다 부른다. 취소되면 목차를 바꾸지 않아 앞 저장본이 남는다.
     */
    fun save(document: Document, createdMillis: Long, modifiedMillis: Long, checkCanceled: () -> Unit = {}) {
        if (!tilesDirectory.isDirectory && !tilesDirectory.mkdirs()) throw IOException("Couldn't create $tilesDirectory")
        val referenced = HashSet<String>()
        val layers = document.layers.map { layer ->
            val raster = when (val content = layer.content) {
                is RasterContent -> content.raster
            }
            val tiles = raster.tiles.map { (key, tile) ->
                checkCanceled()
                TileEntry(key.column, key.row, refOf(tile, referenced))
            }
            LayerEntry(layer.id.value, layer.name, layer.opacity, layer.blendMode, RasterEntry(tiles))
        }
        val manifest = ProjectManifest(
            width = document.width,
            height = document.height,
            nextLayerId = document.nextLayerId,
            createdMillis = createdMillis,
            modifiedMillis = modifiedMillis,
            layers = layers
        )
        checkCanceled()
        writeAtomically(manifestFile) { ProjectManifestCodec.write(manifest, it) }
        // 목차를 바꾼 뒤에만 지운다. 그 전에 꺼지면 앞 목차가 이 파일들을 아직 쓴다.
        tilesDirectory.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
        savedTiles.values.retainAll(referenced)
    }

    /**
     * 저장한 문서를 불러온다. 망가졌거나 더 새 판이면 [UnreadableProjectException],
     * 메모리가 모자라면 [NotEnoughMemoryException], 파일을 읽지 못하면 IOException이다.
     * 같은 파일을 가리키는 자리는 같은 타일 객체를 함께 쓰고, 한 가지 색 타일은 색마다 한 장만 만든다.
     */
    fun load(checkCanceled: () -> Unit = {}): LoadedProject {
        val manifest = BufferedInputStream(FileInputStream(manifestFile)).use { ProjectManifestCodec.read(it) }
        val grid = TileGrid(manifest.width, manifest.height)
        val solidTiles = HashMap<Int, Tile>()
        val fileTiles = HashMap<String, Tile>()
        val loaded = IdentityHashMap<Tile, String>()
        val layers = manifest.layers.map { entry ->
            val tiles = HashMap<TileKey, Tile?>()
            when (val content = entry.content) {
                is RasterEntry -> for (tileEntry in content.tiles) {
                    checkCanceled()
                    if (tileEntry.column !in 0 until grid.columns || tileEntry.row !in 0 until grid.rows) {
                        throw UnreadableProjectException("Tile outside canvas: (${tileEntry.column}, ${tileEntry.row})")
                    }
                    tiles[TileKey(tileEntry.column, tileEntry.row)] = when (val ref = tileEntry.ref) {
                        is TileRef.Solid -> solidTiles.getOrPut(ref.argb) {
                            BlankCanvas.solidTile(ref.argb) ?: throw NotEnoughMemoryException()
                        }
                        is TileRef.File -> fileTiles.getOrPut(ref.name) {
                            TileFiles.read(File(tilesDirectory, ref.name)).also { loaded[it] = ref.name }
                        }
                    }
                }
            }
            try {
                Layer(LayerId(entry.id), entry.name, RasterContent(Raster.EMPTY.withTiles(tiles)), entry.opacity, entry.blendMode)
            } catch (e: IllegalArgumentException) {
                throw UnreadableProjectException("Broken layer ${entry.id}: ${e.message}")
            }
        }
        val document = try {
            Document(manifest.width, manifest.height, layers, manifest.nextLayerId)
        } catch (e: IllegalArgumentException) {
            throw UnreadableProjectException("Broken document: ${e.message}")
        }
        // 불러온 타일은 이미 이 폴더에 있으니 다음 저장에서 다시 쓰지 않는다
        savedTiles.clear()
        savedTiles.putAll(loaded)
        return LoadedProject(document, manifest.createdMillis)
    }

    /** 홈 목록용 작은 그림 [bitmap]을 쓴다(소프트웨어 비트맵). 임시 파일에 다 쓴 뒤 이름을 바꾼다. */
    fun writeThumbnail(bitmap: Bitmap) {
        writeAtomically(thumbnailFile) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, THUMBNAIL_QUALITY, out)) {
                throw IOException("Couldn't encode the thumbnail")
            }
        }
    }

    /** 폴더째 지운다 */
    fun delete() {
        directory.deleteRecursively()
        savedTiles.clear()
    }

    /** [tile]이 있는 곳. 한 가지 색이면 색만, 아니면 파일(처음 보는 타일 객체면 새로 쓴다). 쓴 파일 이름은 [referenced]에 넣는다. */
    private fun refOf(tile: Tile, referenced: MutableSet<String>): TileRef {
        tile.solidColor?.let { return TileRef.Solid(it) }
        val name = savedTiles[tile] ?: run {
            val name = UUID.randomUUID().toString() + TILE_EXTENSION
            TileFiles.write(File(tilesDirectory, name), tile)
            savedTiles[tile] = name
            name
        }
        referenced += name
        return TileRef.File(name)
    }

    /** [target]을 임시 파일에 다 쓰고 디스크에 내린 뒤 이름을 바꿔 한 번에 바꾼다. */
    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { file ->
                val buffered = BufferedOutputStream(file)
                write(buffered)
                buffered.flush()
                file.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Couldn't replace $target")
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    companion object {
        const val MANIFEST = "project.bin"
        const val THUMBNAIL = "thumbnail.webp"
        private const val TILES = "tiles"
        private const val TILE_EXTENSION = ".tile"
        private const val THUMBNAIL_QUALITY = 80

        /** [directory] 프로젝트의 머리말. 목차가 없거나 읽을 수 없으면 null(망가졌거나 저장하던 중). */
        fun readHeader(directory: File): ProjectHeader? =
            try {
                BufferedInputStream(FileInputStream(File(directory, MANIFEST))).use { ProjectManifestCodec.readHeader(it) }
            } catch (e: IOException) {
                null
            }
    }
}

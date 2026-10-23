package com.kiln.stratum.engine.platform

import android.graphics.Bitmap
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.LiquifyRecord
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.LiquifyMeshCodec
import com.kiln.stratum.engine.project.AdjustmentEntry
import com.kiln.stratum.engine.project.GainmapEntry
import com.kiln.stratum.engine.project.GroupEntry
import com.kiln.stratum.engine.project.LayerEntry
import com.kiln.stratum.engine.project.LiquifyEntry
import com.kiln.stratum.engine.project.MaskTileEntry
import com.kiln.stratum.engine.project.ProjectHeader
import com.kiln.stratum.engine.project.ProjectManifest
import com.kiln.stratum.engine.project.ProjectManifestCodec
import com.kiln.stratum.engine.project.RasterEntry
import com.kiln.stratum.engine.project.SkinEntry
import com.kiln.stratum.engine.project.TileEntry
import com.kiln.stratum.engine.project.TileRef
import com.kiln.stratum.engine.project.UnreadableProjectException
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
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
 *   레이어 마스크 타일(.mask, 픽셀 하나에 1바이트, 결정 기록 '피부 보정'의 1c)도 여기에 두고, 다 보이는 마스크 타일은 파일 없이 목차에만 적는다.
 * - thumbnail.webp: 홈 목록용 작은 그림(앱이 그려서 넘긴다)
 * - name.txt: 프로젝트 이름(UTF-8 글자). 없으면 이름이 없는 것('제목 없음')이다. 목차와 따로 두어 이름만 바꿀 때 목차를 다시 쓰지 않는다.
 * - liquify/: 유동화 기록의 손으로 민 격자 파일(LiquifyMeshCodec). 격자 객체마다 하나다(결정 기록 '얼굴 인식 보정' 3단계).
 *
 * - 타일은 한 번 만들면 바뀌지 않으므로, 이 폴더에 이미 쓴 타일 객체는 다시 쓰지 않는다. 그래서 바뀐 타일만 새로 쓴다(격자도 같다).
 * - 한 가지 색 타일(새 캔버스)은 파일 없이 색만 적는다.
 * - 목차는 임시 파일에 다 쓴 뒤 이름을 바꿔 한 번에 바꾼다. 저장하다 앱이 꺼져도 앞 저장본이 그대로 남는다.
 * - 목차가 더 쓰지 않는 타일·격자 파일은 목차를 바꾼 뒤 지운다.
 * - 유동화 기록의 파일이 없거나 망가졌으면 그 기록만 버리고 연다(결과 픽셀은 레이어 타일에 있다).
 *
 * 한 폴더를 여러 스레드에서 동시에 저장·불러오지 않는다(부르는 쪽이 한 번에 하나씩 한다). 파일을 다루므로 메인 스레드가 아닌 곳에서 부른다.
 */
class ProjectFolder(val directory: File) {

    private val tilesDirectory = File(directory, TILES)

    private val liquifyDirectory = File(directory, LIQUIFY)

    /** 이 폴더에 이미 쓴(또는 여기서 불러온) 타일 객체와 그 파일 이름 */
    private val savedTiles = IdentityHashMap<Tile, String>()

    /** 이 폴더에 이미 쓴(또는 여기서 불러온) 마스크 타일 객체와 그 파일 이름 */
    private val savedMasks = IdentityHashMap<MaskTile, String>()

    /** 이 폴더에 이미 쓴(또는 여기서 불러온) 유동화 격자 객체와 그 파일 이름. 기록의 격자는 바뀌지 않는 사본이다. */
    private val savedMeshes = IdentityHashMap<LiquifyMesh, String>()

    /** 목차 파일 */
    val manifestFile: File
        get() = File(directory, MANIFEST)

    /** 홈 목록용 작은 그림 파일 */
    val thumbnailFile: File
        get() = File(directory, THUMBNAIL)

    /** 프로젝트 이름 파일 */
    val nameFile: File
        get() = File(directory, NAME)

    /**
     * [document]를 저장한다. 바뀐 타일만 새로 쓰고, 목차를 한 번에 바꾼 뒤 더 쓰지 않는 타일 파일을 지운다.
     * [createdMillis]는 프로젝트를 처음 만든 시각, [modifiedMillis]는 이번에 저장하는 시각이다.
     * [checkCanceled]는 타일 사이마다 부른다. 취소되면 목차를 바꾸지 않아 앞 저장본이 남는다.
     */
    fun save(document: Document, createdMillis: Long, modifiedMillis: Long, checkCanceled: () -> Unit = {}) {
        if (!tilesDirectory.isDirectory && !tilesDirectory.mkdirs()) throw IOException("Couldn't create $tilesDirectory")
        val referenced = HashSet<String>()
        val referencedMeshes = HashSet<String>()
        fun entriesOf(raster: Raster): List<TileEntry> =
            raster.tiles.map { (key, tile) ->
                checkCanceled()
                TileEntry(key.column, key.row, refOf(tile, referenced))
            }
        // 게인맵 타일도 같은 타일 파일로 둔다(같은 객체는 한 번만 쓴다)
        fun entryOf(gainmap: HdrGainmap): GainmapEntry = GainmapEntry(gainmap.width, gainmap.height, entriesOf(gainmap.raster))
        val layers = document.layers.map { layer ->
            val content = when (val content = layer.content) {
                is RasterContent -> RasterEntry(entriesOf(content.raster))
                is AdjustmentContent -> AdjustmentEntry
                GroupContent -> GroupEntry
            }
            // 유동화 기록: 원래 픽셀은 결과와 바뀌지 않은 타일을 함께 써서 바뀐 타일만 더 쓴다
            val liquify = layer.liquify?.let { record ->
                LiquifyEntry(
                    record.version,
                    entriesOf(record.base),
                    record.baseGainmap?.let { entryOf(it) },
                    record.mesh?.let { meshFileOf(it, referencedMeshes) },
                    record.faces,
                    record.faceValues
                )
            }
            // 레이어 마스크: 다 보이는 타일은 파일 없이 적는다
            val mask = layer.mask?.let { mask ->
                mask.tiles.map { (key, tile) ->
                    checkCanceled()
                    MaskTileEntry(key.column, key.row, if (tile.isFull) null else maskFileOf(tile, referenced))
                }
            }
            val skin = layer.skin?.let { SkinEntry(it.source.value, it.color.value, it.smooth.value, it.spots.value, it.faces, it.segmented, it.texture, it.healed) }
            LayerEntry(
                layer.id.value, layer.name, layer.opacity, layer.blendMode, content, layer.adjustments,
                layer.gainmap?.let { entryOf(it) }, layer.visible, layer.text, liquify, mask, layer.parent?.value,
                skin, (layer.content as? AdjustmentContent)?.skin
            )
        }
        val manifest = ProjectManifest(
            width = document.width,
            height = document.height,
            nextLayerId = document.nextLayerId,
            createdMillis = createdMillis,
            modifiedMillis = modifiedMillis,
            layers = layers,
            colorSpace = document.colorSpace,
            hdr = document.hdr
        )
        checkCanceled()
        writeAtomically(manifestFile) { ProjectManifestCodec.write(manifest, it) }
        // 목차를 바꾼 뒤에만 지운다. 그 전에 꺼지면 앞 목차가 이 파일들을 아직 쓴다.
        tilesDirectory.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
        savedTiles.values.retainAll(referenced)
        savedMasks.values.retainAll(referenced)
        liquifyDirectory.listFiles()?.forEach { if (it.name !in referencedMeshes) it.delete() }
        savedMeshes.values.retainAll(referencedMeshes)
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
        // 같은 파일을 가리키는 자리는 같은 타일 객체를 함께 쓰고, 한 가지 색 타일은 색마다 한 장만 만든다
        fun rasterOf(entries: List<TileEntry>, grid: TileGrid): Raster {
            val tiles = HashMap<TileKey, Tile?>()
            for (tileEntry in entries) {
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
            return Raster.EMPTY.withTiles(tiles)
        }
        val maskTiles = HashMap<String, MaskTile>()
        val loadedMasks = IdentityHashMap<MaskTile, String>()
        // 레이어 마스크. 같은 파일을 가리키는 자리는 같은 마스크 타일 객체를 함께 쓴다.
        fun maskOf(entries: List<MaskTileEntry>): Selection {
            val tiles = HashMap<TileKey, MaskTile>()
            for (tileEntry in entries) {
                checkCanceled()
                if (tileEntry.column !in 0 until grid.columns || tileEntry.row !in 0 until grid.rows) {
                    throw UnreadableProjectException("Mask tile outside canvas: (${tileEntry.column}, ${tileEntry.row})")
                }
                val name = tileEntry.file
                tiles[TileKey(tileEntry.column, tileEntry.row)] = if (name == null) {
                    MaskTile.FULL
                } else {
                    maskTiles.getOrPut(name) { MaskTileFiles.read(File(tilesDirectory, name)).also { loadedMasks[it] = name } }
                }
            }
            return Selection.of(tiles)
        }
        val loadedMeshes = IdentityHashMap<LiquifyMesh, String>()
        // 유동화 기록. 더 새 계산식이거나 파일이 없거나 망가졌으면 기록만 버린다(결과 픽셀은 레이어 타일에 있다).
        fun recordOf(entry: LiquifyEntry): LiquifyRecord? {
            if (entry.version !in 1..LiquifyRecord.VERSION) return null
            return try {
                val base = rasterOf(entry.base, grid)
                val baseGainmap = entry.baseGainmap?.let { HdrGainmap(it.width, it.height, rasterOf(it.tiles, TileGrid(it.width, it.height))) }
                val mesh = entry.meshFile?.let { name ->
                    BufferedInputStream(FileInputStream(File(liquifyDirectory, name))).use {
                        LiquifyMeshCodec.read(it, PixelSize(manifest.width, manifest.height))
                    }.also { loadedMeshes[it] = name }
                }
                LiquifyRecord(base, baseGainmap, mesh, entry.faces, entry.faceValues, entry.version)
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        val layers = manifest.layers.map { entry ->
            val content = when (val content = entry.content) {
                is RasterEntry -> RasterContent(rasterOf(content.tiles, grid))
                AdjustmentEntry -> AdjustmentContent(skin = entry.skinColor)
                GroupEntry -> GroupContent
            }
            try {
                val gainmap = entry.gainmap?.let { HdrGainmap(it.width, it.height, rasterOf(it.tiles, TileGrid(it.width, it.height))) }
                val liquify = entry.liquify?.let { recordOf(it) }
                val mask = entry.mask?.let { maskOf(it) }
                // 피부 기록은 그룹에만 둔다(망가진 파일이 다른 레이어에 붙였으면 버린다)
                val skin = entry.skin?.takeIf { content is GroupContent }?.let {
                    SkinRecord(LayerId(it.source), LayerId(it.color), LayerId(it.smooth), LayerId(it.spots), it.faces, it.segmented, it.texture, it.healed)
                }
                Layer(
                    LayerId(entry.id), entry.name, content, entry.opacity, entry.blendMode, entry.adjustments, gainmap, entry.visible, entry.text,
                    liquify, mask, entry.parent?.let(::LayerId), skin
                )
            } catch (e: IllegalArgumentException) {
                throw UnreadableProjectException("Broken layer ${entry.id}: ${e.message}")
            }
        }
        // 그룹 규칙이 깨진 파일이면 그룹을 풀고(안의 레이어는 그룹 밖으로) 픽셀은 살린다(결정 기록 '피부 보정'의 1g)
        val tree = if (LayerTree.problem(layers) == null) layers else layers.map { it.copy(parent = null) }
        val document = try {
            Document(manifest.width, manifest.height, tree, manifest.nextLayerId, colorSpace = manifest.colorSpace, hdr = manifest.hdr)
        } catch (e: IllegalArgumentException) {
            throw UnreadableProjectException("Broken document: ${e.message}")
        }
        // 불러온 타일·격자는 이미 이 폴더에 있으니 다음 저장에서 다시 쓰지 않는다
        savedTiles.clear()
        savedTiles.putAll(loaded)
        savedMasks.clear()
        savedMasks.putAll(loadedMasks)
        savedMeshes.clear()
        savedMeshes.putAll(loadedMeshes)
        // 피부 정돈의 바탕 지문은 저장하지 않아 지금 그림으로 정한다(결정 기록 '피부 보정'의 4단계)
        return LoadedProject(SkinRecord.withBases(document), manifest.createdMillis)
    }

    /**
     * 불러오는 데 들 메모리(바이트)의 어림. 목차만 읽어, 타일 파일 수(같은 파일은 한 번)와 한 가지 색 수에 꽉 찬 타일 크기를 곱하고,
     * 마스크 타일 파일마다 마스크 타일 한 장(픽셀 하나에 1바이트), 유동화 기록의 격자 파일마다 문서 격자 하나만큼을 더한다.
     * 목차를 읽지 못하면 [load]와 같은 예외를 던진다.
     */
    fun estimatedBytes(): Long {
        val manifest = BufferedInputStream(FileInputStream(manifestFile)).use { ProjectManifestCodec.read(it) }
        val files = HashSet<String>()
        val solids = HashSet<Int>()
        val meshes = HashSet<String>()
        val masks = HashSet<String>()
        for (layer in manifest.layers) {
            layer.mask?.forEach { tile -> tile.file?.let { masks += it } }
            val entries = when (val content = layer.content) {
                is RasterEntry -> content.tiles
                AdjustmentEntry, GroupEntry -> emptyList()
            } + layer.gainmap?.tiles.orEmpty() + layer.liquify?.base.orEmpty() + layer.liquify?.baseGainmap?.tiles.orEmpty()
            for (tile in entries) {
                when (val ref = tile.ref) {
                    is TileRef.File -> files += ref.name
                    is TileRef.Solid -> solids += ref.argb
                }
            }
            layer.liquify?.meshFile?.let { meshes += it }
        }
        return (files.size + solids.size).toLong() * TILE_PIXEL_COUNT * Int.SIZE_BYTES + masks.size.toLong() * TILE_PIXEL_COUNT +
            meshes.size * LiquifyMesh.byteCountFor(PixelSize(manifest.width, manifest.height))
    }

    /** 홈 목록용 작은 그림 [bitmap]을 쓴다(소프트웨어 비트맵). 임시 파일에 다 쓴 뒤 이름을 바꾼다. */
    fun writeThumbnail(bitmap: Bitmap) {
        writeAtomically(thumbnailFile) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, THUMBNAIL_QUALITY, out)) {
                throw IOException("Couldn't encode the thumbnail")
            }
        }
    }

    /**
     * 프로젝트 이름 [name]을 쓴다(빈 글자는 이름이 없는 것). 폴더가 아직 없으면(첫 저장 전) 만든다.
     * 임시 파일에 다 쓴 뒤 이름을 바꿔 한 번에 바꾼다.
     */
    fun writeName(name: String) {
        directory.mkdirs()
        writeAtomically(nameFile) { it.write(name.toByteArray(Charsets.UTF_8)) }
    }

    /** 폴더째 지운다 */
    fun delete() {
        directory.deleteRecursively()
        savedTiles.clear()
        savedMasks.clear()
        savedMeshes.clear()
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

    /** 마스크 타일 [tile](다 보이는 타일이 아닌 것)의 파일 이름. 처음 보는 타일 객체면 새로 쓴다. 쓴 파일 이름은 [referenced]에 넣는다. */
    private fun maskFileOf(tile: MaskTile, referenced: MutableSet<String>): String {
        val name = savedMasks[tile] ?: run {
            val name = UUID.randomUUID().toString() + MASK_EXTENSION
            MaskTileFiles.write(File(tilesDirectory, name), tile)
            savedMasks[tile] = name
            name
        }
        referenced += name
        return name
    }

    /** [mesh]의 격자 파일 이름. 처음 보는 격자 객체면 새로 쓴다. 쓴 파일 이름은 [referenced]에 넣는다. */
    private fun meshFileOf(mesh: LiquifyMesh, referenced: MutableSet<String>): String {
        val name = savedMeshes[mesh] ?: run {
            if (!liquifyDirectory.isDirectory && !liquifyDirectory.mkdirs()) throw IOException("Couldn't create $liquifyDirectory")
            val name = UUID.randomUUID().toString() + MESH_EXTENSION
            writeAtomically(File(liquifyDirectory, name)) { LiquifyMeshCodec.write(mesh, it) }
            savedMeshes[mesh] = name
            name
        }
        referenced += name
        return name
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
        const val NAME = "name.txt"
        private const val TILES = "tiles"
        private const val TILE_EXTENSION = ".tile"
        private const val MASK_EXTENSION = ".mask"
        private const val LIQUIFY = "liquify"
        private const val MESH_EXTENSION = ".mesh"
        private const val THUMBNAIL_QUALITY = 80

        /** 이름 파일에서 읽는 가장 긴 글자 수. 망가진 큰 파일을 다 읽지 않게 한다(앱이 쓰는 이름은 이보다 짧다). */
        private const val MAX_NAME_BYTES = 1024

        /** [directory] 프로젝트의 이름. 이름 파일이 없거나 읽을 수 없으면 null(이름이 없는 것). */
        fun readName(directory: File): String? =
            try {
                val file = File(directory, NAME)
                if (!file.isFile) {
                    null
                } else {
                    FileInputStream(file).use { input ->
                        val bytes = ByteArray(MAX_NAME_BYTES)
                        var read = 0
                        while (read < bytes.size) {
                            val count = input.read(bytes, read, bytes.size - read)
                            if (count < 0) break
                            read += count
                        }
                        String(bytes, 0, read, Charsets.UTF_8)
                    }
                }
            } catch (e: IOException) {
                null
            }

        /** [directory] 프로젝트의 머리말. 목차가 없거나 읽을 수 없으면 null(망가졌거나 저장하던 중). */
        fun readHeader(directory: File): ProjectHeader? =
            try {
                BufferedInputStream(FileInputStream(File(directory, MANIFEST))).use { ProjectManifestCodec.readHeader(it) }
            } catch (e: IOException) {
                null
            }
    }
}

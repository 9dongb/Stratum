package com.kiln.stratum.engine.project

import com.kiln.stratum.engine.document.BlendMode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 프로젝트 파일 형식의 판. 이 앱이 쓰는 판이자 읽을 수 있는 가장 높은 판이다. */
const val PROJECT_FORMAT_VERSION = 1

/**
 * 저장한 프로젝트의 목차(순수 데이터). 캔버스 크기, 레이어와 그 속성, 레이어마다 어느 자리에 어떤 타일이 있는지 적는다.
 * 픽셀은 타일 파일에 따로 둔다(엔진 플랫폼의 ProjectFolder). 실행 취소 기록은 저장하지 않는다(결정 기록 '자동 저장').
 *
 * @property nextLayerId 다음에 만들 레이어의 번호(Document.nextLayerId)
 * @property createdMillis 프로젝트를 처음 만든 시각(밀리초)
 * @property modifiedMillis 마지막으로 저장한 시각(밀리초). 홈의 최근 프로젝트 순서다.
 */
data class ProjectManifest(
    val width: Int,
    val height: Int,
    val nextLayerId: Long,
    val createdMillis: Long,
    val modifiedMillis: Long,
    val layers: List<LayerEntry>
) {
    init {
        require(width > 0 && height > 0) { "Canvas size must be positive: ${width}x$height" }
        require(layers.isNotEmpty()) { "Project needs at least one layer" }
    }

    /** 목록에 보일 머리말 */
    val header: ProjectHeader
        get() = ProjectHeader(width, height, createdMillis, modifiedMillis)
}

/** 레이어 하나의 속성과 내용 */
data class LayerEntry(
    val id: Long,
    val name: String,
    val opacity: Float,
    val blendMode: BlendMode,
    val content: ContentEntry
)

/** 레이어 내용. 나중에 텍스트 같은 종류가 더해진다. */
sealed interface ContentEntry

/** 픽셀 레이어. 타일이 없는 자리는 투명이다. */
data class RasterEntry(val tiles: List<TileEntry>) : ContentEntry

/** [column]·[row] 자리의 타일 */
data class TileEntry(val column: Int, val row: Int, val ref: TileRef)

/** 타일 픽셀이 있는 곳 */
sealed interface TileRef {

    /** tiles 폴더의 타일 파일 [name]. 같은 타일 객체를 여러 자리가 함께 쓰면 같은 파일을 가리킨다. */
    data class File(val name: String) : TileRef

    /** 한 가지 색([argb], 알파를 미리 곱하지 않음)으로 꽉 찬 타일. 파일 없이 색만 적는다(한 가지 색 새 캔버스). */
    data class Solid(val argb: Int) : TileRef
}

/** 홈의 최근 프로젝트 목록에 보일 머리말. 목차의 앞부분만 읽어 얻는다. */
data class ProjectHeader(val width: Int, val height: Int, val createdMillis: Long, val modifiedMillis: Long)

/** 이 앱보다 새 판으로 저장했거나 망가져서 읽을 수 없는 프로젝트 */
class UnreadableProjectException(message: String) : IOException(message)

/**
 * 목차를 바이트로 쓰고 읽는다(순수 계산). 형식:
 *
 * - 머리말: 표지(STRM), 판, 가로, 세로, 다음 레이어 번호, 만든 시각, 고친 시각
 * - 레이어 수와 레이어마다: 번호, 이름, 불투명도, 혼합 모드 코드, 내용 종류, 내용, 덧붙임
 * - 문서 덧붙임
 *
 * 덧붙임은 (꼬리표, 길이, 바이트) 묶음이다. 나중 판이 새 속성(보정 값 등)을 여기에 더해도,
 * 모르는 꼬리표는 건너뛰므로 같은 판 안에서는 서로 읽을 수 있다.
 * 혼합 모드는 순서나 이름이 아니라 고정된 코드로 적는다(설계 메모 '저장 형식').
 */
object ProjectManifestCodec {

    /** 'STRM' */
    private const val MAGIC = 0x5354524D

    private const val CONTENT_RASTER = 1
    private const val TILE_FILE: Byte = 0
    private const val TILE_SOLID: Byte = 1

    /** [manifest]를 [output]에 쓴다. [output]은 닫지 않는다. */
    fun write(manifest: ProjectManifest, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(PROJECT_FORMAT_VERSION)
        out.writeInt(manifest.width)
        out.writeInt(manifest.height)
        out.writeLong(manifest.nextLayerId)
        out.writeLong(manifest.createdMillis)
        out.writeLong(manifest.modifiedMillis)
        out.writeInt(manifest.layers.size)
        for (layer in manifest.layers) {
            out.writeLong(layer.id)
            out.writeUTF(layer.name)
            out.writeFloat(layer.opacity)
            out.writeInt(blendCode(layer.blendMode))
            when (val content = layer.content) {
                is RasterEntry -> {
                    out.writeInt(CONTENT_RASTER)
                    out.writeInt(content.tiles.size)
                    for (tile in content.tiles) {
                        out.writeInt(tile.column)
                        out.writeInt(tile.row)
                        when (val ref = tile.ref) {
                            is TileRef.File -> {
                                out.writeByte(TILE_FILE.toInt())
                                out.writeUTF(ref.name)
                            }
                            is TileRef.Solid -> {
                                out.writeByte(TILE_SOLID.toInt())
                                out.writeInt(ref.argb)
                            }
                        }
                    }
                }
            }
            // 레이어 덧붙임(아직 없음)
            out.writeInt(0)
        }
        // 문서 덧붙임(아직 없음)
        out.writeInt(0)
        out.flush()
    }

    /** [input]에서 목차를 읽는다. 표지·판이 맞지 않거나 망가졌으면 [UnreadableProjectException]이다. */
    fun read(input: InputStream): ProjectManifest {
        val data = DataInputStream(input)
        val header = readHeaderFields(data)
        try {
            val nextLayerId = header.nextLayerId
            val layers = List(data.readCount()) {
                val id = data.readLong()
                val name = data.readUTF()
                val opacity = data.readFloat()
                val blendMode = blendOf(data.readInt())
                val content = when (val type = data.readInt()) {
                    CONTENT_RASTER -> RasterEntry(
                        List(data.readCount()) {
                            val column = data.readInt()
                            val row = data.readInt()
                            val ref = when (val kind = data.readByte()) {
                                TILE_FILE -> TileRef.File(data.readUTF())
                                TILE_SOLID -> TileRef.Solid(data.readInt())
                                else -> throw UnreadableProjectException("Unknown tile kind $kind")
                            }
                            TileEntry(column, row, ref)
                        }
                    )
                    else -> throw UnreadableProjectException("Unknown layer content $type")
                }
                data.skipExtras()
                LayerEntry(id, name, opacity, blendMode, content)
            }
            data.skipExtras()
            return ProjectManifest(header.width, header.height, nextLayerId, header.createdMillis, header.modifiedMillis, layers)
        } catch (e: IllegalArgumentException) {
            throw UnreadableProjectException("Broken project: ${e.message}")
        }
    }

    /** [input]에서 머리말만 읽는다. 홈 목록을 만들 때 쓴다. */
    fun readHeader(input: InputStream): ProjectHeader {
        val header = readHeaderFields(DataInputStream(input))
        return ProjectHeader(header.width, header.height, header.createdMillis, header.modifiedMillis)
    }

    private class HeaderFields(val width: Int, val height: Int, val nextLayerId: Long, val createdMillis: Long, val modifiedMillis: Long)

    private fun readHeaderFields(data: DataInputStream): HeaderFields {
        if (data.readInt() != MAGIC) throw UnreadableProjectException("Not a Stratum project")
        val version = data.readInt()
        if (version !in 1..PROJECT_FORMAT_VERSION) throw UnreadableProjectException("Unsupported project version $version")
        val width = data.readInt()
        val height = data.readInt()
        if (width <= 0 || height <= 0) throw UnreadableProjectException("Broken canvas size ${width}x$height")
        return HeaderFields(width, height, data.readLong(), data.readLong(), data.readLong())
    }

    /** 개수. 음수면 망가진 파일이다. */
    private fun DataInputStream.readCount(): Int =
        readInt().also { if (it < 0) throw UnreadableProjectException("Broken count $it") }

    /** 덧붙임을 건너뛴다. 이 판에서 아는 꼬리표가 아직 없다. */
    private fun DataInputStream.skipExtras() {
        repeat(readCount()) {
            readInt()
            val length = readCount()
            skipBytes(length).also { if (it != length) throw UnreadableProjectException("Broken extra") }
        }
    }

    /** 혼합 모드의 고정 코드. 순서(ordinal)가 바뀌어도 저장한 파일이 그대로 읽힌다. */
    internal fun blendCode(mode: BlendMode): Int =
        when (mode) {
            BlendMode.NORMAL -> 0
            BlendMode.MULTIPLY -> 1
            BlendMode.SCREEN -> 2
            BlendMode.OVERLAY -> 3
        }

    internal fun blendOf(code: Int): BlendMode =
        when (code) {
            0 -> BlendMode.NORMAL
            1 -> BlendMode.MULTIPLY
            2 -> BlendMode.SCREEN
            3 -> BlendMode.OVERLAY
            else -> throw UnreadableProjectException("Unknown blend mode $code")
        }
}

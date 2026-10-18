package com.kiln.stratum.engine.project

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.GainRgb
import com.kiln.stratum.engine.document.HdrInfo
import com.kiln.stratum.engine.document.HdrPrimaries
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 * @property colorSpace 픽셀 값의 색 공간(Document.colorSpace). sRGB가 아니면 문서 덧붙임(꼬리표 1)에 적는다.
 * @property hdr 게인맵을 읽는 값(Document.hdr). 있으면 문서 덧붙임(꼬리표 2)에 적는다.
 */
data class ProjectManifest(
    val width: Int,
    val height: Int,
    val nextLayerId: Long,
    val createdMillis: Long,
    val modifiedMillis: Long,
    val layers: List<LayerEntry>,
    val colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB,
    val hdr: HdrInfo? = null
) {
    init {
        require(width > 0 && height > 0) { "Canvas size must be positive: ${width}x$height" }
        require(layers.isNotEmpty()) { "Project needs at least one layer" }
    }

    /** 목록에 보일 머리말 */
    val header: ProjectHeader
        get() = ProjectHeader(width, height, createdMillis, modifiedMillis)
}

/**
 * 레이어 하나의 속성과 내용. 보정 값은 레이어 덧붙임(꼬리표 1)에, 게인맵은 덧붙임(꼬리표 2)에 적고, 없으면 적지 않는다.
 */
data class LayerEntry(
    val id: Long,
    val name: String,
    val opacity: Float,
    val blendMode: BlendMode,
    val content: ContentEntry,
    val adjustments: Adjustments = Adjustments.NONE,
    val gainmap: GainmapEntry? = null
)

/** 레이어의 게인맵: 크기와, 게인맵 격자의 자리마다 어떤 타일이 있는지(Layer.gainmap) */
data class GainmapEntry(val width: Int, val height: Int, val tiles: List<TileEntry>)

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

    /** 레이어 덧붙임 꼬리표: 기본 보정 값 */
    private const val EXTRA_ADJUSTMENTS = 1

    /** 레이어 덧붙임 꼬리표: 게인맵(크기와 타일 목록) */
    private const val EXTRA_GAINMAP = 2

    /** 문서 덧붙임 꼬리표: 색 공간 코드(int). sRGB면 적지 않는다. */
    private const val EXTRA_COLOR_SPACE = 1

    /** 문서 덧붙임 꼬리표: 게인맵을 읽는 값(HdrInfo) */
    private const val EXTRA_HDR = 2
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
                    writeTiles(out, content.tiles)
                }
            }
            // 레이어 덧붙임: 보정 값·게인맵(있을 때만)
            val extras = ArrayList<Pair<Int, ByteArray>>()
            if (!layer.adjustments.isIdentity) extras += EXTRA_ADJUSTMENTS to adjustmentBytes(layer.adjustments)
            layer.gainmap?.let { extras += EXTRA_GAINMAP to gainmapBytes(it) }
            writeExtras(out, extras)
        }
        // 문서 덧붙임: 색 공간(sRGB가 아닐 때만)·HDR 값(있을 때만)
        val extras = ArrayList<Pair<Int, ByteArray>>()
        if (manifest.colorSpace != DocumentColorSpace.SRGB) {
            extras += EXTRA_COLOR_SPACE to ByteArrayOutputStream().also { DataOutputStream(it).writeInt(colorSpaceCode(manifest.colorSpace)) }.toByteArray()
        }
        manifest.hdr?.let { extras += EXTRA_HDR to hdrBytes(it) }
        writeExtras(out, extras)
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
                    CONTENT_RASTER -> RasterEntry(readTiles(data))
                    else -> throw UnreadableProjectException("Unknown layer content $type")
                }
                var adjustments = Adjustments.NONE
                var gainmap: GainmapEntry? = null
                data.readExtras { tag, bytes ->
                    when (tag) {
                        EXTRA_ADJUSTMENTS -> adjustments = adjustmentsOf(bytes)
                        EXTRA_GAINMAP -> gainmap = gainmapOf(bytes)
                    }
                }
                LayerEntry(id, name, opacity, blendMode, content, adjustments, gainmap)
            }
            var colorSpace = DocumentColorSpace.SRGB
            var hdr: HdrInfo? = null
            data.readExtras { tag, bytes ->
                when (tag) {
                    EXTRA_COLOR_SPACE -> colorSpace = colorSpaceOf(DataInputStream(ByteArrayInputStream(bytes)).readInt())
                    EXTRA_HDR -> hdr = hdrOf(bytes)
                }
            }
            return ProjectManifest(header.width, header.height, nextLayerId, header.createdMillis, header.modifiedMillis, layers, colorSpace, hdr)
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

    /** 덧붙임들을 읽어 [onExtra]에 넘긴다. 모르는 꼬리표는 받는 쪽이 그냥 지나친다. */
    private fun DataInputStream.readExtras(onExtra: (tag: Int, bytes: ByteArray) -> Unit) {
        repeat(readCount()) {
            val tag = readInt()
            val bytes = ByteArray(readCount())
            readFully(bytes)
            onExtra(tag, bytes)
        }
    }

    /** 덧붙임들: 개수와 (꼬리표, 길이, 바이트) 묶음 */
    private fun writeExtras(out: DataOutputStream, extras: List<Pair<Int, ByteArray>>) {
        out.writeInt(extras.size)
        for ((tag, bytes) in extras) {
            out.writeInt(tag)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    /** 타일 목록: 개수와 타일마다 자리·종류·파일 이름 또는 색 */
    private fun writeTiles(out: DataOutputStream, tiles: List<TileEntry>) {
        out.writeInt(tiles.size)
        for (tile in tiles) {
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

    private fun readTiles(data: DataInputStream): List<TileEntry> =
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

    /** 게인맵: 가로, 세로, 타일 목록 */
    private fun gainmapBytes(gainmap: GainmapEntry): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeInt(gainmap.width)
            out.writeInt(gainmap.height)
            writeTiles(out, gainmap.tiles)
            out.flush()
        }.toByteArray()

    private fun gainmapOf(bytes: ByteArray): GainmapEntry {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val width = data.readInt()
        val height = data.readInt()
        if (width <= 0 || height <= 0) throw UnreadableProjectException("Broken gainmap size ${width}x$height")
        return GainmapEntry(width, height, readTiles(data))
    }

    /** HDR 값: 채널마다 세 값인 것 다섯(비율 최소·최대, 감마, 엡실론 SDR·HDR), 화면 비율 둘, 1채널(바이트), 대체 색 영역 코드(-1 없음) */
    private fun hdrBytes(hdr: HdrInfo): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            for (rgb in listOf(hdr.ratioMin, hdr.ratioMax, hdr.gamma, hdr.epsilonSdr, hdr.epsilonHdr)) {
                out.writeFloat(rgb.r)
                out.writeFloat(rgb.g)
                out.writeFloat(rgb.b)
            }
            out.writeFloat(hdr.displayRatioForFullHdr)
            out.writeFloat(hdr.minDisplayRatioForHdrTransition)
            out.writeByte(if (hdr.singleChannel) 1 else 0)
            out.writeInt(hdr.alternatePrimaries?.let { primariesCode(it) } ?: -1)
            out.flush()
        }.toByteArray()

    private fun hdrOf(bytes: ByteArray): HdrInfo {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val rgb = List(5) { GainRgb(data.readFloat(), data.readFloat(), data.readFloat()) }
        return HdrInfo(
            ratioMin = rgb[0],
            ratioMax = rgb[1],
            gamma = rgb[2],
            epsilonSdr = rgb[3],
            epsilonHdr = rgb[4],
            displayRatioForFullHdr = data.readFloat(),
            minDisplayRatioForHdrTransition = data.readFloat(),
            singleChannel = data.readByte().toInt() != 0,
            alternatePrimaries = data.readInt().let { if (it < 0) null else primariesOf(it) }
        )
    }

    private fun primariesCode(primaries: HdrPrimaries): Int =
        when (primaries) {
            HdrPrimaries.SRGB -> 0
            HdrPrimaries.DISPLAY_P3 -> 1
            HdrPrimaries.BT2020 -> 2
        }

    private fun primariesOf(code: Int): HdrPrimaries =
        when (code) {
            0 -> HdrPrimaries.SRGB
            1 -> HdrPrimaries.DISPLAY_P3
            2 -> HdrPrimaries.BT2020
            else -> throw UnreadableProjectException("Unknown HDR primaries $code")
        }

    /** 보정 값: 실수 7개(노출, 색온도, 그림자, 하이라이트, 밝기, 대비, 채도), 곡선 점 수와 점들(x, y) */
    private fun adjustmentBytes(adjustments: Adjustments): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeFloat(adjustments.exposure)
            out.writeFloat(adjustments.temperature)
            out.writeFloat(adjustments.shadows)
            out.writeFloat(adjustments.highlights)
            out.writeFloat(adjustments.brightness)
            out.writeFloat(adjustments.contrast)
            out.writeFloat(adjustments.saturation)
            out.writeInt(adjustments.curve.points.size)
            for (point in adjustments.curve.points) {
                out.writeFloat(point.x)
                out.writeFloat(point.y)
            }
            out.flush()
        }.toByteArray()

    private fun adjustmentsOf(bytes: ByteArray): Adjustments {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        try {
            val values = FloatArray(7) { data.readFloat() }
            val points = List(data.readCount()) { CurvePoint(data.readFloat(), data.readFloat()) }
            return Adjustments(
                exposure = values[0],
                temperature = values[1],
                shadows = values[2],
                highlights = values[3],
                brightness = values[4],
                contrast = values[5],
                saturation = values[6],
                curve = ToneCurve(points)
            )
        } catch (e: IllegalArgumentException) {
            throw UnreadableProjectException("Broken adjustments: ${e.message}")
        }
    }

    /** 색 공간의 고정 코드 */
    private fun colorSpaceCode(space: DocumentColorSpace): Int =
        when (space) {
            DocumentColorSpace.SRGB -> 0
            DocumentColorSpace.DISPLAY_P3 -> 1
        }

    private fun colorSpaceOf(code: Int): DocumentColorSpace =
        when (code) {
            0 -> DocumentColorSpace.SRGB
            1 -> DocumentColorSpace.DISPLAY_P3
            else -> throw UnreadableProjectException("Unknown color space $code")
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

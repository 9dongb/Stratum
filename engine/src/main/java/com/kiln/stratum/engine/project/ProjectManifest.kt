package com.kiln.stratum.engine.project

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.GainRgb
import com.kiln.stratum.engine.document.HdrInfo
import com.kiln.stratum.engine.document.HdrPrimaries
import com.kiln.stratum.engine.face.FaceLandmarks
import com.kiln.stratum.engine.face.FaceModel
import com.kiln.stratum.engine.face.FaceSlider
import com.kiln.stratum.engine.face.FaceValues
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinFaceTone
import com.kiln.stratum.engine.skin.SkinReference
import com.kiln.stratum.engine.skin.SkinSpot
import com.kiln.stratum.engine.skin.SkinTone
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 프로젝트 파일 형식의 판. 이 앱이 읽을 수 있는 가장 높은 판이다.
 * 판 2는 레이어 마스크(결정 기록 '피부 보정'의 1c)·조정 레이어(1e)·레이어 그룹(1g)을 담는다. 앞 판 앱이 그것을 빼고 열면 보이는 모습이 달라지므로,
 * 그것이 있는 프로젝트만 판 2로 쓰고 나머지는 판 1 그대로다([ProjectManifest.formatVersion]).
 * 판 3은 캔버스 밖 픽셀(캔버스 밖 자리의 타일, 끝 자리에서 캔버스 밖까지 담은 타일, 결정 기록 '캔버스 밖 픽셀')을 담는다. 앞 판 앱은 캔버스 밖 자리를
 * 망가진 파일로 보고, 끝 타일을 캔버스 안쪽으로 잘라 읽어서 그 프로젝트만 판 3으로 쓴다.
 */
const val PROJECT_FORMAT_VERSION = 3

/**
 * 저장한 프로젝트의 목차(순수 데이터). 캔버스 크기, 레이어와 그 속성, 레이어마다 어느 자리에 어떤 타일이 있는지 적는다.
 * 픽셀은 타일 파일에 따로 둔다(엔진 플랫폼의 ProjectFolder). 실행 취소 기록은 저장하지 않는다(결정 기록 '자동 저장').
 *
 * @property nextLayerId 다음에 만들 레이어의 번호(Document.nextLayerId)
 * @property createdMillis 프로젝트를 처음 만든 시각(밀리초)
 * @property modifiedMillis 마지막으로 저장한 시각(밀리초). 홈의 최근 프로젝트 순서다.
 * @property colorSpace 픽셀 값의 색 공간(Document.colorSpace). sRGB가 아니면 문서 덧붙임(꼬리표 1)에 적는다.
 * @property hdr 게인맵을 읽는 값(Document.hdr). 있으면 문서 덧붙임(꼬리표 2)에 적는다.
 * @property offCanvas 캔버스 밖 픽셀이 있는지(판 3, 결정 기록 '캔버스 밖 픽셀'). 저장하는 쪽이 문서에서 정하고, 읽으면 판 3이었는지다
 *   (판 3 전의 파일은 끝 타일을 캔버스 안쪽으로 잘라 연다).
 */
data class ProjectManifest(
    val width: Int,
    val height: Int,
    val nextLayerId: Long,
    val createdMillis: Long,
    val modifiedMillis: Long,
    val layers: List<LayerEntry>,
    val colorSpace: DocumentColorSpace = DocumentColorSpace.SRGB,
    val hdr: HdrInfo? = null,
    val offCanvas: Boolean = false
) {
    init {
        require(width > 0 && height > 0) { "Canvas size must be positive: ${width}x$height" }
        require(layers.isNotEmpty()) { "Project needs at least one layer" }
    }

    /** 목록에 보일 머리말 */
    val header: ProjectHeader
        get() = ProjectHeader(width, height, createdMillis, modifiedMillis)

    /** 이 목차를 쓸 판: 캔버스 밖 픽셀이 있으면 3, 레이어 마스크·조정 레이어·그룹이 있으면 2, 없으면 1([PROJECT_FORMAT_VERSION]) */
    val formatVersion: Int
        get() = when {
            offCanvas -> 3
            layers.any { it.mask != null || it.content is AdjustmentEntry || it.content is GroupEntry || it.parent != null } -> 2
            else -> 1
        }
}

/**
 * 레이어 하나의 속성과 내용. 보정 값은 레이어 덧붙임(꼬리표 1)에, 게인맵은 덧붙임(꼬리표 2)에, 숨김은 덧붙임(꼬리표 3)에,
 * 다시 고칠 수 있는 글자는 덧붙임(꼬리표 4)에, 다시 고칠 수 있는 유동화 기록은 덧붙임(꼬리표 5)에, 틴트는 덧붙임(꼬리표 6)에,
 * 레이어 마스크는 덧붙임(꼬리표 7, 판 2)에, [피부 보정] 그룹의 기록은 덧붙임(꼬리표 9)에, 피부 색 조정 레이어의 값은 덧붙임(꼬리표 10)에 적고,
 * 없으면(보이면) 적지 않는다.
 * 그래서 숨김을 모르는 앞 판의 프로젝트는 모두 보이는 레이어로 열리고, 글자·유동화 기록을 모르는 앞 판 앱은 그 레이어를 픽셀 레이어로 연다
 * (픽셀은 늘 타일에 있다).
 */
data class LayerEntry(
    val id: Long,
    val name: String,
    val opacity: Float,
    val blendMode: BlendMode,
    val content: ContentEntry,
    val adjustments: Adjustments = Adjustments.NONE,
    val gainmap: GainmapEntry? = null,
    val visible: Boolean = true,
    val text: TextSpec? = null,
    val liquify: LiquifyEntry? = null,
    val mask: List<MaskTileEntry>? = null,
    val parent: Long? = null,
    val skin: SkinEntry? = null,
    val skinColor: SkinColor? = null
)

/**
 * [피부 보정] 그룹의 기록(document.SkinRecord, 결정 기록 '피부 보정'의 2c). 레이어 번호는 같은 목차의 레이어다.
 */
data class SkinEntry(
    val source: Long,
    val color: Long,
    val smooth: Long,
    val spots: Long,
    val faces: List<SkinFaceArea>,
    val segmented: Boolean,
    val texture: Float,
    val healed: List<SkinSpot> = emptyList()
)

/**
 * 레이어 마스크 타일 한 장(결정 기록 '피부 보정'의 1c). [file]은 tiles 폴더의 마스크 타일 파일(픽셀 하나에 1바이트)이고,
 * null이면 다 보이는 타일이다(파일 없음). 목록에 없는 자리는 가린 곳이다.
 */
data class MaskTileEntry(val column: Int, val row: Int, val file: String?)

/**
 * 레이어의 다시 고칠 수 있는 유동화 기록(document.LiquifyRecord, 결정 기록 '얼굴 인식 보정' 3단계). 유동화 전 원래 픽셀·게인맵의 타일과
 * 찾은 얼굴·값은 목차에 적고, 손으로 민 격자는 liquify 폴더의 파일 [meshFile](손으로 민 것이 없으면 null)에 둔다(목차를 작게).
 *
 * @property version 얼굴 보정 계산식의 판(LiquifyRecord.version)
 * @property base 유동화 전 원래 픽셀의 타일(문서 격자). 결과와 바뀌지 않은 타일은 같은 타일 파일을 가리킨다.
 * @property faceValues 얼굴마다의 슬라이더 값([faces]와 같은 차례)
 */
data class LiquifyEntry(
    val version: Int,
    val base: List<TileEntry>,
    val baseGainmap: GainmapEntry?,
    val meshFile: String?,
    val faces: List<FaceLandmarks>,
    val faceValues: List<FaceValues>
) {
    init {
        require(faceValues.size == faces.size) { "${faceValues.size} face values for ${faces.size} faces" }
    }
}

/** 레이어의 게인맵: 크기와, 게인맵 격자의 자리마다 어떤 타일이 있는지(Layer.gainmap) */
data class GainmapEntry(val width: Int, val height: Int, val tiles: List<TileEntry>)

/** 레이어 내용. 나중에 텍스트 같은 종류가 더해진다. */
sealed interface ContentEntry

/** 픽셀 레이어. 타일이 없는 자리는 투명이다. */
data class RasterEntry(val tiles: List<TileEntry>) : ContentEntry

/** 조정 레이어(판 2, 결정 기록 '피부 보정'의 1e). 픽셀이 없고, 보정 값은 레이어 덧붙임(꼬리표 1·6)에 있다. */
data object AdjustmentEntry : ContentEntry

/** 레이어 그룹(통과, 판 2, 결정 기록 '피부 보정'의 1g). 안의 레이어는 덧붙임 꼬리표 8로 이 그룹을 가리킨다. */
data object GroupEntry : ContentEntry

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

    /** 내용 종류: 조정 레이어(판 2) */
    private const val CONTENT_ADJUSTMENT = 2

    /** 내용 종류: 레이어 그룹(판 2) */
    private const val CONTENT_GROUP = 3

    /** 레이어 덧붙임 꼬리표: 기본 보정 값 */
    private const val EXTRA_ADJUSTMENTS = 1

    /** 레이어 덧붙임 꼬리표: 게인맵(크기와 타일 목록) */
    private const val EXTRA_GAINMAP = 2

    /** 레이어 덧붙임 꼬리표: 숨긴 레이어(내용 없음). 보이면 적지 않는다. */
    private const val EXTRA_HIDDEN = 3

    /** 레이어 덧붙임 꼬리표: 다시 고칠 수 있는 글자(사용자 피드백 8차 1-6) */
    private const val EXTRA_TEXT = 4

    /** 레이어 덧붙임 꼬리표: 다시 고칠 수 있는 유동화 기록(결정 기록 '얼굴 인식 보정' 3단계) */
    private const val EXTRA_LIQUIFY = 5

    /**
     * 레이어 덧붙임 꼬리표: 기본 보정의 틴트(실수 하나, 결정 기록 '피부 보정'의 1b). 0이면 적지 않는다.
     * 보정 값([EXTRA_ADJUSTMENTS])과 따로 두어, 틴트를 모르는 앞 판 앱도 틴트만 빼고 연다.
     */
    private const val EXTRA_TINT = 6

    /** 레이어 덧붙임 꼬리표: 레이어 마스크의 타일 목록(판 2, 결정 기록 '피부 보정'의 1c). 마스크가 없으면 적지 않는다. */
    private const val EXTRA_MASK = 7

    /** 레이어 덧붙임 꼬리표: 감싼 그룹의 레이어 번호(long, 판 2, 결정 기록 '피부 보정'의 1g). 그룹 밖이면 적지 않는다. */
    private const val EXTRA_PARENT = 8

    /** 레이어 덧붙임 꼬리표: [피부 보정] 그룹의 기록(결정 기록 '피부 보정'의 2c). 망가졌거나 더 새 판이면 기록만 버린다(그룹은 보통 그룹). */
    private const val EXTRA_SKIN = 9

    /** 레이어 덧붙임 꼬리표: 피부 색 조정 레이어의 값(결정 기록 '피부 보정'의 2c). 없으면 기본 보정 조정 레이어다. */
    private const val EXTRA_SKIN_COLOR = 10

    /** 피부 기록·피부 색 덧붙임 안의 판. 이 앱보다 새 판이면 그 덧붙임을 버린다. */
    private const val SKIN_VERSION = 1

    /** 문서 덧붙임 꼬리표: 색 공간 코드(int). sRGB면 적지 않는다. */
    private const val EXTRA_COLOR_SPACE = 1

    /** 문서 덧붙임 꼬리표: 게인맵을 읽는 값(HdrInfo) */
    private const val EXTRA_HDR = 2
    private const val TILE_FILE: Byte = 0
    private const val TILE_SOLID: Byte = 1
    private const val MASK_FULL: Byte = 0
    private const val MASK_FILE: Byte = 1

    /** [manifest]를 [output]에 쓴다. [output]은 닫지 않는다. */
    fun write(manifest: ProjectManifest, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(manifest.formatVersion)
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
                AdjustmentEntry -> out.writeInt(CONTENT_ADJUSTMENT)
                GroupEntry -> out.writeInt(CONTENT_GROUP)
            }
            // 레이어 덧붙임: 보정 값·틴트·게인맵(있을 때만)
            val extras = ArrayList<Pair<Int, ByteArray>>()
            if (!layer.adjustments.copy(tint = 0f).isIdentity) extras += EXTRA_ADJUSTMENTS to adjustmentBytes(layer.adjustments)
            if (layer.adjustments.tint != 0f) extras += EXTRA_TINT to floatBytes(layer.adjustments.tint)
            layer.gainmap?.let { extras += EXTRA_GAINMAP to gainmapBytes(it) }
            if (!layer.visible) extras += EXTRA_HIDDEN to ByteArray(0)
            layer.text?.let { extras += EXTRA_TEXT to textBytes(it) }
            layer.liquify?.let { extras += EXTRA_LIQUIFY to liquifyBytes(it) }
            layer.mask?.let { extras += EXTRA_MASK to maskBytes(it) }
            layer.parent?.let { extras += EXTRA_PARENT to ByteArrayOutputStream().also { out -> DataOutputStream(out).writeLong(it) }.toByteArray() }
            layer.skin?.let { extras += EXTRA_SKIN to skinBytes(it) }
            layer.skinColor?.let { extras += EXTRA_SKIN_COLOR to skinColorBytes(it) }
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
                    CONTENT_ADJUSTMENT -> AdjustmentEntry
                    CONTENT_GROUP -> GroupEntry
                    else -> throw UnreadableProjectException("Unknown layer content $type")
                }
                var adjustments = Adjustments.NONE
                var tint = 0f
                var gainmap: GainmapEntry? = null
                var visible = true
                var text: TextSpec? = null
                var liquify: LiquifyEntry? = null
                var mask: List<MaskTileEntry>? = null
                var parent: Long? = null
                var skin: SkinEntry? = null
                var skinColor: SkinColor? = null
                data.readExtras { tag, bytes ->
                    when (tag) {
                        EXTRA_ADJUSTMENTS -> adjustments = adjustmentsOf(bytes)
                        EXTRA_GAINMAP -> gainmap = gainmapOf(bytes)
                        EXTRA_HIDDEN -> visible = false
                        EXTRA_TEXT -> text = textOf(bytes)
                        EXTRA_LIQUIFY -> liquify = liquifyOrNull(bytes)
                        EXTRA_TINT -> tint = DataInputStream(ByteArrayInputStream(bytes)).readFloat()
                        EXTRA_MASK -> mask = maskOf(bytes)
                        EXTRA_PARENT -> parent = DataInputStream(ByteArrayInputStream(bytes)).readLong()
                        EXTRA_SKIN -> skin = skinOrNull(bytes)
                        EXTRA_SKIN_COLOR -> skinColor = skinColorOrNull(bytes)
                    }
                }
                LayerEntry(id, name, opacity, blendMode, content, adjustments.copy(tint = tint), gainmap, visible, text, liquify, mask, parent, skin, skinColor)
            }
            var colorSpace = DocumentColorSpace.SRGB
            var hdr: HdrInfo? = null
            data.readExtras { tag, bytes ->
                when (tag) {
                    EXTRA_COLOR_SPACE -> colorSpace = colorSpaceOf(DataInputStream(ByteArrayInputStream(bytes)).readInt())
                    EXTRA_HDR -> hdr = hdrOf(bytes)
                }
            }
            return ProjectManifest(
                header.width, header.height, nextLayerId, header.createdMillis, header.modifiedMillis, layers, colorSpace, hdr, offCanvas = header.version >= 3
            )
        } catch (e: IllegalArgumentException) {
            throw UnreadableProjectException("Broken project: ${e.message}")
        }
    }

    /** [input]에서 머리말만 읽는다. 홈 목록을 만들 때 쓴다. */
    fun readHeader(input: InputStream): ProjectHeader {
        val header = readHeaderFields(DataInputStream(input))
        return ProjectHeader(header.width, header.height, header.createdMillis, header.modifiedMillis)
    }

    private class HeaderFields(val version: Int, val width: Int, val height: Int, val nextLayerId: Long, val createdMillis: Long, val modifiedMillis: Long)

    private fun readHeaderFields(data: DataInputStream): HeaderFields {
        if (data.readInt() != MAGIC) throw UnreadableProjectException("Not a Stratum project")
        val version = data.readInt()
        if (version !in 1..PROJECT_FORMAT_VERSION) throw UnreadableProjectException("Unsupported project version $version")
        val width = data.readInt()
        val height = data.readInt()
        if (width <= 0 || height <= 0) throw UnreadableProjectException("Broken canvas size ${width}x$height")
        return HeaderFields(version, width, height, data.readLong(), data.readLong(), data.readLong())
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

    /** 다시 고칠 수 있는 글자: 글자, 상자 왼쪽 위(x, y), 크기, 색, 상자 폭·높이 */
    private fun textBytes(text: TextSpec): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeUTF(text.text)
            out.writeFloat(text.x)
            out.writeFloat(text.y)
            out.writeFloat(text.style.size)
            out.writeInt(text.style.color)
            out.writeFloat(text.width)
            out.writeFloat(text.height)
            out.flush()
        }.toByteArray()

    private fun textOf(bytes: ByteArray): TextSpec {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val text = data.readUTF()
        val x = data.readFloat()
        val y = data.readFloat()
        val style = TextStyle(size = data.readFloat(), color = data.readInt())
        return TextSpec(text, x, y, style, width = data.readFloat(), height = data.readFloat())
    }

    /**
     * 유동화 기록: 계산식 판, 원래 픽셀의 타일 목록, 원래 게인맵(있음 표시와 게인맵), 격자 파일 이름(빈 글자는 없음),
     * 얼굴 수와 얼굴마다 점수·점 좌표 수·좌표들·0이 아닌 슬라이더 수·(저장 번호, 값)들
     */
    private fun liquifyBytes(liquify: LiquifyEntry): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeInt(liquify.version)
            writeTiles(out, liquify.base)
            out.writeBoolean(liquify.baseGainmap != null)
            liquify.baseGainmap?.let { out.write(gainmapBytes(it)) }
            out.writeUTF(liquify.meshFile.orEmpty())
            out.writeInt(liquify.faces.size)
            liquify.faces.forEachIndexed { index, face ->
                out.writeFloat(face.presence)
                out.writeInt(face.points.size)
                for (value in face.points) out.writeFloat(value)
                val values = liquify.faceValues[index].nonZero()
                out.writeInt(values.size)
                for ((code, value) in values) {
                    out.writeInt(code)
                    out.writeFloat(value)
                }
            }
            out.flush()
        }.toByteArray()

    /**
     * 유동화 기록을 읽는다. 망가졌으면 null이다: 기록만 버리고(결과 픽셀은 타일에 있다) 프로젝트는 연다(계획 '저장 방식').
     * 점이 숫자가 아닌 얼굴, 이 앱이 모르는 슬라이더(더 새 앱이 더한 것)나 숫자가 아닌 슬라이더 값이 있어도 기록을 버린다.
     * 그 값을 빼고 이어서 고치면 다시 열 때 그 슬라이더로 고친 모양이 조용히 사라지기 때문이다(결과 픽셀은 그대로 남는다).
     */
    private fun liquifyOrNull(bytes: ByteArray): LiquifyEntry? =
        try {
            val data = DataInputStream(ByteArrayInputStream(bytes))
            val version = data.readInt()
            val base = readTiles(data)
            val baseGainmap = if (data.readBoolean()) {
                val width = data.readInt()
                val height = data.readInt()
                if (width <= 0 || height <= 0) throw UnreadableProjectException("Broken gainmap size ${width}x$height")
                GainmapEntry(width, height, readTiles(data))
            } else {
                null
            }
            val meshFile = data.readUTF().ifEmpty { null }
            val faces = ArrayList<FaceLandmarks>()
            val values = ArrayList<FaceValues>()
            repeat(data.readCount()) {
                val presence = data.readFloat()
                val count = data.readInt()
                if (count != FaceModel.LANDMARK_COUNT * 2) throw UnreadableProjectException("Broken face points $count")
                val points = FloatArray(count) { data.readFloat() }
                val face = FaceLandmarks(points, presence)
                if (!face.isFinite) throw UnreadableProjectException("Broken face points")
                val pairs = List(data.readCount()) { data.readInt() to data.readFloat() }
                if (pairs.any { (code, value) -> FaceSlider.ofCode(code) == null || !value.isFinite() }) throw UnreadableProjectException("Unknown face value")
                faces += face
                values += FaceValues.of(pairs)
            }
            LiquifyEntry(version, base, baseGainmap, meshFile, faces, values)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * 피부 기록: 판, 원본·색 보정·피부 정돈·잡티 제거 레이어 번호, 분할했는지, 질감 유지, 얼굴 수와 얼굴마다 자리(가운데 x·y, 반지름, 길이 단위),
     * 지운 잡티 수와 자리마다 x·y·반지름(5단계에서 끝에 더함: 그 앞 판의 파일은 여기서 끝나 지운 잡티가 없다)
     */
    private fun skinBytes(skin: SkinEntry): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeInt(SKIN_VERSION)
            out.writeLong(skin.source)
            out.writeLong(skin.color)
            out.writeLong(skin.smooth)
            out.writeLong(skin.spots)
            out.writeBoolean(skin.segmented)
            out.writeFloat(skin.texture)
            out.writeInt(skin.faces.size)
            for (face in skin.faces) writeFaceArea(out, face)
            out.writeInt(skin.healed.size)
            for (spot in skin.healed) {
                out.writeFloat(spot.x)
                out.writeFloat(spot.y)
                out.writeFloat(spot.radius)
            }
            out.flush()
        }.toByteArray()

    /** 피부 기록을 읽는다. 망가졌거나 이 앱보다 새 판이면 null이다(기록만 버리고 그룹은 보통 그룹으로 연다). */
    private fun skinOrNull(bytes: ByteArray): SkinEntry? =
        try {
            val data = DataInputStream(ByteArrayInputStream(bytes))
            if (data.readInt() != SKIN_VERSION) throw UnreadableProjectException("Unknown skin record")
            val source = data.readLong()
            val color = data.readLong()
            val smooth = data.readLong()
            val spots = data.readLong()
            val segmented = data.readBoolean()
            val texture = data.readFloat()
            if (!(texture in 0f..1f)) throw UnreadableProjectException("Broken texture $texture")
            // 수는 남은 바이트로 거른다(망가진 큰 수로 큰 목록을 만들지 않게)
            val faces = List(data.readCount().also { if (it > data.available() / 16) throw UnreadableProjectException("Broken face count $it") }) { readFaceArea(data) }
            val healed = if (data.available() > 0) {
                List(data.readCount().also { if (it > data.available() / 12) throw UnreadableProjectException("Broken spot count $it") }) {
                    SkinSpot(data.readFloat(), data.readFloat(), data.readFloat())
                }
            } else {
                emptyList()
            }
            SkinEntry(source, color, smooth, spots, faces, segmented, texture, healed)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * 피부 색: 판, 부분 붉은기, 전체 붉은기, 톤 균일화, 얼굴 수와 얼굴마다 자리, 피부 통계(가운데 L·색상·채도, 보통 채도 직선 둘, 퍼짐 둘),
     * 기준이 있는지와 그 색상·채도
     */
    private fun skinColorBytes(color: SkinColor): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeInt(SKIN_VERSION)
            out.writeFloat(color.local)
            out.writeFloat(color.global)
            out.writeFloat(color.even)
            out.writeInt(color.faces.size)
            for (face in color.faces) {
                writeFaceArea(out, face.area)
                val tone = face.tone
                for (value in floatArrayOf(tone.lightness, tone.hue, tone.chroma, tone.chromaBase, tone.chromaSlope, tone.hueSpread, tone.chromaSpread)) {
                    out.writeFloat(value)
                }
                val reference = face.reference
                out.writeBoolean(reference != null)
                if (reference != null) {
                    out.writeFloat(reference.hue)
                    out.writeFloat(reference.chroma)
                }
            }
            out.flush()
        }.toByteArray()

    /** 피부 보정의 얼굴 자리: 가운데 x·y, 반지름, 길이 단위 */
    private fun writeFaceArea(out: DataOutputStream, area: SkinFaceArea) {
        out.writeFloat(area.centerX)
        out.writeFloat(area.centerY)
        out.writeFloat(area.radius)
        out.writeFloat(area.unit)
    }

    /** [writeFaceArea]로 쓴 것을 읽는다. 망가졌으면 IllegalArgumentException(SkinFaceArea가 거름)이다. */
    private fun readFaceArea(data: DataInputStream): SkinFaceArea = SkinFaceArea(data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat())

    /** 피부 색을 읽는다. 망가졌거나 이 앱보다 새 판이면 null이다(바꾸는 것이 없는 피부 색으로 연다). */
    private fun skinColorOrNull(bytes: ByteArray): SkinColor? =
        try {
            val data = DataInputStream(ByteArrayInputStream(bytes))
            if (data.readInt() != SKIN_VERSION) throw UnreadableProjectException("Unknown skin color")
            val local = data.readFloat()
            val global = data.readFloat()
            val even = data.readFloat()
            // 수는 남은 바이트로 거른다(얼굴 하나는 적어도 45바이트, 망가진 큰 수로 큰 목록을 만들지 않게)
            val count = data.readCount().also { if (it > data.available() / 45) throw UnreadableProjectException("Broken face count $it") }
            val faces = List(count) {
                val area = readFaceArea(data)
                val tone = SkinTone(data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat())
                val reference = if (data.readBoolean()) SkinReference(data.readFloat(), data.readFloat()) else null
                SkinFaceTone(area, tone, reference)
            }
            SkinColor(local, global, even, faces)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
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

    /** 마스크 타일 목록: 개수와 타일마다 자리, 종류(0 다 보임, 1 파일)와 파일 이름 */
    private fun maskBytes(tiles: List<MaskTileEntry>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeInt(tiles.size)
            for (tile in tiles) {
                out.writeInt(tile.column)
                out.writeInt(tile.row)
                if (tile.file == null) {
                    out.writeByte(MASK_FULL.toInt())
                } else {
                    out.writeByte(MASK_FILE.toInt())
                    out.writeUTF(tile.file)
                }
            }
            out.flush()
        }.toByteArray()

    private fun maskOf(bytes: ByteArray): List<MaskTileEntry> {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        return List(data.readCount()) {
            val column = data.readInt()
            val row = data.readInt()
            when (val kind = data.readByte()) {
                MASK_FULL -> MaskTileEntry(column, row, null)
                MASK_FILE -> MaskTileEntry(column, row, data.readUTF())
                else -> throw UnreadableProjectException("Unknown mask tile kind $kind")
            }
        }
    }

    /** 실수 하나 */
    private fun floatBytes(value: Float): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).writeFloat(value) }.toByteArray()

    /** 보정 값: 실수 7개(노출, 색온도, 그림자, 하이라이트, 밝기, 대비, 채도), 곡선 점 수와 점들(x, y). 틴트는 따로 적는다([EXTRA_TINT]). */
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

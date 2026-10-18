package com.kiln.stratum.engine.project

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.DocumentColorSpace
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectManifestTest {

    private val manifest = ProjectManifest(
        width = 4000,
        height = 3000,
        nextLayerId = 3,
        createdMillis = 1_792_000_000_000,
        modifiedMillis = 1_792_000_123_456,
        layers = listOf(
            LayerEntry(
                id = 0,
                name = "배경",
                opacity = 1f,
                blendMode = BlendMode.NORMAL,
                content = RasterEntry(
                    listOf(
                        TileEntry(0, 0, TileRef.File("a.tile")),
                        TileEntry(1, 0, TileRef.File("a.tile")),
                        TileEntry(15, 11, TileRef.Solid(0xFFFFFFFF.toInt()))
                    )
                )
            ),
            LayerEntry(2, "Layer 2", 0.35f, BlendMode.OVERLAY, RasterEntry(emptyList()))
        )
    )

    private fun bytesOf(manifest: ProjectManifest): ByteArray =
        ByteArrayOutputStream().also { ProjectManifestCodec.write(manifest, it) }.toByteArray()

    @Test
    fun writeThenRead_givesTheSameManifest() {
        val read = ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(manifest)))

        assertEquals(manifest, read)
    }

    @Test
    fun header_isReadWithoutTheLayers() {
        val header = ProjectManifestCodec.readHeader(ByteArrayInputStream(bytesOf(manifest)))

        assertEquals(ProjectHeader(4000, 3000, 1_792_000_000_000, 1_792_000_123_456), header)
        assertEquals(manifest.header, header)
    }

    @Test
    fun blendModes_useFixedCodes() {
        // 저장한 파일의 코드는 enum 순서가 바뀌어도 그대로여야 한다
        assertEquals(0, ProjectManifestCodec.blendCode(BlendMode.NORMAL))
        assertEquals(1, ProjectManifestCodec.blendCode(BlendMode.MULTIPLY))
        assertEquals(2, ProjectManifestCodec.blendCode(BlendMode.SCREEN))
        assertEquals(3, ProjectManifestCodec.blendCode(BlendMode.OVERLAY))
        for (mode in BlendMode.entries) assertEquals(mode, ProjectManifestCodec.blendOf(ProjectManifestCodec.blendCode(mode)))
    }

    @Test
    fun unknownExtras_areSkipped() {
        // 같은 판에서 나중에 더할 덧붙임(꼬리표 7, 3바이트)을 레이어와 문서 끝에 직접 넣어 본다
        val single = manifest.copy(layers = listOf(LayerEntry(0, "A", 1f, BlendMode.NORMAL, RasterEntry(emptyList()))))
        val bytes = ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.writeInt(0x5354524D)
            out.writeInt(PROJECT_FORMAT_VERSION)
            out.writeInt(4000)
            out.writeInt(3000)
            out.writeLong(3)
            out.writeLong(1_792_000_000_000)
            out.writeLong(1_792_000_123_456)
            out.writeInt(1)
            out.writeLong(0)
            out.writeUTF("A")
            out.writeFloat(1f)
            out.writeInt(0)
            out.writeInt(1)
            out.writeInt(0)
            out.writeInt(1)
            out.writeInt(7)
            out.writeInt(3)
            out.write(byteArrayOf(1, 2, 3))
            out.writeInt(1)
            out.writeInt(9)
            out.writeInt(0)
        }.toByteArray()

        assertEquals(single, ProjectManifestCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun otherFiles_andNewerVersions_areRejected() {
        assertThrows(UnreadableProjectException::class.java) {
            ProjectManifestCodec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 1)))
        }
        val newer = bytesOf(manifest).also { it[7] = (PROJECT_FORMAT_VERSION + 1).toByte() }
        assertThrows(UnreadableProjectException::class.java) { ProjectManifestCodec.read(ByteArrayInputStream(newer)) }
        // 쓰다 만 파일(끝이 잘림)도 읽지 않는다
        val cut = bytesOf(manifest).copyOf(40)
        assertThrows(EOFException::class.java) { ProjectManifestCodec.read(ByteArrayInputStream(cut)) }
    }

    @Test
    fun adjustments_roundTrip_andNoneWritesNothingExtra() {
        val curve = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.4f, 0.5f), CurvePoint(1f, 0.95f)))
        val adjusted = manifest.copy(
            layers = listOf(manifest.layers[0].copy(adjustments = Adjustments(exposure = -0.5f, saturation = 0.3f, curve = curve)), manifest.layers[1])
        )

        assertEquals(adjusted, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(adjusted))))
        // 보정이 없는 레이어는 덧붙임을 쓰지 않아 크기가 그대로다
        assertTrue(bytesOf(adjusted).size > bytesOf(manifest).size)
    }

    @Test
    fun colorSpace_roundTrips_andSrgbWritesNothingExtra() {
        val wide = manifest.copy(colorSpace = DocumentColorSpace.DISPLAY_P3)

        assertEquals(wide, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(wide))))
        assertEquals(DocumentColorSpace.SRGB, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(manifest))).colorSpace)
        // 문서 덧붙임 하나(꼬리표·길이·코드)만큼 길다
        assertEquals(bytesOf(manifest).size + 12, bytesOf(wide).size)
    }
}

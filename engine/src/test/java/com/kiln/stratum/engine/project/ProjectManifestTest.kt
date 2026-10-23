package com.kiln.stratum.engine.project

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.GainRgb
import com.kiln.stratum.engine.document.HdrInfo
import com.kiln.stratum.engine.document.HdrPrimaries
import com.kiln.stratum.engine.face.FaceSlider
import com.kiln.stratum.engine.face.FaceValues
import com.kiln.stratum.engine.face.TestFace
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinFaceTone
import com.kiln.stratum.engine.skin.SkinReference
import com.kiln.stratum.engine.skin.SkinTone
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // 같은 판에서 나중에 더할 덧붙임(꼬리표 99, 3바이트)을 레이어와 문서 끝에 직접 넣어 본다
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
            out.writeInt(99)
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
    fun tint_roundTrips_inItsOwnExtra() {
        fun withTop(adjustments: Adjustments) = manifest.copy(layers = listOf(manifest.layers[0].copy(adjustments = adjustments), manifest.layers[1]))
        val tinted = withTop(Adjustments(tint = -0.4f))
        val both = withTop(Adjustments(exposure = 0.2f, tint = 0.3f))
        val exposureOnly = withTop(Adjustments(exposure = 0.2f))

        assertEquals(tinted, read(bytesOf(tinted)))
        assertEquals(both, read(bytesOf(both)))
        // 틴트만 바꾼 레이어는 틴트 덧붙임 하나(꼬리표·길이·실수 하나)만 더 쓴다
        assertEquals(bytesOf(manifest).size + 12, bytesOf(tinted).size)
        // 보정 값 덧붙임은 틴트가 없을 때와 같아, 틴트를 모르는 앞 판 앱도 나머지 보정을 그대로 읽는다
        assertEquals(bytesOf(exposureOnly).size + 12, bytesOf(both).size)
    }

    @Test
    fun mask_roundTrips_andOnlyProjectsWithMasksUseVersion2() {
        val tiles = listOf(MaskTileEntry(0, 0, null), MaskTileEntry(1, 0, "m.mask"), MaskTileEntry(15, 11, "m.mask"))
        val masked = manifest.copy(layers = listOf(manifest.layers[0].copy(mask = tiles), manifest.layers[1]))
        val bytes = bytesOf(masked)

        assertEquals(masked, read(bytes))
        // 판(머리말 둘째 int): 마스크가 있으면 2, 없으면 1이라 앞 판 앱도 마스크 없는 프로젝트를 그대로 연다
        assertEquals(2, ByteBuffer.wrap(bytes, 4, 4).int)
        assertEquals(1, ByteBuffer.wrap(bytesOf(manifest), 4, 4).int)
        // 다 가린 마스크(타일 없음)도 마스크다
        val hidden = manifest.copy(layers = listOf(manifest.layers[0].copy(mask = emptyList()), manifest.layers[1]))
        assertEquals(hidden, read(bytesOf(hidden)))
    }

    @Test
    fun adjustmentLayer_roundTrips_inVersion2() {
        val adjust = LayerEntry(3, "Adjust", 0.6f, BlendMode.MULTIPLY, AdjustmentEntry, Adjustments(saturation = -0.5f, tint = 0.2f))
        val withAdjustment = manifest.copy(layers = manifest.layers + adjust, nextLayerId = 4)
        val bytes = bytesOf(withAdjustment)

        assertEquals(withAdjustment, read(bytes))
        // 앞 판 앱은 조정 레이어를 모르므로 판 2로 쓴다
        assertEquals(2, ByteBuffer.wrap(bytes, 4, 4).int)
    }

    @Test
    fun groups_roundTrip_inVersion2() {
        val group = LayerEntry(3, "Group", 0.5f, BlendMode.NORMAL, GroupEntry, visible = false)
        val child = manifest.layers[1].copy(parent = 3)
        val grouped = manifest.copy(layers = listOf(manifest.layers[0], child, group), nextLayerId = 4)
        val bytes = bytesOf(grouped)

        assertEquals(grouped, read(bytes))
        assertEquals(2, ByteBuffer.wrap(bytes, 4, 4).int)
    }

    @Test
    fun skinGroupRecord_andSkinColor_roundTrip_inVersion2() {
        val area = SkinFaceArea(120.5f, 340.25f, 90f, 41f)
        val skin = SkinEntry(source = 0, color = 4, smooth = 5, spots = 6, faces = listOf(area), segmented = false, texture = 0.7f)
        // 얼굴 둘: 하나는 자동 기준, 하나는 스포이드로 고른 기준(결정 기록 '피부 보정'의 3단계)
        val tone = SkinTone(0.7f, 0.85f, 0.055f, 0.054f, 0.12f, 0.04f, 0.006f)
        val faces = listOf(SkinFaceTone(area, tone), SkinFaceTone(area.copy(centerX = 600f), tone.copy(hue = 0.7f), SkinReference(0.82f, 0.06f)))
        val color = LayerEntry(4, "Color", 1f, BlendMode.NORMAL, AdjustmentEntry, parent = 3, skinColor = SkinColor(0.2f, 0.5f, 1f, faces))
        val group = LayerEntry(3, "Skin", 1f, BlendMode.NORMAL, GroupEntry, skin = skin)
        val withSkin = manifest.copy(layers = listOf(manifest.layers[0], color, group), nextLayerId = 7)
        val bytes = bytesOf(withSkin)

        assertEquals(withSkin, read(bytes))
        assertEquals(2, ByteBuffer.wrap(bytes, 4, 4).int)
        // 바꾸는 것이 없는 피부 색도 피부 색 종류로 남는다(기본 보정 조정 레이어와 구별)
        val zero = withSkin.copy(layers = listOf(manifest.layers[0], color.copy(skinColor = SkinColor()), group))
        assertEquals(SkinColor(), read(bytesOf(zero)).layers[1].skinColor)
    }

    @Test
    fun brokenOrNewerSkinRecords_areDropped_andTheProjectStillOpens() {
        val newer = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(2); writeLong(0); flush() } }.toByteArray()
        for (payload in listOf(ByteArray(0), ByteArray(3) { 7 }, newer)) {
            val skin = read(withTopLayerExtra(9, payload))
            assertNull(skin.layers[1].skin)
            assertEquals(manifest, skin)
            val color = read(withTopLayerExtra(10, payload))
            assertNull(color.layers[1].skinColor)
            assertEquals(manifest, color)
        }
    }

    @Test
    fun gainmapAndHdrValues_roundTrip() {
        val hdr = HdrInfo(
            ratioMin = GainRgb(1f),
            ratioMax = GainRgb(4f, 3.5f, 3f),
            gamma = GainRgb(1f),
            epsilonSdr = GainRgb(0.015625f),
            epsilonHdr = GainRgb(0.015625f),
            displayRatioForFullHdr = 4f,
            minDisplayRatioForHdrTransition = 1f,
            singleChannel = true,
            alternatePrimaries = HdrPrimaries.BT2020
        )
        val gainmap = GainmapEntry(1000, 750, listOf(TileEntry(0, 0, TileRef.File("g.tile")), TileEntry(3, 2, TileRef.Solid(0xFF000000.toInt()))))
        val photo = manifest.copy(layers = listOf(manifest.layers[0].copy(gainmap = gainmap), manifest.layers[1]), hdr = hdr)

        assertEquals(photo, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(photo))))
        assertEquals(null, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(manifest))).hdr)
    }

    @Test
    fun hiddenLayer_roundTrips_andShownLayersWriteNothingExtra() {
        val hidden = manifest.copy(layers = listOf(manifest.layers[0], manifest.layers[1].copy(visible = false)))

        assertEquals(hidden, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(hidden))))
        // 레이어 덧붙임 하나(꼬리표·길이, 내용 없음)만큼 길다. 숨김을 모르는 앞 프로젝트는 모두 보이는 레이어로 열린다.
        assertEquals(bytesOf(manifest).size + 8, bytesOf(hidden).size)
        assertTrue(ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(manifest))).layers.all { it.visible })
    }

    @Test
    fun colorSpace_roundTrips_andSrgbWritesNothingExtra() {
        val wide = manifest.copy(colorSpace = DocumentColorSpace.DISPLAY_P3)

        assertEquals(wide, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(wide))))
        assertEquals(DocumentColorSpace.SRGB, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(manifest))).colorSpace)
        // 문서 덧붙임 하나(꼬리표·길이·코드)만큼 길다
        assertEquals(bytesOf(manifest).size + 12, bytesOf(wide).size)
    }

    @Test
    fun textLayer_roundTrips_andPixelLayersWriteNothingExtra() {
        // 다시 고칠 수 있는 글자(사용자 피드백 8차 1-6)는 레이어 덧붙임(꼬리표 4)이다. 픽셀은 늘 타일에 있어 앞 판 앱은 픽셀 레이어로 연다.
        val spec = TextSpec("안녕\nStratum", x = 120.5f, y = -8f, style = TextStyle(size = 96f, color = 0xFF2F6FE4.toInt()), width = 410f, height = 230f)
        val text = manifest.copy(layers = listOf(manifest.layers[0], manifest.layers[1].copy(text = spec)))

        assertEquals(text, ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(text))))
        assertTrue(ProjectManifestCodec.read(ByteArrayInputStream(bytesOf(manifest))).layers.all { it.text == null })
    }

    private val face = TestFace(eyeX = 1800f, eyeY = 1200f, eyeDistance = 300f, angle = 0.2f).landmarks()

    /** 유동화 기록(결정 기록 '얼굴 인식 보정' 3단계): 원래 픽셀의 타일, 원래 게인맵, 격자 파일, 두 얼굴과 그 값 */
    private val liquify = LiquifyEntry(
        version = 1,
        base = listOf(TileEntry(0, 0, TileRef.File("base.tile")), TileEntry(1, 0, TileRef.File("a.tile")), TileEntry(2, 0, TileRef.Solid(0xFF102030.toInt()))),
        baseGainmap = GainmapEntry(1000, 750, listOf(TileEntry(0, 0, TileRef.File("g.tile")))),
        meshFile = "m.mesh",
        faces = listOf(face, TestFace(eyeX = 600f, eyeY = 900f, eyeDistance = 120f).landmarks()),
        faceValues = listOf(FaceValues.ZERO.with(FaceSlider.EYE_SIZE, 0.35f).with(FaceSlider.FACE_WIDTH, -1f), FaceValues.ZERO)
    )

    private fun read(bytes: ByteArray): ProjectManifest = ProjectManifestCodec.read(ByteArrayInputStream(bytes))

    @Test
    fun liquifyRecord_roundTrips_andOtherLayersWriteNothingExtra() {
        val edited = manifest.copy(layers = listOf(manifest.layers[0], manifest.layers[1].copy(liquify = liquify)))
        // 게인맵·격자·얼굴이 없는 기록(손으로 민 것만 있거나, 손으로 민 것 없이 얼굴만)도 그대로다
        val plain = manifest.copy(
            layers = listOf(manifest.layers[0].copy(liquify = liquify.copy(baseGainmap = null, meshFile = null)), manifest.layers[1].copy(liquify = liquify.copy(faces = emptyList(), faceValues = emptyList())))
        )

        assertEquals(edited, read(bytesOf(edited)))
        assertEquals(plain, read(bytesOf(plain)))
        assertTrue(read(bytesOf(manifest)).layers.all { it.liquify == null })
    }

    /** [manifest]의 맨 위 레이어(덧붙임 없음)에 꼬리표 [tag] 덧붙임 [payload]를 단 목차 바이트 */
    private fun withTopLayerExtra(tag: Int, payload: ByteArray): ByteArray {
        val bytes = bytesOf(manifest)
        return ByteArrayOutputStream().also { buffer ->
            // 끝의 8바이트는 맨 위 레이어의 덧붙임 수(0)와 문서 덧붙임 수(0)다
            buffer.write(bytes, 0, bytes.size - 8)
            DataOutputStream(buffer).apply {
                writeInt(1)
                writeInt(tag)
                writeInt(payload.size)
                write(payload)
                writeInt(0)
                flush()
            }
        }.toByteArray()
    }

    /** 얼굴 하나인 유동화 기록 바이트(손으로 만듦): 점 좌표 [pointCount]개, 슬라이더 값들 [values](저장 번호, 값) */
    private fun liquifyPayload(pointCount: Int = face.points.size, values: List<Pair<Int, Float>>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                writeInt(1)
                writeInt(0)
                writeBoolean(false)
                writeUTF("")
                writeInt(1)
                writeFloat(face.presence)
                writeInt(pointCount)
                repeat(pointCount) { writeFloat(face.points[it % face.points.size]) }
                writeInt(values.size)
                for ((code, value) in values) {
                    writeInt(code)
                    writeFloat(value)
                }
                flush()
            }
        }.toByteArray()

    @Test
    fun brokenLiquifyRecord_isDropped_andTheProjectStillOpens() {
        // 기록만 버리고(결과 픽셀은 타일에 있다) 프로젝트는 연다
        for (payload in listOf(ByteArray(0), ByteArray(3) { 7 }, liquifyPayload(pointCount = 10, values = emptyList()))) {
            val read = read(withTopLayerExtra(5, payload))

            assertNull(read.layers[1].liquify)
            assertEquals(manifest, read)
        }
    }

    @Test
    fun liquifyValues_ofSlidersThisAppKnows_areRead() {
        val payload = liquifyPayload(values = listOf(FaceSlider.NOSE_WIDTH.code to 0.5f, FaceSlider.BRIDGE_WIDTH.code to -0.25f))

        val read = read(withTopLayerExtra(5, payload)).layers[1].liquify

        assertEquals(listOf(face), read?.faces)
        assertEquals(listOf(FaceValues.ZERO.with(FaceSlider.NOSE_WIDTH, 0.5f).with(FaceSlider.BRIDGE_WIDTH, -0.25f)), read?.faceValues)
    }

    @Test
    fun liquifyValues_ofSlidersThisAppDoesNotKnow_orNotNumbers_dropTheRecord() {
        // 그 값을 빼고 이어서 고치면 그 슬라이더로 고친 모양이 조용히 사라지므로, 기록을 버리고 결과 픽셀만 남긴다
        val unknown = liquifyPayload(values = listOf(FaceSlider.NOSE_WIDTH.code to 0.5f, 99 to 0.25f))
        val notANumber = liquifyPayload(values = listOf(FaceSlider.NOSE_WIDTH.code to 0.5f, FaceSlider.EYE_SIZE.code to Float.NaN))

        for (payload in listOf(unknown, notANumber)) {
            val read = read(withTopLayerExtra(5, payload))

            assertNull(read.layers[1].liquify)
            assertEquals(manifest, read)
        }
    }
}

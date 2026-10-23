package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.CreateSkinRetouch
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.HealSkinSpots
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.RebuildSkinRetouch
import com.kiln.stratum.engine.platform.SkinLayerNames
import com.kiln.stratum.engine.platform.SkinMasker
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.skin.NoSpotsException
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 잡티 자동 제거(결정 기록 '피부 보정'의 5단계): 뾰루지는 잡티 제거 레이어에 지우고, 진한 점은 남기고, 피부 정돈을 다시 만들고, 다시 만들기가 기록한 잡티를
 * 다시 지운다. 시험 사진은 [SkinSpotPhoto]이고, 그 피부색 타원을 얼굴·피부로 보는 가짜 모델([FakeFaceModel]·[FakeSkinModel])로 진짜 찾는 순서를 거친다.
 */
@RunWith(AndroidJUnit4::class)
class HealSkinSpotsTest {

    private val finder = FaceFinder(FakeFaceModel())
    private val masker = SkinMasker(FakeSkinModel())
    private val names = SkinLayerNames("Skin retouch", "Skin color", "Smoothing", "Blemishes")

    private val skin = SkinSpotPhoto.SKIN

    private fun created(): Document = CreateSkinRetouch(LayerId(0), names, finder, masker).applyTo(SkinSpotPhoto.document())

    private val Document.group get() = layers.last()

    private fun Document.spotsPixel(x: Int, y: Int): Int {
        val spots = layers[indexOfLayer(checkNotNull(group.skin).spots)]
        return (spots.content as RasterContent).raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0
    }

    @Test
    fun theSpotsAreInsideTheSkinMask() {
        val document = created()
        val mask = checkNotNull(document.layers[document.indexOfLayer(checkNotNull(document.group.skin).color)].mask)
        for ((x, y) in PIMPLES) assertTrue("pimple ($x, $y): ${mask.alphaAt(x.toInt() + 6, y.toInt())}", mask.alphaAt(x.toInt() + 6, y.toInt()) > 220)
    }

    @Test
    fun heal_removesThePimples_keepsTheMole_andRebuildsTheSmoothing() {
        val document = created()
        val group = document.group.id

        val healed = HealSkinSpots(group).applyTo(document)

        // 원본은 그대로이고, 잡티 제거 레이어에 뾰루지 자리만 둘레 피부색으로 그렸다
        assertSame(document.layers[0], healed.layers[0])
        for ((x, y) in PIMPLES) {
            val fixed = healed.spotsPixel(x.toInt(), y.toInt())
            assertEquals("pimple ($x, $y)", 255, Color.alpha(fixed))
            assertTrue("pimple ($x, $y) ${Integer.toHexString(fixed)}", abs(Color.green(fixed) - skin[1]) <= 12 && abs(Color.red(fixed) - skin[0]) <= 12)
        }
        assertEquals(0, Color.alpha(healed.spotsPixel(MOLE.first.toInt(), MOLE.second.toInt())))
        // 기록: 지운 두 자리와 결과(남긴 점 수는 피부 마스크가 점을 넣었는지에 따른다)
        val record = checkNotNull(healed.group.skin)
        assertEquals(2, record.healed.size)
        for ((x, y) in PIMPLES) assertTrue("recorded ($x, $y)", record.healed.any { hypot(it.x - x, it.y - y) < 4f })
        assertEquals(2, checkNotNull(record.healReport).healed)
        // 피부 정돈은 지운 그림으로 다시 만들어 낡지 않았다
        assertFalse(record.isStale(healed))
        val smooth = healed.layers[healed.indexOfLayer(record.smooth)]
        val before = document.layers[document.indexOfLayer(record.smooth)]
        assertTrue((smooth.content as RasterContent).raster !== (before.content as RasterContent).raster)
    }

    @Test
    fun healingAgain_findsNothingNew() {
        val document = created()
        val healed = HealSkinSpots(document.group.id).applyTo(document)

        val error = assertThrows(NoSpotsException::class.java) { HealSkinSpots(healed.group.id).applyTo(healed) }
        assertEquals(checkNotNull(healed.group.skin).healReport?.moles, error.moles)
    }

    @Test
    fun rebuild_healsTheRecordedSpotsAgain_butNotOneTheUserErased() {
        val document = created()
        val healed = HealSkinSpots(document.group.id).applyTo(document)
        val record = checkNotNull(healed.group.skin)
        // 사용자가 잡티 제거 레이어의 한 자리를 지우개로 되살렸다
        val (x, y) = PIMPLES[0]
        val erased = PaintStroke(record.spots, listOf(StrokePoint(x, y)), BrushSettings(size = 24f, hardness = 1f), erase = true).applyTo(healed)
        assertEquals(0, Color.alpha(erased.spotsPixel(x.toInt(), y.toInt())))

        val rebuilt = RebuildSkinRetouch(healed.group.id, record.texture, remeasure = true).applyTo(erased)

        val again = checkNotNull(rebuilt.group.skin)
        assertEquals(1, again.healed.size)
        assertTrue(hypot(again.healed[0].x - PIMPLES[1].first, again.healed[0].y - PIMPLES[1].second) < 4f)
        assertEquals(0, Color.alpha(rebuilt.spotsPixel(x.toInt(), y.toInt())))
        assertEquals(255, Color.alpha(rebuilt.spotsPixel(PIMPLES[1].first.toInt(), PIMPLES[1].second.toInt())))
        assertFalse(again.isStale(rebuilt))
        // 다시 만들기는 결과를 알리지 않는다(지운 자리를 다시 지운 것뿐)
        assertEquals(null, again.healReport)
    }

    private companion object {
        val PIMPLES = SkinSpotPhoto.PIMPLES
        val MOLE = SkinSpotPhoto.MOLE
    }
}

package com.kiln.stratum.ui.layout

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 끌어 놓기(사용자 피드백 12차 4)에서 받는 것: 사진 형식일 때만 받고, 여러 장이면 첫 주소만 */
@RunWith(AndroidJUnit4::class)
class PhotoDropTest {

    @Test
    fun onlyPhotoDragsAreAccepted() {
        assertTrue(isPhotoDrag(setOf("image/jpeg")))
        assertTrue(isPhotoDrag(setOf("text/plain", "image/png")))
        assertFalse(isPhotoDrag(setOf("text/plain")))
        assertFalse(isPhotoDrag(emptySet()))
    }

    @Test
    fun theFirstAddressIsTaken_textItemsAreSkipped() {
        val first = Uri.parse("content://media/external/images/media/161")
        val second = Uri.parse("content://media/external/images/media/162")
        val clip = ClipData(ClipDescription("photos", arrayOf("image/jpeg")), ClipData.Item("caption"))
        clip.addItem(ClipData.Item(first))
        clip.addItem(ClipData.Item(second))

        assertEquals(first, firstPhoto(clip))
        assertNull(firstPhoto(null))
        assertNull(firstPhoto(ClipData.newPlainText("note", "not a photo")))
    }
}

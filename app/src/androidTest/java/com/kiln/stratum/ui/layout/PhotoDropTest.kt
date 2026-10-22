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

    @Test
    fun withKnownTypes_theFirstPhotoIsTaken_notAnotherFile() {
        // 사진과 다른 파일을 함께 끌어 오면 형식이 사진인 첫 주소를 고른다(12차 검토)
        val document = Uri.parse("content://files/report.pdf")
        val photo = Uri.parse("content://media/external/images/media/162")
        val clip = ClipData(ClipDescription("files", arrayOf("application/pdf", "image/jpeg")), ClipData.Item(document))
        clip.addItem(ClipData.Item(photo))
        val types = mapOf(document to "application/pdf", photo to "image/jpeg")

        assertEquals(photo, firstPhoto(clip) { types[it] })
        // 형식을 알 수 없으면 첫 주소다
        assertEquals(document, firstPhoto(clip) { null })
    }
}

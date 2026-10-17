package com.kiln.stratum.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 내보낸 사진을 갤러리와 공유용 임시 파일에 두는 일을 에뮬레이터에서 확인한다. 갤러리에 만든 항목은 끝나면 지운다. */
@RunWith(AndroidJUnit4::class)
class ExportStorageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val created = mutableListOf<Uri>()

    @After
    fun deleteCreated() {
        for (uri in created) resolver.delete(uri, null, null)
    }

    /** 테스트마다 다른 이름. 에뮬레이터 갤러리에 남아도 알아볼 수 있게 한다. */
    private fun testName(extension: String) = "StratumTest_${System.nanoTime()}.$extension"

    /** 이름이 [displayName]인 갤러리 항목 수('쓰는 중'인 것까지) */
    private fun countInGallery(displayName: String): Int {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(displayName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return checkNotNull(resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)).use { it.count }
    }

    @Test
    fun saveToGallery_leavesAFinishedPhotoInPicturesStratum() {
        val name = testName("png")
        val content = byteArrayOf(1, 2, 3, 4, 5)

        val uri = ExportStorage.saveToGallery(context, name, "image/png") { it.write(content) }.also { created += it }

        val columns = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.MIME_TYPE
        )
        checkNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(name, cursor.getString(0))
            assertEquals("Pictures/Stratum/", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("image/png", cursor.getString(3))
        }
        assertArrayEquals(content, checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() })
    }

    @Test
    fun saveToGallery_failedWrite_leavesNothing() {
        val name = testName("jpg")

        assertThrows(IOException::class.java) {
            ExportStorage.saveToGallery(context, name, "image/jpeg") {
                it.write(1)
                throw IOException("Encoder failed")
            }
        }

        assertEquals(0, countInGallery(name))
    }

    @Test
    fun shareFile_isReadable_andReplacesTheLastOne() {
        val first = ExportStorage.writeShareFile(context, "Stratum_first.png") { it.write(byteArrayOf(1, 2, 3)) }
        val second = ExportStorage.writeShareFile(context, "Stratum_second.png") { it.write(byteArrayOf(4, 5)) }

        assertEquals(ExportStorage.shareAuthority(context), second.authority)
        assertArrayEquals(byteArrayOf(4, 5), checkNotNull(resolver.openInputStream(second)).use { it.readBytes() })
        // 공유 파일은 하나만 둔다
        assertEquals(listOf("Stratum_second.png"), File(context.cacheDir, "shared").list()?.toList())
        assertThrows(FileNotFoundException::class.java) { resolver.openInputStream(first) }
    }

    @Test
    fun shareFile_failedWrite_leavesNoFile() {
        assertThrows(IOException::class.java) {
            ExportStorage.writeShareFile(context, "Stratum_broken.jpg") {
                it.write(1)
                throw IOException("Encoder failed")
            }
        }

        assertFalse(File(File(context.cacheDir, "shared"), "Stratum_broken.jpg").exists())
    }

    @Test
    fun viewIntent_opensThePhotoWithReadAccess() {
        val uri = Uri.parse("content://media/external/images/media/42")

        val intent = ExportStorage.viewIntent(uri, "image/png")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("image/png", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun shareChooser_sendsThePhotoWithReadAccess() {
        val uri = ExportStorage.writeShareFile(context, "Stratum_share.jpg") { it.write(byteArrayOf(9)) }

        val chooser = ExportStorage.shareChooser(uri, "image/jpeg")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

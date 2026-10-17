package com.kiln.stratum.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 내보낸 사진을 둘 곳. 갤러리의 '사진/Stratum' 폴더와, 공유할 때 쓰는 앱 임시 파일이다.
 * 내용은 [write]가 쓰고(엔진이 압축한다), 여기서는 자리를 만들고 실패하면 치운다. 원본 사진·프로젝트는 건드리지 않는다.
 * 파일을 쓰므로 메인 스레드가 아닌 곳에서 부른다.
 */
object ExportStorage {

    /** 갤러리 안의 폴더(사진/Stratum) */
    val GALLERY_FOLDER: String = Environment.DIRECTORY_PICTURES + "/Stratum"

    /** 공유할 파일을 두는 앱 임시 폴더(cache/shared). `res/xml/share_paths.xml`과 같다. */
    private const val SHARE_FOLDER = "shared"

    /** 압축기가 4KB씩 쓰므로 모아서 쓴다 */
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 갤러리의 '사진/Stratum' 폴더에 [displayName] 사진을 만들고 [write]로 내용을 쓴다. 만든 사진의 주소를 돌려준다.
     *
     * - 다 쓸 때까지 '쓰는 중'(IS_PENDING)으로 두어 갤러리에 반쯤 쓴 사진이 보이지 않게 한다.
     * - [write]가 실패하거나 취소되면(예외) 만든 항목을 지우고 그 예외를 그대로 내보낸다.
     * - 같은 이름이 있으면 안드로이드가 이름 뒤에 번호를 붙인다.
     * - 권한이 필요 없다. 안드로이드 10부터 앱은 자기 사진을 미디어 저장소에 그냥 넣을 수 있다.
     */
    fun saveToGallery(context: Context, displayName: String, mimeType: String, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't add $displayName to the gallery")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open $uri")
            BufferedOutputStream(stream, BUFFER_SIZE).use { out ->
                write(out)
                out.flush()
            }
            val finished = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, finished, null, null) != 1) throw IOException("Couldn't finish $uri")
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * 공유할 파일 [fileName]을 앱 임시 폴더에 만들고 [write]로 내용을 쓴다. 다른 앱에 넘길 주소를 돌려준다.
     * 앞에 공유한 파일은 지운다(공유 파일은 하나만 둔다). [write]가 실패하거나 취소되면 쓰다 만 파일을 지운다.
     */
    fun writeShareFile(context: Context, fileName: String, write: (OutputStream) -> Unit): Uri {
        val folder = File(context.cacheDir, SHARE_FOLDER)
        folder.listFiles()?.forEach { it.delete() }
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't create $folder")
        val file = File(folder, fileName)
        try {
            BufferedOutputStream(FileOutputStream(file), BUFFER_SIZE).use { out ->
                write(out)
                out.flush()
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return FileProvider.getUriForFile(context, shareAuthority(context), file)
    }

    /** 공유 파일 주소의 이름(매니페스트의 ShareFileProvider와 같다) */
    fun shareAuthority(context: Context): String = context.packageName + ".share"

    /**
     * [uri] 사진을 다른 앱으로 보내는 안드로이드 공유 창.
     * 받는 앱에 읽기 권한을 직접 준다(공유 창의 미리보기도 이것으로 읽는다).
     * 공유(ACTION_SEND)에는 공유 창 제목이 쓰이지 않아(소스 확인) 제목을 주지 않는다.
     */
    fun shareChooser(uri: Uri, mimeType: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}

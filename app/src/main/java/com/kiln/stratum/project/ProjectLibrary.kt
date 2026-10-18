package com.kiln.stratum.project

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.platform.DocumentColors
import com.kiln.stratum.engine.platform.LoadedProject
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.ProjectFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 홈 목록에 보일 프로젝트 하나. [thumbnail]은 없을 수도 있다(아직 그리지 못함).
 * [name]이 비었으면 이름이 없는 것이고 화면에는 '제목 없음'으로 보인다(결정 기록 '프로젝트 이름').
 */
data class ProjectInfo(
    val id: String,
    val width: Int,
    val height: Int,
    val modifiedMillis: Long,
    val thumbnail: File,
    val name: String = ""
)

/**
 * 앱 내부 저장소의 프로젝트들(files/projects). 앱 전체에서 하나만 둔다([get]).
 * 목록([projects]), 새 프로젝트, 저장([save]), 불러오기([open]), 지우기([delete])를 맡는다.
 *
 * - 저장은 앱 전체의 범위에서 돌아, 편집 화면(ViewModel)이 사라져도 끝까지 한다. 저장하다 앱이 꺼지면 앞 저장본이 남는다.
 * - 저장·불러오기·지우기는 한 번에 하나씩 한다. 같은 폴더를 동시에 다루지 않고, 큰 문서의 메모리가 겹치지 않는다.
 * - 같은 프로젝트에 저장을 여러 번 부르면 가장 나중 문서만 저장한다. 이미 저장한 문서 객체는 다시 저장하지 않는다.
 * - 실행 취소 기록은 저장하지 않는다(결정 기록 '자동 저장').
 */
class ProjectLibrary private constructor(context: Context) {

    private val root = File(context.filesDir, ROOT)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 저장·불러오기·지우기를 한 번에 하나만 */
    private val mutex = Mutex()

    /** 프로젝트마다 하나. 그 폴더에 이미 쓴 타일을 기억한다. */
    private val folders = HashMap<String, ProjectFolder>()

    /** 프로젝트마다 마지막으로 저장을 부탁받은 문서와, 마지막으로 저장한 문서 */
    private val requested = HashMap<String, Document>()
    private val saved = HashMap<String, Document>()

    /** 저장하고 있거나 부탁받은 프로젝트. 목록을 정리할 때 아직 목차가 없는 새 폴더를 지우지 않게 한다. */
    private val active = HashSet<String>()

    /**
     * 새 프로젝트의 이름. 첫 저장을 부탁할 때 받아 두고, 실제로 저장하는 순간(목차를 쓰기 전) 이름 파일에 쓴다.
     * 앞 저장이 더 나중 부탁에 밀려 건너뛰어도 이름은 그 나중 저장이 쓴다.
     */
    private val pendingNames = HashMap<String, String>()

    /** 목록 다시 읽기를 한 번에 하나만(늦게 끝난 앞 읽기가 새 목록을 덮지 않게) */
    private val refreshMutex = Mutex()

    /** 아직 끝나지 않은 저장 작업 */
    private val pending = HashSet<Job>()

    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())

    /** 최근 프로젝트. 마지막으로 저장한 시각이 늦은 것부터다. */
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    private val _saveFailures = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 저장하지 못한 프로젝트(저장 공간 부족 등). 편집 화면이 알린다. */
    val saveFailures: SharedFlow<String> = _saveFailures.asSharedFlow()

    /** 새 프로젝트 번호. 폴더 이름으로 쓴다. */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * [id] 프로젝트에 [document]를 저장한다(백그라운드). [createdMillis]는 처음 만든 시각이다.
     * [name]이 있으면(새 프로젝트의 첫 저장) 그 이름도 쓴다. 빈 글자는 이름이 없는 것이다.
     * 저장한 뒤 홈 목록용 작은 그림을 다시 그리고 목록을 고친다.
     */
    fun save(id: String, createdMillis: Long, document: Document, name: String? = null): Job {
        synchronized(this) {
            requested[id] = document
            active += id
            if (name != null) pendingNames[id] = name
        }
        // 끝나기 전에 [pending]에 넣어 두려고 바로 시작하지 않고 만든 뒤 시작한다
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutex.withLock {
                val latest = synchronized(this@ProjectLibrary) {
                    // 더 나중 부탁이 있으면 그쪽이 저장한다. 이미 저장한 문서면 할 일이 없다.
                    requested[id]?.takeIf { it === document && saved[id] !== document }
                }
                if (latest != null) write(id, createdMillis, latest)
                synchronized(this@ProjectLibrary) {
                    if (requested[id] === document) {
                        requested.remove(id)
                        active -= id
                    }
                }
            }
        }
        synchronized(this) { pending += job }
        job.invokeOnCompletion { synchronized(this) { pending -= job } }
        job.start()
        return job
    }

    /**
     * 부탁받은 저장이 모두 끝날 때까지 기다린다.
     * 다른 문서를 열기 전에 메모리가 겹치지 않게 하고, 방금 닫은 프로젝트를 다시 열 때 마지막 편집까지 저장된 것을 읽게 한다.
     */
    suspend fun awaitSaves() {
        synchronized(this) { pending.toList() }.joinAll()
    }

    /**
     * [id] 프로젝트를 불러온다. 저장하던 것이 있으면 끝날 때까지 기다린다.
     * 망가졌거나 없으면 IOException, 메모리가 모자라면 [NotEnoughMemoryException]이다.
     * [availableBytes]보다 많은 메모리가 들 것 같으면 불러오지 않는다.
     */
    suspend fun open(id: String, availableBytes: Long, checkCanceled: () -> Unit): LoadedProject {
        require(isValidId(id)) { "Bad project id: $id" }
        awaitSaves()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val folder = folder(id)
                if (folder.estimatedBytes() > availableBytes) throw NotEnoughMemoryException()
                folder.load(checkCanceled).also { loaded ->
                    synchronized(this@ProjectLibrary) { saved[id] = loaded.document }
                }
            }
        }
    }

    /** [id] 프로젝트를 지운다. */
    fun delete(id: String): Job = delete(listOf(id))

    /**
     * [ids] 프로젝트들을 지운다(홈의 '선택'). 아직 하지 않은 저장 부탁도 지워, 기다리던 저장이 지운 폴더를 다시 만들지 않게 한다.
     */
    fun delete(ids: Collection<String>): Job =
        scope.launch {
            mutex.withLock {
                for (id in ids) {
                    synchronized(this@ProjectLibrary) {
                        requested.remove(id)
                        active -= id
                        pendingNames.remove(id)
                    }
                    folder(id).delete()
                    synchronized(this@ProjectLibrary) {
                        folders.remove(id)
                        saved.remove(id)
                    }
                }
            }
            refreshNow()
        }

    /**
     * [id] 프로젝트의 이름을 [name]으로 바꾼다(빈 글자는 이름 없음). 목차는 다시 쓰지 않아 고친 시각(목록 순서)은 그대로다.
     * 목차가 없는(지워졌거나 아직 저장하지 않은) 프로젝트는 그대로 둔다.
     */
    fun rename(id: String, name: String): Job =
        scope.launch {
            mutex.withLock {
                val folder = folder(id)
                if (folder.manifestFile.exists()) {
                    try {
                        folder.writeName(name)
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not rename project $id", e)
                    }
                }
            }
            refreshNow()
        }

    /** 목록을 다시 읽는다. */
    fun refresh(): Job = scope.launch { refreshNow() }

    private fun folder(id: String): ProjectFolder =
        synchronized(this) { folders.getOrPut(id) { ProjectFolder(File(root, id)) } }

    /** 저장하고, 작은 그림을 그리고, 목록을 고친다. [mutex] 안에서 부른다. */
    private suspend fun write(id: String, createdMillis: Long, document: Document) {
        val folder = folder(id)
        try {
            // 새 프로젝트의 이름은 목차보다 먼저 써서, 목록에 처음 보일 때부터 이름이 있다
            synchronized(this) { pendingNames.remove(id) }?.let { folder.writeName(it) }
            folder.save(document, createdMillis, System.currentTimeMillis())
            synchronized(this) { saved[id] = document }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save project $id", e)
            _saveFailures.tryEmit(id)
            return
        } catch (e: NotEnoughMemoryException) {
            Log.w(TAG, "Not enough memory to save project $id", e)
            _saveFailures.tryEmit(id)
            return
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to save project $id", e)
            _saveFailures.tryEmit(id)
            return
        }
        writeThumbnail(folder, document)
        refreshNow()
    }

    /** 홈 목록용 작은 그림(긴 변 [THUMBNAIL_EDGE]px 이하)을 GPU로 그려 쓴다. 그리지 못하면 앞 그림을 그대로 둔다. */
    private fun writeThumbnail(folder: ProjectFolder, document: Document) {
        val longEdge = max(document.width, document.height)
        var level = 0
        while ((longEdge + (1 shl level) - 1) shr level > THUMBNAIL_EDGE) level++
        try {
            val drawn = PreviewRenderer.render(document, level) ?: return
            val pixels = try {
                drawn.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                drawn.recycle()
            } ?: return
            try {
                // 넓은 색 문서는 그 색 정보를 붙여 쓴다(홈에서 같은 색으로 보인다)
                DocumentColors.tagForEncoding(pixels, document.colorSpace)
                folder.writeThumbnail(pixels)
            } finally {
                pixels.recycle()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write a thumbnail", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for a thumbnail", e)
        }
    }

    /**
     * 프로젝트 폴더를 읽어 목록을 고친다. 목차가 없는 폴더는 처음 저장하다 꺼진 것이라,
     * 지금 저장하는 중이 아니면 지운다. 망가져서 머리말을 읽을 수 없는 것은 목록에서 뺀다.
     */
    private suspend fun refreshNow() = refreshMutex.withLock {
        val directories = withContext(Dispatchers.IO) { root.listFiles()?.filter { it.isDirectory && isValidId(it.name) } }
            ?: emptyList()
        val list = ArrayList<ProjectInfo>()
        for (directory in directories) {
            val header = withContext(Dispatchers.IO) { ProjectFolder.readHeader(directory) }
            if (header == null) {
                val inUse = synchronized(this) { directory.name in active }
                if (!inUse && !File(directory, ProjectFolder.MANIFEST).exists()) {
                    withContext(Dispatchers.IO) { directory.deleteRecursively() }
                }
                continue
            }
            list += ProjectInfo(
                id = directory.name,
                width = header.width,
                height = header.height,
                modifiedMillis = header.modifiedMillis,
                thumbnail = File(directory, ProjectFolder.THUMBNAIL),
                name = withContext(Dispatchers.IO) { ProjectFolder.readName(directory) }?.let(ProjectNames::clean).orEmpty()
            )
        }
        _projects.value = list.sortedByDescending { it.modifiedMillis }
    }

    companion object {
        private const val TAG = "ProjectLibrary"
        private const val ROOT = "projects"

        /** 홈 목록용 작은 그림의 긴 변(px) 상한 */
        const val THUMBNAIL_EDGE = 512

        @Volatile
        private var instance: ProjectLibrary? = null

        /** 앱 전체에서 하나인 프로젝트 모음 */
        fun get(context: Context): ProjectLibrary =
            instance ?: synchronized(this) {
                instance ?: ProjectLibrary(context.applicationContext).also { instance = it }
            }

        /** 폴더 이름으로 쓸 수 있는 프로젝트 번호인지(UUID). 저장된 상태에서 되살린 값을 그대로 경로에 쓰지 않게 한다. */
        fun isValidId(id: String): Boolean =
            try {
                UUID.fromString(id).toString() == id
            } catch (e: IllegalArgumentException) {
                false
            }
    }
}

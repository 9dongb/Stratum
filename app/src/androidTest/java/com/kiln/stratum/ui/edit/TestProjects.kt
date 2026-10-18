package com.kiln.stratum.ui.edit

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 테스트가 만든 프로젝트(files/projects)를 모두 지운다. 실제 ViewModel로 문서를 여는 테스트가 끝날 때(@After) 부른다.
 *
 * 테스트의 ViewModel은 화면(Activity)이 갖지 않아 테스트가 끝나도 정리되지 않는다. 그래서 편집 뒤 잡아 둔 자동 저장
 * ([EditorViewModel.AUTOSAVE_DELAY_MILLIS] 뒤)이 폴더를 지운 다음에 돌아 프로젝트를 다시 만들었다(에뮬레이터 홈에 테스트 캔버스가 남음).
 * 먼저 [viewModels]를 닫는다(홈으로 나갈 때와 같다). 기다리던 저장은 바로 부탁하고, 그 뒤로는 저장을 부탁하지 않는다.
 * 부탁한 저장이 모두 끝나기를 기다린 뒤 지운다.
 */
fun deleteTestProjects(context: Context, vararg viewModels: EditorViewModel) {
    // 편집 상태는 메인 스레드에서만 다룬다
    InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.forEach { it.close() } }
    runBlocking { ProjectLibrary.get(context).awaitSaves() }
    File(context.filesDir, "projects").deleteRecursively()
}

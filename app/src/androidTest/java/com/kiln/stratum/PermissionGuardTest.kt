package com.kiln.stratum

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 앱이 바라는 권한이 늘지 않았는지 본다. 라이브러리가 권한을 몰래 끌고 오면(LiteRT의 FOREGROUND_SERVICE 등) 실패한다.
 * 설정 화면의 "Stratum은 인터넷을 쓰지 않아요" 약속과 결정 기록 'AI 제거·AI 선택'을 지킨다.
 * 권한을 일부러 더하면 사용자 승인 뒤 이 목록도 함께 고친다.
 */
@RunWith(AndroidJUnit4::class)
class PermissionGuardTest {

    @Test
    fun theAppAsksOnlyForTheAndroidxReceiverPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        // androidx(core)가 앱 안의 동적 리시버를 지키려고 만드는 권한 하나뿐이다
        assertEquals(setOf("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), requested)
    }
}

package com.kiln.stratum

import android.os.SystemClock
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 앱을 열면 상태 표시줄이 숨고 아래 제스처 막대는 그대로인지 확인한다(결정 기록 '전체 화면'). */
@RunWith(AndroidJUnit4::class)
class FullScreenTest {

    @Test
    fun appOpensWithoutTheStatusBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun visible(type: Int): Boolean {
                var shown = true
                scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(type) ?: true }
                return shown
            }
            val until = SystemClock.uptimeMillis() + 5_000
            while (visible(WindowInsets.Type.statusBars()) && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)

            assertTrue("status bar shown", !visible(WindowInsets.Type.statusBars()))
            assertTrue("navigation bar hidden", visible(WindowInsets.Type.navigationBars()))
        }
    }
}

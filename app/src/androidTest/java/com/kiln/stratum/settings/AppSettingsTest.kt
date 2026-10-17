package com.kiln.stratum.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.export.MAX_EXPORT_QUALITY
import com.kiln.stratum.ui.edit.ExportChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 앱 설정을 저장하고 다시 읽는지 에뮬레이터에서 확인한다. 앱의 설정은 건드리지 않게 테스트용 저장소를 쓴다. */
@RunWith(AndroidJUnit4::class)
class AppSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun preferences(): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @After
    fun deletePreferences() {
        context.deleteSharedPreferences(FILE)
    }

    @Test
    fun empty_readsTheDefaults() {
        assertEquals(SettingsValues(), AppSettings(preferences()).values.value)
    }

    @Test
    fun changes_areSaved_andReadBackNextTime() {
        val changed = SettingsValues(
            export = ExportChoice(ExportFormat.WEBP, 75, ExportSize.LongEdge(2048)),
            previewQuality = PreviewQuality.LIGHT,
            twoFingerTapUndo = false
        )

        AppSettings(preferences()).update { changed }

        // 앱을 다시 켠 것처럼 새로 읽는다
        assertEquals(changed, AppSettings(preferences()).values.value)
    }

    @Test
    fun originalSize_isSaved_andReadBack() {
        val settings = AppSettings(preferences())
        settings.update { it.copy(export = ExportChoice(ExportFormat.PNG, 90, ExportSize.LongEdge(1080))) }
        settings.update { it.copy(export = it.export.copy(size = ExportSize.Original)) }

        assertEquals(ExportChoice(ExportFormat.PNG, 90, ExportSize.Original), AppSettings(preferences()).values.value.export)
    }

    @Test
    fun unknownSavedValues_areReadAsDefaults() {
        // 다음 판에서 지운 형식이나 망가진 값
        preferences().edit()
            .putString("exportFormat", "HEIC")
            .putInt("exportQuality", 500)
            .putString("exportSize", "big")
            .putString("previewQuality", "ULTRA")
            .commit()

        val values = AppSettings(preferences()).values.value

        assertEquals(ExportFormat.JPEG, values.export.format)
        assertEquals(MAX_EXPORT_QUALITY, values.export.quality)
        assertEquals(ExportSize.Original, values.export.size)
        assertEquals(PreviewQuality.STANDARD, values.previewQuality)
        assertTrue(values.twoFingerTapUndo)
    }

    @Test
    fun inMemory_startsWithDefaults_andKeepsChangesToItself() {
        val settings = AppSettings.inMemory()

        settings.update { it.copy(twoFingerTapUndo = false) }

        assertFalse(settings.values.value.twoFingerTapUndo)
        assertEquals(SettingsValues(), AppSettings.inMemory().values.value)
    }

    private companion object {
        const val FILE = "settings-test"
    }
}

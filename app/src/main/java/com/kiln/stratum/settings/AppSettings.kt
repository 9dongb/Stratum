package com.kiln.stratum.settings

import android.content.Context
import android.content.SharedPreferences
import com.kiln.stratum.engine.export.DEFAULT_EXPORT_QUALITY
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.export.MAX_EXPORT_QUALITY
import com.kiln.stratum.engine.export.MIN_EXPORT_QUALITY
import com.kiln.stratum.ui.edit.ExportChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 미리 보기 화질 */
enum class PreviewQuality {
    /** 화면 크기에 맞는 선명한 축소본(기본) */
    STANDARD,

    /** 큰 사진은 축소본을 한 단계(반) 작게 그린다. 슬라이더·브러시 미리 보기가 가볍고 메모리를 덜 쓴다. 확대하면 선명하게 다시 그린다. */
    LIGHT
}

/**
 * 앱 설정 값(바꾸지 않는 값).
 *
 * @property export 앱을 켠 뒤 내보내기 창이 처음 열릴 때의 고르기. 같은 실행 안에서는 마지막으로 내보낸 고르기가 이어진다.
 * @property previewQuality 미리 보기 화질
 * @property twoFingerTapUndo 캔버스를 두 손가락으로 톡 누르면 실행 취소
 * @property recentColors 최근 색(브러시·글자, 맨 앞이 가장 최근, [RecentColors]). 설정 화면에는 보이지 않고, 앱을 다시 켜도 남는다.
 */
data class SettingsValues(
    val export: ExportChoice = ExportChoice(),
    val previewQuality: PreviewQuality = PreviewQuality.STANDARD,
    val twoFingerTapUndo: Boolean = true,
    val recentColors: List<Int> = RecentColors.DEFAULT
)

/**
 * 앱 설정. 앱 전체에서 하나이고, 안드로이드 기본 저장소(SharedPreferences)에 남는다(새 라이브러리 없음).
 * 값은 이름·숫자로 저장한다. 모르는 값(다음 판에서 지운 형식 등)은 기본값으로 읽는다.
 * 테스트는 앱의 설정을 건드리지 않게 따로 만든 저장소로 만들거나, 저장하지 않는 설정([inMemory])을 쓴다.
 *
 * @param preferences 값을 남길 저장소. null이면 남기지 않는다.
 */
class AppSettings internal constructor(private val preferences: SharedPreferences?) {

    private val _values = MutableStateFlow(preferences?.let(::read) ?: SettingsValues())

    /** 지금 설정 */
    val values: StateFlow<SettingsValues> = _values.asStateFlow()

    /** 설정을 [change]로 바꾸고 저장한다. 메인 스레드에서 부른다(저장은 백그라운드에서 끝난다). */
    fun update(change: (SettingsValues) -> SettingsValues) {
        val updated = change(_values.value)
        if (updated == _values.value) return
        _values.value = updated
        preferences?.let { write(it, updated) }
    }

    companion object {
        private const val FILE = "settings"
        private const val KEY_EXPORT_FORMAT = "exportFormat"
        private const val KEY_EXPORT_QUALITY = "exportQuality"
        private const val KEY_EXPORT_SIZE = "exportSize"
        private const val KEY_PREVIEW_QUALITY = "previewQuality"
        private const val KEY_TWO_FINGER_UNDO = "twoFingerTapUndo"
        private const val KEY_RECENT_COLORS = "recentColors"

        /** 크기 저장 값: 원본은 이 글자, 긴 변은 픽셀 숫자 */
        private const val ORIGINAL = "original"

        @Volatile
        private var instance: AppSettings? = null

        /** 앱 전체에서 하나인 설정 */
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)).also { instance = it }
            }

        /** 기본값으로 시작하고 저장하지 않는 설정(테스트). 앱의 설정을 읽지도 바꾸지도 않는다. */
        internal fun inMemory(): AppSettings = AppSettings(null)

        private fun read(preferences: SharedPreferences): SettingsValues {
            val defaults = SettingsValues()
            val format = preferences.getString(KEY_EXPORT_FORMAT, null)
                ?.let { name -> ExportFormat.entries.firstOrNull { it.name == name } } ?: defaults.export.format
            val quality = preferences.getInt(KEY_EXPORT_QUALITY, DEFAULT_EXPORT_QUALITY).coerceIn(MIN_EXPORT_QUALITY, MAX_EXPORT_QUALITY)
            val size = when (val saved = preferences.getString(KEY_EXPORT_SIZE, ORIGINAL)) {
                ORIGINAL, null -> ExportSize.Original
                else -> saved.toIntOrNull()?.takeIf { it > 0 }?.let { ExportSize.LongEdge(it) } ?: ExportSize.Original
            }
            val preview = preferences.getString(KEY_PREVIEW_QUALITY, null)
                ?.let { name -> PreviewQuality.entries.firstOrNull { it.name == name } } ?: defaults.previewQuality
            return SettingsValues(
                export = ExportChoice(format, quality, size),
                previewQuality = preview,
                twoFingerTapUndo = preferences.getBoolean(KEY_TWO_FINGER_UNDO, defaults.twoFingerTapUndo),
                recentColors = RecentColors.decode(preferences.getString(KEY_RECENT_COLORS, null))
            )
        }

        private fun write(preferences: SharedPreferences, values: SettingsValues) {
            preferences.edit()
                .putString(KEY_EXPORT_FORMAT, values.export.format.name)
                .putInt(KEY_EXPORT_QUALITY, values.export.quality)
                .putString(
                    KEY_EXPORT_SIZE,
                    when (val size = values.export.size) {
                        ExportSize.Original -> ORIGINAL
                        is ExportSize.LongEdge -> size.pixels.toString()
                    }
                )
                .putString(KEY_PREVIEW_QUALITY, values.previewQuality.name)
                .putBoolean(KEY_TWO_FINGER_UNDO, values.twoFingerTapUndo)
                .putString(KEY_RECENT_COLORS, RecentColors.encode(values.recentColors))
                .apply()
        }
    }
}

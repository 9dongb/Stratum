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

/** 앱 테마(설정의 '테마', 결정 기록 '테마 고르기') */
enum class ThemeMode {
    /** 다크(기본) */
    DARK,

    /** 라이트 */
    LIGHT,

    /** 휴대폰의 다크 모드 설정을 따른다 */
    SYSTEM;

    /** 이 설정이면 다크로 그리는지. [systemDark]는 휴대폰이 다크 모드인지다. */
    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            DARK -> true
            LIGHT -> false
            SYSTEM -> systemDark
        }
}

/**
 * 앱 설정 값(바꾸지 않는 값).
 *
 * @property export 앱을 켠 뒤 내보내기 창이 처음 열릴 때의 고르기. 같은 실행 안에서는 마지막으로 내보낸 고르기가 이어진다.
 * @property previewQuality 미리 보기 화질
 * @property twoFingerTapUndo 캔버스를 두 손가락으로 톡 누르면 실행 취소
 * @property threeFingerGestures 세 손가락 제스처: 캔버스를 톡 누르면 다시 실행, 위아래로 밀면 브러시 크기, 좌우로 밀면 단단함(유동화는 압력)
 * @property themeMode 앱 테마(다크·라이트·휴대폰 설정 따름)
 * @property paintColors 전경색·배경색(브러시·글자가 함께 씀, [PaintColors]). 설정 화면에는 보이지 않고, 앱을 다시 켜도 남는다.
 * @property stripPosition 메인 화면 도구 옵션 띠를 옮겨 놓은 자리(없으면 처음 자리, [StripPosition]). 설정 화면에는 보이지 않는다.
 */
data class SettingsValues(
    val export: ExportChoice = ExportChoice(),
    val previewQuality: PreviewQuality = PreviewQuality.STANDARD,
    val twoFingerTapUndo: Boolean = true,
    val threeFingerGestures: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val paintColors: PaintColors = PaintColors.DEFAULT,
    val stripPosition: StripPosition? = null
)

/**
 * 메인 화면 도구 옵션 띠를 손잡이로 옮겨 놓은 자리(사용자 피드백 9차). 캔버스 영역 왼쪽 위에서 띠 왼쪽 위까지의 거리(dp)다.
 * 화면 크기가 바뀌면(접기·돌리기) 편집 화면이 영역 안으로 맞춰 놓는다.
 */
data class StripPosition(val x: Float, val y: Float)

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
        private const val KEY_THREE_FINGER = "threeFingerGestures"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_FOREGROUND = "foregroundColor"
        private const val KEY_BACKGROUND = "backgroundColor"
        private const val KEY_STRIP_X = "stripX"
        private const val KEY_STRIP_Y = "stripY"

        /** 전에 쓰던 최근 색(사용자 피드백 7차에서 전경색·배경색으로 바꿈). 저장할 때 지운다. */
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
            val theme = preferences.getString(KEY_THEME_MODE, null)
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: defaults.themeMode
            return SettingsValues(
                export = ExportChoice(format, quality, size),
                previewQuality = preview,
                twoFingerTapUndo = preferences.getBoolean(KEY_TWO_FINGER_UNDO, defaults.twoFingerTapUndo),
                threeFingerGestures = preferences.getBoolean(KEY_THREE_FINGER, defaults.threeFingerGestures),
                themeMode = theme,
                paintColors = PaintColors(
                    foreground = PaintColors.opaque(preferences.getInt(KEY_FOREGROUND, defaults.paintColors.foreground)),
                    background = PaintColors.opaque(preferences.getInt(KEY_BACKGROUND, defaults.paintColors.background))
                ),
                stripPosition = if (preferences.contains(KEY_STRIP_X) && preferences.contains(KEY_STRIP_Y)) {
                    StripPosition(preferences.getFloat(KEY_STRIP_X, 0f), preferences.getFloat(KEY_STRIP_Y, 0f))
                        .takeIf { it.x.isFinite() && it.y.isFinite() }
                } else {
                    null
                }
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
                .putBoolean(KEY_THREE_FINGER, values.threeFingerGestures)
                .putString(KEY_THEME_MODE, values.themeMode.name)
                .putInt(KEY_FOREGROUND, values.paintColors.foreground)
                .putInt(KEY_BACKGROUND, values.paintColors.background)
                .remove(KEY_RECENT_COLORS)
                .apply {
                    val strip = values.stripPosition
                    if (strip == null) {
                        remove(KEY_STRIP_X)
                        remove(KEY_STRIP_Y)
                    } else {
                        putFloat(KEY_STRIP_X, strip.x)
                        putFloat(KEY_STRIP_Y, strip.y)
                    }
                }
                .apply()
        }
    }
}

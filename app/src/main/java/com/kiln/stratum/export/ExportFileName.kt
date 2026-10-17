package com.kiln.stratum.export

import com.kiln.stratum.engine.export.ExportFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 파일 이름의 날짜·시각 모양. 기기 언어와 상관없이 숫자로 쓴다. */
private val TIME_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

/** 내보낸 파일 이름. 예: `Stratum_20261018_103015.jpg`([time]은 기기 시간) */
fun exportFileName(time: LocalDateTime, format: ExportFormat): String =
    "Stratum_" + time.format(TIME_PATTERN) + "." + format.extension

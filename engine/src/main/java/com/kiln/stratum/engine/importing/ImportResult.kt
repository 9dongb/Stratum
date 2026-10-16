package com.kiln.stratum.engine.importing

import com.kiln.stratum.engine.document.Document

/** 사진 불러오기 결과. */
sealed interface ImportResult {

    /** 불러온 사진. 문서의 크기가 실제로 해독된 크기다. */
    data class Success(val document: Document) : ImportResult

    /** 불러오지 못했다. */
    data class Failure(val reason: ImportFailure) : ImportResult
}

/** 사진을 불러오지 못한 까닭. 앱은 이 값에 맞는 안내 문구를 strings.xml에서 고른다. */
enum class ImportFailure {
    /** 메모리가 모자라다. */
    NOT_ENOUGH_MEMORY,

    /** 파일을 읽을 수 없다(지원하지 않는 형식, 깨진 파일, 접근 권한 없음 등). */
    UNREADABLE
}

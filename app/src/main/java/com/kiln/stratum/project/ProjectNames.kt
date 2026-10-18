package com.kiln.stratum.project

/**
 * 프로젝트 이름 다듬기(결정 기록 '프로젝트 이름'). 빈 글자는 이름이 없는 것이고, 화면에는 '제목 없음'으로 보인다.
 * 순수 계산이라 JVM 테스트로 확인한다.
 */
object ProjectNames {

    /** 이름의 가장 긴 글자 수(홈 카드 한 줄과 이름 바꾸기 칸에 맞춘다) */
    const val MAX_LENGTH = 50

    /** 사용자가 적은 이름: 줄바꿈·연속 공백을 공백 하나로, 앞뒤 공백을 지우고 [MAX_LENGTH]자까지. 비면 ""(이름 없음). */
    fun clean(name: String): String = name.replace(WHITESPACE, " ").trim().take(MAX_LENGTH).trim()

    /**
     * 사진 파일의 표시 이름(예: IMG_20261018_1234.jpg)으로 만든 프로젝트 이름: 확장자를 빼고 [clean]으로 다듬는다.
     * 점으로 시작하는 이름(.hidden)처럼 확장자를 빼면 비는 이름은 그대로 쓴다. 표시 이름이 없으면 ""(이름 없음).
     */
    fun fromFileName(displayName: String?): String {
        if (displayName.isNullOrBlank()) return ""
        val base = displayName.substringBeforeLast('.', displayName).takeIf { it.isNotBlank() } ?: displayName
        return clean(base)
    }

    private val WHITESPACE = Regex("\\s+")
}

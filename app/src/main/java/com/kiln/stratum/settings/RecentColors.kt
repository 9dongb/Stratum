package com.kiln.stratum.settings

import java.util.Locale

/**
 * 최근 색(브러시·글자가 함께 씀, 결정 기록 '색 팔레트·최근 색'). 맨 앞이 가장 최근에 고른 색이고 [MAX]개까지 남는다.
 * 색은 불투명한 `0xAARRGGBB`다(브러시·글자 색은 알파를 쓰지 않는다). 설정 저장소에 글자로 남는다.
 */
object RecentColors {

    /** 남기는 수 */
    const val MAX = 6

    /** 처음 최근 색: 검정·흰색 */
    val DEFAULT: List<Int> = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())

    private const val OPAQUE = 0xFF000000.toInt()

    /** [recent] 맨 앞에 [color]를 둔 목록. 같은 색이 있으면 앞으로 옮기고, [MAX]개를 넘으면 오래된 것을 버린다. */
    fun add(recent: List<Int>, color: Int): List<Int> {
        val opaque = color or OPAQUE
        return (listOf(opaque) + recent.filter { it != opaque }).take(MAX)
    }

    /** 저장할 글자: 16진 여덟 자리를 쉼표로 잇는다('FF000000,FFFFFFFF') */
    fun encode(colors: List<Int>): String = colors.joinToString(",") { String.format(Locale.ROOT, "%08X", it) }

    /** 저장한 글자를 목록으로. 저장한 적이 없으면(null) [DEFAULT]이고, 망가진 값은 건너뛴다. */
    fun decode(text: String?): List<Int> {
        if (text == null) return DEFAULT
        return text.split(',')
            .mapNotNull { part -> part.trim().toLongOrNull(16)?.takeIf { it in 0L..0xFFFFFFFFL }?.toInt()?.or(OPAQUE) }
            .distinct()
            .take(MAX)
    }
}

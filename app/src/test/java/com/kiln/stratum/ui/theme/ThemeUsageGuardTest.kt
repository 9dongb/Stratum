package com.kiln.stratum.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 화면 코드가 색·모서리·글자 크기를 직접 쓰지 않고, M3 버튼 같은 부품을 직접 부르지 않는지 본다(결정 기록 '디자인(시안)').
 * 사용자: "색·글자 크기·모서리 값은 테마 파일에 모아두고, 화면 코드에는 직접 쓰지 마"
 * 테마(`ui/theme`)와 앱 부품(`ui/components`)만 이런 값을 쓸 수 있다. 고르는 색 자체(`Color(current)` 같은 데이터)는 괜찮다.
 */
class ThemeUsageGuardTest {

    /** 단위 테스트는 app 모듈 폴더에서 돈다 */
    private val uiRoot = File("src/main/java/com/kiln/stratum/ui")

    private val allowed = setOf("theme", "components")

    /** 직접 쓰면 안 되는 것과 그 까닭 */
    private val rules = listOf(
        Regex("""Color\(0x""") to "색 값은 ui/theme의 토큰으로",
        Regex("""Color\.(White|Black|Gray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""") to "색 이름도 ui/theme의 토큰으로",
        Regex("""\.copy\(alpha""") to "흐린 색도 토큰으로(비활성 글자 색 등)",
        Regex("""RoundedCornerShape\(|CircleShape|CornerRadius\(\s*[0-9]""") to "모서리는 StratumShapes·StratumRadius로",
        Regex("""\b[0-9]+(\.[0-9]+)?\.sp\b|fontSize\s*=""") to "글자 크기는 MaterialTheme.typography로",
        Regex("""MaterialTheme\.(colorScheme|shapes)\b""") to "색·모양은 StratumTheme.colors·StratumShapes로",
        Regex("""TopAppBarDefaults\.topAppBarColors\(""") to "상단 바 색은 stratumTopAppBarColors()로",
        Regex(
            """\b(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|IconButton|IconToggleButton|FilterChip|AssistChip|""" +
                """InputChip|SegmentedButton|SingleChoiceSegmentedButtonRow|Slider|RangeSlider|Switch|DropdownMenu)\("""
        ) to "버튼·칩·분할 버튼·슬라이더·스위치·메뉴는 ui/components 부품으로"
    )

    @Test
    fun screenCode_usesThemeTokensAndAppParts() {
        assertTrue("화면 코드 폴더를 찾지 못함: ${uiRoot.absolutePath}", uiRoot.isDirectory)
        val problems = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(uiRoot).path.substringBefore(File.separatorChar) !in allowed }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    // 주석 줄은 보지 않는다(설명에 부품 이름이 나올 수 있다)
                    val code = line.substringBefore("//").trimStart()
                    if (code.startsWith("*") || code.startsWith("/*")) {
                        emptyList()
                    } else {
                        rules.filter { (pattern, _) -> pattern.containsMatchIn(code) }
                            .map { (_, why) -> "${file.relativeTo(uiRoot)}:${index + 1}: $why\n    ${line.trim()}" }
                    }
                }
            }
            .toList()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

package com.kiln.stratum.project

import org.junit.Assert.assertEquals
import org.junit.Test

/** 프로젝트 이름 다듬기(결정 기록 '프로젝트 이름') */
class ProjectNamesTest {

    @Test
    fun clean_trimsAndJoinsWhitespace() {
        assertEquals("Sea at dusk", ProjectNames.clean("  Sea \n at\tdusk  "))
        assertEquals("", ProjectNames.clean("   "))
    }

    @Test
    fun clean_capsTheLength() {
        assertEquals(ProjectNames.MAX_LENGTH, ProjectNames.clean("가".repeat(80)).length)
    }

    @Test
    fun fromFileName_dropsTheExtension() {
        assertEquals("IMG_20261018_1234", ProjectNames.fromFileName("IMG_20261018_1234.jpg"))
        assertEquals("my.photo", ProjectNames.fromFileName("my.photo.heic"))
        assertEquals("noext", ProjectNames.fromFileName("noext"))
    }

    @Test
    fun fromFileName_keepsDotNames_andEmptyForNothing() {
        assertEquals(".hidden", ProjectNames.fromFileName(".hidden"))
        assertEquals("", ProjectNames.fromFileName(null))
        assertEquals("", ProjectNames.fromFileName("  "))
    }
}

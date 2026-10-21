package com.kiln.stratum.engine.document

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 레이어 픽셀을 바꾸는 명령이 모두 [Layer.withPixels](또는 글자를 다시 그린 [Layer.withText])를 쓰는지 지킨다(사용자 피드백 8차 1-6).
 * 픽셀을 바꾸고 글자 정보를 남기면, 다시 고칠 때 바꾼 픽셀(브러시 자국 등)이 사라진다. 그래서 `copy(content = …)`는 Layer.kt에서만 쓴다.
 */
class LayerPixelsGuardTest {

    private val engineRoot = File("src/main/java/com/kiln/stratum/engine")

    @Test
    fun onlyLayerReplacesTheContent() {
        assertTrue("engine sources not found from ${File(".").absolutePath}", engineRoot.isDirectory)
        val pattern = Regex("""\.copy\(\s*content\s*=""")
        val offenders = engineRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Layer.kt" }
            .flatMap { file -> file.readLines().withIndex().filter { pattern.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}" } }
            .toList()
        assertTrue("Use Layer.withPixels (or withText) to replace layer pixels: $offenders", offenders.isEmpty())
    }
}

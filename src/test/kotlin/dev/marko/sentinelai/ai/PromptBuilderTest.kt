package dev.marko.sentinelai.ai

import dev.marko.sentinelai.scan.RiskLevel
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [PromptBuilder] — prompt construction for Claude AI analysis.
 *
 * Verifies prompt structure, diff extraction, content truncation,
 * and file count capping.
 */
class PromptBuilderTest {

    private fun makeFile(
        path: String = "src/Main.kt",
        content: String = "fun main() {}",
        riskLevel: RiskLevel = RiskLevel.HIGH,
        rawDiff: String? = null
    ) = FileWithContent(
        relativePath = path,
        absolutePath = "/project/$path",
        riskLevel = riskLevel,
        content = content,
        rawDiff = rawDiff
    )

    // Basic prompt structure

    @Test
    fun `scan prompt contains file path`() {
        val prompt = PromptBuilder.buildScanPrompt(listOf(makeFile(path = "src/Config.kt")))
        assertTrue("Prompt should contain the file path", prompt.contains("src/Config.kt"))
    }

    @Test
    fun `scan prompt contains risk level`() {
        val prompt = PromptBuilder.buildScanPrompt(listOf(makeFile(riskLevel = RiskLevel.CRITICAL)))
        assertTrue("Prompt should contain risk level", prompt.contains("CRITICAL"))
    }

    @Test
    fun `scan prompt contains analysis instructions`() {
        val prompt = PromptBuilder.buildScanPrompt(listOf(makeFile()))
        assertTrue("Prompt should contain diff analysis instructions",
            prompt.contains("Analyze the following code diff"))
    }

    @Test
    fun `scan prompt contains JSON response format`() {
        val prompt = PromptBuilder.buildScanPrompt(listOf(makeFile()))
        assertTrue("Prompt should request JSON format",
            prompt.contains("\"findings\""))
        assertTrue("Prompt should request analyzed_files count",
            prompt.contains("\"analyzed_files\""))
    }

    // Diff extraction

    @Test
    fun `uses raw diff when available and filters to added lines`() {
        val rawDiff = """
            --- a/Config.kt
            +++ b/Config.kt
            @@ -1,3 +1,4 @@
             unchanged line
            -removed line
            +added line
            +another added line
        """.trimIndent()

        val prompt = PromptBuilder.buildScanPrompt(listOf(makeFile(rawDiff = rawDiff)))
        assertTrue("Prompt should include added lines", prompt.contains("+added line"))
        assertTrue("Prompt should include second added line", prompt.contains("+another added line"))
        assertFalse("Prompt should exclude +++ header", prompt.contains("+++ b/Config.kt"))
    }

    @Test
    fun `falls back to full content prefixed with plus when no raw diff`() {
        val content = "val x = 1\nval y = 2"
        val prompt = PromptBuilder.buildScanPrompt(listOf(makeFile(content = content, rawDiff = null)))
        assertTrue("Fallback should prefix lines with +", prompt.contains("+ val x = 1"))
        assertTrue("Fallback should prefix all lines", prompt.contains("+ val y = 2"))
    }

    // Content limits

    @Test
    fun `caps files at MAX_FILES_PER_PROMPT of 6`() {
        val files = (1..10).map { makeFile(path = "File$it.kt", content = "content $it") }
        val prompt = PromptBuilder.buildScanPrompt(files)

        // First 6 files should be present
        for (i in 1..6) {
            assertTrue("File$i.kt should be in prompt", prompt.contains("File$i.kt"))
        }
        // Files 7-10 should NOT be present
        for (i in 7..10) {
            assertFalse("File$i.kt should NOT be in prompt", prompt.contains("File$i.kt"))
        }
    }

    // Deep scan prompt

    @Test
    fun `deep scan prompt contains full file content`() {
        val content = "val secret = \"hidden\"\nval safe = getenv()"
        val prompt = PromptBuilder.buildDeepScanPrompt(makeFile(content = content))
        assertTrue("Deep scan prompt should contain file content", prompt.contains(content))
    }

    @Test
    fun `deep scan prompt contains file path and risk level`() {
        val prompt = PromptBuilder.buildDeepScanPrompt(
            makeFile(path = "Security.kt", riskLevel = RiskLevel.CRITICAL)
        )
        assertTrue(prompt.contains("Security.kt"))
        assertTrue(prompt.contains("CRITICAL"))
    }

    @Test
    fun `deep scan prompt mentions deep security scan`() {
        val prompt = PromptBuilder.buildDeepScanPrompt(makeFile())
        assertTrue("Should mention deep scan",
            prompt.contains("deep security scan", ignoreCase = true))
    }
}

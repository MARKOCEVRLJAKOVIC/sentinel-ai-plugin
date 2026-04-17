package dev.marko.sentinelai.config

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [SentinelConfig] — YAML configuration loader.
 *
 * Tests default values, [shouldIgnore] matching, YAML loading from disk,
 * and the glob-to-regex conversion logic.
 */
class SentinelConfigTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    // ── Default values ───────────────────────────────────────────────────────

    @Test
    fun `default AI provider is anthropic`() {
        assertEquals("anthropic", SentinelConfig.aiProvider)
    }

    @Test
    fun `default AI model is claude-haiku-4-5`() {
        assertEquals("claude-haiku-4-5", SentinelConfig.aiModel)
    }

    @Test
    fun `default API key env var is SENTINEL_API_KEY`() {
        assertEquals("SENTINEL_API_KEY", SentinelConfig.apiKeyEnvVar)
    }

    @Test
    fun `default timeout is 10 seconds`() {
        assertEquals(10, SentinelConfig.aiTimeoutSeconds)
    }

    @Test
    fun `default timeout behavior is WARN`() {
        assertEquals(TimeoutBehavior.WARN, SentinelConfig.timeoutBehavior)
    }

    @Test
    fun `default blockOn contains expected categories`() {
        assertTrue(SentinelConfig.blockOn.contains("api_keys"))
        assertTrue(SentinelConfig.blockOn.contains("system_prompts"))
        assertTrue(SentinelConfig.blockOn.contains("private_keys"))
        assertTrue(SentinelConfig.blockOn.contains("db_credentials"))
    }

    @Test
    fun `default warnOn contains expected categories`() {
        assertTrue(SentinelConfig.warnOn.contains("pii"))
        assertTrue(SentinelConfig.warnOn.contains("debug_logs"))
    }

    // ── neverSendToCloud default patterns ────────────────────────────────────

    @Test
    fun `neverSendToCloud matches pem files`() {
        assertTrue("Should match .pem",
            SentinelConfig.neverSendToCloud.any { it.containsMatchIn("server.pem") })
    }

    @Test
    fun `neverSendToCloud matches key files`() {
        assertTrue("Should match .key",
            SentinelConfig.neverSendToCloud.any { it.containsMatchIn("private.key") })
    }

    @Test
    fun `neverSendToCloud matches secrets files`() {
        assertTrue("Should match secrets",
            SentinelConfig.neverSendToCloud.any { it.containsMatchIn("config/secrets.yml") })
    }

    @Test
    fun `neverSendToCloud matches credentials files`() {
        assertTrue("Should match credentials",
            SentinelConfig.neverSendToCloud.any { it.containsMatchIn("aws/credentials.json") })
    }

    @Test
    fun `neverSendToCloud does not match normal kotlin files`() {
        assertFalse("Should not match .kt",
            SentinelConfig.neverSendToCloud.any { it.containsMatchIn("Main.kt") })
    }

    // ── TimeoutBehavior enum ─────────────────────────────────────────────────

    @Test
    fun `TimeoutBehavior has three values`() {
        assertEquals(3, TimeoutBehavior.entries.size)
        assertNotNull(TimeoutBehavior.valueOf("WARN"))
        assertNotNull(TimeoutBehavior.valueOf("BLOCK"))
        assertNotNull(TimeoutBehavior.valueOf("ALLOW"))
    }

    // ── YAML reload ──────────────────────────────────────────────────────────

    @Test
    fun `reload loads values from sentinel yml`() {
        val configFile = File(tempDir.root, ".sentinel.yml")
        configFile.writeText("""
            sentinel:
              ai_provider: openai
              ai_model: gpt-4
              ai_timeout_seconds: 30
              timeout_behavior: block
        """.trimIndent())

        SentinelConfig.reload(tempDir.root.absolutePath)

        assertEquals("openai", SentinelConfig.aiProvider)
        assertEquals("gpt-4", SentinelConfig.aiModel)
        assertEquals(30, SentinelConfig.aiTimeoutSeconds)
        assertEquals(TimeoutBehavior.BLOCK, SentinelConfig.timeoutBehavior)

        // Reset back to defaults for other tests
        resetConfigDefaults()
    }

    @Test
    fun `reload with no config file keeps defaults`() {
        val emptyDir = tempDir.newFolder("empty")
        val previousModel = SentinelConfig.aiModel

        SentinelConfig.reload(emptyDir.absolutePath)

        // Should not change anything
        assertEquals(previousModel, SentinelConfig.aiModel)
    }

    @Test
    fun `reload handles malformed YAML gracefully`() {
        val configFile = File(tempDir.root, ".sentinel.yml")
        configFile.writeText("this is not valid yaml: [[[")

        // Should not throw
        SentinelConfig.reload(tempDir.root.absolutePath)
    }

    @Test
    fun `reload loads ignore patterns`() {
        val configFile = File(tempDir.root, ".sentinel.yml")
        configFile.writeText("""
            sentinel:
              ignore_patterns:
                - "**/*.generated.kt"
                - "**/build/**"
        """.trimIndent())

        SentinelConfig.reload(tempDir.root.absolutePath)

        assertTrue("Should ignore generated files",
            SentinelConfig.shouldIgnore("src/Model.generated.kt"))

        resetConfigDefaults()
    }

    @Test
    fun `shouldIgnore returns false when no ignore patterns set`() {
        // With default empty patterns
        resetConfigDefaults()
        assertFalse(SentinelConfig.shouldIgnore("src/Main.kt"))
    }

    // ── CustomPattern ────────────────────────────────────────────────────────

    @Test
    fun `CustomPattern data class holds name, description, and keywords`() {
        val pattern = CustomPattern(
            name = "internal-api",
            description = "Internal API endpoints",
            keywords = listOf("internal", "private-api")
        )
        assertEquals("internal-api", pattern.name)
        assertEquals("Internal API endpoints", pattern.description)
        assertEquals(2, pattern.keywords.size)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Reset SentinelConfig back to defaults by reloading from a non-existent path.
     * Since SentinelConfig is a singleton, we need to restore state for test isolation.
     */
    private fun resetConfigDefaults() {
        // Reload from a path with no .sentinel.yml — this won't change values,
        // but we can work around it by reloading with known good defaults.
        val resetDir = tempDir.newFolder("reset")
        File(resetDir, ".sentinel.yml").writeText("""
            sentinel:
              ai_provider: anthropic
              ai_model: claude-haiku-4-5
              api_key_env: SENTINEL_API_KEY
              ai_timeout_seconds: 10
              timeout_behavior: warn
              ignore_patterns: []
        """.trimIndent())
        SentinelConfig.reload(resetDir.absolutePath)
    }
}

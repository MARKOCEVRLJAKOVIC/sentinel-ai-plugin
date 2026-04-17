package dev.marko.sentinelai.scan

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [Level1Scanner] — the regex-based Level 1 security scanner.
 *
 * Tests are grouped by:
 *  1. True-positive detection (secrets, keys, credentials)
 *  2. False-positive avoidance (placeholders, env vars, comments)
 *  3. Structural correctness (line numbers, snippet truncation)
 */
class Level1ScannerTest {

    // ── True positives: API keys ─────────────────────────────────────────────

    @Test
    fun `detects hardcoded API key assignment`() {
        val code = """val apiKey = "ABCDEFGHIJ1234567890""""
        val findings = Level1Scanner.scan("Test.kt", code)
        assertTrue("Should detect hardcoded API key", findings.isNotEmpty())
    }

    @Test
    fun `detects OpenAI sk- key`() {
        val code = """val key = "sk-abcdefghijklmnopqrstuvwxyz""""
        val findings = Level1Scanner.scan("Test.kt", code)
        assertTrue("Should detect OpenAI key", findings.any { it.description.contains("OpenAI") })
    }

    @Test
    fun `detects OpenAI project key sk-proj-`() {
        val code = """val key = "sk-proj-abcdefghijklmnopqrstuvwxyz""""
        val findings = Level1Scanner.scan("Test.kt", code)
        assertTrue("Should detect OpenAI project key", findings.any { it.description.contains("OpenAI") })
    }

    @Test
    fun `detects GitHub personal access token`() {
        val code = """val token = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmno""""
        val findings = Level1Scanner.scan("Test.kt", code)
        assertTrue("Should detect GitHub PAT", findings.any { it.description.contains("GitHub") })
    }

    @Test
    fun `detects GitHub app secret`() {
        val code = """val secret = "ghs_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmno""""
        val findings = Level1Scanner.scan("Test.kt", code)
        assertTrue("Should detect GitHub app secret", findings.any { it.description.contains("GitHub") })
    }

    @Test
    fun `detects Google API key`() {
        val code = """val key = "AIzaSyA1234567890abcdefghijklmnopqrstuv""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Should detect Google API key", findings.any { it.description.contains("Google") })
    }

    @Test
    fun `detects AWS access key ID`() {
        val code = """val accessKey = "AKIAIOSFODNN7EXAMPLEKEY""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Should detect AWS Access Key", findings.any { it.description.contains("AWS") })
    }

    @Test
    fun `detects bearer token`() {
        val code = """val header = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abcdefghijk""""
        val findings = Level1Scanner.scan("Auth.kt", code)
        assertTrue("Should detect bearer token", findings.any { it.description.contains("Bearer") })
    }

    // ── True positives: passwords and secrets ────────────────────────────────

    @Test
    fun `detects hardcoded password`() {
        val code = """val password = "SuperSecretP@ss123""""
        val findings = Level1Scanner.scan("Db.kt", code)
        assertTrue("Should detect hardcoded password", findings.any { it.description.contains("password", ignoreCase = true) })
    }

    @Test
    fun `detects hardcoded secret`() {
        val code = """val clientSecret = "mySecretValueABCD1234""""
        val findings = Level1Scanner.scan("OAuth.kt", code)
        assertTrue("Should detect hardcoded secret", findings.isNotEmpty())
    }

    @Test
    fun `detects hardcoded token`() {
        val code = """val authToken = "abcdefghijklmnop12345678""""
        val findings = Level1Scanner.scan("Service.kt", code)
        assertTrue("Should detect hardcoded token", findings.isNotEmpty())
    }

    // ── True positives: private keys and certificates ────────────────────────

    @Test
    fun `detects private key header`() {
        val code = """val pem = "-----BEGIN RSA PRIVATE KEY-----""""
        val findings = Level1Scanner.scan("Crypto.kt", code)
        assertTrue("Should detect private key", findings.any { it.description.contains("Private key") })
    }

    @Test
    fun `detects certificate header`() {
        val code = """val cert = "-----BEGIN CERTIFICATE-----""""
        val findings = Level1Scanner.scan("Tls.kt", code)
        assertTrue("Should detect certificate", findings.any { it.description.contains("Certificate") })
    }

    // ── True positives: connection strings ───────────────────────────────────

    @Test
    fun `detects JDBC connection string with credentials`() {
        val code = """val url = "jdbc:postgresql://db.host:5432/mydb?user=admin&password=secret""""
        val findings = Level1Scanner.scan("Db.kt", code)
        assertTrue("Should detect JDBC credentials", findings.isNotEmpty())
    }

    @Test
    fun `detects credentials embedded in URL`() {
        val code = """val url = "https://admin:password123@api.example.com/v1""""
        val findings = Level1Scanner.scan("Api.kt", code)
        assertTrue("Should detect URL credentials", findings.any { it.description.contains("Credentials") || it.description.contains("URL") })
    }

    // ── True positives: high-entropy strings ─────────────────────────────────

    @Test
    fun `detects high-entropy string in variable assignment`() {
        val code = """val secret = "aGVsbG8gd29ybGQgdGhpcyBpcyBhIHRlc3Qgb2YgYmFzZTY0IGVuY29kaW5n""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Should detect high-entropy string", findings.isNotEmpty())
    }

    // ── False positives: should NOT be detected ──────────────────────────────

    @Test
    fun `ignores System_getenv calls`() {
        val code = """val apiKey = System.getenv("API_KEY")"""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("System.getenv should be safe", findings.isEmpty())
    }

    @Test
    fun `ignores placeholder values`() {
        val code = """val apiKey = "your-api-key-here""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Placeholder should be safe", findings.isEmpty())
    }

    @Test
    fun `ignores TODO comments in values`() {
        val code = """val password = "TODO: replace with real password""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("TODO should be safe", findings.isEmpty())
    }

    @Test
    fun `ignores single-line comments`() {
        val code = """// val apiKey = "sk-realkey1234567890abcdef""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Comments should be skipped", findings.isEmpty())
    }

    @Test
    fun `ignores hash comments`() {
        val code = """# password = "SuperSecretP@ss123""""
        val findings = Level1Scanner.scan("Config.yml", code)
        assertTrue("Hash comments should be skipped", findings.isEmpty())
    }

    @Test
    fun `ignores block comment lines`() {
        val code = """/* val secret = "sk-realkey1234567890abcdef" */"""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Block comments should be skipped", findings.isEmpty())
    }

    @Test
    fun `ignores star-prefixed doc comment lines`() {
        val code = """* val secret = "sk-realkey1234567890abcdef""""
        val findings = Level1Scanner.scan("Config.kt", code)
        assertTrue("Javadoc-style lines should be skipped", findings.isEmpty())
    }

    @Test
    fun `ignores mock data markers`() {
        val code = """val apiKey = "mock-api-key-for-testing""""
        val findings = Level1Scanner.scan("TestHelper.kt", code)
        assertTrue("Mock data should be safe", findings.isEmpty())
    }

    @Test
    fun `ignores process_env references`() {
        val code = """const apiKey = process.env.API_KEY"""
        val findings = Level1Scanner.scan("config.js", code)
        assertTrue("process.env should be safe", findings.isEmpty())
    }

    // ── Structural correctness ───────────────────────────────────────────────

    @Test
    fun `reports correct line number`() {
        val code = "line1\nline2\nval apiKey = \"sk-abcdefghijklmnopqrstuvwxyz\"\nline4"
        val findings = Level1Scanner.scan("Test.kt", code)
        assertTrue("Should have findings", findings.isNotEmpty())
        assertEquals("Line number should be 3 (1-indexed)", 3, findings.first().line)
    }

    @Test
    fun `reports correct file path in findings`() {
        val code = """val apiKey = "sk-abcdefghijklmnopqrstuvwxyz""""
        val findings = Level1Scanner.scan("/project/src/Config.kt", code)
        assertEquals("/project/src/Config.kt", findings.first().file)
    }

    @Test
    fun `snippet is truncated to 80 characters`() {
        val longLine = "val apiKey = \"sk-${"a".repeat(200)}\""
        val findings = Level1Scanner.scan("Test.kt", longLine)
        assertTrue("Should have findings", findings.isNotEmpty())
        assertTrue("Snippet should be at most 80 chars", findings.first().snippet.length <= 80)
    }

    @Test
    fun `returns empty list for clean code`() {
        val code = """
            val name = "John"
            val count = 42
            fun greet() = println("Hello")
        """.trimIndent()
        val findings = Level1Scanner.scan("Clean.kt", code)
        assertTrue("Clean code should have no findings", findings.isEmpty())
    }

    @Test
    fun `scans multi-line content and reports all findings`() {
        val code = """
            val key1 = "sk-abcdefghijklmnopqrstuvwxyz"
            val key2 = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmno"
        """.trimIndent()
        val findings = Level1Scanner.scan("Multi.kt", code)
        assertTrue("Should find at least 2 issues", findings.size >= 2)
    }
}

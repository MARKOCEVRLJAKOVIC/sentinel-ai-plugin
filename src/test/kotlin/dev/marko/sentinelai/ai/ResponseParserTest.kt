package dev.marko.sentinelai.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ResponseParser] — Claude response JSON parsing.
 *
 * Verifies correct parsing of well-formed JSON, fallback extraction from
 * markdown-wrapped responses, and graceful handling of malformed input.
 */
class ResponseParserTest {

    // Successful parsing

    @Test
    fun `parses clean JSON with findings`() {
        val json = """
        {
            "findings": [
                {
                    "file": "Config.kt",
                    "line": 10,
                    "category": "API_KEY",
                    "severity": "CRITICAL",
                    "description": "Hardcoded API key",
                    "snippet": "val key = \"sk-abc123\"",
                    "suggestion": "Use System.getenv()"
                }
            ],
            "analyzed_files": 1
        }
        """.trimIndent()

        val result = ResponseParser.parse(json)
        assertTrue("Should be Success", result is AiScanResult.Success)
        val success = result as AiScanResult.Success
        assertEquals(1, success.findings.size)
        assertEquals("Config.kt", success.findings[0].file)
        assertEquals(10, success.findings[0].line)
        assertEquals("API_KEY", success.findings[0].category)
        assertEquals("CRITICAL", success.findings[0].severity)
    }

    @Test
    fun `parses clean JSON with empty findings`() {
        val json = """{"findings": [], "analyzed_files": 3}"""

        val result = ResponseParser.parse(json)
        assertTrue("Should be Success", result is AiScanResult.Success)
        assertEquals(0, (result as AiScanResult.Success).findings.size)
    }

    @Test
    fun `parses multiple findings`() {
        val json = """
        {
            "findings": [
                {
                    "file": "A.kt", "line": 1, "category": "API_KEY",
                    "severity": "CRITICAL", "description": "Key found",
                    "snippet": "val k = \"abc\"", "suggestion": "Remove"
                },
                {
                    "file": "B.kt", "line": 5, "category": "PII",
                    "severity": "MEDIUM", "description": "Email logged",
                    "snippet": "log(email)", "suggestion": "Mask it"
                }
            ],
            "analyzed_files": 2
        }
        """.trimIndent()

        val result = ResponseParser.parse(json)
        assertTrue(result is AiScanResult.Success)
        assertEquals(2, (result as AiScanResult.Success).findings.size)
    }

    // Fallback extraction

    @Test
    fun `extracts JSON from markdown code fence`() {
        val wrapped = """
        Here are the results:
        ```json
        {"findings": [], "analyzed_files": 1}
        ```
        """.trimIndent()

        val result = ResponseParser.parse(wrapped)
        assertTrue("Should extract JSON from markdown", result is AiScanResult.Success)
    }

    @Test
    fun `extracts JSON with preamble text`() {
        val withPreamble = """
        I analyzed the code and found no issues.
        {"findings": [], "analyzed_files": 2}
        """.trimIndent()

        val result = ResponseParser.parse(withPreamble)
        assertTrue("Should extract JSON after preamble", result is AiScanResult.Success)
    }

    // Error cases

    @Test
    fun `returns ParseError for empty string`() {
        val result = ResponseParser.parse("")
        assertTrue("Empty input should be ParseError", result is AiScanResult.ParseError)
    }

    @Test
    fun `returns ParseError for blank string`() {
        val result = ResponseParser.parse("   ")
        assertTrue("Blank input should be ParseError", result is AiScanResult.ParseError)
    }

    @Test
    fun `returns ParseError for no JSON content`() {
        val result = ResponseParser.parse("No security issues found in the code.")
        assertTrue("Plain text should be ParseError", result is AiScanResult.ParseError)
    }

    @Test
    fun `returns ParseError for malformed JSON`() {
        val result = ResponseParser.parse("""{"findings": [incomplete""")
        assertTrue("Malformed JSON should be ParseError", result is AiScanResult.ParseError)
    }

    @Test
    fun `preserves raw response in ParseError`() {
        val raw = "Not JSON at all"
        val result = ResponseParser.parse(raw)
        assertTrue(result is AiScanResult.ParseError)
        assertEquals(raw, (result as AiScanResult.ParseError).rawResponse)
    }
}

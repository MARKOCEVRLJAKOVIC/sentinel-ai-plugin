package dev.marko.sentinelai.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AiScanResult] sealed class computed properties.
 *
 * Verifies [hasCriticalFindings] and [allFindings] behave correctly
 * across all sealed variants.
 */
class AiScanResultTest {

    private fun finding(severity: String = "HIGH") = AiFinding(
        file = "Test.kt",
        line = 1,
        category = "API_KEY",
        severity = severity,
        description = "Test finding",
        snippet = "val x = 123",
        suggestion = "Remove it"
    )

    // hasCriticalFindings

    @Test
    fun `hasCriticalFindings returns true when CRITICAL finding exists`() {
        val result = AiScanResult.Success(listOf(finding("CRITICAL")))
        assertTrue(result.hasCriticalFindings)
    }

    @Test
    fun `hasCriticalFindings returns true when mixed severities include CRITICAL`() {
        val result = AiScanResult.Success(listOf(
            finding("HIGH"),
            finding("CRITICAL"),
            finding("MEDIUM")
        ))
        assertTrue(result.hasCriticalFindings)
    }

    @Test
    fun `hasCriticalFindings returns false for HIGH-only findings`() {
        val result = AiScanResult.Success(listOf(finding("HIGH")))
        assertFalse(result.hasCriticalFindings)
    }

    @Test
    fun `hasCriticalFindings returns false for empty findings`() {
        val result = AiScanResult.Success(emptyList())
        assertFalse(result.hasCriticalFindings)
    }

    @Test
    fun `hasCriticalFindings returns false for ConnectionError`() {
        val result = AiScanResult.ConnectionError("timeout")
        assertFalse(result.hasCriticalFindings)
    }

    @Test
    fun `hasCriticalFindings returns false for ParseError`() {
        val result = AiScanResult.ParseError("bad json")
        assertFalse(result.hasCriticalFindings)
    }

    @Test
    fun `hasCriticalFindings returns false for NoHighRiskFiles`() {
        assertFalse(AiScanResult.NoHighRiskFiles.hasCriticalFindings)
    }

    // allFindings

    @Test
    fun `allFindings returns findings list for Success`() {
        val findings = listOf(finding(), finding("CRITICAL"))
        val result = AiScanResult.Success(findings)
        assertEquals(findings, result.allFindings)
    }

    @Test
    fun `allFindings returns empty list for empty Success`() {
        val result = AiScanResult.Success(emptyList())
        assertTrue(result.allFindings.isEmpty())
    }

    @Test
    fun `allFindings returns empty list for ConnectionError`() {
        val result = AiScanResult.ConnectionError("error")
        assertTrue(result.allFindings.isEmpty())
    }

    @Test
    fun `allFindings returns empty list for ParseError`() {
        val result = AiScanResult.ParseError("bad")
        assertTrue(result.allFindings.isEmpty())
    }

    @Test
    fun `allFindings returns empty list for NoHighRiskFiles`() {
        assertTrue(AiScanResult.NoHighRiskFiles.allFindings.isEmpty())
    }
}

package dev.marko.sentinelai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture

// ── AI Finding (Level 2 output) ─────────────────────────────────────────

@Serializable
data class AiFinding(
    val file: String,
    val line: Int,
    val category: String, // API_KEY | SYSTEM_PROMPT | PII | DB_CREDENTIAL | PRIVATE_KEY
    val severity: String, // CRITICAL | HIGH | MEDIUM
    val description: String,
    val snippet: String,
    val suggestion: String
)

@Serializable
data class SecurityFindingsResponse(
    val findings: List<AiFinding>,
    @SerialName("analyzed_files") val analyzedFiles: Int
)

// Scan Result (what VcsPushHandler receives)

sealed class AiScanResult {
    /** AI returned findings (list may be empty = clean) */
    data class Success(val findings: List<AiFinding>) : AiScanResult()

    /** AI responded but JSON could not be parsed */
    data class ParseError(val rawResponse: String) : AiScanResult()

    /** AI unreachable or HTTP error */
    data class ConnectionError(val message: String) : AiScanResult()

    /** No HIGH/CRITICAL files were in the diff — nothing to analyse */
    object NoHighRiskFiles : AiScanResult()

    val hasCriticalFindings: Boolean
        get() = this is Success && findings.any { it.severity == "CRITICAL" }

    val allFindings: List<AiFinding>
        get() = if (this is Success) findings else emptyList()
}

// Pending analysis state

/**
 * Shared state between CheckinHandler (producer) and PushHandler (consumer).
 * Stored as an IntelliJ project-level service (see plugin.xml).
 */
data class PendingAnalysis(
    val future: CompletableFuture<AiScanResult>,
    val commitHash: String = "", // optional: for logging
    val filesAnalysed: List<String> = emptyList()
)

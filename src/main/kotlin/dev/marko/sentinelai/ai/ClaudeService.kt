package dev.marko.sentinelai.ai

import com.intellij.openapi.diagnostic.Logger
import dev.marko.sentinelai.config.SentinelConfig
import dev.marko.sentinelai.scan.RiskLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates the Level 2 AI scan via the Claude Haiku API.
 *
 * Responsibilities:
 *  1. Filter incoming files to HIGH/CRITICAL only
 *  2. Enforce the never_send_to_cloud list — CRITICAL files bypass cloud AI
 *  3. Build the prompt via [PromptBuilder]
 *  4. Call [ClaudeClient] (suspend, non-blocking)
 *  5. Parse the response via [ResponseParser]
 *  6. Return an [AiScanResult] directly (caller launches the coroutine)
 *
 * The [Mutex] ensures at most one Claude call is in-flight at a time,
 * replacing the old single-thread executor.
 */
object ClaudeService {

    private val LOG = Logger.getInstance(ClaudeService::class.java)

    // Mutex — we never want two Claude calls racing
    private val scanMutex = Mutex()

    /**
     * Run a Level 2 analysis for [files].
     *
     * This is a suspend function — call it from a coroutine launched via
     * `serviceCoroutineScope`. The [Mutex] serialises concurrent calls.
     *
     * @param files   All changed files (will be filtered internally to MEDIUM+)
     * @param model   Claude model name (from .sentinel.yml, default claude-haiku-4-5)
     * @param apiKey  Anthropic API key (read from env variable)
     */
    suspend fun analyze(
        files: List<FileWithContent>,
        model: String = "claude-haiku-4-5",
        apiKey: String
    ): AiScanResult {

        if (apiKey.isBlank()) {
            LOG.warn("No API key configured — skipping Level 2 scan")
            return AiScanResult.ConnectionError("No API key. Set the SENTINEL_API_KEY environment variable.")
        }

        val highRiskFiles = files.filter { it.riskLevel >= RiskLevel.MEDIUM }

        if (highRiskFiles.isEmpty()) {
            LOG.info("No MEDIUM+ files — skipping Level 2 scan")
            return AiScanResult.NoHighRiskFiles
        }

        // Filter out files that must never be sent to cloud AI
        val cloudSafeFiles = highRiskFiles.filter { file ->
            val blocked = SentinelConfig.neverSendToCloud.any { pattern ->
                pattern.containsMatchIn(file.relativePath) || pattern.containsMatchIn(file.absolutePath)
            }
            if (blocked) {
                LOG.info("Skipping ${file.relativePath} — matches never_send_to_cloud")
            }
            !blocked
        }

        if (cloudSafeFiles.isEmpty()) {
            LOG.info("All HIGH/CRITICAL files are blocked by never_send_to_cloud — Level 1 only")
            return AiScanResult.NoHighRiskFiles
        }

        LOG.info("Starting Level 2 Claude scan for ${cloudSafeFiles.size} file(s)")

        return scanMutex.withLock {
            runScan(cloudSafeFiles, model, apiKey)
        }
    }

    // Internal

    private suspend fun runScan(
        files: List<FileWithContent>,
        model: String,
        apiKey: String
    ): AiScanResult {
        return try {
            val prompt = PromptBuilder.buildScanPrompt(files)
            LOG.debug("Level 2 scan prompt length = ${prompt.length}")

            val rawResponse = ClaudeClient.analyze(
                apiKey       = apiKey,
                userPrompt   = prompt,
                systemPrompt = SENTINEL_SYSTEM_PROMPT,
                model        = model
            )

            ResponseParser.parse(rawResponse)
        } catch (e: ClaudeException) {
            LOG.warn("Claude API error — ${e.message}")
            AiScanResult.ConnectionError(e.message ?: "Unknown error")
        }
    }
}

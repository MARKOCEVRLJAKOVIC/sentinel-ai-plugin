package dev.marko.sentinelai.ai

import com.intellij.openapi.diagnostic.Logger
import dev.marko.sentinelai.config.SentinelConfig
import dev.marko.sentinelai.scan.RiskLevel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private val LOG = Logger.getInstance(ClaudeService::class.java)

/**
 * Orchestrates the Level 2 AI scan via the Claude Haiku API.
 *
 * Responsibilities:
 *  1. Filter incoming files to HIGH/CRITICAL only
 *  2. Enforce the never_send_to_cloud list — CRITICAL files bypass cloud AI
 *  3. Build the prompt via [PromptBuilder]
 *  4. Call [ClaudeClient] on a background thread
 *  5. Parse the response via [ResponseParser]
 *  6. Return a [CompletableFuture<AiScanResult>] immediately (non-blocking)
 *
 * The [SentinelCheckinHandler] stores the future in [SentinelState]; the
 * [SentinelPushHandler] reads it when the developer tries to push.
 */
object ClaudeService {

    // Single-threaded executor — we never want two Claude calls racing
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SentinelAI-Claude").apply { isDaemon = true }
    }

    /**
     * Launch a Level 2 analysis for [files] asynchronously.
     *
     * @param files   All changed files (will be filtered internally to HIGH+)
     * @param model   Claude model name (from .sentinel.yml, default claude-haiku-4-5)
     * @param apiKey  Anthropic API key (read from env variable)
     */
    fun analyzeAsync(
        files: List<FileWithContent>,
        model: String = "claude-haiku-4-5",
        apiKey: String
    ): CompletableFuture<AiScanResult> {

        if (apiKey.isBlank()) {
            LOG.warn("SentinelAI: No API key configured — skipping Level 2 scan")
            return CompletableFuture.completedFuture(
                AiScanResult.ConnectionError("No API key. Set the SENTINEL_API_KEY environment variable.")
            )
        }

        val highRiskFiles = files.filter { it.riskLevel >= RiskLevel.HIGH }

        if (highRiskFiles.isEmpty()) {
            LOG.info("SentinelAI: No HIGH/CRITICAL files — skipping Level 2 scan")
            return CompletableFuture.completedFuture(AiScanResult.NoHighRiskFiles)
        }

        // Filter out files that must never be sent to cloud AI
        val cloudSafeFiles = highRiskFiles.filter { file ->
            val blocked = SentinelConfig.neverSendToCloud.any { pattern ->
                pattern.containsMatchIn(file.relativePath) || pattern.containsMatchIn(file.absolutePath)
            }
            if (blocked) {
                LOG.info("SentinelAI: Skipping ${file.relativePath} — matches never_send_to_cloud")
            }
            !blocked
        }

        if (cloudSafeFiles.isEmpty()) {
            LOG.info("SentinelAI: All HIGH/CRITICAL files are blocked by never_send_to_cloud — Level 1 only")
            return CompletableFuture.completedFuture(AiScanResult.NoHighRiskFiles)
        }

        LOG.info("SentinelAI: Starting async Level 2 Claude scan for ${cloudSafeFiles.size} file(s)")

        return CompletableFuture.supplyAsync({
            runScan(cloudSafeFiles, model, apiKey)
        }, executor)
    }

    // Internal

    private fun runScan(
        files: List<FileWithContent>,
        model: String,
        apiKey: String
    ): AiScanResult {
        return try {
            val prompt = PromptBuilder.buildScanPrompt(files)
            LOG.debug("SentinelAI: Level 2 scan prompt length = ${prompt.length}")

            val rawResponse = ClaudeClient.analyze(
                apiKey       = apiKey,
                userPrompt   = prompt,
                systemPrompt = SENTINEL_SYSTEM_PROMPT,
                model        = model
            )

            ResponseParser.parse(rawResponse)
        } catch (e: ClaudeException) {
            LOG.warn("SentinelAI: Claude API error — ${e.message}")
            AiScanResult.ConnectionError(e.message ?: "Unknown error")
        }
    }
}

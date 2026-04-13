package dev.marko.sentinelai

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private val LOG = Logger.getInstance(OllamaService::class.java)

/**
 * Orchestrates the Level 2 AI scan.
 *
 * Responsibilities:
 *  1. Filter incoming files to HIGH/CRITICAL only
 *  2. Build the prompt via [PromptBuilder]
 *  3. Call [OllamaClient] on a background thread
 *  4. Optionally run a "deep scan" second pass for each HIGH/CRITICAL file
 *  5. Parse the response via [ResponseParser]
 *  6. Return a [CompletableFuture<AiScanResult>] immediately (non-blocking)
 *
 * The [SentinelCheckinHandler] stores the future in [SentinelState]; the
 * [SentinelPushHandler] reads it when the developer tries to push.
 */
object OllamaService {

    // Single-threaded executor — we never want two Ollama calls racing
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SentinelAI-Ollama").apply { isDaemon = true }
    }

    /**
     * Launch a Level 2 analysis for [files] asynchronously.
     *
     * @param files        All changed files (will be filtered internally to HIGH+)
     * @param model        Ollama model name (from .sentinel.yml, default codellama:7b)
     * @param ollamaUrl    Base URL of the local Ollama server
     * @param runDeepScan  Whether to run a second full-file pass for each file
     */
    fun analyzeAsync(
        files: List<FileWithContent>,
        model: String = "codellama:7b",
        ollamaUrl: String = "http://localhost:11434",
        runDeepScan: Boolean = true
    ): CompletableFuture<AiScanResult> {

        val highRiskFiles = files.filter { it.riskLevel >= RiskLevel.HIGH }

        if (highRiskFiles.isEmpty()) {
            LOG.info("SentinelAI: No HIGH/CRITICAL files — skipping Level 2 scan")
            return CompletableFuture.completedFuture(AiScanResult.NoHighRiskFiles)
        }

        LOG.info("SentinelAI: Starting async Level 2 scan for ${highRiskFiles.size} file(s)")

        return CompletableFuture.supplyAsync({
            runScan(highRiskFiles, model, ollamaUrl, runDeepScan)
        }, executor)
    }

    // Internal

    private fun runScan(
        files: List<FileWithContent>,
        model: String,
        ollamaUrl: String,
        runDeepScan: Boolean
    ): AiScanResult {
        val allFindings = mutableListOf<AiFinding>()

        // Pass 1: Diff scan (all HIGH/CRITICAL files in one prompt)
        try {
            val diffPrompt = PromptBuilder.buildScanPrompt(files)
            LOG.debug("SentinelAI: Level 2 diff scan prompt length = ${diffPrompt.length}")

            val rawResponse = OllamaClient.generate(
                prompt       = diffPrompt,
                systemPrompt = SENTINEL_SYSTEM_PROMPT,
                model        = model,
                baseUrl      = ollamaUrl
            )

            when (val result = ResponseParser.parse(rawResponse)) {
                is AiScanResult.Success      -> allFindings.addAll(result.findings)
                is AiScanResult.ParseError   -> return result   // surface parser errors
                is AiScanResult.ConnectionError -> return result
                else                         -> Unit
            }
        } catch (e: OllamaException) {
            LOG.warn("SentinelAI: Ollama connection failed — ${e.message}")
            return AiScanResult.ConnectionError(e.message ?: "Unknown error")
        }

        // Pass 2: Deep scan per file (full file content, not just diff)
        if (runDeepScan) {
            files.forEach { file ->
                try {
                    val deepPrompt = PromptBuilder.buildDeepScanPrompt(file)
                    LOG.debug("SentinelAI: Deep scan for ${file.relativePath}")

                    val rawDeep = OllamaClient.generate(
                        prompt       = deepPrompt,
                        systemPrompt = SENTINEL_SYSTEM_PROMPT,
                        model        = model,
                        baseUrl      = ollamaUrl
                    )

                    val deepResult = ResponseParser.parse(rawDeep)
                    if (deepResult is AiScanResult.Success) {
                        // Only add findings not already caught in pass 1
                        val newFindings = deepResult.findings.filter { deepFinding ->
                            allFindings.none { it.file == deepFinding.file && it.line == deepFinding.line }
                        }
                        allFindings.addAll(newFindings)
                        if (newFindings.isNotEmpty()) {
                            LOG.info("SentinelAI: Deep scan found ${newFindings.size} additional issue(s) in ${file.relativePath}")
                        }
                    }
                } catch (e: OllamaException) {
                    // Deep scan failure is non-fatal — log and continue
                    LOG.warn("SentinelAI: Deep scan failed for ${file.relativePath}: ${e.message}")
                }
            }
        }

        LOG.info("SentinelAI: Level 2 scan complete — ${allFindings.size} total finding(s)")
        return AiScanResult.Success(allFindings)
    }
}
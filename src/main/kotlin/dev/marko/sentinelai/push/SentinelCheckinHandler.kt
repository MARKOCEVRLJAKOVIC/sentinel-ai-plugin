package dev.marko.sentinelai.push

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.checkin.CheckinHandler
import dev.marko.sentinelai.ai.AiScanResult
import dev.marko.sentinelai.ai.ClaudeService
import dev.marko.sentinelai.ai.PendingAnalysis
import dev.marko.sentinelai.ai.toFileWithContent
import dev.marko.sentinelai.config.SentinelConfig
import dev.marko.sentinelai.scan.Level1Scanner
import dev.marko.sentinelai.scan.RiskLevel
import dev.marko.sentinelai.scan.ScanFinding
import dev.marko.sentinelai.state.SentinelState
import dev.marko.sentinelai.ui.AiResultDialog
import dev.marko.sentinelai.ui.SentinelBlockDialog
import dev.marko.sentinelai.ui.showLevel2ErrorNotification
import dev.marko.sentinelai.ui.showLevel2ResultNotification
import dev.marko.sentinelai.ui.showLevel2SkippedNotification
import dev.marko.sentinelai.ui.showLevel2StartedNotification

private val LOG = Logger.getInstance(SentinelCheckinHandler::class.java)

/**
 * Level 1 (sync) + Level 2 trigger (async).
 *
 * Flow:
 *  1. Run Level 1 regex/PSI scan synchronously on ALL files.
 *     If CRITICAL findings: show block dialog, let developer decide.
 *  2. Launch Level 2 Claude Haiku scan asynchronously on MEDIUM+ files.
 *     Commit completes immediately; result is stored in SentinelState.
 *  3. PushHandler picks up the result when the developer hits "Push".
 */
class SentinelCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project
        val projectBasePath = project.basePath ?: ""

        LOG.info("SentinelAI: beforeCheckin() triggered — project=${project.name}")

        // Classify all changed files
        val allFiles = panel.virtualFiles.map { vf ->
            vf.toFileWithContent(projectBasePath)
        }
        LOG.info("SentinelAI: ${allFiles.size} file(s) in commit: ${allFiles.map { "${it.relativePath}[${it.riskLevel}]" }}")

        // Level 1: synchronous scan on ALL files
        val level1Findings = mutableListOf<ScanFinding>()
        allFiles.forEach { fileCtx ->
            level1Findings.addAll(Level1Scanner.scan(fileCtx.absolutePath, fileCtx.content))
        }

        if (level1Findings.isNotEmpty()) {
            LOG.info("SentinelAI: Level 1 found ${level1Findings.size} issue(s) — showing block dialog")
            val dialog = SentinelBlockDialog(project, level1Findings)
            dialog.show()

            if (!dialog.shouldCommitAnyway()) {
                LOG.info("SentinelAI: User chose 'Fix Issues' — cancelling commit")
                return ReturnResult.CANCEL
            }
            LOG.info("SentinelAI: User chose 'Commit Anyway' — proceeding to Level 2")
        }

        // Level 2: async Claude Haiku scan on MEDIUM+ files
        launchLevel2Scan(allFiles)

        return ReturnResult.COMMIT
    }

    private fun launchLevel2Scan(allFiles: List<dev.marko.sentinelai.ai.FileWithContent>) {
        val project = panel.project

        val highRiskFiles = allFiles.filter { it.riskLevel >= RiskLevel.MEDIUM }
        LOG.info("SentinelAI: ${highRiskFiles.size} file(s) qualify for Level 2 (MEDIUM+)")

        if (highRiskFiles.isEmpty()) {
            LOG.info("SentinelAI: No MEDIUM+ files — skipping Level 2")
            return
        }

        val apiKey = SentinelConfig.apiKey
        LOG.info("SentinelAI: API key present = ${apiKey.isNotBlank()}, env var = ${SentinelConfig.apiKeyEnvVar}")

        if (apiKey.isBlank()) {
            LOG.warn("SentinelAI: No API key — Level 2 scan cannot run")
            ApplicationManager.getApplication().invokeLater {
                showLevel2SkippedNotification(project,
                    "No API key. Set ${SentinelConfig.apiKeyEnvVar} environment variable and restart IntelliJ.")
            }
            return
        }

        try {
            val future = ClaudeService.analyzeAsync(
                files = highRiskFiles,
                model = SentinelConfig.aiModel,
                apiKey = apiKey
            )

            val fileNames = highRiskFiles.map { it.relativePath }

            SentinelState.getInstance(project).setPending(
                PendingAnalysis(
                    future = future,
                    filesAnalysed = fileNames
                )
            )

            LOG.info("SentinelAI: Level 2 async scan launched for ${fileNames.size} file(s)")
            ApplicationManager.getApplication().invokeLater {
                showLevel2StartedNotification(project, fileNames.size)
            }

            // Completion callback - show results dialog when Claude responds
            future.thenAccept { result ->
                LOG.info("SentinelAI: Level 2 scan completed — result type: ${result::class.simpleName}")
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is AiScanResult.Success -> {
                            if (result.findings.isEmpty()) {
                                showLevel2ResultNotification(project, 0, fileNames)
                            } else {
                                result.findings.forEach { f ->
                                    LOG.info("SentinelAI: Finding — ${f.category}/${f.severity} in ${f.file}:${f.line}: ${f.description}")
                                }
                                // Show full detail dialog with all findings
                                showLevel2ResultNotification(project, result.findings.size, fileNames)
                                AiResultDialog(project, result.findings).show()
                            }
                        }
                        is AiScanResult.ConnectionError -> {
                            showLevel2ErrorNotification(project, result.message)
                        }
                        is AiScanResult.ParseError -> {
                            showLevel2ErrorNotification(project, "Failed to parse AI response")
                            LOG.warn("SentinelAI: Parse error — raw: ${result.rawResponse.take(300)}")
                        }
                        is AiScanResult.NoHighRiskFiles -> {
                            LOG.info("SentinelAI: All files filtered by never_send_to_cloud — Level 1 only")
                        }
                    }
                }
            }.exceptionally { ex ->
                LOG.error("SentinelAI: Level 2 scan failed with exception", ex)
                ApplicationManager.getApplication().invokeLater {
                    showLevel2ErrorNotification(project, ex.message ?: "Unknown error")
                }
                null
            }
        } catch (e: Exception) {
            LOG.error("SentinelAI: Failed to launch Level 2 scan", e)
            ApplicationManager.getApplication().invokeLater {
                showLevel2ErrorNotification(project, e.message ?: "Failed to start AI scan")
            }
        }
    }
}
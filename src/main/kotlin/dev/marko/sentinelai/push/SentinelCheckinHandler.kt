package dev.marko.sentinelai.push

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.checkin.CheckinHandler
import dev.marko.sentinelai.ai.ClaudeService
import dev.marko.sentinelai.ai.PendingAnalysis
import dev.marko.sentinelai.ai.toFileWithContent
import dev.marko.sentinelai.config.SentinelConfig
import dev.marko.sentinelai.scan.Level1Scanner
import dev.marko.sentinelai.scan.RiskLevel
import dev.marko.sentinelai.scan.ScanFinding
import dev.marko.sentinelai.state.SentinelState
import dev.marko.sentinelai.ui.SentinelBlockDialog

/**
 * Level 1 (sync) + Level 2 trigger (async).
 *
 * Flow:
 *  1. Run Level 1 regex/PSI scan synchronously on MEDIUM+ files.
 *     If CRITICAL findings: show block dialog, let developer decide.
 *  2. Launch Level 2 Claude Haiku scan asynchronously on HIGH/CRITICAL files.
 *     Commit completes immediately; result is stored in SentinelState.
 *  3. PushHandler picks up the result when the developer hits "Push".
 */
class SentinelCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val projectBasePath = panel.project.basePath ?: ""

        // Classify all changed files
        val allFiles = panel.virtualFiles.map { vf ->
            vf.toFileWithContent(projectBasePath)
        }

        // Level 1: synchronous scan on MEDIUM+ files
        val level1Findings = mutableListOf<ScanFinding>()
        allFiles.forEach { fileCtx ->
            level1Findings.addAll(Level1Scanner.scan(fileCtx.absolutePath, fileCtx.content))
        }

        if (level1Findings.isNotEmpty()) {
            val dialog = SentinelBlockDialog(panel.project, level1Findings)
            dialog.show()

            // Developer chose to fix, cancel commit
            if (!dialog.shouldCommitAnyway()) {
                return ReturnResult.CANCEL
            }
            // Developer chose "Commit Anyway", proceed but still run AI scan
        }

        // Level 2: async Claude Haiku scan on HIGH/CRITICAL files
        val highRiskFiles = allFiles.filter { it.riskLevel >= RiskLevel.MEDIUM }
        if (highRiskFiles.isNotEmpty()) {
            val future = ClaudeService.analyzeAsync(
                files  = highRiskFiles,
                model  = SentinelConfig.aiModel,
                apiKey = SentinelConfig.apiKey
            )

            SentinelState.getInstance(panel.project).setPending(
                PendingAnalysis(
                    future = future,
                    filesAnalysed = highRiskFiles.map { it.relativePath }
                )
            )
        }

        return ReturnResult.COMMIT
    }
}
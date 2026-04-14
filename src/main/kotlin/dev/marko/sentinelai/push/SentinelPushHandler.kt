package dev.marko.sentinelai.push

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.marko.sentinelai.ai.AiFinding
import dev.marko.sentinelai.ai.AiScanResult
import dev.marko.sentinelai.ai.PendingAnalysis
import dev.marko.sentinelai.config.SentinelConfig
import dev.marko.sentinelai.config.TimeoutBehavior
import dev.marko.sentinelai.state.SentinelState
import dev.marko.sentinelai.ui.AiResultDialog
import dev.marko.sentinelai.ui.SentinelWaitDialog
import dev.marko.sentinelai.ui.showConnectionErrorNotification
import dev.marko.sentinelai.ui.showTimeoutNotification
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SentinelPushHandler(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(SentinelPushHandler::class.java)
    }

    fun checkBeforePush(): Boolean {
        val state = SentinelState.getInstance(project)
        val pending = state.takeAndClear()

        if (pending == null) {
            LOG.info("No pending AI analysis — push allowed")
            return true
        }

        LOG.info("Checking AI result for files: ${pending.filesAnalysed}")

        return if (pending.result.isCompleted) {
            val result = runBlocking { pending.result.await() }
            handleCompletedResult(result)
        } else {
            handleRunningAnalysis(pending)
        }
    }

    private fun handleCompletedResult(result: AiScanResult): Boolean {
        return when (result) {
            is AiScanResult.Success -> {
                if (result.findings.isEmpty()) {
                    LOG.info("AI scan clean — push allowed")
                    true
                } else {
                    LOG.info("AI found ${result.findings.size} issue(s)")
                    showFindingsAndDecide(result.findings)
                }
            }
            is AiScanResult.NoHighRiskFiles -> {
                LOG.info("No high-risk files — push allowed")
                true
            }
            is AiScanResult.ParseError -> {
                LOG.warn("Parse error, allowing push. Raw:\n${result.rawResponse.take(200)}")
                true
            }
            is AiScanResult.ConnectionError -> {
                LOG.warn("Connection error: ${result.message}")
                showConnectionErrorNotification(project, result.message)
                true
            }
        }
    }

    private fun handleRunningAnalysis(pending: PendingAnalysis): Boolean {
        val timeoutSec = SentinelConfig.aiTimeoutSeconds

        val waitDialog = SentinelWaitDialog(project, pending.result, timeoutSec)
        waitDialog.show()

        // If user clicked "Push Anyway"
        if (waitDialog.isOK) {
            logPushAnyway(pending, "timeout_user_action")
            return true
        }

        // Dialog closed because Deferred completed — get the result
        if (pending.result.isCompleted) {
            val result = runBlocking { pending.result.await() }
            return handleCompletedResult(result)
        }

        // Timeout or cancel
        return handleTimeout(pending)
    }

    private fun handleTimeout(pending: PendingAnalysis): Boolean {
        logPushAnyway(pending, "timeout_auto")
        return when (SentinelConfig.timeoutBehavior) {
            TimeoutBehavior.ALLOW -> {
                LOG.info("Timeout — ALLOW policy, push proceeds")
                true
            }
            TimeoutBehavior.WARN -> {
                showTimeoutNotification(project, SentinelConfig.aiTimeoutSeconds)
                true
            }
            TimeoutBehavior.BLOCK -> {
                Messages.showErrorDialog(
                    project,
                    "SentinelAI AI scan did not complete within ${SentinelConfig.aiTimeoutSeconds}s.\n" +
                            "Push blocked (timeout_behavior = block in .sentinel.yml).\n" +
                            "Check your network and API key, or change timeout_behavior to 'warn'.",
                    "SentinelAI — Push Blocked"
                )
                false
            }
        }
    }

    private fun showFindingsAndDecide(findings: List<AiFinding>): Boolean {
        val dialog = AiResultDialog(project, findings)
        dialog.show()

        return if (dialog.shouldPushAnyway()) {
            logPushAnyway(null, "user_override_findings", findings)
            true
        } else {
            false
        }
    }

    private fun logPushAnyway(
        pending: PendingAnalysis?,
        reason: String,
        findings: List<AiFinding> = emptyList()
    ) {
        LOG.warn(
            "Push allowed despite AI scan — reason=$reason, " +
                    "files=${pending?.filesAnalysed}, findings=${findings.size}"
        )
    }
}
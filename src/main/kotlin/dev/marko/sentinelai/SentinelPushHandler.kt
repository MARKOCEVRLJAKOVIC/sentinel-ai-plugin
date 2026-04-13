package dev.marko.sentinelai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOG = Logger.getInstance(SentinelPushHandler::class.java)

class SentinelPushHandler(private val project: Project) {

    fun checkBeforePush(): Boolean {
        val state = SentinelState.getInstance(project)
        val pending = state.takeAndClear()

        if (pending == null) {
            LOG.info("SentinelAI: No pending AI analysis — push allowed")
            return true
        }

        LOG.info("SentinelAI: Checking AI result for files: ${pending.filesAnalysed}")

        return if (pending.future.isDone) {
            handleCompletedFuture(pending.future.get())
        } else {
            handleRunningFuture(pending)
        }
    }

    private fun handleCompletedFuture(result: AiScanResult): Boolean {
        return when (result) {
            is AiScanResult.Success -> {
                if (result.findings.isEmpty()) {
                    LOG.info("SentinelAI: AI scan clean — push allowed")
                    true
                } else {
                    LOG.info("SentinelAI: AI found ${result.findings.size} issue(s)")
                    showFindingsAndDecide(result.findings)
                }
            }
            // ISPRAVKA 1: uklonjen višak razmaka u tipu
            is AiScanResult.NoHighRiskFiles -> {
                LOG.info("SentinelAI: No high-risk files — push allowed")
                true
            }
            is AiScanResult.ParseError -> {
                LOG.warn("SentinelAI: Parse error, allowing push. Raw:\n${result.rawResponse.take(200)}")
                true
            }
            is AiScanResult.ConnectionError -> {
                LOG.warn("SentinelAI: Connection error: ${result.message}")
                showConnectionErrorNotification(project, result.message)
                true
            }
        }
    }

    private fun handleRunningFuture(pending: PendingAnalysis): Boolean {
        val timeoutSec = SentinelConfig.aiTimeoutSeconds

        val waitDialog = SentinelWaitDialog(project, pending.future, timeoutSec)
        waitDialog.show()

        val result = pollWithDialog(waitDialog, pending, timeoutSec)

        if (waitDialog.isOK && result == null) {
            logPushAnyway(pending, "timeout_user_action")
            return true
        }

        return if (result != null) {
            handleCompletedFuture(result)
        } else {
            handleTimeout(pending)
        }
    }

    private fun pollWithDialog(
        dialog: DialogWrapper,
        pending: PendingAnalysis,
        timeoutSec: Int
    ): AiScanResult? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L

        while (System.currentTimeMillis() < deadline) {
            if (dialog.isOK) return null
            try {
                return pending.future.get(500, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // continue polling
            }
        }
        return null
    }

    private fun handleTimeout(pending: PendingAnalysis): Boolean {
        logPushAnyway(pending, "timeout_auto")
        return when (SentinelConfig.timeoutBehavior) {
            TimeoutBehavior.ALLOW -> {
                LOG.info("SentinelAI: Timeout — ALLOW policy, push proceeds")
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
                            "Start Ollama and try again, or change timeout_behavior to 'warn'.",
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
            "SentinelAI: Push allowed despite AI scan — reason=$reason, " +
                    "files=${pending?.filesAnalysed}, findings=${findings.size}"
        )
    }
}

enum class TimeoutBehavior { WARN, BLOCK, ALLOW }
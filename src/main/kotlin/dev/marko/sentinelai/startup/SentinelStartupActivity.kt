package dev.marko.sentinelai.startup

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import dev.marko.sentinelai.push.SentinelPushHandler
import dev.marko.sentinelai.ai.ClaudeClient
import dev.marko.sentinelai.config.SentinelConfig
import dev.marko.sentinelai.state.SentinelState
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val LOG = Logger.getInstance("SentinelAI.Startup")

/**
 * Runs once when a project opens.
 *
 * Responsibilities:
 *  1. Load .sentinel.yml config
 *  2. Optionally run Claude API health check and notify if unavailable
 *  3. Log which model is configured
 */
class SentinelStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        SentinelConfig.reload(basePath)

        LOG.info("Initialized for project '${project.name}' — model=${SentinelConfig.aiModel}")

        val apiKey = SentinelConfig.apiKey
        if (apiKey.isBlank()) {
            LOG.warn("No API key found in env var '${SentinelConfig.apiKeyEnvVar}'. Level 2 AI scanning is disabled.")
            return
        }

        // Background health check — now properly suspends instead of blocking
        val healthError = ClaudeClient.healthCheck(
            apiKey = apiKey,
            model  = SentinelConfig.aiModel
        )
        if (healthError != null) {
            LOG.warn("Claude health check failed — $healthError")
        } else {
            LOG.info("Claude API reachable, model '${SentinelConfig.aiModel}' validated ✓")
        }
    }
}

/**
 * Listens for Git repository changes and intercepts pushes.
 *
 * Uses [serviceCoroutineScope] to launch the push check on the EDT
 * without blocking the listener callback thread.
 */
class SentinelGitPushListener : GitRepositoryChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun repositoryChanged(repository: GitRepository) {
        val project = repository.project
        val state = SentinelState.getInstance(project)
        if (state.hasPending) {
            scope.launch(Dispatchers.EDT) {
                SentinelPushHandler(project).checkBeforePush()
            }
        }
    }
}

/**
 * Action registered in the Push dialog toolbar (optional UI enhancement).
 *
 * Adds a "SentinelAI Status" indicator button that shows:
 *  - AI scan complete, no issues
 *  - AI scan running…
 *  - Issues found — click to review
 *
 * Register this in plugin.xml under <actions> if desired.
 */
class SentinelPushStatusAction : AnAction(
    "SentinelAI Check",
    "Check AI scan result before pushing",
    null  // Add an icon here via IconLoader.getIcon() for production
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val handler = SentinelPushHandler(project)
        val allowed = handler.checkBeforePush()
        if (allowed) {
            Messages.showInfoMessage(
                project,
                "SentinelAI: No blocking issues found. Safe to push.",
                "SentinelAI — All Clear"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run { e.presentation.isEnabled = false; return }
        val hasPending = SentinelState.getInstance(project).hasPending
        e.presentation.text = if (hasPending) "SentinelAI (scanning…)" else "SentinelAI"
    }
}
package dev.marko.sentinelai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

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

        LOG.info("SentinelAI: Initialized for project '${project.name}' — model=${SentinelConfig.aiModel}")

        val apiKey = SentinelConfig.apiKey
        if (apiKey.isBlank()) {
            LOG.warn("SentinelAI: No API key found in env var '${SentinelConfig.apiKeyEnvVar}'. Level 2 AI scanning is disabled.")
            return
        }

        // Background health check, only warn, never block startup
        val healthError = ClaudeClient.healthCheck(
            apiKey = apiKey,
            model  = SentinelConfig.aiModel
        )
        if (healthError != null) {
            LOG.warn("SentinelAI: Claude health check failed — $healthError")
            // You can show a balloon notification here using NotificationGroupManager
            // For MVP: just log; developer will see the error on push
        } else {
            LOG.info("SentinelAI: Claude API reachable, model '${SentinelConfig.aiModel}' validated ✓")
        }
    }
}

/**
 * Listens for Git repository changes and intercepts pushes.
 *
 * NOTE: In IntelliJ, there is no clean single "before push" hook that reliably
 * blocks. The most practical MVP approach is to hook into the push via the
 * CheckinHandler (Strategy A from plugin.xml), which already fires for the
 * combined Commit+Push action.
 *
 * This listener is kept here as a hook point for future Strategy C (git hook
 * script generation), where the plugin writes a pre-push hook to .git/hooks/.
 *
 * For now: it monitors repository state changes so SentinelState can be
 * cleared if a push completes externally (e.g., via terminal).
 */
class SentinelGitPushListener : GitRepositoryChangeListener {

    override fun repositoryChanged(repository: GitRepository) {
        val project = repository.project
        val state = SentinelState.getInstance(project)
        if (state.hasPending) {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeLater {
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
class SentinelPushStatusAction : com.intellij.openapi.actionSystem.AnAction(
    "SentinelAI Check",
    "Check AI scan result before pushing",
    null  // Add an icon here via IconLoader.getIcon() for production
) {
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: return
        val handler = SentinelPushHandler(project)
        val allowed = handler.checkBeforePush()
        if (allowed) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "SentinelAI: No blocking issues found. Safe to push.",
                "SentinelAI — All Clear"
            )
        }
    }

    override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: run { e.presentation.isEnabled = false; return }
        val hasPending = SentinelState.getInstance(project).hasPending
        e.presentation.text = if (hasPending) "SentinelAI (scanning…)" else "SentinelAI"
    }
}
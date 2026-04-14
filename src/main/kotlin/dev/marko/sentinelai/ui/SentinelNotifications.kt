package dev.marko.sentinelai.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

private const val NOTIFICATION_GROUP = "SentinelAI"

// Level 2 lifecycle notifications

fun showLevel2StartedNotification(project: Project, fileCount: Int) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            "SentinelAI — AI Scan Started",
            "Level 2 Claude Haiku scan launched for $fileCount file(s). " +
                    "Results will appear when analysis completes.",
            NotificationType.INFORMATION
        )
        .notify(project)
}

fun showLevel2SkippedNotification(project: Project, reason: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            "SentinelAI — AI Scan Skipped",
            "Level 2 scan was not started: $reason",
            NotificationType.WARNING
        )
        .notify(project)
}

fun showLevel2ResultNotification(project: Project, findingsCount: Int, files: List<String>) {
    if (findingsCount == 0) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "SentinelAI — AI Scan Clean ✓",
                "Claude Haiku found no security issues in ${files.size} file(s).",
                NotificationType.INFORMATION
            )
            .notify(project)
    } else {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "SentinelAI — ⚠ $findingsCount Issue(s) Found",
                "Claude Haiku detected $findingsCount security issue(s). " +
                        "Push will be gated — review findings before pushing.",
                NotificationType.WARNING
            )
            .notify(project)
    }
}

fun showLevel2ErrorNotification(project: Project, error: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            "SentinelAI — AI Scan Error",
            "Level 2 scan failed: $error",
            NotificationType.ERROR
        )
        .notify(project)
}

// Push-time notifications

fun showTimeoutNotification(project: Project, timeoutSeconds: Int) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            "SentinelAI - Scan Timeout",
            "AI scan did not complete within ${timeoutSeconds}s. " +
                    "Push allowed (timeout_behavior = warn). " +
                    "Consider increasing ai_timeout_seconds in .sentinel.yml.",
            NotificationType.WARNING
        )
        .notify(project)
}

fun showConnectionErrorNotification(project: Project, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            "SentinelAI - Claude API Unreachable",
            "AI scan skipped: $message\n" +
                    "Push allowed. Check your API key and network to enable Level 2 scanning.",
            NotificationType.WARNING
        )
        .notify(project)
}
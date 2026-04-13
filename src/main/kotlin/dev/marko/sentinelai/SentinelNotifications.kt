package dev.marko.sentinelai

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

private const val NOTIFICATION_GROUP = "SentinelAI"

fun showTimeoutNotification(project: Project, timeoutSeconds: Int) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            "SentinelAI — Scan Timeout",
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
            "SentinelAI — Ollama Unreachable",
            "AI scan skipped: $message\n" +
                    "Push allowed. Start Ollama to enable Level 2 scanning.",
            NotificationType.WARNING
        )
        .notify(project)
}
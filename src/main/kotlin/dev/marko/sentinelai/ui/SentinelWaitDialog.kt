package dev.marko.sentinelai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.marko.sentinelai.ai.AiScanResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.util.concurrent.CompletableFuture
import javax.swing.*

class SentinelWaitDialog(
    private val project: Project,
    private val future: CompletableFuture<AiScanResult>,
    private val timeoutSeconds: Int
) : DialogWrapper(project) {

    private val progressBar   = JProgressBar(0, timeoutSeconds * 2) // tick every 500ms
    private val statusLabel   = JBLabel("AI scan in progress…")
    private var tickCount     = 0

    private val timer: Timer = Timer(500) {
        tickCount++
        progressBar.value = tickCount

        val secondsElapsed = tickCount / 2
        val remaining      = timeoutSeconds - secondsElapsed
        statusLabel.text   = if (remaining > 0)
            "AI scan in progress… (${remaining}s remaining)"
        else
            "Scan taking longer than expected…"

        if (future.isDone) {
            timer.stop()
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = "SentinelAI — Waiting for AI Scan"
        setOKButtonText("Push Anyway")
        setCancelButtonText("Cancel Push")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 16))
        panel.border = JBUI.Borders.empty(20, 24)
        panel.preferredSize = java.awt.Dimension(480, 160)

        // Header
        val header = JBLabel("🔍  SentinelAI is scanning your changes")
        header.font = header.font.deriveFont(Font.BOLD, 13f)
        header.foreground = Color(30, 100, 180)
        panel.add(header, BorderLayout.NORTH)

        // Center: status + progress bar
        val centerPanel = JPanel(BorderLayout(0, 8))
        centerPanel.isOpaque = false

        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 12f)
        statusLabel.foreground = Color.DARK_GRAY
        centerPanel.add(statusLabel, BorderLayout.NORTH)

        progressBar.isIndeterminate = false
        progressBar.value           = 0
        progressBar.isStringPainted = false
        centerPanel.add(progressBar, BorderLayout.CENTER)

        panel.add(centerPanel, BorderLayout.CENTER)

        // Footer
        val footer = JBLabel("'Push Anyway' skips the AI check. Result will be logged.")
        footer.font = footer.font.deriveFont(Font.ITALIC, 11f)
        footer.foreground = Color.GRAY
        panel.add(footer, BorderLayout.SOUTH)

        return panel
    }

    override fun show() {
        timer.start()
        super.show()
    }

    override fun doCancelAction() {
        timer.stop()
        super.doCancelAction()
    }

    override fun doOKAction() {
        timer.stop()
        super.doOKAction()
    }

    override fun dispose() {
        timer.stop()
        super.dispose()
    }
}
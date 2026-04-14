package dev.marko.sentinelai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.marko.sentinelai.ai.AiFinding
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*

class AiResultDialog(
    private val project: Project,
    private val findings: List<AiFinding>
) : DialogWrapper(project) {

    init {
        title = "SentinelAI — AI Scan Results"
        setCancelButtonText("Close")
        setOKButtonText("Acknowledge & Continue")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.border = JBUI.Borders.empty(12)
        panel.preferredSize = java.awt.Dimension(660, 420)

        // Header
        val criticalCount = findings.count { it.severity == "CRITICAL" }
        val highCount     = findings.count { it.severity == "HIGH" }

        val headerText = buildString {
            append("AI scan found ${findings.size} issue(s)")
            if (criticalCount > 0) append("  •  $criticalCount CRITICAL")
            if (highCount     > 0) append("  •  $highCount HIGH")
        }
        val header = JBLabel(headerText)
        header.font = header.font.deriveFont(Font.BOLD, 14f)
        header.foreground = Color(180, 30, 30)
        panel.add(header, BorderLayout.NORTH)

        // Findings list
        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.background = UIManager.getColor("EditorPane.background")

        findings.forEach { finding ->
            listPanel.add(createFindingCard(finding))
            listPanel.add(Box.createVerticalStrut(8))
        }

        val scrollPane = JBScrollPane(listPanel)
        scrollPane.border = BorderFactory.createLineBorder(Color(200, 60, 60), 1)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Footer warning
        val footer = JBLabel("Pushing secrets exposes credentials. 'Push Anyway' will be logged.")
        footer.font = footer.font.deriveFont(Font.ITALIC, 11f)
        footer.foreground = Color.GRAY
        panel.add(footer, BorderLayout.SOUTH)

        return panel
    }

    private fun createFindingCard(finding: AiFinding): JPanel {
        val severityColor = when (finding.severity) {
            "CRITICAL" -> Color(180, 30, 30)
            "HIGH"     -> Color(200, 100, 0)
            else       -> Color(150, 130, 0)
        }
        val bgColor = when (finding.severity) {
            "CRITICAL" -> Color(255, 240, 240)
            "HIGH"     -> Color(255, 248, 235)
            else       -> Color(255, 255, 235)
        }

        val card = JPanel(BorderLayout(8, 4))
        card.border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(severityColor, 1),
            JBUI.Borders.empty(8)
        )
        card.background = bgColor
        card.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 110)

        // Top row: severity badge + file location
        val topPanel = JPanel(BorderLayout())
        topPanel.isOpaque = false

        val badge = JBLabel("[${finding.severity}]  ${finding.category}  —  ${finding.description}")
        badge.font = badge.font.deriveFont(Font.BOLD, 12f)
        badge.foreground = severityColor

        val location = JBLabel("${shortenPath(finding.file)} : line ${finding.line}")
        location.font = location.font.deriveFont(Font.PLAIN, 11f)
        location.foreground = Color.GRAY

        topPanel.add(badge,    BorderLayout.WEST)
        topPanel.add(location, BorderLayout.EAST)

        // Snippet
        val snippet = JBLabel("  ${finding.snippet}")
        snippet.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        snippet.foreground = Color(60, 60, 60)
        snippet.border = JBUI.Borders.empty(3, 0, 2, 0)

        // Suggestion
        val suggestion = JBLabel("  ➜  ${finding.suggestion}")
        suggestion.font = suggestion.font.deriveFont(Font.ITALIC, 11f)
        suggestion.foreground = Color(0, 120, 0)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.isOpaque = false
        bottomPanel.add(snippet,    BorderLayout.NORTH)
        bottomPanel.add(suggestion, BorderLayout.CENTER)

        card.add(topPanel,    BorderLayout.NORTH)
        card.add(bottomPanel, BorderLayout.CENTER)

        return card
    }

    private fun shortenPath(path: String): String {
        val parts = path.replace("\\", "/").split("/")
        return if (parts.size > 3) ".../${parts.takeLast(3).joinToString("/")}" else path
    }

    fun shouldPushAnyway(): Boolean = isOK
}
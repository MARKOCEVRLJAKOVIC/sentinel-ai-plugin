package dev.marko.sentinelai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.marko.sentinelai.scan.ScanFinding
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*

class SentinelBlockDialog(
    private val project: Project,
    private val findings: List<ScanFinding>
) : DialogWrapper(project) {

    init {
        title = "SentinelAI — Security Issues Detected"
        setCancelButtonText("Fix Issues (Cancel Commit)")
        setOKButtonText("Commit Anyway")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.border = JBUI.Borders.empty(12)
        panel.preferredSize = java.awt.Dimension(620, 400)

        // --- Header ---
        val header = JPanel(BorderLayout())
        val icon = JBLabel("${findings.size} security issue(s) found before commit")
        icon.font = icon.font.deriveFont(Font.BOLD, 14f)
        icon.foreground = Color(200, 80, 0)
        header.add(icon, BorderLayout.CENTER)
        panel.add(header, BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.background = UIManager.getColor("EditorPane.background")

        findings.forEach { finding ->
            val card = createFindingCard(finding)
            listPanel.add(card)
            listPanel.add(Box.createVerticalStrut(8))
        }

        val scrollPane = JBScrollPane(listPanel)
        scrollPane.border = BorderFactory.createLineBorder(Color(200, 60, 60), 1)
        panel.add(scrollPane, BorderLayout.CENTER)

        val footer = JBLabel("Committing secrets may expose credentials. Review findings above.")
        footer.font = footer.font.deriveFont(Font.ITALIC, 11f)
        footer.foreground = Color.GRAY
        panel.add(footer, BorderLayout.SOUTH)

        return panel
    }

    private fun createFindingCard(finding: ScanFinding): JPanel {
        val card = JPanel(BorderLayout(8, 4))
        card.border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(Color(220, 100, 100), 1),
            JBUI.Borders.empty(8)
        )
        card.background = Color(255, 245, 245)
        card.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 90)

        val topPanel = JPanel(BorderLayout())
        topPanel.isOpaque = false

        val desc = JBLabel("🔴  ${finding.description}")
        desc.font = desc.font.deriveFont(Font.BOLD, 12f)
        desc.foreground = Color(180, 30, 30)

        val location = JBLabel("${shortenPath(finding.file)} : line ${finding.line}")
        location.font = location.font.deriveFont(Font.PLAIN, 11f)
        location.foreground = Color.GRAY

        topPanel.add(desc, BorderLayout.WEST)
        topPanel.add(location, BorderLayout.EAST)

        // Snippet
        val snippet = JBLabel("  ${finding.snippet}")
        snippet.font = Font("JetBrains Mono", Font.PLAIN, 11).let {
            // fallback
            if (it.family == "JetBrains Mono") it else Font(Font.MONOSPACED, Font.PLAIN, 11)
        }
        snippet.foreground = Color(80, 80, 80)
        snippet.border = JBUI.Borders.empty(4, 0, 0, 0)

        card.add(topPanel, BorderLayout.NORTH)
        card.add(snippet, BorderLayout.CENTER)

        return card
    }

    private fun shortenPath(path: String): String {
        val parts = path.replace("\\", "/").split("/")
        return if (parts.size > 3) ".../${parts.takeLast(3).joinToString("/")}"
        else path
    }

    fun shouldCommitAnyway(): Boolean = isOK
}
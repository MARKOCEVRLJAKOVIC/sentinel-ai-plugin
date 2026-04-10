package dev.marko.sentinelai

import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.CheckinProjectPanel

class SentinelCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val findings = mutableListOf<ScanFinding>()

        panel.virtualFiles.forEach { file ->
            val filePath = file.path
            val riskLevel = RiskMapEngine.classify(filePath)

            println("===SENTINEL: $filePath → $riskLevel")

            if (riskLevel >= RiskLevel.MEDIUM) {
                val content = String(file.contentsToByteArray())
                val fileFindings = Level1Scanner.scan(filePath, content)
                findings.addAll(fileFindings)
            }
        }

        if (findings.isNotEmpty()) {
            findings.forEach { finding ->
                println("=== SENTINEL FINDING: [${finding.file}:${finding.line}] ${finding.description}")
                println("    ${finding.snippet}")
            }

            // TODO: Instead of printing add dialog block
            return ReturnResult.COMMIT
        }

        println("=== SENTINEL: No issues found, commit allowed")
        return ReturnResult.COMMIT
    }
}
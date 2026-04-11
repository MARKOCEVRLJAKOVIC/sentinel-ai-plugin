package dev.marko.sentinelai

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.checkin.CheckinHandler

class SentinelCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val findings = mutableListOf<ScanFinding>()

        panel.virtualFiles.forEach { file ->
            val riskLevel = RiskMapEngine.classify(file.path)
            if (riskLevel >= RiskLevel.MEDIUM) {
                val content = String(file.contentsToByteArray())
                findings.addAll(Level1Scanner.scan(file.path, content))
            }
        }

        if (findings.isEmpty()) return ReturnResult.COMMIT

        val dialog = SentinelBlockDialog(panel.project, findings)
        dialog.show()

        return if (dialog.shouldCommitAnyway()) {
            ReturnResult.COMMIT
        } else {
            ReturnResult.CANCEL
        }
    }
}
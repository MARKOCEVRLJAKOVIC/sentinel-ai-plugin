package dev.marko.sentinelai.push

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import dev.marko.sentinelai.SentinelCheckinHandler
import git4idea.GitVcs

class SentinelCheckinHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {
    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return SentinelCheckinHandler(panel)
    }
}
package dev.marko.sentinelai.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference
import dev.marko.sentinelai.ai.PendingAnalysis

/**
 * Project-level service that bridges [SentinelCheckinHandler] (commit time)
 * with [SentinelPushHandler] (push time).
 *
 * Lifecycle:
 *  1. CheckinHandler stores a Deferred<AiScanResult> here after commit
 *  2. PushHandler reads & clears it before each push
 *
 * Registered in plugin.xml as:
 *  <projectService serviceImplementation="dev.marko.sentinelai.state.SentinelState"/>
 */
@Service(Service.Level.PROJECT)
class SentinelState {

    // AtomicReference makes read-then-clear safe across threads without locking
    private val pendingRef = AtomicReference<PendingAnalysis?>(null)

    /** Store a new pending analysis. Called from CheckinHandler on EDT. */
    fun setPending(analysis: PendingAnalysis) {
        // Cancel any leftover deferred from a previous (possibly abandoned) commit
        pendingRef.getAndSet(analysis)?.result?.cancel()
    }

    /**
     * Read and atomically clear the pending analysis.
     * Returns null if no analysis is pending (clean commit, or no HIGH-risk files).
     */
    fun takeAndClear(): PendingAnalysis? = pendingRef.getAndSet(null)

    /** True if an analysis is currently running or waiting to be consumed. */
    val hasPending: Boolean get() = pendingRef.get() != null

    companion object {
        fun getInstance(project: Project): SentinelState = project.service()
    }
}
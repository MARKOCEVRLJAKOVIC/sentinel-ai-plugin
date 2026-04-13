package dev.marko.sentinelai

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-level service that bridges [SentinelCheckinHandler] (commit time)
 * with [SentinelPushHandler] (push time).
 *
 * Lifecycle:
 *  1. CheckinHandler stores a CompletableFuture<AiScanResult> here after commit
 *  2. PushHandler reads & clears it before each push
 *
 * Registered in plugin.xml as:
 *  <projectService serviceImplementation="dev.marko.sentinelai.SentinelState"/>
 */
@Service(Service.Level.PROJECT)
class SentinelState {

    // AtomicReference makes read-then-clear safe across threads without locking
    private val pendingRef = AtomicReference<PendingAnalysis?>(null)

    /** Store a new pending analysis. Called from CheckinHandler on EDT. */
    fun setPending(analysis: PendingAnalysis) {
        // Cancel any leftover future from a previous (possibly abandoned) commit
        pendingRef.getAndSet(analysis)?.future?.cancel(true)
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
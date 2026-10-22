package com.gtrainer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.time.Instant

internal data class CapturedScopedQueueJob(val job: ReviewQueueJob,val request: ScopedEvidenceRequest,
    val evidence: CapturedScopedEvidence,val revisions: ScopedRoutingRevisions)

/** One worker's inert profile adapter. No provider construction, execution admission or inference path. */
internal class PersonalScopedQueueAdapter(private val store: HistoryStore,private val clock: java.time.Clock=java.time.Clock.systemUTC()) {
    suspend fun prepare(job: ReviewQueueJob) {
        val snapshot=prepareFacts(job)
        val intent=requireNotNull(job.scopedIntent)
        val selection=intent.providerSelection ?: return
        if(snapshot.state!="PREPARED") return
        // Optional preview failure cannot undo/retry the already committed factual result.
        try {
            currentCoroutineContext().ensureActive()
            val prepared=ScopedExecutionService(store,clock).assemble(intent.profile,ScopedExecutionSource(snapshotId=snapshot.snapshotId),selection)
            currentCoroutineContext().ensureActive()
            store.attachScopedQueuePreview(job,snapshot.snapshotId,prepared)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { store.blockScopedQueuePreview(job.id,snapshot.snapshotId) }
    }

    private suspend fun prepareFacts(job: ReviewQueueJob): ScopedEvidenceSnapshot {
        // One recapture tolerates an unrelated owner write. This is not a provider/correction retry.
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            if(job.scopedIntent?.adaptation!=null) {
                val capture=store.captureAdaptationResult(job,clock.instant())
                val prepared=PreparedAdaptationResult.prepare(capture)
                currentCoroutineContext().ensureActive()
                try { return store.publishAdaptationResult(prepared,clock.instant()) }
                catch(conflict: ScopedEvidenceConflict) { if(attempt==1) throw conflict }
                catch(conflict: PlanConflict) { if(attempt==1) throw conflict }
                return@repeat
            }
            val adaptation=if(job.scopedIntent?.purpose in setOf("morning","evening")) store.captureBriefAdaptation(job,clock.instant())?.let {
                val prepared=PreparedAdaptationResult.prepare(it)
                store.publishAdaptationResult(prepared,clock.instant()).also { snapshot -> require(snapshot.state=="PREPARED") { "Adaptation result preparation blocked" } }
            } else null
            val captured=store.captureScopedQueueJob(job,clock,adaptation?.packet?.adaptation?.workKey)
            val read=captured.evidence.read
            val report=ScopedTrends.report(read.scope,read.history,read.statuses)
            val prepared=prepareScopedEvidence(captured.request,captured.evidence,report)
            currentCoroutineContext().ensureActive()
            try { return store.publishScopedQueueJob(captured,prepared,clock.instant()) }
            catch(conflict: ScopedEvidenceConflict) { if(attempt==1) throw conflict }
        }
        throw ScopedEvidenceConflict()
    }
}

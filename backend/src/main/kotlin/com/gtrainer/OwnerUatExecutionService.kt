package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
internal data class OwnerUatJobRequest(val policySha256: String,val packetSha256: String) {
    init { for(value in listOf(policySha256,packetSha256)) require(value.matches(Regex("[a-f0-9]{64}"))) }
}

@Serializable
data class OwnerUatJobStatus(val executionId: String,val state: String,val reason: String?,
    val runtimePhase: String?,val attempts: Int,val deadlineUtc: String?)

internal data class OwnerUatSubmission(val accepted: Boolean,val reason: String?,val status: OwnerUatJobStatus)

@Serializable
data class OwnerUatRequestAdmission(val manualRequestAllowed: Boolean,val requestAdmissionState: String,
    val blockingReasons: List<String>)

internal fun unavailableOwnerUatRequest()=OwnerUatRequestAdmission(false,"service_unavailable",listOf("owner_uat_service_unavailable"))

/** Normal-app manual jobs. Default application wiring supplies no runtime/factory.
 * Only the durable ownership claim acknowledges a request; browser loss never cancels its job. */
class OwnerUatExecutionService internal constructor(
    private val store: HistoryStore,
    private val policy: OwnerUatAdmission,
    control: PersonalRuntimeControl,
    private val factory: PersonalProviderFactory,
    private val scope: CoroutineScope,
    clock: Clock=Clock.systemUTC(),
    nanos: ()->Long=System::nanoTime,
    supervision: OwnerUatSupervisorClient?=null,
) {
    private val authority=PersonalReviewRuntimeAdmission(store,control,clock=clock,nanos=nanos,
        limits=ConnectedReviewExecutionLimits.ownerLocalUat(),ownerUat=policy,supervision=supervision)
    private class Pending(val acknowledged: CompletableDeferred<OwnerUatSubmission> = CompletableDeferred()) { lateinit var job: Job }
    private val pending=mutableMapOf<String,Pending>()
    private var closed=false

    /** Permission to submit a guarded request, not runtime acceptance or a health observation. */
    internal fun requestAdmission(stored: StoredScopedExecution): OwnerUatRequestAdmission {
        val reason=when {
            synchronized(pending) { closed } -> "runtime_admission_closed"
            store.personalReviewForExecution(stored.binding.executionId)!=null -> "already_requested"
            stored.state!="IN_FLIGHT" -> "snapshot_not_in_flight"
            else -> policy.policyRefusal(stored.binding) ?: policy.runtimeRefusal(factory.identity)
                ?: if(store.unresolvedPersonalReviews().any { it.claim.resource.domain==factory.identity.domain }) "runtime_occupied_until_stop_proof"
                    else if(synchronized(pending) { pending.isNotEmpty() }) "runtime_busy" else null
        }
        return if(reason==null) OwnerUatRequestAdmission(true,"guarded_request_available",emptyList())
            else OwnerUatRequestAdmission(false,"guarded_request_blocked",listOf(reason))
    }

    internal fun status(id: String): OwnerUatJobStatus {
        validateContextId(id)
        val capture=store.captureScopedExecution(id)
        val current=capture.current()
        val stored=try { store.finishScopedExecutionCheck(capture,current) }
            catch(_: ScopedEvidenceConflict) { capture.stored.copy(state="UNVERIFIED",staleReason="inspection_conflict",output=null) }
        val lease=store.personalReviewForExecution(id)
        return OwnerUatJobStatus(id,stored.state,stored.staleReason,lease?.phase?.name,lease?.attempts ?: 0,lease?.claim?.deadlineUtc)
    }

    internal suspend fun request(id: String,request: OwnerUatJobRequest): OwnerUatSubmission {
        validateContextId(id)
        val stored=store.captureScopedExecution(id).stored
        require(stored.binding.consentSha256==request.packetSha256) { "Execution packet changed" }
        // A claimed request is a durable one-shot, even after failure, stop or service restart.
        if(store.personalReviewForExecution(id)!=null) return OwnerUatSubmission(true,null,status(id))
        val inspected=policy.inspectExecution(stored.binding)
        val denied=when {
            inspected.ownerUat?.policySha256!=request.policySha256 -> "owner_uat_binding_changed"
            factory.identity.selection!=stored.binding.providerSelection -> "provider_selection_mismatch"
            else -> policy.policyRefusal(stored.binding) ?: policy.runtimeRefusal(factory.identity)
        }
        if(denied!=null) return OwnerUatSubmission(false,denied,status(id))
        val entry=synchronized(pending) {
            if(closed) return OwnerUatSubmission(false,"runtime_admission_closed",status(id))
            pending[id]?.let { return@synchronized it }
            if(pending.isNotEmpty()) return OwnerUatSubmission(false,"runtime_admission_closed",status(id))
            Pending().also { item ->
                item.job=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
                    try {
                        val result=authority.execute(id,factory) { claim ->
                            // A receipt is the committed reservation, not a concurrent evidence re-read.
                            item.acknowledged.complete(OwnerUatSubmission(true,null,
                                OwnerUatJobStatus(id,"IN_FLIGHT",null,"RESERVED",0,claim.deadlineUtc)))
                        }
                        if(!item.acknowledged.isCompleted) item.acknowledged.complete(OwnerUatSubmission(false,result.reason,status(id)))
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) {
                        if(!item.acknowledged.isCompleted) item.acknowledged.complete(OwnerUatSubmission(false,"runtime_admission_closed",
                            OwnerUatJobStatus(id,"UNAVAILABLE","runtime_admission_closed",null,0,null)))
                    } finally { synchronized(pending) { if(pending[id]===item) pending.remove(id) } }
                }
                item.job.invokeOnCompletion {
                    if(!item.acknowledged.isCompleted) item.acknowledged.completeExceptionally(CancellationException("Owner execution interrupted"))
                }
                pending[id]=item
            }
        }
        entry.job.start()
        return entry.acknowledged.await()
    }

    internal suspend fun cancel(id: String,packetSha256: String): OwnerUatJobStatus {
        val binding=store.captureScopedExecution(id).stored.binding
        require(binding.consentSha256==packetSha256) { "Execution packet changed" }
        val lease=store.personalReviewForExecution(id)
        if(lease!=null && lease.claim.resource==factory.identity && lease.phase!=PersonalRuntimePhase.STOP_CONFIRMED)
            authority.cancel(lease.claim)
        else if(lease==null) store.failScopedExecution(id,"cancelled")
        val job=synchronized(pending) { pending[id]?.job }
        job?.cancel()
        if(lease!=null && lease.claim.resource==factory.identity) withContext(NonCancellable) { authority.cleanup(lease.claim) }
        return status(id)
    }

    internal fun rearm(digest: String)=policy.arm(digest)

    /** Synchronous revocation precedes cancellation. Shutdown never frees unverified occupancy. */
    internal fun disarm() {
        policy.disarm()
        store.unresolvedPersonalReviews().filter { it.claim.resource==factory.identity }.forEach { authority.cancel(it.claim) }
        synchronized(pending) { pending.values.forEach { it.job.cancel() } }
    }

    internal fun close() {
        synchronized(pending) { if(closed) return;closed=true }
        disarm()
    }
}

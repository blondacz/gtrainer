package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock

/** Owned personal execution around the existing executor. Ordinary qualification stays closed by default;
 * owner UAT uses separate policy permission, never a fabricated qualification. Injected controls/factories
 * do not establish acceptance of a production runtime. Normal installation wiring remains opt-in. */
internal class PersonalReviewRuntimeAdmission(
    private val store: HistoryStore,
    private val control: PersonalRuntimeControl,
    private val qualification: PersonalProfileQualification=PersonalProfileQualification { "profile_not_qualified" },
    private val consent: ConnectedReviewConsentLedger=ConnectedReviewConsentLedger(),
    private val limits: ConnectedReviewExecutionLimits=ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL),
    private val clock: Clock=Clock.systemUTC(),
    private val nanos: ()->Long=System::nanoTime,
    private val ownerUat: OwnerUatAdmission?=null,
    private val supervision: OwnerUatSupervisorClient?=null,
) {
    private val epoch=store.personalOwnerEpoch()
    init {
        require(limits.policy==ConnectedReviewExecutionPolicy.PERSONAL ||
            (limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT && ownerUat!=null)) { "Personal admission requires explicit policy" }
        require(supervision==null || limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT)
        supervision?.let { store.configureOwnerUatClock(it.clock) }
    }

    private fun refusal(binding: ScopedExecutionBinding): String? =
        if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) requireNotNull(ownerUat).policyRefusal(binding)
        else qualification.refusal(binding)?.let { "profile_not_qualified" }

    suspend fun execute(id: String,factory: PersonalProviderFactory,onReserved: (PersonalReviewClaim)->Unit = {}): ConnectedReviewExecutionResult {
        val ownerOriginNanos=if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) nanos() else null
        val continuousOrigin=try { supervision?.clock?.sample() } catch(_: Exception) { null }
        val capture=store.captureScopedExecution(id)
        val binding=capture.stored.binding
        fun refused(reason: String)=ConnectedReviewExecutionResult(id,"REFUSED",reason,binding.consentSha256,emptyList())
        if(supervision!=null && continuousOrigin==null) return refused("runtime_admission_closed")
        if(binding.profile !in ScopedProfileAdmission.profiles) return refused("unsupported_profile")
        refusal(binding)?.let { return refused(it) }
        val registration=if(continuousOrigin!=null) runCatching { ownerUat?.supervisionRegistration(continuousOrigin) }.getOrNull()
            ?: return refused("runtime_admission_closed") else null
        val identity=factory.identity
        if(binding.providerSelection!=identity.selection) return refused("provider_selection_mismatch")
        if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT)
            requireNotNull(ownerUat).runtimeRefusal(identity)?.let { return refused(it) }
        if(binding.providerSelection.providerKind=="hosted" && !consent.permits(id,binding.providerSelection,binding.consentSha256))
            return refused("consent_required")
        val current=capture.current()
        val observation=try {
            if(supervision!=null) {
                val elapsed=requireNotNull(continuousOrigin?.elapsedNanosUntil(supervision.clock.sample()))
                if(elapsed>=120_000_000_000L) return refused("runtime_admission_closed")
                withTimeoutOrNull(((120_000_000_000L-elapsed)/1_000_000).coerceAtLeast(1)) { control.observe(identity) }
            } else control.observe(identity)
        } catch(c: CancellationException) { throw c } catch(_: Exception) { null }
            ?: return refused("runtime_health_unavailable")
        val origin=ownerOriginNanos ?: nanos()
        currentCoroutineContext().ensureActive()
        val elapsed=if(registration!=null) registration.origin.elapsedNanosUntil(requireNotNull(supervision).clock.sample())
            ?: return refused("runtime_admission_closed") else 0L
        if(registration!=null && elapsed>=120_000_000_000L) return refused("runtime_admission_closed")
        val reservationMillis=limits.totalTimeoutMillis + if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) 60_000 else 0
        val remainingReservation=reservationMillis-elapsed/1_000_000
        val claim=try { store.reservePersonalReview(capture,current,epoch,observation,identity,clock,remainingReservation,limits.policy,registration) }
            catch(_: ScopedEvidenceConflict) { null }
            ?: return refused("runtime_admission_closed")
        var ownedProvider: ConnectedReviewProvider? = null
        var fenceMonitor: Job? = null
        try {
            if(supervision!=null) {
                if(!supervision.acknowledge(claim)) return refused("runtime_admission_closed")
                val parent=currentCoroutineContext()[Job]
                fenceMonitor=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                    while(isActive) {
                        val job=store.supervisedOwnerUatJob(claim.claimId)
                        val failure=runCatching {
                            val sample=supervision.clock.sample()
                            when {
                                job==null -> "owner_lost"
                                job.outcome=="published" -> null
                                job.tombstoned -> job.outcome ?: "cancelled"
                                !job.registration.origin.compatible(sample) || sample.ticks<job.lastObservedTicks -> "clock_unverified"
                                !job.permits(sample) -> "expired"
                                job.supervisorProcess?.current()!=true -> "owner_lost"
                                else -> null
                            }
                        }.getOrElse { "clock_unverified" }
                        if(failure!=null) { store.revokePersonalReview(claim,"runtime_admission_closed",failure); parent?.cancel(); break }
                        delay(50)
                    }
                }
            }
            onReserved(claim)
            refusal(binding)?.let { return refused(it) }
            currentCoroutineContext().ensureActive()
            if(!store.beginPersonalStart(claim,observation,clock)) return refused("runtime_admission_closed")
            // Cancellation can tombstone this claim while create is blocked. The factory/control contract
            // must prevent that identity from starting after a confirmed stop, even without a provider handle.
            val provider=factory.create(claim)
            ownedProvider=provider
            val storedRepository=ScopedExecutionRepository(store,claim,clock)
            val repository=object: ConnectedReviewExecutionRepository by storedRepository {
                override fun prepareConnectedReviewPublication(snapshotId: String,output: ValidatedConnectedReviewOutput,currentEvidenceDigest: String,
                    currentContext: List<ContextRevisionRefV1>,provider: String,model: String,contractVersion: String): ()->ConnectedReviewStatus {
                    val publish=storedRepository.prepareConnectedReviewPublication(snapshotId,output,currentEvidenceDigest,currentContext,provider,model,contractVersion)
                    if(limits.policy!=ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) return publish
                    return { requireNotNull(ownerUat).publishIfPermitted(binding,publish)
                        ?: storedRepository.failConnectedReview(snapshotId,"runtime_admission_closed") }
                }
            }
            val gate=ConnectedReviewExecutionGate { _,_ ->
                val denied=refusal(binding) ?: if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT)
                    requireNotNull(ownerUat).runtimeRefusal(identity) else null
                when {
                    denied!=null -> denied
                    store.personalOwnerEpoch()!=claim.ownerEpoch -> "runtime_admission_closed"
                    store.personalRuntimeLease(claim.claimId)?.publicationAllowed!=true -> "runtime_admission_closed"
                    binding.providerSelection.providerKind=="hosted" && !consent.permits(id,binding.providerSelection,binding.consentSha256) -> "consent_revoked"
                    else -> null
                }
            }
            // Shared exact hosted-consent publication lock wraps the owned transaction.
            // Lock order: consent monitor -> HistoryStore monitor -> JDBC. Never invert it.
            return ConnectedReviewService(repository,ConnectedReviewProviderCatalog(listOf(provider)),consent,gate,limits,nanos,origin,
                personalAttemptAuthority={ number ->
                    val observed=control.observe(identity)
                    observed!=null && store.authorizePersonalAttempt(claim,number,observed,clock)
                }).executeManually(id,binding.providerSelection)
        } catch(c: CancellationException) {
            throw c
        } catch(_: Exception) {
            return ConnectedReviewExecutionResult(id,"UNAVAILABLE","runtime_admission_closed",binding.consentSha256,emptyList())
        } finally {
            withContext(NonCancellable) {
                fenceMonitor?.cancel()
                try { (ownedProvider as? AutoCloseable)?.close() } catch(_: Exception) { /* Physical stop is still mandatory. */ }
                cleanup(claim)
            }
        }
    }

    /** Can be invoked from another coroutine/thread without waiting for a blocked factory/generate. */
    fun cancel(claim: PersonalReviewClaim) { store.revokePersonalReview(claim) }

    /** Revocation commits before any external cleanup; no owner lock spans control-plane operations.
     * Timeouts leave UNKNOWN. Only exact physical-stop + delayed-start fencing evidence frees occupancy. */
    suspend fun cleanup(claim: PersonalReviewClaim) {
        val lease=store.revokePersonalReview(claim) ?: return
        if(lease.phase==PersonalRuntimePhase.STOP_CONFIRMED) return
        val evidence=try { withTimeoutOrNull(if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) 60_000 else 5_000) { control.stop(claim) } }
            catch(_: Exception) { null }
        if(evidence==null || !store.confirmPersonalStop(epoch,claim,evidence,clock.instant()))
            store.markPersonalStopUnknown(epoch,claim)
    }
}

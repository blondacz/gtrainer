package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

/** Closed personal adapter around the existing executor. Intentionally not wired into Application/queue/routes.
 * The injected observer/factory and qualification are synthetic seams, not accepted runtime implementations. */
internal class PersonalReviewRuntimeAdmission(
    private val store: HistoryStore,
    private val control: PersonalRuntimeControl,
    private val qualification: PersonalProfileQualification=PersonalProfileQualification { "profile_not_qualified" },
    private val consent: ConnectedReviewConsentLedger=ConnectedReviewConsentLedger(),
    private val limits: ConnectedReviewExecutionLimits=ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL),
    private val clock: Clock=Clock.systemUTC(),
    private val nanos: ()->Long=System::nanoTime,
) {
    private val epoch=store.personalOwnerEpoch()
    init { require(limits.policy==ConnectedReviewExecutionPolicy.PERSONAL) { "Personal admission requires personal policy" } }

    suspend fun execute(id: String,factory: PersonalProviderFactory): ConnectedReviewExecutionResult {
        val capture=store.captureScopedExecution(id)
        val binding=capture.stored.binding
        fun refused(reason: String)=ConnectedReviewExecutionResult(id,"REFUSED",reason,binding.consentSha256,emptyList())
        if(binding.profile !in ScopedProfileAdmission.profiles) return refused("unsupported_profile")
        if(qualification.refusal(binding)!=null) return refused("profile_not_qualified")
        val identity=factory.identity
        if(binding.providerSelection!=identity.selection) return refused("provider_selection_mismatch")
        if(binding.providerSelection.providerKind=="hosted" && !consent.permits(id,binding.providerSelection,binding.consentSha256))
            return refused("consent_required")
        val current=capture.current()
        val observation=try { control.observe(identity) } catch(c: CancellationException) { throw c } catch(_: Exception) { null }
            ?: return refused("runtime_health_unavailable")
        val origin=nanos()
        val claim=try { store.reservePersonalReview(capture,current,epoch,observation,identity,clock,limits.totalTimeoutMillis) }
            catch(_: ScopedEvidenceConflict) { null }
            ?: return refused("runtime_admission_closed")
        try {
            if(!store.beginPersonalStart(claim,observation,clock)) return refused("runtime_admission_closed")
            // Cancellation can tombstone this claim while create is blocked. The factory/control contract
            // must prevent that identity from starting after a confirmed stop, even without a provider handle.
            val provider=factory.create(claim)
            val repository=ScopedExecutionRepository(store,claim,clock)
            val gate=ConnectedReviewExecutionGate { _,_ ->
                when {
                    qualification.refusal(binding)!=null -> "profile_not_qualified"
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
            withContext(NonCancellable) { cleanup(claim) }
        }
    }

    /** Can be invoked from another coroutine/thread without waiting for a blocked factory/generate. */
    fun cancel(claim: PersonalReviewClaim) { store.revokePersonalReview(claim) }

    /** Revocation commits before any external cleanup; no owner lock spans control-plane operations.
     * Timeouts leave UNKNOWN. Only exact physical-stop + delayed-start fencing evidence frees occupancy. */
    suspend fun cleanup(claim: PersonalReviewClaim) {
        val lease=store.revokePersonalReview(claim) ?: return
        if(lease.phase==PersonalRuntimePhase.STOP_CONFIRMED) return
        val evidence=try { withTimeoutOrNull(5_000) { control.stop(claim) } }
            catch(_: Exception) { null }
        if(evidence==null || !store.confirmPersonalStop(epoch,claim,evidence,clock.instant()))
            store.markPersonalStopUnknown(epoch,claim)
    }
}

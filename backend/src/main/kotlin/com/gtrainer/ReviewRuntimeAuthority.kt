package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Instant

/** Personal-only contracts. No production observer or runtime is supplied by this change. */
@Serializable
internal data class PersonalResourceIdentity(val domain: String,val runtimeId: String,
    val selection: ConnectedReviewProviderSelection) {
    init { for(value in listOf(domain,runtimeId)) require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) }
}

internal data class PersonalResourceObservation(val identity: PersonalResourceIdentity,val observedAt: Instant,
    val validUntil: Instant,val healthy: Boolean) {
    fun current(expected: PersonalResourceIdentity,now: Instant)=identity==expected && healthy && observedAt<=now && now<validUntil
}

@Serializable
internal data class PersonalReviewClaim(val claimId: String,val ownerEpoch: String,val generation: Long,
    val executionId: String,val executionGeneration: Long,val profile: String,val resource: PersonalResourceIdentity,
    val packetDigest: String,val dependencyDigest: String,val reservedUtc: String,val deadlineUtc: String) {
    init {
        listOf(claimId,ownerEpoch,executionId).forEach(::validateContextId)
        require(generation>0 && executionGeneration>0 && profile in ScopedProfileAdmission.profiles)
        listOf(packetDigest,dependencyDigest).forEach { require(it.matches(Regex("[a-f0-9]{64}"))) }
        require(Instant.parse(reservedUtc)<Instant.parse(deadlineUtc))
    }
    override fun toString()="PersonalReviewClaim([REDACTED])"
}

internal enum class PersonalRuntimePhase { RESERVED, START_INTENT, EXECUTING, STOPPING, STOP_CONFIRMED, UNKNOWN }
internal data class PersonalRuntimeLease(val claim: PersonalReviewClaim,val phase: PersonalRuntimePhase,
    val publicationAllowed: Boolean,val attempts: Int)

/** The control plane must tombstone this exact start identity even if construction/send is stuck.
 * EOF, coroutine completion and absence of a process without a late-start fence are not evidence. */
internal data class PersonalStopEvidence(val claim: PersonalReviewClaim,val observedAt: Instant,
    val physicallyStopped: Boolean,val lateStartsFenced: Boolean)

internal interface PersonalRuntimeControl {
    suspend fun observe(expected: PersonalResourceIdentity): PersonalResourceObservation?
    suspend fun stop(claim: PersonalReviewClaim): PersonalStopEvidence?
}

/** Metadata is inert; construction may allocate/start and therefore requires a durable START_INTENT. */
internal interface PersonalProviderFactory {
    val identity: PersonalResourceIdentity
    fun create(claim: PersonalReviewClaim): ConnectedReviewProvider
}

internal fun interface PersonalProfileQualification {
    fun refusal(binding: ScopedExecutionBinding): String?
}

internal class PersonalRuntimeConflict: IllegalStateException("Personal runtime authority is unavailable")

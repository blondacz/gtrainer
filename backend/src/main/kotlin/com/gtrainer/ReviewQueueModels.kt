package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class ReviewQueuePolicy(val debounceSeconds: Int = 120, val cooldownSeconds: Int = 300, val maximumDeferralSeconds: Int = 600) {
    init {
        require(debounceSeconds in 0..3600 && cooldownSeconds in 0..86400 && maximumDeferralSeconds in 1..86400)
        require(debounceSeconds <= maximumDeferralSeconds)
    }
}
@Serializable
data class ReviewQueueJob(val id: String, val key: String, val intent: ReviewDue?, val reasons: List<String>, val occurrenceIds: List<String>,
                           val firstQueuedUtc: String, val lastQueuedUtc: String, val eligibleUtc: String, val maximumDeferralUtc: String,
                           val manual: Boolean = false, val startedUtc: String? = null,
                           val scopedIntent: ScopedQueueIntent? = null) {
    init { require((scopedIntent != null) xor (intent != null)) }
    fun requireLegacyIntent(): ReviewDue = checkNotNull(intent) { "Scoped queue job has no legacy intent" }
    override fun toString() = "ReviewQueueJob([REDACTED])"
}
@Serializable
data class ReviewQueueOutcome(val id: String, val intent: ReviewDue?, val status: String, val reason: String, val finishedUtc: String,
                              val startedUtc: String?, val facts: FactualReview? = null, val interpretation: ReviewInterpretationResponse? = null,
                                val stale: Boolean = false, val snapshotId: String? = null, val scopedIntent: ScopedQueueIntent? = null,
                                val evidenceCutoffUtc: String?=null,val preview: ScopedQueuePreview?=null) {
    init {
        require((scopedIntent == null) xor (intent == null))
        require((scopedIntent == null) || (facts == null && interpretation == null))
        require(snapshotId == null || scopedIntent != null)
        require((evidenceCutoffUtc==null && preview==null) || scopedIntent!=null)
        evidenceCutoffUtc?.let(java.time.Instant::parse)
        require(preview==null || scopedIntent?.providerSelection!=null)
        require(preview?.state!="awaiting_consent" || scopedIntent?.providerSelection?.providerKind=="hosted")
        require(preview?.state!="blocked_admission" || scopedIntent?.providerSelection?.providerKind=="local")
    }
    override fun toString() = "ReviewQueueOutcome([REDACTED])"
}
@Serializable
data class ReviewQueueState(val pending: List<ReviewQueueJob> = emptyList(), val running: ReviewQueueJob? = null,
                           val outcomes: List<ReviewQueueOutcome> = emptyList(), val cooldownUntilUtc: Map<String, String> = emptyMap(),
                           val lastSuccess: ReviewQueueOutcome? = null) {
    override fun toString() = "ReviewQueueState([REDACTED])"
}
@Serializable
data class ReviewQueueStatus(val reason: String, val executionAvailable: Boolean, val policy: ReviewQueuePolicy,
                            val pending: List<ReviewQueueJob>, val running: ReviewQueueJob?, val outcomes: List<ReviewQueueOutcome>,
                            val lastSuccess: ReviewQueueOutcome?) {
    override fun toString() = "ReviewQueueStatus([REDACTED])"
}
@Serializable
data class ReviewNowRequest(val expectedVersion: Long, val presetId: String, val level: String = "interim") {
    init {
        require(expectedVersion >= 0 && presetId.matches(Regex("[a-z0-9-]{1,40}")) && level in setOf("interim", "thorough"))
    }
}
class ReviewQueueCapacity : IllegalStateException("Review queue capacity reached")

/** Content-free routing status. Exact prompts stay behind user-triggered execution inspection. */
@Serializable
data class ScopedQueuePreview(val state: String,val reason: String,val executionId: String?=null,val createdUtc: String?=null) {
    init {
        require(state in setOf("not_prepared","awaiting_consent","blocked_admission","blocked","unavailable","stale"))
        require(reason.matches(Regex("[a-z][a-z0-9_]{1,63}")))
        require((executionId==null)==(createdUtc==null))
        executionId?.let(::validateContextId);createdUtc?.let(java.time.Instant::parse)
        if(state in setOf("awaiting_consent","blocked_admission")) require(executionId!=null)
    }
}

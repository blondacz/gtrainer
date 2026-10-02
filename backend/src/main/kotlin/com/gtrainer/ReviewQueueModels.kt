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
data class ReviewQueueJob(val id: String, val key: String, val intent: ReviewDue, val reasons: List<String>, val occurrenceIds: List<String>,
                          val firstQueuedUtc: String, val lastQueuedUtc: String, val eligibleUtc: String, val maximumDeferralUtc: String,
                          val manual: Boolean = false, val startedUtc: String? = null) {
    override fun toString() = "ReviewQueueJob([REDACTED])"
}
@Serializable
data class ReviewQueueOutcome(val id: String, val intent: ReviewDue, val status: String, val reason: String, val finishedUtc: String,
                              val startedUtc: String?, val facts: FactualReview? = null, val interpretation: ReviewInterpretationResponse? = null,
                              val stale: Boolean = false) {
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

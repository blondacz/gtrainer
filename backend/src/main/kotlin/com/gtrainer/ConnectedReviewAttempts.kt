package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectedReviewAttemptPhase { STARTED, CORRECTING, ACCEPTED, REJECTED, FAILED, INVALIDATED, CANCELLED }

@Serializable
data class ConnectedReviewAttemptEvent(val number: Int?, val phase: ConnectedReviewAttemptPhase, val reason: String?)

/** Best-effort metadata observation, never permission or authoritative publication/stop evidence. */
fun interface ConnectedReviewAttemptObserver {
    fun observe(event: ConnectedReviewAttemptEvent)
}

/** Owns response-free attempt metadata; callers receive copies, not the mutable history. */
internal class ConnectedReviewAttempts(private val observer: ConnectedReviewAttemptObserver) {
    private val results = mutableListOf<ConnectedReviewAttemptResult>()

    fun starting(number: Int, correcting: Boolean) = notify(ConnectedReviewAttemptEvent(number,
        if (correcting) ConnectedReviewAttemptPhase.CORRECTING else ConnectedReviewAttemptPhase.STARTED, null))

    operator fun plusAssign(result: ConnectedReviewAttemptResult) {
        results.add(result)
        notify(ConnectedReviewAttemptEvent(result.number, phase(result.status), result.reason))
    }

    fun cancelled() = notify(ConnectedReviewAttemptEvent(null, ConnectedReviewAttemptPhase.CANCELLED, "cancelled"))
    fun toList(): List<ConnectedReviewAttemptResult> = results.toList()

    private fun notify(event: ConnectedReviewAttemptEvent) {
        try { observer.observe(event) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Telemetry failure cannot change a committed outcome or leak an exception. */ }
    }

    private fun phase(status: String): ConnectedReviewAttemptPhase = when (status) {
        "accepted" -> ConnectedReviewAttemptPhase.ACCEPTED
        "rejected" -> ConnectedReviewAttemptPhase.REJECTED
        "invalidated" -> ConnectedReviewAttemptPhase.INVALIDATED
        else -> ConnectedReviewAttemptPhase.FAILED
    }
}

package com.gtrainer

import kotlinx.serialization.encodeToString
import java.time.Instant

/** Snapshot-free, durable coalescing. Provider execution is outside the database transaction. */
internal object ReviewQueueRules {
    private const val CAPACITY = 256
    fun key(intent: ReviewDue): String = AnalysisClaims.hash(ReviewFocusProtocol.json.encodeToString(listOf(
        intent.reviewType, ReviewFocusProtocol.json.encodeToString(intent.scope), intent.oldest, intent.newest,
        intent.activitySha256 ?: "", intent.focus,
        intent.configurationVersion.toString(), intent.modelId, intent.selectionVersion.toString(),
    )))

    fun enqueue(queue: ReviewQueueState, intents: List<ReviewDue>, policy: ReviewQueuePolicy, now: Instant,
                manual: Boolean = false): ReviewQueueState {
        val pending = queue.pending.toMutableList()
        for (intent in intents) {
            val key = key(intent)
            val index = pending.indexOfFirst { it.key == key }
            val old = pending.getOrNull(index)
            if (old?.occurrenceIds?.contains(intent.occurrenceId) == true) continue
            val first = old?.firstQueuedUtc?.let(Instant::parse) ?: now
            val deadline = old?.maximumDeferralUtc?.let(Instant::parse) ?: first.plusSeconds(policy.maximumDeferralSeconds.toLong())
            val isManual = manual || old?.manual == true
            val settling = if (isManual) now else maxOf(now.plusSeconds(policy.debounceSeconds.toLong()), intent.notBeforeUtc?.let(Instant::parse) ?: now)
            val cooldown = queue.cooldownUntilUtc[key]?.let(Instant::parse) ?: now
            // Maximum deferral wins over continually extending debounce/cooldown.
            val eligible = (if (isManual) now else maxOf(settling, cooldown)).coerceAtMost(deadline)
            val mergedIntent = if (old == null) intent else intent.copy(
                level = if (old.intent.level == "thorough" || intent.level == "thorough") "thorough" else "interim",
                budget = ReviewExecutionPolicy(maxOf(old.intent.budget.callTimeoutMillis, intent.budget.callTimeoutMillis),
                    maxOf(old.intent.budget.jobTimeoutMillis, intent.budget.jobTimeoutMillis),
                    old.intent.budget.allowCorrectiveAttempt || intent.budget.allowCorrectiveAttempt))
            val occurrences = ((old?.occurrenceIds ?: emptyList()) + intent.occurrenceId).distinct()
            if (occurrences.size > 50_000) throw ReviewQueueCapacity()
            val job = ReviewQueueJob(old?.id ?: intent.occurrenceId, key, mergedIntent,
                ((old?.reasons ?: emptyList()) + intent.reasons).distinct(), occurrences, first.toString(), now.toString(),
                eligible.toString(), deadline.toString(), isManual)
            if (index >= 0) pending[index] = job else pending += job
            if (pending.size + (if (queue.running == null) 0 else 1) > CAPACITY) throw ReviewQueueCapacity()
        }
        return queue.copy(pending = pending)
    }

    fun claim(queue: ReviewQueueState, now: Instant): ReviewQueueState {
        if (queue.running != null) return queue
        val job = queue.pending.filter { Instant.parse(it.eligibleUtc) <= now }
            .minWithOrNull(compareBy<ReviewQueueJob> { Instant.parse(it.maximumDeferralUtc) }.thenBy { Instant.parse(it.firstQueuedUtc) })
            ?: return queue
        return queue.copy(pending = queue.pending - job, running = job.copy(startedUtc = now.toString()))
    }

    fun finish(queue: ReviewQueueState, outcome: ReviewQueueOutcome, policy: ReviewQueuePolicy, now: Instant): ReviewQueueState {
        val job = queue.running?.takeIf { it.id == outcome.id } ?: return queue // Invalidated result cannot resurrect itself.
        val cooldown = now.plusSeconds(policy.cooldownSeconds.toLong())
        val pending = queue.pending.map { next ->
            if (next.key != job.key || next.manual) next else next.copy(eligibleUtc = maxOf(Instant.parse(next.eligibleUtc), cooldown)
                .coerceAtMost(Instant.parse(next.maximumDeferralUtc)).toString())
        }
        val cooldowns = queue.cooldownUntilUtc.filterValues { Instant.parse(it) > now }.toMutableMap()
        cooldowns[job.key] = cooldown.toString()
        return queue.copy(running = null, pending = pending, outcomes = (queue.outcomes + outcome).takeLast(24), cooldownUntilUtc = cooldowns,
            lastSuccess = if (outcome.status in setOf("available", "factual_only")) outcome else queue.lastSuccess)
    }

    fun invalidate(queue: ReviewQueueState, reason: String, now: Instant, recover: Boolean = false): ReviewQueueState {
        val abandoned = queue.pending + listOfNotNull(queue.running)
        val outcomes = abandoned.map { job -> ReviewQueueOutcome(job.id, job.intent,
            if (recover && job.startedUtc != null) "interrupted" else "cancelled", reason, now.toString(), job.startedUtc) }
        return ReviewQueueState(outcomes = (queue.outcomes + outcomes).takeLast(24), lastSuccess = queue.lastSuccess)
    }

    fun reconcileImports(queue: ReviewQueueState, changes: List<ImportedReviewChange>, now: Instant): ReviewQueueState {
        val latest = changes.filter { it.category == "activities" }.groupBy { it.identitySha256 }.mapValues { (_, records) -> records.maxBy { it.revision } }
        val obsolete = queue.pending.filter { job ->
            val correction = job.intent.activitySha256?.let { latest[it] } ?: return@filter false
            correction.observedDate != job.intent.newest || (job.intent.scope.sport != null && correction.sport != job.intent.scope.sport)
        }
        return queue.copy(pending = queue.pending - obsolete.toSet(), outcomes = (queue.outcomes + obsolete.map { job ->
            ReviewQueueOutcome(job.id, job.intent, "cancelled", "input_scope_changed", now.toString(), job.startedUtc)
        }).takeLast(24)) // Never repeatedly cancel the running snapshot on imports.
    }
}

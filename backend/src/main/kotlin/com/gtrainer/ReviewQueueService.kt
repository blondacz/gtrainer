package com.gtrainer

import kotlinx.coroutines.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex

/** No default/hosted model, no OOM/provider retries. Normal environment wiring has no qualification guard. */
class ReviewQueueService(private val store: HistoryStore, private val reviews: ReviewInterpretationService,
                         private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private val worker = AtomicReference<Job?>(null)
    private val pumping = Mutex()
    init {
        store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.invalidate(state.queue,
            "restart_requires_explicit_request", clock.instant(), recover = true)) to Unit }
    }

    private fun modelMatches(job: ReviewQueueJob): Boolean = reviews.status().let {
        it.enabled && it.selectedModelId == job.intent.modelId && it.selectionVersion == job.intent.selectionVersion
    }
    private fun current(job: ReviewQueueJob): Boolean = store.reviewScheduleState().let {
        it.version == job.intent.configurationVersion && !it.configuration.paused && it.queue.running?.id == job.id &&
            modelMatches(job) && (job.manual || it.configuration.enabled)
    }

    suspend fun invalidate(reason: String) = withContext(Dispatchers.IO) {
        worker.get()?.cancel()
        store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.invalidate(state.queue, reason, clock.instant())) to Unit }
    }
    fun cancelObsolete() { worker.get()?.cancel() }

    suspend fun requestNow(request: ReviewNowRequest): ReviewQueueStatus = withContext(Dispatchers.IO) {
        val model = reviews.status()
        require(model.enabled && model.selectedModelId != null && model.profile == ReviewFocusProtocol.PROFILE)
        store.updateReviewQueue { state ->
            if (state.version != request.expectedVersion) throw ReviewScheduleConflict()
            require(!state.configuration.paused)
            val preset = state.configuration.presets.singleOrNull { it.id == request.presetId }
                ?: throw IllegalArgumentException("Unknown review preset")
            require(preset.scope.kind != "activity") { "Activity scope requires a received activity" }
            require(reviews.permitsScheduleBudget(preset.budget))
            val now = clock.instant()
            val today = now.atZone(ZoneId.of(state.configuration.timeZone)).toLocalDate()
            val oldest = when (preset.scope.kind) {
                "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                "rolling" -> today.minusDays(preset.scope.days.toLong() - 1)
                else -> today
            }
            val intent = ReviewDue(AnalysisClaims.hash(UUID.randomUUID().toString()), preset.id, state.version,
                requireNotNull(model.selectedModelId), model.selectionVersion, preset.reviewType, preset.scope, oldest.toString(),
                today.toString(), null, request.level, preset.focus, preset.budget, listOf("manual_request"), now.toString(), timeZone = state.configuration.timeZone)
            require(reviews.status().selectionVersion == model.selectionVersion)
            state.copy(queue = ReviewQueueRules.enqueue(state.queue, listOf(intent), state.configuration.queuePolicy, now, manual = true)) to Unit
        }
        status()
    }

    suspend fun status(): ReviewQueueStatus = withContext(Dispatchers.IO) {
        val state = store.reviewScheduleState()
        if ((state.queue.pending + listOfNotNull(state.queue.running)).any { !modelMatches(it) || it.intent.configurationVersion != state.version })
            invalidate("model_selection_changed")
        val updated = store.reviewScheduleState()
        val model = reviews.status()
        val reason = when {
            updated.configuration.paused -> "schedules_paused"
            !model.enabled || model.selectedModelId == null -> "explicit_local_selection_required"
            !model.runtimeGuardConfigured -> "runtime_guard_unavailable"
            updated.queue.running != null -> "review_running"
            updated.queue.pending.isNotEmpty() -> "review_queued"
            else -> "review_idle"
        }
        fun label(outcome: ReviewQueueOutcome): ReviewQueueOutcome {
            if (outcome.facts == null) return outcome
            val stale = try {
                val job = ReviewQueueJob(outcome.id, "", outcome.intent, emptyList(), emptyList(), "", "", "", "")
                val report = store.reviewJobReport(job, LocalDate.now(clock), LocalDate.now(clock.withZone(ZoneId.of(job.intent.timeZone))))
                !model.enabled || model.selectedModelId != outcome.intent.modelId || model.selectionVersion != outcome.intent.selectionVersion ||
                    updated.version != outcome.intent.configurationVersion || Trends.analysisInput(report).evidenceReportSha256 != outcome.facts.evidenceReportSha256
            } catch (_: Exception) { true }
            return outcome.copy(stale = stale)
        }
        ReviewQueueStatus(reason, model.enabled && model.selectedModelId != null && model.runtimeGuardConfigured && !updated.configuration.paused,
            updated.configuration.queuePolicy, updated.queue.pending, updated.queue.running, updated.queue.outcomes.map(::label), updated.queue.lastSuccess?.let(::label))
    }

    suspend fun pump(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!scope.isActive || !pumping.tryLock()) return@withContext
        try {
        status() // Reconcile model changes, never data changes, before claiming.
        if (!reviews.status().runtimeGuardConfigured || !reviews.status().enabled || worker.get() != null) return@withContext
        val job = store.updateReviewQueue { state ->
            if (state.configuration.paused) return@updateReviewQueue state to null
            val next = ReviewQueueRules.claim(state.queue, clock.instant())
            state.copy(queue = next) to next.running
        } ?: return@withContext
        val task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (!current(job)) return@launch
                // Prepare the latest immutable report only after the durable running claim.
                val report = store.reviewJobReport(job, LocalDate.now(clock), LocalDate.now(clock.withZone(ZoneId.of(job.intent.timeZone))))
                val input = Trends.analysisInput(report)
                val facts = FactualReviews.prepare(input, job.intent.focus)
                val request = ReviewInterpretationRequest(ReviewFocusProtocol.PROFILE, report.current.oldest, report.current.newest,
                    report.selectedSport, job.intent.focus, input.evidenceReportSha256, job.intent.modelId, job.intent.selectionVersion)
                val interpretation = reviews.interpretQueued(request, report, job.intent.budget) { current(job) }
                if (interpretation.reason == "analysis_busy" && interpretation.attempts.isEmpty()) {
                    store.updateReviewQueue { state ->
                        if (state.queue.running?.id != job.id) state to Unit else {
                            val pending = job.copy(startedUtc = null, eligibleUtc = clock.instant().plusSeconds(1).toString())
                            val merged = ReviewQueueRules.enqueue(state.queue.copy(running = null), listOf(pending.intent),
                                state.configuration.queuePolicy, clock.instant(), pending.manual)
                            state.copy(queue = merged.copy(pending = merged.pending.map { if (it.key == pending.key) it.copy(
                                firstQueuedUtc = pending.firstQueuedUtc, maximumDeferralUtc = pending.maximumDeferralUtc,
                                eligibleUtc = minOf(it.eligibleUtc, pending.eligibleUtc), reasons = (pending.reasons + it.reasons).distinct(),
                                occurrenceIds = (pending.occurrenceIds + it.occurrenceIds).distinct()) else it })) to Unit
                        }
                    }
                    return@launch
                }
                if (!current(job)) return@launch
                val outcome = ReviewQueueOutcome(job.id, job.intent, if (interpretation.status == "available") "available" else "factual_only",
                    interpretation.reason, clock.instant().toString(), job.startedUtc, facts, interpretation)
                reviews.publishIfSelected(job.intent.modelId, job.intent.selectionVersion) {
                    store.updateReviewQueue { state ->
                        if (!current(job)) state to Unit else state.copy(queue = ReviewQueueRules.finish(state.queue, outcome,
                            state.configuration.queuePolicy, clock.instant())) to Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                finishFailure(job, "review_cancelled")
                throw cancelled
            } catch (_: Exception) { finishFailure(job, "review_unavailable") }
            finally { worker.compareAndSet(currentCoroutineContext()[Job], null) }
        }
        if (!worker.compareAndSet(null, task)) {
            task.cancel()
            finishFailure(job, "review_unavailable")
        } else {
            task.invokeOnCompletion { error ->
                if (error is CancellationException) finishFailure(job, "review_cancelled")
                worker.compareAndSet(task, null)
            }
            task.start()
        }
        } finally { pumping.unlock() }
    }

    private fun finishFailure(job: ReviewQueueJob, reason: String) {
        runCatching { store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.finish(state.queue,
            ReviewQueueOutcome(job.id, job.intent, "unavailable", reason, clock.instant().toString(), job.startedUtc),
            state.configuration.queuePolicy, clock.instant())) to Unit } }
    }
    override fun close() { worker.get()?.cancel() }
}

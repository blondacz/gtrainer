package com.gtrainer

import kotlinx.coroutines.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No default/hosted model, no OOM/provider retries. Normal environment wiring has no qualification guard. */
class ReviewQueueService(private val store: HistoryStore, private val reviews: ReviewInterpretationService,
                         private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private data class WorkerClaim(val id: String,val task: Job)
    private val worker = AtomicReference<WorkerClaim?>(null)
    private val pumping = Mutex()
    init {
        store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.invalidate(state.queue,
            "restart_requires_explicit_request", clock.instant(), recover = true)) to Unit }
    }

    private fun modelMatches(job: ReviewQueueJob): Boolean {
        val intent=job.intent ?: return true // Scoped factual preparation has no legacy model dependency.
        return reviews.status().let { it.enabled && it.selectedModelId==intent.modelId && it.selectionVersion==intent.selectionVersion }
    }
    private fun configurationVersion(job: ReviewQueueJob)=job.scopedIntent?.configurationVersion ?: job.requireLegacyIntent().configurationVersion
    private fun current(job: ReviewQueueJob): Boolean = store.reviewScheduleState().let {
        it.version == configurationVersion(job) && !it.configuration.paused && it.queue.running?.id == job.id &&
            modelMatches(job) && (job.manual || it.configuration.enabled)
    }

    suspend fun invalidate(reason: String) = withContext(Dispatchers.IO) {
        pumping.withLock {
            val removed=store.updateReviewQueue { state ->
                val queue=state.queue
                val obsolete=(queue.pending+listOfNotNull(queue.running)).filter { reason!="model_selection_changed" || it.intent!=null }
                val ids=obsolete.map { it.id }.toSet()
                val outcomes=obsolete.map { ReviewQueueOutcome(it.id,it.intent,"cancelled",reason,clock.instant().toString(),it.startedUtc,scopedIntent=it.scopedIntent) }
                state.copy(queue=queue.copy(pending=queue.pending.filter { it.id !in ids },running=queue.running?.takeUnless { it.id in ids },
                    outcomes=(queue.outcomes+outcomes).takeLast(24))) to queue.running?.id?.takeIf { it in ids }
            }
            worker.get()?.takeIf { it.id==removed }?.task?.cancel()
        }
    }
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        pumping.withLock { reconcileLocked() }
    }
    private fun reconcileLocked() {
        val state = store.reviewScheduleState()
        val obsolete=(state.queue.pending + listOfNotNull(state.queue.running)).filter {
                 !modelMatches(it) || configurationVersion(it) != state.version
            }.associateBy { it.id }
        if(obsolete.isNotEmpty()) {
            val cancelledRunning=store.updateReviewQueue { latest ->
                val queue=latest.queue
                val abandoned=(queue.pending+listOfNotNull(queue.running)).filter { obsolete[it.id]==it }
                val ids=abandoned.map { it.id }.toSet()
                val outcomes=abandoned.map { ReviewQueueOutcome(it.id,it.intent,"cancelled","model_selection_changed",clock.instant().toString(),it.startedUtc,scopedIntent=it.scopedIntent) }
                latest.copy(queue=queue.copy(pending=queue.pending.filter { it.id !in ids },running=queue.running?.takeUnless { it.id in ids },
                    outcomes=(queue.outcomes+outcomes).takeLast(24))) to (queue.running?.id in ids)
            }
            // The pump mutex prevents a replacement worker from being installed while this identity is cancelled.
            if(cancelledRunning) worker.get()?.takeIf { it.id==state.queue.running?.id }?.task?.cancel()
        }
    }
    fun cancelObsolete() {
        val claim=worker.get() ?: return
        if(store.reviewScheduleState().queue.running?.id!=claim.id) claim.task.cancel()
    }

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
            val (oldest, newest) = ReviewScopeResolver.legacy(preset.scope, today, today)
            val intent = ReviewDue(AnalysisClaims.hash(UUID.randomUUID().toString()), preset.id, state.version,
                requireNotNull(model.selectedModelId), model.selectionVersion, preset.reviewType, preset.scope, oldest.toString(),
                newest.toString(), null, request.level, preset.focus, preset.budget, listOf("manual_request"), now.toString(), timeZone = state.configuration.timeZone)
            require(reviews.status().selectionVersion == model.selectionVersion)
            state.copy(queue = ReviewQueueRules.enqueue(state.queue, listOf(intent), state.configuration.queuePolicy, now, manual = true)) to Unit
        }
        status()
    }

    suspend fun status(): ReviewQueueStatus = withContext(Dispatchers.IO) {
        val updated = store.reviewScheduleState()
        val model = reviews.status()
        val obsolete = (updated.queue.pending + listOfNotNull(updated.queue.running)).any {
            !modelMatches(it) || configurationVersion(it) != updated.version
        }
        val reason = when {
            updated.configuration.paused -> "schedules_paused"
            updated.queue.running?.scopedIntent!=null -> "factual_preparation_running"
            updated.queue.pending.any { it.scopedIntent!=null } -> "factual_preparation_queued"
            !model.enabled || model.selectedModelId == null -> "explicit_local_selection_required"
            !model.runtimeGuardConfigured -> "runtime_guard_unavailable"
            updated.queue.running != null && !obsolete -> "review_running"
            updated.queue.pending.isNotEmpty() && !obsolete -> "review_queued"
            else -> "review_idle"
        }
        fun label(outcome: ReviewQueueOutcome): ReviewQueueOutcome {
            if(outcome.scopedIntent!=null) {
                val revisions=store.scopedRoutingRevisions()
                val snapshot=outcome.snapshotId?.let { id -> try { store.storedScopedEvidence(id) } catch(_: ScopedEvidenceNotFound) { null } }
                val stale=if(snapshot?.snapshot?.packet?.adaptation!=null) try {
                    store.captureScopedEvidenceInspection(snapshot.snapshot.snapshotId,clock.instant()).currentDigest()!=snapshot.bindingSha256
                } catch(_: Exception) { true } else outcome.snapshotId!=null && snapshot==null || outcome.scopedIntent.configurationVersion!=updated.version ||
                    outcome.scopedIntent.sourceRevision!=revisions.source || outcome.scopedIntent.planReasonRevision!=revisions.planReason
                val preview=outcome.preview?.let { saved ->
                    if(saved.executionId==null) saved else try {
                        val inspected=ScopedExecutionService(store,clock).inspect(saved.executionId)
                        if(stale || inspected.state!="IN_FLIGHT") saved.copy(state="stale",reason="preview_not_current") else saved
                    } catch(_: Exception) { saved.copy(state="unavailable",reason="preview_not_available") }
                }
                return outcome.copy(stale=stale,preview=preview)
            }
            if (outcome.facts == null) return outcome
            val stale = try {
                val intent=checkNotNull(outcome.intent)
                val job = ReviewQueueJob(outcome.id, "", outcome.intent, emptyList(), emptyList(), "", "", "", "")
                val report = store.reviewJobReport(job, LocalDate.now(clock), LocalDate.now(clock.withZone(ZoneId.of(intent.timeZone))))
                !model.enabled || model.selectedModelId != intent.modelId || model.selectionVersion != intent.selectionVersion ||
                    updated.version != intent.configurationVersion || Trends.analysisInput(report).evidenceReportSha256 != outcome.facts.evidenceReportSha256
            } catch (_: Exception) { true }
            return outcome.copy(stale = stale)
        }
        ReviewQueueStatus(reason, model.enabled && model.selectedModelId != null && model.runtimeGuardConfigured && !updated.configuration.paused,
            updated.configuration.queuePolicy, updated.queue.pending, updated.queue.running, updated.queue.outcomes.map(::label), updated.queue.lastSuccess?.let(::label))
    }

    suspend fun pump(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (!scope.isActive || !pumping.tryLock()) return@withContext
        try {
            reconcileLocked() // Reconcile model changes, never data changes, before claiming.
        if (worker.get() != null) return@withContext
        val job = store.updateReviewQueue { state ->
            if (state.configuration.paused) return@updateReviewQueue state to null
            val model=reviews.status()
            val next = ReviewQueueRules.claim(state.queue, clock.instant()) { it.scopedIntent!=null || (model.runtimeGuardConfigured && model.enabled) }
            state.copy(queue = next) to next.running
        } ?: return@withContext
        val task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (!current(job)) { finishFailure(job,"queue_binding_changed");return@launch }
                if(job.scopedIntent!=null) {
                    PersonalScopedQueueAdapter(store,clock).prepare(job)
                    return@launch
                }
                val intent=job.requireLegacyIntent()
                // Prepare the latest immutable report only after the durable running claim.
                val report = store.reviewJobReport(job, LocalDate.now(clock), LocalDate.now(clock.withZone(ZoneId.of(intent.timeZone))))
                val input = Trends.analysisInput(report)
                val facts = FactualReviews.prepare(input, intent.focus)
                val request = ReviewInterpretationRequest(ReviewFocusProtocol.PROFILE, report.current.oldest, report.current.newest,
                    report.selectedSport, intent.focus, input.evidenceReportSha256, intent.modelId, intent.selectionVersion)
                val interpretation = reviews.interpretQueued(request, report, intent.budget) { current(job) }
                if (interpretation.reason == "interpretation_busy" && interpretation.attempts.isEmpty()) {
                    store.updateReviewQueue { state ->
                        if (state.queue.running?.id != job.id) state to Unit else {
                            val pending = job.copy(startedUtc = null, eligibleUtc = clock.instant().plusSeconds(1).toString())
                            val merged = ReviewQueueRules.enqueue(state.queue.copy(running = null), listOf(pending.requireLegacyIntent()),
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
                reviews.publishIfSelected(intent.modelId, intent.selectionVersion) {
                    store.updateReviewQueue { state ->
                        if (!current(job)) state to Unit else state.copy(queue = ReviewQueueRules.finish(state.queue, outcome,
                            state.configuration.queuePolicy, clock.instant())) to Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                finishFailure(job, "review_cancelled")
                throw cancelled
            } catch (_: Exception) { finishFailure(job, "review_unavailable") }
            finally { releaseWorker(job.id,currentCoroutineContext()[Job]) }
        }
        val claim=WorkerClaim(job.id,task)
        if (!worker.compareAndSet(null, claim)) {
            task.cancel()
            finishFailure(job, "review_unavailable")
        } else {
            task.invokeOnCompletion { error ->
                if (error is CancellationException) finishFailure(job, "review_cancelled")
                worker.compareAndSet(claim, null)
            }
            task.start()
        }
        } finally { pumping.unlock() }
    }

    private fun finishFailure(job: ReviewQueueJob, reason: String) {
        runCatching { store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.finish(state.queue,
            ReviewQueueOutcome(job.id, job.intent, "unavailable", reason, clock.instant().toString(), job.startedUtc,scopedIntent=job.scopedIntent),
            state.configuration.queuePolicy, clock.instant())) to Unit } }
    }
    private fun releaseWorker(id: String,task: Job?) { worker.get()?.takeIf { it.id==id && it.task===task }?.let { worker.compareAndSet(it,null) } }
    override fun close() { worker.get()?.task?.cancel() }
}

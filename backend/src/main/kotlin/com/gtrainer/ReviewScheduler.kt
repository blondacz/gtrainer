package com.gtrainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.temporal.TemporalAdjusters

/** Code-owned routing only. No provider, import, consent grant or model-selection side effects. */
internal object ReviewScheduleRules {
    fun evaluate(state: ReviewSchedulerState, changes: List<ImportedReviewChange>, latestRevision: Long,
                 history: (LocalDate, LocalDate) -> HistoryResponse, now: Instant, model: ReviewModelStatus,
                 armedInProcess: Boolean, verifiedStepSources: Set<String> = emptySet(), queueRouting: Boolean = false): Pair<ReviewSchedulerState, ReviewScheduleStatus> {
        val config = state.configuration
        val zone = ZoneId.of(config.timeZone)
        val today = now.atZone(zone).toLocalDate()
        val reason = when {
            !config.enabled -> "schedules_disabled"
            config.paused -> "schedules_paused"
            !armedInProcess || !model.enabled || model.selectedModelId == null || model.profile != ReviewFocusProtocol.PROFILE -> "explicit_local_selection_required"
            model.models.none { it.id == model.selectedModelId && it.experimental } -> "local_model_unavailable"
            model.selectionVersion != state.modelVersion || model.selectedModelId != state.modelId -> "model_selection_changed"
            else -> "scheduler_ready"
        }
        val memories = state.memories.toMutableMap()
        val due = mutableListOf<ReviewDue>()
        val statuses = mutableListOf<ReviewTriggerStatus>()
        val armed = state.armedUtc?.let(Instant::parse) ?: now
        // Cursor/configuration baselines, not wall-clock timestamps, establish post-arm changes.
        // Retain temporarily future metadata even after advancing the database cursor.
        val fresh = changes.filter { it.revision > state.cursor }
        val freshLatest = fresh.groupBy { it.category to it.identitySha256 }.mapValues { (_, items) -> items.maxBy { it.revision } }
        val deferredSleep = state.deferredChanges.filter { it.sleepChanged }.map { it.category to it.identitySha256 }.toSet()
        val remainingDeferred = state.deferredChanges.filter { prior ->
            (freshLatest[prior.category to prior.identitySha256]?.revision ?: prior.revision) <= prior.revision
        }
        val received = (remainingDeferred + fresh.map { change ->
            val key = change.category to change.identitySha256
            if (key in deferredSleep && change.sleepAvailable && freshLatest[key]?.revision == change.revision)
                change.copy(sleepChanged = true) else change
        }).distinctBy { it.revision }
        require(received.size <= 50_000) { "Review changes exceed bound" }
        val rawEligible = received.filter { Instant.parse(it.receivedUtc) <= now && LocalDate.parse(it.observedDate) <= today }
        val eligibleRevisions = rawEligible.map { it.revision }.toSet()
        val changesByIdentity = received.groupBy { it.category to it.identitySha256 }
        val latestByIdentity = changesByIdentity.mapValues { (_, items) -> items.maxBy { it.revision } }
        fun unhandledFutureSleep(latest: ImportedReviewChange): Boolean = latest.sleepAvailable &&
            changesByIdentity.getValue(latest.category to latest.identitySha256).any { it.sleepChanged && it.revision !in eligibleRevisions }
        val eligible = rawEligible.map { change ->
            if (latestByIdentity[change.category to change.identitySha256]?.revision == change.revision && unhandledFutureSleep(change))
                change.copy(sleepChanged = true) else change
        }
        val deferred = received.filter { it.revision !in eligibleRevisions }.filter { latestByIdentity[it.category to it.identitySha256]?.revision == it.revision }.map { latest ->
            // A future HRV-only correction must not erase an as-yet unhandled sleep arrival.
            latest.copy(sleepChanged = latest.sleepChanged || unhandledFutureSleep(latest))
        }
        fun scopeDates(scope: ReviewScope, date: LocalDate): Pair<LocalDate, LocalDate> = when (scope.kind) {
            "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6).coerceAtMost(today) }
            "rolling" -> date.minusDays(scope.days.toLong() - 1) to date
            else -> date to date
        }
        fun emit(preset: ReviewPreset, trigger: ReviewTrigger, date: LocalDate, occurrence: String, activity: String? = null, notBefore: String? = null) {
            if (date > today) return // Future-dated source records are not a review of future physiology.
            val (oldest, newest) = scopeDates(preset.scope, date)
            if (oldest > newest) return
            val identity = "${state.version}:${preset.id}:${trigger.id}:${preset.scope.kind}:${preset.scope.days}:${preset.scope.sport ?: ""}:$oldest:$newest:$occurrence:${activity ?: ""}"
            due += ReviewDue(AnalysisClaims.hash(identity), preset.id, state.version, requireNotNull(model.selectedModelId),
                model.selectionVersion, preset.reviewType, preset.scope, oldest.toString(), newest.toString(), activity,
                preset.level, preset.focus, preset.budget, listOf(trigger.kind), now.toString(), notBefore, config.timeZone)
        }
        for (preset in config.presets) for (trigger in preset.triggers) {
            val key = "${preset.id}/${trigger.id}"
            var memory = memories[key] ?: ReviewTriggerMemory()
            val supported = trigger.kind != "daily_steps" || (verifiedStepSources.isNotEmpty() && preset.scope.sport == null)
            val triggerReason = when {
                !preset.enabled -> "preset_disabled"
                !supported -> "verified_daily_steps_unavailable"
                reason != "scheduler_ready" -> reason
                else -> "waiting_for_trigger"
            }
            if (triggerReason != "waiting_for_trigger") {
                statuses += ReviewTriggerStatus(preset.id, trigger.id, trigger.kind, triggerReason, null, null)
                continue
            }
            val relevant = eligible.filter { change ->
                    (preset.scope.sport == null || change.category == "wellness" || change.sport == preset.scope.sport)
            }
            var nextCheck: Instant? = null
            var nextReview: Instant? = null
            when (trigger.kind) {
                "daily_time", "weekly_time" -> {
                    val scheduledDate = if (trigger.kind == "daily_time") today
                        else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(requireNotNull(trigger.dayOfWeek))))
                    // atZone moves a DST gap forward and chooses the earlier offset in an overlap.
                    fun at(date: LocalDate) = date.atTime(LocalTime.parse(trigger.localTime)).atZone(zone).toInstant()
                    val occurrence = at(scheduledDate)
                    if (occurrence > armed && occurrence <= now && (memory.lastOccurrence == null || scheduledDate.toString() > requireNotNull(memory.lastOccurrence))) {
                        emit(preset, trigger, scheduledDate, scheduledDate.toString())
                        memory = memory.copy(lastOccurrence = scheduledDate.toString())
                    }
                    val increment = if (trigger.kind == "daily_time") 1L else 7L
                    var nextDate = if (occurrence > now && occurrence > armed &&
                        (memory.lastOccurrence == null || scheduledDate.toString() > requireNotNull(memory.lastOccurrence))) scheduledDate
                        else scheduledDate.plusDays(increment)
                    memory.lastOccurrence?.let { nextDate = nextDate.coerceAtLeast(LocalDate.parse(it).plusDays(increment)) }
                    if (at(nextDate) <= armed) {
                        val armDate = armed.atZone(zone).toLocalDate()
                        nextDate = if (trigger.kind == "daily_time") armDate else armDate.with(
                            TemporalAdjusters.nextOrSame(DayOfWeek.of(requireNotNull(trigger.dayOfWeek))))
                        if (at(nextDate) <= armed) nextDate = nextDate.plusDays(increment)
                        memory.lastOccurrence?.let { nextDate = nextDate.coerceAtLeast(LocalDate.parse(it).plusDays(increment)) }
                    }
                    nextReview = at(nextDate)
                }
                "changed_data" -> {
                    memory = memory.copy(changed = memory.changed || relevant.isNotEmpty(),
                        changedRevision = maxOf(memory.changedRevision, relevant.maxOfOrNull { it.revision } ?: 0))
                    val interval = trigger.intervalMinutes.toLong() * 60
                    val check = memory.nextCheckUtc?.let(Instant::parse) ?: armed.plusSeconds(interval)
                    nextCheck = check
                    if (check <= now) {
                        if (memory.changed) emit(preset, trigger, today, "check:$check:revision:${memory.changedRevision}")
                        nextCheck = check.plusSeconds(((Duration.between(check, now).seconds / interval) + 1) * interval)
                        memory = memory.copy(changed = false)
                    }
                    memory = memory.copy(nextCheckUtc = nextCheck.toString())
                }
                "activity_arrival", "wellness_arrival", "sleep_arrival" -> {
                    val incoming = relevant.filter { trigger.kind == "sleep_arrival" || latestByIdentity[it.category to it.identitySha256]?.revision == it.revision }.filter { when (trigger.kind) {
                        "activity_arrival" -> it.category == "activities"
                        "sleep_arrival" -> it.category == "wellness" && it.sleepChanged
                        else -> it.category == "wellness"
                    } }.map { change -> ReviewArrival(change.observedDate,
                        if (preset.scope.kind == "activity") change.identitySha256 else null,
                        Instant.parse(change.receivedUtc).plusSeconds(trigger.settleSeconds.toLong()).toString(),
                        change.identitySha256, change.category, change.revision) }
                    val candidates = (memory.arrivals + incoming).groupBy { it.category to it.identitySha256 }
                        .values.map { items -> items.maxBy { it.revision } }
                    val arrivals = candidates.mapNotNull { arrival ->
                        val corrected = latestByIdentity[arrival.category to arrival.identitySha256] ?: return@mapNotNull arrival
                        // Reconcile identity before date/sport/eligibility filtering. A correction must
                        // not leave stale work for its old date/sport, even when the correction is future.
                        if ((preset.scope.sport != null && corrected.category == "activities" && corrected.sport != preset.scope.sport) ||
                            (trigger.kind == "sleep_arrival" && !corrected.sleepAvailable)) return@mapNotNull null
                        if (trigger.kind != "sleep_arrival" || corrected.sleepChanged || corrected.observedDate != arrival.observedDate)
                            return@mapNotNull arrival.copy(observedDate = corrected.observedDate, revision = corrected.revision,
                                eligibleUtc = Instant.parse(corrected.receivedUtc).plusSeconds(trigger.settleSeconds.toLong()).toString())
                        arrival // HRV-only correction with unchanged populated sleep does not restart sleep settling.
                    }
                    require(arrivals.size <= 50_000) { "Review arrivals exceed bound" }
                    val ready = arrivals.filter { (if (queueRouting) Instant.parse(it.eligibleUtc).minusSeconds(trigger.settleSeconds.toLong()) <= now
                        else Instant.parse(it.eligibleUtc) <= now) && LocalDate.parse(it.observedDate) <= today }
                    ready.groupBy { it.observedDate to it.activitySha256 }.values.forEach { items ->
                        val arrival = items.maxBy { it.revision }
                        emit(preset, trigger, LocalDate.parse(arrival.observedDate), "arrival:revision:${arrival.revision}", arrival.activitySha256,
                            items.maxOf { it.eligibleUtc })
                    }
                    memory = memory.copy(arrivals = arrivals - ready.toSet())
                    nextReview = memory.arrivals.minOfOrNull { arrival -> maxOf(Instant.parse(arrival.eligibleUtc),
                        LocalDate.parse(arrival.observedDate).atStartOfDay(zone).toInstant()) }
                }
                "record_count" -> {
                    val start = if (trigger.period == "day") today else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val period = start.toString()
                    val count = relevant.count { Instant.parse(it.receivedUtc).atZone(zone).toLocalDate() >= start }
                    val previous = memory.countsByPeriod[period] ?: if (memory.countPeriod == period) memory.count else 0
                    val total = (previous.toLong() + count).coerceAtMost(1_000_000).toInt()
                    val counts = memory.countsByPeriod.toMutableMap()
                    memory.countPeriod?.let { if (it !in counts) counts[it] = memory.count }
                    counts[period] = total
                    require(counts.size <= 50_000) { "Review periods exceed bound" }
                    memory = memory.copy(countPeriod = period, count = total, countsByPeriod = counts)
                    if (total >= requireNotNull(trigger.threshold) && (memory.thresholdPeriod == null || period > requireNotNull(memory.thresholdPeriod))) {
                        emit(preset, trigger, today, "threshold:$period")
                        memory = memory.copy(thresholdPeriod = period)
                    }
                }
                "daily_steps" -> {
                    // Never sum overlapping daily providers, extrapolate a missing day or substitute zero.
                    val dailyRecords = history(today, today).wellness.filter { it.source in verifiedStepSources && it.date == today.toString() }
                    val steps = dailyRecords.singleOrNull()?.measurements?.get("steps")?.takeIf {
                        it.unit == "count" && it.value.isFinite() && it.value in 0.0..250_000.0 && it.value % 1.0 == 0.0
                    }?.value
                    if (steps == null) {
                        statuses += ReviewTriggerStatus(preset.id, trigger.id, trigger.kind, "usable_daily_steps_unavailable", null, null)
                        memories[key] = memory
                        continue
                    }
                    if (steps >= requireNotNull(trigger.threshold) && (memory.thresholdPeriod == null || today.toString() > requireNotNull(memory.thresholdPeriod))) {
                        emit(preset, trigger, today, "threshold:$today")
                        memory = memory.copy(thresholdPeriod = today.toString())
                    }
                }
            }
            memories[key] = memory
            statuses += ReviewTriggerStatus(preset.id, trigger.id, trigger.kind,
                if (due.any { it.presetId == preset.id && trigger.kind in it.reasons }) "review_due" else triggerReason,
                nextCheck?.toString(), nextReview?.toString())
        }
        val next = if (reason == "scheduler_ready") state.copy(cursor = latestRevision, memories = memories, deferredChanges = deferred)
            else state.copy(cursor = latestRevision, memories = emptyMap(), deferredChanges = emptyList(), armedUtc = now.toString())
        return next to ReviewScheduleStatus(config, state.version, reason, statuses, due)
    }
}

/** Persisted configuration, memory-only arming; restart/model changes require explicit re-enable.
 * Normal wiring accepts queue batches and rule memory atomically. Without a receiver this remains preview-only.
 */
class ReviewScheduler(private val store: HistoryStore, private val reviews: ReviewInterpretationService,
                      private val clock: Clock = Clock.systemUTC(), private val verifiedStepSources: Set<String> = emptySet(),
                      private val accept: ((List<ReviewDue>) -> Boolean)? = null, private val durableQueue: Boolean = false) {
    private var armed = false

    suspend fun configure(update: ReviewScheduleUpdate): ReviewScheduleStatus = withContext(Dispatchers.IO) {
        synchronized(this@ReviewScheduler) {
            val model = reviews.status()
            val config = update.configuration
            require(config.presets.all { reviews.permitsScheduleBudget(it.budget) }) { "Budget exceeds operator policy" }
            if (config.enabled && !config.paused) require(model.enabled && model.selectedModelId != null &&
                model.profile == ReviewFocusProtocol.PROFILE && model.models.any { it.id == model.selectedModelId }) { "Explicit local model required" }
            store.updateReviewSchedule(update.expectedVersion, config, clock.instant(), model.selectedModelId, model.selectionVersion)
            armed = config.enabled && !config.paused
            evaluate(false)
        }
    }

    suspend fun status(): ReviewScheduleStatus = withContext(Dispatchers.IO) { synchronized(this@ReviewScheduler) { evaluate(false) } }
    suspend fun tick(): ReviewScheduleStatus = withContext(Dispatchers.IO) { synchronized(this@ReviewScheduler) { evaluate(true) } }

    private fun evaluate(commit: Boolean): ReviewScheduleStatus = store.evaluateReviewSchedule { state, changes, revision, history ->
        val model = reviews.status()
        if (model.selectionVersion != state.modelVersion || model.selectedModelId != state.modelId || !model.enabled) armed = false
        val now = clock.instant()
        val (next, result) = ReviewScheduleRules.evaluate(state, changes, revision, history, now, model, armed, verifiedStepSources, durableQueue)
        // Routing never calls a provider. Queue state and consumed occurrences commit together.
        val accepted = commit && (durableQueue || result.due.isEmpty() || (accept != null && accept.invoke(result.due)))
        val updated = if (accepted && durableQueue) next.copy(queue = ReviewQueueRules.enqueue(ReviewQueueRules.reconcileImports(next.queue, changes, now), result.due,
            state.configuration.queuePolicy, now)) else next
        val reason = if (result.reason == "scheduler_ready" && accept == null && !durableQueue) "queue_not_configured" else result.reason
        (if (accepted) updated else state) to result.copy(reason = reason, executionAvailable = durableQueue && model.enabled &&
            model.runtimeGuardConfigured && !state.configuration.paused)
    }
}

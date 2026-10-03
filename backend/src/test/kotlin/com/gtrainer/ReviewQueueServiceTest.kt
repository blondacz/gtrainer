package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class ReviewQueueServiceTest {
    private val budget = ReviewExecutionPolicy(100, 1000)
    private fun configuration(enabled: Boolean = true, paused: Boolean = false,
                              policy: ReviewQueuePolicy = ReviewQueuePolicy(0, 0, 60)) =
        ReviewScheduleConfiguration(enabled, paused, "UTC", listOf(ReviewPreset("synthetic", "daily_combined", true,
            ReviewScope("rolling", 2), budget = budget,
            triggers = listOf(ReviewTrigger("arrival", "activity_arrival", settleSeconds = 0)))), policy)

    private fun capacityJobs(original: ReviewQueueJob, reviewType: String = "weekly") = (0 until 256).map { index ->
        val due = original.intent.copy(occurrenceId = if (index == 0) original.id else AnalysisClaims.hash("full-queue-$index"),
            reviewType = reviewType, oldest = LocalDate.parse(original.intent.oldest).minusDays(index.toLong()).toString(),
            newest = LocalDate.parse(original.intent.newest).minusDays(index.toLong()).toString())
        original.copy(id = due.occurrenceId, key = ReviewQueueRules.key(due), intent = due,
            occurrenceIds = if (index == 0) original.occurrenceIds else listOf(due.occurrenceId))
    }

    private class Fixture(val path: Path, val clock: ReviewTestClock, val model: SyntheticReviewModel,
                          val scope: CoroutineScope, private val guard: (suspend () -> Boolean)?, private val gate: Mutex) {
        var store = HistoryStore(path)
        var reviews = newReviews()
        var queue = ReviewQueueService(store, reviews, clock)
        var scheduler = ReviewScheduler(store, reviews, clock, durableQueue = true)
        private fun newReviews() = ReviewInterpretationService(listOf(model), ReviewExecutionPolicy(100, 1000, true), guard, gate)
        fun seed(history: HistoryResponse = analysisHistory()) {
            store.begin("activities", clock.instant())
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, history.activities), clock.instant())
            store.begin("wellness", clock.instant())
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, history.wellness), clock.instant())
        }
        fun importCurrent(value: Double) {
            val activity = analysisHistory().activities.last().copy(movingTime = Measurement(value, "seconds"))
            store.begin("activities", clock.instant())
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(activity)), clock.instant())
        }
        suspend fun configure(config: ReviewScheduleConfiguration) =
            scheduler.configure(ReviewScheduleUpdate(store.reviewScheduleState().version, config))
        suspend fun request(level: String = "interim") =
            queue.requestNow(ReviewNowRequest(store.reviewScheduleState().version, "synthetic", level))
        suspend fun completed(): ReviewQueueOutcome {
            eventually { store.reviewScheduleState().queue.running == null && store.reviewScheduleState().queue.outcomes.isNotEmpty() }
            return queue.status().outcomes.last()
        }
        fun restart() {
            queue.close(); reviews.close(); store.close()
            store = HistoryStore(path)
            reviews = newReviews()
            queue = ReviewQueueService(store, reviews, clock)
            scheduler = ReviewScheduler(store, reviews, clock, durableQueue = true)
        }
    }

    private fun fixture(model: SyntheticReviewModel = SyntheticReviewModel(), guard: (suspend () -> Boolean)? = { true },
                        gate: Mutex = Mutex(), test: suspend Fixture.() -> Unit) = runBlocking {
        coroutineScope {
            val directory = Files.createTempDirectory("gtrainer-synthetic-review-queue-")
            val workerJob = SupervisorJob(coroutineContext[Job])
            val workerScope = CoroutineScope(coroutineContext + workerJob)
            val fixture = Fixture(directory.resolve("synthetic.sqlite3"),
                ReviewTestClock(Instant.parse("2020-06-02T10:00:00Z")), model, workerScope, guard, gate)
            try { fixture.test() } finally {
                fixture.queue.close(); fixture.reviews.close()
                workerJob.cancelAndJoin()
                fixture.store.close()
                Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(directory)
            }
        }
    }

    @Test fun `manual request requires explicit model but works immediately with automation disabled at night`() = fixture {
        clock.now = Instant.parse("2020-06-02T23:45:00Z")
        seed()
        configure(configuration(enabled = false, policy = ReviewQueuePolicy(120, 300, 600)))
        assertFailsWith<IllegalArgumentException> { request() }
        assertTrue(model.packets.isEmpty())
        reviews.select(reviewSelection())
        val status = request("thorough")
        val job = status.pending.single()
        assertEquals("review_queued", status.reason)
        assertTrue(status.executionAvailable)
        assertTrue(job.manual)
        assertEquals("thorough", job.intent.level)
        assertEquals(clock.instant().toString(), job.eligibleUtc)
        assertEquals(listOf("manual_request"), job.reasons)
        assertEquals(budget, job.intent.budget)
        queue.pump(scope)
        val result = completed()
        assertEquals("available", result.status)
        assertFalse(result.stale)
        assertEquals(1, model.packets.size)
        assertTrue(result.interpretation!!.interpretations.all { it.supportingFacts.isNotEmpty() })
        assertEquals(result, queue.status().lastSuccess)
        assertTrue(scheduler.tick().due.isEmpty())
    }

    @Test fun `automatic arrivals are not suppressed in evening and repeated ticks do not replay occurrences`() = fixture {
        seed()
        reviews.select(reviewSelection())
        configure(configuration())
        clock.now = Instant.parse("2020-06-02T23:45:00Z")
        importCurrent(2100.0)
        val due = scheduler.tick().due.single()
        val pending = queue.status().pending.single()
        assertFalse(pending.manual)
        assertEquals(due.occurrenceId, pending.id)
        assertEquals(due.budget, pending.intent.budget)
        assertTrue(scheduler.tick().due.isEmpty())
        assertEquals(listOf(pending), queue.status().pending)
        queue.pump(scope)
        assertEquals("available", completed().status)
        repeat(3) { scheduler.tick(); queue.pump(scope) }
        assertEquals(1, model.packets.size)
        assertTrue(queue.status().pending.isEmpty())
    }

    @Test fun `manual request after completion bypasses stored cooldown without waiting for debounce`() = fixture {
        seed(); reviews.select(reviewSelection())
        configure(configuration(policy = ReviewQueuePolicy(120, 300, 600)))
        request(); queue.pump(scope)
        val first = completed()
        eventually { scope.coroutineContext[Job]!!.children.none { it.isActive } }
        assertTrue(store.reviewScheduleState().queue.cooldownUntilUtc.values.all { Instant.parse(it) > clock.instant() })
        val manual = request().pending.single()
        assertEquals(clock.instant().toString(), manual.eligibleUtc)
        queue.pump(scope)
        eventually { store.reviewScheduleState().queue.outcomes.size == 2 && store.reviewScheduleState().queue.running == null }
        val second = queue.status().lastSuccess!!
        assertNotEquals(first.id, second.id)
        assertEquals("available", second.status)
        assertEquals(2, model.packets.size)
        assertEquals(model.packets.first(), model.packets.last())
    }

    @Test fun `missing guard fails closed even for manual work without claiming or calling synthetic provider`() = fixture(guard = null) {
        seed()
        reviews.select(reviewSelection())
        configure(configuration(enabled = false))
        request()
        val before = store.reviewScheduleState().queue
        repeat(3) { queue.pump(scope) }
        val status = queue.status()
        assertEquals("runtime_guard_unavailable", status.reason)
        assertFalse(status.executionAvailable)
        assertEquals(before, store.reviewScheduleState().queue)
        assertNull(status.running)
        assertTrue(status.outcomes.isEmpty())
        assertTrue(model.packets.isEmpty())
    }

    @Test fun `false guard publishes factual support with zero provider calls and no automatic retry`() = fixture(guard = { false }) {
        seed()
        reviews.select(reviewSelection(correction = true))
        configure(configuration())
        request()
        queue.pump(scope)
        val outcome = completed()
        assertEquals("factual_only", outcome.status)
        assertEquals("runtime_guard_failed", outcome.reason)
        assertNotNull(outcome.facts)
        assertTrue(outcome.interpretation!!.attempts.isEmpty())
        assertTrue(model.packets.isEmpty())
        clock.now = clock.now.plusSeconds(86400)
        repeat(3) { queue.pump(scope) }
        assertTrue(queue.status().pending.isEmpty())
        assertEquals(1, queue.status().outcomes.size)
        assertEquals(outcome.id, queue.status().lastSuccess!!.id)
    }

    @Test fun `queued budget forbids correction despite operator permission and selected correction opt in`() {
        val model = SyntheticReviewModel { _, _ -> "invalid synthetic JSON" }
        fixture(model) {
            seed(); reviews.select(reviewSelection(correction = true)); configure(configuration())
            assertTrue(reviews.status().correctionEnabled)
            assertTrue(reviews.status().executionPolicy.allowCorrectiveAttempt)
            val pending = request().pending.single()
            assertFalse(pending.intent.budget.allowCorrectiveAttempt)
            queue.pump(scope)
            val outcome = completed()
            assertEquals("factual_only", outcome.status)
            assertEquals("invalid_json", outcome.reason)
            assertNotNull(outcome.facts)
            assertEquals(listOf(ReviewAttempt(1, "rejected", "invalid_json")), outcome.interpretation!!.attempts)
            assertEquals(1, model.packets.size)
            assertEquals(listOf<String?>(null), model.feedback)
            repeat(3) { queue.pump(scope) }
            assertEquals(1, model.packets.size)
            assertTrue(queue.status().pending.isEmpty())
        }
    }

    @Test fun `Japan and Auckland queued local dates may lead UTC without changing UTC report evaluation`() {
        for (zone in listOf("Asia/Tokyo", "Pacific/Auckland")) for (manual in listOf(false, true)) {
            fixture {
                clock.now = Instant.parse("2020-06-02T20:00:00Z")
                val history = analysisHistory().let { original -> original.copy(
                    activities = original.activities.map { activity -> activity.copy(
                        startLocal = "${LocalDate.parse(activity.startLocal.take(10)).plusDays(1)}T10:00:00") },
                    wellness = original.wellness.map { wellness -> wellness.copy(date = LocalDate.parse(wellness.date).plusDays(1).toString()) }) }
                seed(history)
                reviews.select(reviewSelection())
                configure(configuration().copy(timeZone = zone))
                val job = if (manual) request().pending.single() else {
                    store.begin("activities", clock.instant())
                    store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(history.activities.last().copy(
                        movingTime = Measurement(2100.0, "seconds")))), clock.instant())
                    assertEquals("2020-06-03", scheduler.tick().due.single().newest)
                    queue.status().pending.single()
                }
                val utcDate = LocalDate.now(clock)
                val localDate = LocalDate.now(clock.withZone(ZoneId.of(zone)))
                assertEquals(LocalDate.parse("2020-06-02"), utcDate)
                assertEquals(LocalDate.parse("2020-06-03"), localDate)
                assertEquals(zone, job.intent.timeZone)
                assertEquals("2020-06-02", job.intent.oldest)
                assertEquals("2020-06-03", job.intent.newest)
                assertFailsWith<IllegalArgumentException> { store.reviewJobReport(job, utcDate) }
                val report = store.reviewJobReport(job, utcDate, latestAllowedDate = localDate)
                assertEquals(utcDate.toString(), report.evaluatedOnUtc)
                assertEquals(job.intent.oldest, report.current.oldest)
                assertEquals(job.intent.newest, report.current.newest)
                val expected = FactualReviews.prepare(Trends.analysisInput(report))
                queue.pump(scope)
                val outcome = completed()
                assertEquals("available", outcome.status, "$zone manual=$manual")
                assertEquals(expected, outcome.facts)
                assertFalse(outcome.stale)
                assertEquals(1, model.packets.size)
                assertEquals(expected.evidenceReportSha256, outcome.interpretation!!.evidenceReportSha256)
                assertFalse(queue.status().lastSuccess!!.stale)
            }
        }
    }

    @Test fun `claim snapshots latest imports rather than input at enqueue time and copies job budget`() = fixture {
        seed()
        reviews.select(reviewSelection())
        configure(configuration(policy = ReviewQueuePolicy(10, 0, 60)).let { config ->
            config.copy(presets = listOf(config.presets.single().copy(budget = ReviewExecutionPolicy(100, 900))))
        })
        importCurrent(2100.0)
        scheduler.tick()
        val pending = store.reviewScheduleState().queue.pending.single()
        val oldHash = Trends.analysisInput(store.reviewJobReport(pending, LocalDate.now(clock))).evidenceReportSha256
        importCurrent(2400.0)
        val expected = Trends.analysisInput(store.reviewJobReport(pending, LocalDate.now(clock))).evidenceReportSha256
        assertNotEquals(oldHash, expected)
        queue.pump(scope)
        assertTrue(model.packets.isEmpty())
        assertNull(queue.status().running)
        clock.now = clock.now.plusSeconds(10)
        queue.pump(scope)
        val outcome = completed()
        assertEquals("available", outcome.status)
        assertEquals(ReviewExecutionPolicy(100, 900), outcome.intent.budget)
        assertEquals(expected, outcome.facts!!.evidenceReportSha256)
        assertEquals(expected, outcome.interpretation!!.evidenceReportSha256)
        assertFalse(outcome.stale)
        assertEquals(1, model.packets.size)
        assertFailsWith<IllegalArgumentException> {
            configure(configuration().let { it.copy(presets = listOf(it.presets.single().copy(budget = ReviewExecutionPolicy(101, 1000)))) })
        }
    }

    @Test fun `imports during suspended provider keep fixed running snapshot with historical support and one stale followup`() {
        val started = CompletableDeferred<ReviewFocusPacket>()
        val release = CompletableDeferred<Unit>()
        val model = SyntheticReviewModel { packet, _ -> started.complete(packet); release.await(); relevantFocus(packet) }
        fixture(model) {
            seed()
            reviews.select(reviewSelection())
            configure(configuration(policy = ReviewQueuePolicy(0, 300, 60)))
            val original = request().pending.single()
            val expectedFacts = FactualReviews.prepare(Trends.analysisInput(store.reviewJobReport(original, LocalDate.now(clock))))
            queue.pump(scope)
            withTimeout(5000) { started.await() }
            val running = assertNotNull(store.reviewScheduleState().queue.running)
            assertEquals(budget, running.intent.budget)
            importCurrent(2100.0)
            scheduler.tick()
            importCurrent(2400.0)
            scheduler.tick()
            val during = store.reviewScheduleState().queue
            assertEquals(running, during.running)
            assertEquals(2, during.pending.single().occurrenceIds.size)
            assertTrue(during.outcomes.isEmpty())
            assertEquals(1, model.packets.size)
            queue.pump(scope)
            assertEquals(running, store.reviewScheduleState().queue.running)
            release.complete(Unit)
            val outcome = completed()
            assertEquals("available", outcome.status)
            assertTrue(outcome.stale)
            assertEquals(expectedFacts, outcome.facts)
            val interpretation = assertNotNull(outcome.interpretation)
            assertEquals(expectedFacts.evidenceReportSha256, interpretation.evidenceReportSha256)
            val historicalFacts = expectedFacts.groups.flatMap { it.facts }
            assertTrue(interpretation.interpretations.flatMap { it.supportingFacts }.all { it in historicalFacts })
            val pending = queue.status().pending.single()
            assertEquals(pending.maximumDeferralUtc, pending.eligibleUtc)
            assertEquals(outcome.id, queue.status().lastSuccess!!.id)
            assertTrue(queue.status().lastSuccess!!.stale)
            assertEquals(1, model.packets.size)
        }
    }

    @Test fun `model selection change invalidates running and pending and prevents obsolete publication`() {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val model = SyntheticReviewModel { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        fixture(model) {
            seed(); reviews.select(reviewSelection()); configure(configuration())
            request(); queue.pump(scope)
            withTimeout(5000) { started.await() }
            request()
            reviews.select(reviewSelection(enabled = false))
            val status = queue.status()
            withTimeout(5000) { stopped.await() }
            assertEquals("explicit_local_selection_required", status.reason)
            assertFalse(status.executionAvailable)
            assertNull(status.running)
            assertTrue(status.pending.isEmpty())
            assertNull(status.lastSuccess)
            assertTrue(status.outcomes.none { it.status == "available" || it.interpretation != null })
            assertTrue(status.outcomes.any { it.reason == "model_selection_changed" })
            reviews.select(reviewSelection())
            queue.pump(scope)
            assertEquals(1, model.packets.size)
        }
    }

    @Test fun `selection changes wait for publication critical callback and obsolete versions cannot commit`() = fixture {
        seed()
        val selected = reviews.select(reviewSelection())
        configure(configuration())
        request()
        store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.claim(state.queue, clock.instant())) to Unit }
        val running = assertNotNull(store.reviewScheduleState().queue.running)
        val success = ReviewQueueOutcome(running.id, running.intent, "available", "synthetic_commit", clock.instant().toString(), running.startedUtc)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val selecting = CountDownLatch(1)
        val published = CompletableFuture<Boolean?>()
        val changed = CompletableFuture<ReviewModelStatus>()
        val publisher = thread(start = false, name = "synthetic-review-publication") {
            try {
                published.complete(reviews.publishIfSelected(running.intent.modelId, running.intent.selectionVersion) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.finish(state.queue, success,
                        state.configuration.queuePolicy, clock.instant())) to Unit }
                    true
                })
            } catch (error: Throwable) { published.completeExceptionally(error) }
        }
        val selector = thread(start = false, name = "synthetic-review-selection") {
            selecting.countDown()
            try { changed.complete(reviews.select(reviewSelection(enabled = false))) }
            catch (error: Throwable) { changed.completeExceptionally(error) }
        }
        try {
            publisher.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            selector.start()
            assertTrue(selecting.await(5, TimeUnit.SECONDS))
            eventually { selector.state == Thread.State.BLOCKED || changed.isDone }
            assertEquals(Thread.State.BLOCKED, selector.state)
            assertFalse(changed.isDone)
            assertEquals(selected.selectionVersion, reviews.status().selectionVersion)
            assertEquals(running, store.reviewScheduleState().queue.running)
            release.countDown()
            assertEquals(true, published.get(5, TimeUnit.SECONDS))
            assertFalse(changed.get(5, TimeUnit.SECONDS).enabled)
            assertEquals(success, store.reviewScheduleState().queue.lastSuccess)
            assertNull(store.reviewScheduleState().queue.running)
            var forbiddenCalls = 0
            assertNull(reviews.publishIfSelected(running.intent.modelId, running.intent.selectionVersion) { forbiddenCalls++; true })
            val reselected = reviews.select(reviewSelection())
            assertTrue(reselected.selectionVersion > running.intent.selectionVersion)
            assertNull(reviews.publishIfSelected(running.intent.modelId, running.intent.selectionVersion) { forbiddenCalls++; true })
            assertNull(reviews.publishIfSelected("synthetic-other", reselected.selectionVersion) { forbiddenCalls++; true })
            assertEquals(0, forbiddenCalls)
            assertEquals(listOf(success), store.reviewScheduleState().queue.outcomes)
            assertTrue(model.packets.isEmpty())
        } finally {
            release.countDown()
            publisher.join(5000); selector.join(5000)
            assertFalse(publisher.isAlive || selector.isAlive)
        }
    }

    @Test fun `parent cancellation during lazy task installation clears worker and allows future pumps`() = fixture {
        seed(); reviews.select(reviewSelection()); configure(configuration())
        val original = request().pending.single()
        val parent = SupervisorJob(scope.coroutineContext[Job])
        val context = scope.coroutineContext + parent
        val reads = AtomicInteger()
        // pump checks isActive first; launch reads the context again after the durable claim.
        val cancellingScope = object : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() {
                    if (reads.incrementAndGet() == 2) {
                        assertEquals(original.id, store.reviewScheduleState().queue.running?.id)
                        parent.cancel()
                    }
                    return context
                }
        }
        try {
            queue.pump(cancellingScope)
            withTimeout(5000) { parent.join() }
            assertTrue(parent.isCancelled)
            assertTrue(reads.get() >= 2)
            val cancelled = completed()
            assertEquals(original.id, cancelled.id)
            assertEquals("review_cancelled", cancelled.reason)
            assertEquals("unavailable", cancelled.status)
            assertNull(queue.status().running)
            assertTrue(model.packets.isEmpty())
            val next = request().pending.single()
            queue.pump(scope)
            eventually { store.reviewScheduleState().queue.outcomes.any { it.id == next.id } }
            val outcome = queue.status().outcomes.single { it.id == next.id }
            assertEquals("available", outcome.status)
            assertEquals(next.id, queue.status().lastSuccess!!.id)
            assertEquals(1, model.packets.size)
        } finally { parent.cancelAndJoin() }
    }

    @Test fun `configuration pause and import removal cancel obsolete workers directly without resurrecting their result`() {
        for (change in listOf("configuration", "pause", "remove")) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val model = SyntheticReviewModel { _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            fixture(model) {
                seed(); reviews.select(reviewSelection()); configure(configuration())
                request(); queue.pump(scope)
                withTimeout(5000) { started.await() }
                request()
                when (change) {
                    "configuration" -> configure(configuration().let { it.copy(presets = listOf(it.presets.single().copy(focus = "wellness"))) })
                    "pause" -> configure(configuration(paused = true))
                    else -> store.removeImports()
                }
                queue.cancelObsolete()
                withTimeout(5000) { stopped.await() }
                eventually { scope.coroutineContext[Job]!!.children.none { it.isActive } }
                val status = queue.status()
                assertNull(status.running, change)
                assertTrue(status.pending.isEmpty(), change)
                assertNull(status.lastSuccess, change)
                assertTrue(status.outcomes.none { it.status == "available" || it.interpretation != null }, change)
                if (change == "pause") {
                    assertEquals("schedules_paused", status.reason)
                    assertFailsWith<IllegalArgumentException> { request() }
                }
                if (change == "remove") assertTrue(status.outcomes.isEmpty())
                queue.pump(scope)
                assertEquals(1, model.packets.size)
            }
        }
    }

    @Test fun `shared analysis gate requeues busy work without attempts and starts only after gate is released`() {
        val started = CompletableDeferred<Unit>()
        val prototypeModel = SyntheticAnalysisModel { started.complete(Unit); awaitCancellation() }
        val prototype = AnalysisService(listOf(prototypeModel))
        try {
            fixture(gate = prototype.inferenceGate()) {
                seed(); reviews.select(reviewSelection()); configure(configuration())
                val report = analysisReport()
                val selected = prototype.select(syntheticModelOption.id)
                val occupying = scope.async { prototype.analyze(analysisRequest(report, selected), report) { report } }
                withTimeout(5000) { started.await() }
                val original = request().pending.single()
                queue.pump(scope)
                eventually { store.reviewScheduleState().queue.pending.isNotEmpty() && scope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
                val waiting = queue.status()
                assertNull(waiting.running)
                assertTrue(waiting.outcomes.isEmpty())
                assertEquals(original.id, waiting.pending.single().id)
                assertEquals(original.firstQueuedUtc, waiting.pending.single().firstQueuedUtc)
                assertEquals(original.maximumDeferralUtc, waiting.pending.single().maximumDeferralUtc)
                assertTrue(model.packets.isEmpty())
                assertEquals(1, prototypeModel.calls)
                occupying.cancelAndJoin()
                clock.now = clock.now.plusSeconds(1)
                queue.pump(scope)
                val outcome = completed()
                assertEquals("available", outcome.status)
                assertEquals(listOf(ReviewAttempt(1, "accepted", null)), outcome.interpretation!!.attempts)
                assertEquals(1, model.packets.size)
            }
        } finally { prototype.close() }
    }

    @Test fun `queue cancellation releases shared analysis gate and keeps cancelled result out of last success`() {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val model = SyntheticReviewModel { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val prototype = AnalysisService(listOf(SyntheticAnalysisModel()))
        try {
            fixture(model, gate = prototype.inferenceGate()) {
                seed(); reviews.select(reviewSelection()); configure(configuration())
                request(); queue.pump(scope)
                withTimeout(5000) { started.await() }
                val report = analysisReport()
                val selected = prototype.select(syntheticModelOption.id)
                assertEquals("analysis_busy", prototype.analyze(analysisRequest(report, selected), report) { report }.reason)
                queue.invalidate("user_cancelled")
                withTimeout(5000) { stopped.await() }
                eventually { scope.coroutineContext[Job]!!.children.none { it.isActive } }
                val status = queue.status()
                assertNull(status.running)
                assertTrue(status.pending.isEmpty())
                assertNull(status.lastSuccess)
                assertEquals("cancelled", status.outcomes.single().status)
                assertEquals("available", prototype.analyze(analysisRequest(report, selected), report) { report }.status)
            }
        } finally { prototype.close() }
    }

    @Test fun `durable capacity rejection rolls back scheduler occurrence memory and queue as one transaction`() = fixture {
        seed(); reviews.select(reviewSelection()); configure(configuration())
        val original = request().pending.single()
        store.updateReviewQueue { state ->
            val jobs = (0 until 256).map { index ->
                val due = original.intent.copy(occurrenceId = AnalysisClaims.hash("capacity-$index"), modelId = "synthetic-$index")
                original.copy(id = due.occurrenceId, key = ReviewQueueRules.key(due), intent = due, occurrenceIds = listOf(due.occurrenceId))
            }
            state.copy(queue = state.queue.copy(pending = jobs)) to Unit
        }
        importCurrent(2100.0)
        val before = store.reviewScheduleState()
        assertFailsWith<ReviewQueueCapacity> { scheduler.tick() }
        assertEquals(before, store.reviewScheduleState())
        assertTrue(model.packets.isEmpty())
    }

    @Test fun `busy restoration preserves all occurrence ids and its reserved slot against concurrent producer admission`() {
        val gate = Mutex()
        fixture(gate = gate) {
            seed(); reviews.select(reviewSelection()); configure(configuration())
            request()
            val original = request().pending.single()
            assertEquals(2, original.occurrenceIds.size)
            store.updateReviewQueue { state -> state.copy(queue = state.queue.copy(
                pending = capacityJobs(original, original.intent.reviewType).mapIndexed { index, job ->
                    if (index == 0) job else job.copy(eligibleUtc = clock.instant().plusSeconds(30).toString())
                })) to Unit }
            val context = scope.coroutineContext
            val reads = AtomicInteger()
            val admitted = CompletableDeferred<Unit>()
            val restoringScope = object : CoroutineScope {
                override val coroutineContext: CoroutineContext
                    get() {
                        if (reads.incrementAndGet() == 2) {
                            val claimed = store.reviewScheduleState()
                            assertEquals(original.id, claimed.queue.running?.id)
                            assertEquals(255, claimed.queue.pending.size)
                            val overflow = original.intent.copy(occurrenceId = AnalysisClaims.hash("busy-overflow"), focus = "wellness")
                            assertFailsWith<ReviewQueueCapacity> {
                                store.updateReviewQueue { state -> state.copy(queue = ReviewQueueRules.enqueue(state.queue,
                                    listOf(overflow), state.configuration.queuePolicy, clock.instant())) to Unit }
                            }
                            assertEquals(claimed, store.reviewScheduleState())
                            admitted.complete(Unit)
                        }
                        return context
                    }
            }
            gate.lock()
            try {
                queue.pump(restoringScope)
                withTimeout(5000) { admitted.await() }
                eventually { store.reviewScheduleState().queue.running == null && scope.coroutineContext[Job]!!.children.none { it.isActive } }
                val restored = queue.status()
                assertEquals(256, restored.pending.size)
                assertEquals(original.occurrenceIds, restored.pending.single { it.id == original.id }.occurrenceIds)
                assertTrue(restored.outcomes.isEmpty())
                assertTrue(model.packets.isEmpty())
            } finally { gate.unlock() }
            queue.pump(scope)
            val outcome = completed()
            assertEquals("available", outcome.status)
            assertEquals(original.id, outcome.id)
            assertEquals(255, queue.status().pending.size)
            assertEquals(1, model.packets.size)
        }
    }

    @Test fun `producer backpressure rolls back due memory but does not prevent independently pumped consumer progress`() = fixture {
        seed(); reviews.select(reviewSelection()); configure(configuration())
        val original = request().pending.single()
        val jobs = capacityJobs(original)
        store.updateReviewQueue { state -> state.copy(queue = state.queue.copy(pending = jobs)) to Unit }
        importCurrent(2100.0)
        val before = store.reviewScheduleState()
        assertFailsWith<ReviewQueueCapacity> { scheduler.tick() }
        assertEquals(before, store.reviewScheduleState())
        queue.pump(scope)
        val outcome = completed()
        assertEquals(jobs.first().id, outcome.id)
        assertEquals("available", outcome.status)
        assertEquals(255, queue.status().pending.size)
        assertEquals(1, model.packets.size)
        eventually { scope.coroutineContext[Job]!!.children.none { it.isActive } }
        assertEquals(1, scheduler.tick().due.size)
        assertEquals(256, queue.status().pending.size)
        assertTrue(scheduler.tick().due.isEmpty())
        assertEquals(outcome.id, queue.status().lastSuccess!!.id)
    }

    @Test fun `restart recovers persisted running and pending without replay and forgets explicit model selection`() = fixture {
        seed(); reviews.select(reviewSelection()); configure(configuration())
        request(); queue.pump(scope)
        val success = completed()
        eventually { scope.coroutineContext[Job]!!.children.none { it.isActive } }
        request()
        store.updateReviewQueue { state ->
            state.copy(queue = ReviewQueueRules.claim(state.queue, clock.instant())) to Unit
        }
        val running = assertNotNull(store.reviewScheduleState().queue.running)
        request()
        val pending = store.reviewScheduleState().queue.pending.single()
        restart()
        assertFalse(reviews.status().enabled)
        assertNull(reviews.status().selectedModelId)
        val recovered = queue.status()
        assertEquals("explicit_local_selection_required", recovered.reason)
        assertFalse(recovered.executionAvailable)
        assertNull(recovered.running)
        assertTrue(recovered.pending.isEmpty())
        val retained = assertNotNull(recovered.lastSuccess)
        assertEquals(success.id, retained.id)
        assertEquals("interrupted", recovered.outcomes.single { it.id == running.id }.status)
        assertEquals("cancelled", recovered.outcomes.single { it.id == pending.id }.status)
        assertEquals(success.facts, retained.facts)
        assertEquals(success.interpretation, retained.interpretation)
        assertTrue(retained.stale)
        assertEquals("explicit_local_selection_required", scheduler.tick().reason)
        clock.now = clock.now.plusSeconds(86400)
        repeat(3) { queue.pump(scope); scheduler.tick() }
        assertEquals(1, model.packets.size)
        reviews.select(reviewSelection())
        assertEquals("explicit_local_selection_required", scheduler.tick().reason)
        configure(configuration())
        assertTrue(scheduler.tick().due.isEmpty())
        assertTrue(queue.status().pending.isEmpty())
        assertEquals(1, model.packets.size)
    }

    companion object {
        private suspend fun eventually(condition: () -> Boolean) = withTimeout(5000) {
            while (!condition()) { yield(); delay(1) }
        }
    }
}

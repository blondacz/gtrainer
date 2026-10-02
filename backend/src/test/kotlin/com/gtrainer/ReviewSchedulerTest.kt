package com.gtrainer

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.*
import kotlin.test.*

internal class ReviewTestClock(var now: Instant) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
}

class ReviewSchedulerTest {
    private fun <T> fixture(test: (HistoryStore, ReviewTestClock, SyntheticReviewModel, ReviewInterpretationService) -> T): T {
        val directory = Files.createTempDirectory("gtrainer-synthetic-scheduler-")
        val store = HistoryStore(directory.resolve("synthetic.sqlite3"))
        val clock = ReviewTestClock(Instant.parse("2020-06-01T10:00:00Z"))
        val model = SyntheticReviewModel()
        val reviews = ReviewInterpretationService(listOf(model), ReviewExecutionPolicy(100, 1000, true), { true })
        return try { test(store, clock, model, reviews) } finally {
            reviews.close(); store.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
    private fun config(enabled: Boolean = true, paused: Boolean = false, trigger: ReviewTrigger = ReviewTrigger("arrival", "activity_arrival", settleSeconds = 0)) =
        ReviewScheduleConfiguration(enabled, paused, "Europe/Prague", listOf(ReviewPreset("custom", "weekly", true,
            scope = ReviewScope("day"), level = "thorough", budget = ReviewExecutionPolicy(100, 1000), triggers = listOf(trigger))))

    @Test fun `defaults disabled and enable requires separate explicit selection without hosted or prototype fallback`() = fixture { store, clock, model, reviews -> runBlocking {
        val scheduler = ReviewScheduler(store, reviews, clock)
        val status = scheduler.status()
        assertEquals("schedules_disabled", status.reason)
        assertFalse(status.configuration.enabled)
        assertTrue(status.configuration.presets.all { !it.enabled })
        assertTrue(status.due.isEmpty())
        assertFailsWith<IllegalArgumentException> { scheduler.configure(ReviewScheduleUpdate(0, config())) }
        assertEquals(0, store.reviewScheduleState().version)
        reviews.select(reviewSelection())
        val enabled = scheduler.configure(ReviewScheduleUpdate(0, config()))
        assertEquals("queue_not_configured", enabled.reason)
        assertFalse(enabled.executionAvailable)
        assertTrue(model.packets.isEmpty())
        assertFailsWith<ReviewScheduleConflict> { scheduler.configure(ReviewScheduleUpdate(0, config())) }
        assertEquals(1, scheduler.status().configurationVersion)
        assertFailsWith<IllegalArgumentException> { scheduler.configure(ReviewScheduleUpdate(1, config().copy(presets = listOf(
            config().presets.single().copy(budget = ReviewExecutionPolicy(101, 1000)))))) }
        assertEquals(1, scheduler.status().configurationVersion)
    } }

    @Test fun `without queue due intents stay unconsumed and status never mutates scheduler or calls providers`() = fixture { store, clock, model, reviews -> runBlocking {
        reviews.select(reviewSelection())
        val scheduler = ReviewScheduler(store, reviews, clock)
        scheduler.configure(ReviewScheduleUpdate(0, config()))
        clock.now = clock.now.plusSeconds(1)
        store.begin("activities", clock.now)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.now)
        val original = store.reviewScheduleState()
        val preview = scheduler.status()
        assertEquals(1, preview.due.size)
        assertEquals(original, store.reviewScheduleState())
        assertEquals(preview, scheduler.tick())
        assertEquals(original, store.reviewScheduleState())
        assertEquals(preview.due.single().occurrenceId, scheduler.tick().due.single().occurrenceId)
        assertTrue(model.packets.isEmpty())
    } }

    @Test fun `accepted batch persists once and duplicate imports cannot create further due work`() = fixture { store, clock, model, reviews -> runBlocking {
        reviews.select(reviewSelection())
        val accepted = mutableListOf<ReviewDue>()
        val scheduler = ReviewScheduler(store, reviews, clock, accept = { accepted += it; true })
        scheduler.configure(ReviewScheduleUpdate(0, config()))
        clock.now = clock.now.plusSeconds(1)
        store.begin("activities", clock.now)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.now)
        assertEquals(1, scheduler.tick().due.size)
        assertEquals(1, accepted.size)
        assertEquals("weekly", accepted.single().reviewType)
        assertEquals("thorough", accepted.single().level)
        assertEquals("day", accepted.single().scope.kind)
        assertTrue(scheduler.tick().due.isEmpty())
        store.begin("activities", clock.now)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.now)
        assertTrue(scheduler.tick().due.isEmpty())
        assertEquals(1, accepted.size)
        assertTrue(model.packets.isEmpty())
    } }

    @Test fun `rejected or throwing handoff never consumes a threshold occurrence`() = fixture { store, clock, _, reviews -> runBlocking {
        reviews.select(reviewSelection())
        var accept = false
        var fail = false
        val scheduler = ReviewScheduler(store, reviews, clock, accept = { if (fail) error("private diagnostic"); accept })
        scheduler.configure(ReviewScheduleUpdate(0, config(trigger = ReviewTrigger("count", "record_count", threshold = 1))))
        clock.now = clock.now.plusSeconds(1)
        store.begin("activities", clock.now)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.now)
        val prior = store.reviewScheduleState()
        val due = scheduler.tick().due.single()
        assertEquals(prior, store.reviewScheduleState())
        fail = true
        assertFailsWith<IllegalStateException> { scheduler.tick() }
        assertEquals(prior, store.reviewScheduleState())
        fail = false; accept = true
        assertEquals(due.occurrenceId, scheduler.tick().due.single().occurrenceId)
        assertTrue(scheduler.tick().due.isEmpty())
    } }

    @Test fun `pause model change restart removal and enable reset pending baseline without retrospective import storms`() = fixture { store, clock, model, reviews -> runBlocking {
        reviews.select(reviewSelection())
        val scheduler = ReviewScheduler(store, reviews, clock, accept = { true })
        scheduler.configure(ReviewScheduleUpdate(0, config(trigger = ReviewTrigger("arrival", "activity_arrival", settleSeconds = 600))))
        clock.now = clock.now.plusSeconds(1)
        store.begin("activities", clock.now)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.now)
        assertTrue(scheduler.tick().due.isEmpty())
        assertTrue(store.reviewScheduleState().memories.values.single().arrivals.isNotEmpty())
        scheduler.configure(ReviewScheduleUpdate(1, config(paused = true)))
        clock.now = clock.now.plusSeconds(1000)
        assertEquals("schedules_paused", scheduler.tick().reason)
        assertTrue(scheduler.tick().due.isEmpty())
        scheduler.configure(ReviewScheduleUpdate(2, config()))
        assertTrue(scheduler.tick().due.isEmpty())
        reviews.select(reviewSelection(enabled = false))
        assertTrue(scheduler.tick().due.isEmpty())
        reviews.select(reviewSelection())
        assertEquals("explicit_local_selection_required", scheduler.tick().reason)
        scheduler.configure(ReviewScheduleUpdate(3, config()))
        val restarted = ReviewScheduler(store, reviews, clock, accept = { true })
        assertEquals("explicit_local_selection_required", restarted.tick().reason)
        assertTrue(restarted.tick().due.isEmpty())
        store.removeImports()
        assertTrue(store.reviewScheduleState().memories.isEmpty())
        assertEquals(0, store.reviewScheduleState().cursor)
        assertTrue(store.reviewScheduleState().configuration.enabled)
        assertTrue(scheduler.tick().due.isEmpty())
        assertTrue(model.packets.isEmpty())
    } }
}

package com.gtrainer

import java.time.Instant
import kotlin.test.*

class ReviewScheduleRegressionTest {
    private val synthetic = SyntheticReviewModel()
    private val service = ReviewInterpretationService(listOf(synthetic), runtimeGuard = { true })
    private val selected = service.select(reviewSelection())
    private val clock = ReviewTestClock(Instant.parse("2020-06-01T00:00:00Z"))
    private val emptyHistory = HistoryResponse(emptyList(), emptyList())
    private val activityHash = "a".repeat(64)

    @AfterTest fun `regressions never call inference`() {
        try { assertTrue(synthetic.packets.isEmpty()) } finally { service.close() }
    }

    private fun state(trigger: ReviewTrigger, scope: ReviewScope = ReviewScope(),
                      armed: String = "2020-06-01T00:00:00Z", cursor: Long = 0) = ReviewSchedulerState(
        configuration = ReviewScheduleConfiguration(enabled = true, timeZone = "UTC", presets = listOf(
            ReviewPreset("synthetic", "daily_combined", enabled = true, scope = scope, triggers = listOf(trigger)))),
        version = 7, cursor = cursor, modelVersion = selected.selectionVersion,
        modelId = selected.selectedModelId, armedUtc = armed)

    private fun change(revision: Long = 1, category: String = "wellness", date: String = "2020-06-01",
                       received: String = "2020-06-01T10:00:00Z", identity: String = activityHash,
                       sport: String? = null, sleepChanged: Boolean = false, sleepAvailable: Boolean = false) =
        ImportedReviewChange(revision, category, date, received, identity, sport, sleepChanged, sleepAvailable)

    private fun evaluate(state: ReviewSchedulerState, now: String, changes: List<ImportedReviewChange> = emptyList(),
                         history: HistoryResponse = emptyHistory, verified: Set<String> = emptySet()):
        Pair<ReviewSchedulerState, ReviewScheduleStatus> {
        clock.now = Instant.parse(now)
        val revision = maxOf(state.cursor, changes.maxOfOrNull { it.revision } ?: 0)
        return ReviewScheduleRules.evaluate(state, changes, revision, { _, _ -> history },
            clock.instant(), selected, true, verified)
    }

    private fun memory(state: ReviewSchedulerState) = state.memories.getValue("synthetic/trigger")

    @Test fun `post arm revision is eligible even when receipt precedes armed time after rewind`() {
        for (kind in listOf("wellness_arrival", "record_count", "changed_data")) {
            val trigger = if (kind == "record_count") ReviewTrigger("trigger", kind, threshold = 1)
                else ReviewTrigger("trigger", kind, settleSeconds = 0)
            val initial = state(trigger, armed = "2020-06-01T12:00:00Z", cursor = 10)
            val postArm = change(revision = 11, received = "2020-06-01T10:00:00Z")
            val preArm = change(revision = 10, received = "2020-06-01T10:00:00Z", identity = "b".repeat(64))
            val (next, status) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(preArm, postArm))
            assertEquals(11L, next.cursor, kind)
            assertTrue(next.deferredChanges.isEmpty(), kind)
            if (kind == "changed_data") {
                assertTrue(status.due.isEmpty())
                assertTrue(memory(next).changed)
                assertEquals(11L, memory(next).changedRevision)
                assertEquals(1, evaluate(next, "2020-06-01T14:00:00Z").second.due.size)
            } else {
                assertEquals(1, status.due.size, kind)
                if (kind == "record_count") assertEquals(1, memory(next).count)
                assertTrue(evaluate(next, "2020-06-01T14:00:00Z", listOf(preArm, postArm)).second.due.isEmpty())
            }
        }
    }

    @Test fun `future receipt survives cursor advance and becomes eligible without rereading imports`() {
        val initial = state(ReviewTrigger("trigger", "wellness_arrival", settleSeconds = 0))
        val future = change(received = "2020-06-01T11:00:00Z")
        val (deferred, waiting) = evaluate(initial, "2020-06-01T10:00:00Z", listOf(future))
        assertTrue(waiting.due.isEmpty())
        assertEquals(1L, deferred.cursor)
        assertEquals(listOf(future), deferred.deferredChanges)
        val (stillDeferred, before) = evaluate(deferred, "2020-06-01T10:59:59Z")
        assertTrue(before.due.isEmpty())
        assertEquals(listOf(future), stillDeferred.deferredChanges)
        val (done, due) = evaluate(stillDeferred, "2020-06-01T11:00:00Z")
        assertEquals("2020-06-01", due.due.single().newest)
        assertTrue(done.deferredChanges.isEmpty())
        assertTrue(evaluate(done, "2020-06-01T12:00:00Z").second.due.isEmpty())
    }

    @Test fun `future observed date survives cursor advance and becomes eligible on that date`() {
        val initial = state(ReviewTrigger("trigger", "wellness_arrival", settleSeconds = 0))
        val future = change(date = "2020-06-02")
        val (deferred, waiting) = evaluate(initial, "2020-06-01T10:00:00Z", listOf(future))
        assertTrue(waiting.due.isEmpty())
        assertEquals(1L, deferred.cursor)
        assertEquals(listOf(future), deferred.deferredChanges)
        val (done, due) = evaluate(deferred, "2020-06-02T00:00:00Z")
        assertEquals("2020-06-02", due.due.single().oldest)
        assertEquals("2020-06-02", due.due.single().newest)
        assertTrue(done.deferredChanges.isEmpty())
        assertTrue(evaluate(done, "2020-06-02T01:00:00Z").second.due.isEmpty())
    }

    @Test fun `superseded future receipt or observed revision never resurrects an old arrival`() {
        val initial = state(ReviewTrigger("trigger", "activity_arrival", settleSeconds = 0), ReviewScope("activity", sport = "Ride"))
        val futures = listOf(
            change(category = "activities", received = "2020-06-02T10:00:00Z", sport = "Ride"),
            change(category = "activities", date = "2020-06-02", sport = "Ride"))
        for (future in futures) {
            val (deferred, _) = evaluate(initial, "2020-06-01T10:00:00Z", listOf(future))
            assertEquals(listOf(future), deferred.deferredChanges)
            val correction = change(revision = 2, category = "activities", received = "2020-06-01T10:01:00Z", sport = "Ride")
            val (done, due) = evaluate(deferred, "2020-06-01T10:01:00Z", listOf(correction))
            assertEquals("2020-06-01", due.due.single().newest)
            assertEquals(activityHash, due.due.single().activitySha256)
            assertTrue(done.deferredChanges.isEmpty())
            assertTrue(evaluate(done, "2020-06-02T10:00:00Z").second.due.isEmpty())
        }
    }

    @Test fun `pending activity date correction rebinds routing and replaces old intent`() {
        val scope = ReviewScope("activity", sport = "Ride")
        val initial = state(ReviewTrigger("trigger", "activity_arrival"), scope)
        val original = change(category = "activities", sport = "Ride")
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        val oldIntent = evaluate(pending, "2020-06-01T10:03:00Z").second.due.single()
        val correction = original.copy(revision = 2, observedDate = "2020-05-31", receivedUtc = "2020-06-01T10:01:00Z")
        val (rebound, waiting) = evaluate(pending, "2020-06-01T10:02:00Z", listOf(correction))
        assertTrue(waiting.due.isEmpty())
        assertEquals("2020-06-01T10:03:00Z", waiting.triggers.single().nextReviewUtc)
        assertEquals("2020-05-31", memory(rebound).arrivals.single().observedDate)
        assertEquals(activityHash, memory(rebound).arrivals.single().identitySha256)
        assertEquals(2L, memory(rebound).arrivals.single().revision)
        val (done, due) = evaluate(rebound, "2020-06-01T10:03:00Z")
        val intent = due.due.single()
        assertEquals("2020-05-31", intent.oldest)
        assertEquals("2020-05-31", intent.newest)
        assertEquals(activityHash, intent.activitySha256)
        assertEquals(scope, intent.scope)
        assertNotEquals(oldIntent.occurrenceId, intent.occurrenceId)
        assertTrue(evaluate(done, "2020-06-01T11:00:00Z").second.due.isEmpty())
    }

    @Test fun `pending activity corrected to a nonmatching sport cancels old intent`() {
        val initial = state(ReviewTrigger("trigger", "activity_arrival"), ReviewScope("activity", sport = "Ride"))
        val original = change(category = "activities", sport = "Ride")
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        val correction = original.copy(revision = 2, sport = "Run", receivedUtc = "2020-06-01T10:01:00Z")
        val (canceled, status) = evaluate(pending, "2020-06-01T10:02:00Z", listOf(correction))
        assertTrue(status.due.isEmpty())
        assertTrue(memory(canceled).arrivals.isEmpty())
        assertNull(status.triggers.single().nextReviewUtc)
        assertTrue(evaluate(canceled, "2020-06-01T11:00:00Z").second.due.isEmpty())
    }

    @Test fun `future activity date correction suppresses old date before becoming eligible`() {
        val initial = state(ReviewTrigger("trigger", "activity_arrival"), ReviewScope("activity", sport = "Ride"))
        val original = change(category = "activities", sport = "Ride")
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        val cases = listOf(
            original.copy(revision = 2, observedDate = "2020-05-31", receivedUtc = "2020-06-01T10:10:00Z") to "2020-06-01T10:12:00Z",
            original.copy(revision = 2, observedDate = "2020-06-02", receivedUtc = "2020-06-01T10:01:00Z") to "2020-06-02T00:00:00Z")
        for ((correction, readyAt) in cases) {
            val (rebound, waiting) = evaluate(pending, "2020-06-01T10:02:00Z", listOf(correction))
            assertTrue(waiting.due.isEmpty())
            assertEquals(listOf(correction), rebound.deferredChanges)
            assertEquals(correction.observedDate, memory(rebound).arrivals.single().observedDate)
            assertEquals(readyAt, waiting.triggers.single().nextReviewUtc)
            val (done, due) = evaluate(rebound, readyAt)
            assertEquals(correction.observedDate, due.due.single().newest)
            assertEquals(activityHash, due.due.single().activitySha256)
            assertTrue(done.deferredChanges.isEmpty())
            assertTrue(evaluate(done, "2020-06-02T11:00:00Z").second.due.isEmpty())
        }
    }

    @Test fun `future nonmatching sport correction cancels pending activity immediately`() {
        val initial = state(ReviewTrigger("trigger", "activity_arrival"), ReviewScope("activity", sport = "Ride"))
        val original = change(category = "activities", sport = "Ride")
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        val corrections = listOf(
            original.copy(revision = 2, sport = "Run", receivedUtc = "2020-06-01T10:10:00Z"),
            original.copy(revision = 2, sport = "Run", observedDate = "2020-06-02", receivedUtc = "2020-06-01T10:01:00Z"))
        for (correction in corrections) {
            val (canceled, status) = evaluate(pending, "2020-06-01T10:02:00Z", listOf(correction))
            assertTrue(status.due.isEmpty())
            assertTrue(memory(canceled).arrivals.isEmpty())
            assertNull(status.triggers.single().nextReviewUtc)
            val (done, later) = evaluate(canceled, "2020-06-02T11:00:00Z")
            assertTrue(later.due.isEmpty())
            assertTrue(done.deferredChanges.isEmpty())
            assertTrue(memory(done).arrivals.isEmpty())
        }
    }

    @Test fun `activity intent hashes are stable and scoped by route and configuration`() {
        val trigger = ReviewTrigger("trigger", "activity_arrival", settleSeconds = 0)
        val imported = listOf(change(category = "activities", sport = "Ride"))
        val scopes = listOf(ReviewScope("activity"), ReviewScope("activity", sport = "Ride"),
            ReviewScope(sport = "Ride"), ReviewScope("rolling", 1, "Ride"))
        val ids = scopes.map { scope ->
            val initial = state(trigger, scope)
            val first = evaluate(initial, "2020-06-01T10:00:00Z", imported).second.due.single()
            val again = evaluate(initial, "2020-06-01T10:01:00Z", imported).second.due.single()
            assertEquals(first.occurrenceId, again.occurrenceId)
            assertTrue(first.occurrenceId.matches(Regex("[a-f0-9]{64}")))
            assertEquals(if (scope.kind == "activity") activityHash else null, first.activitySha256)
            assertEquals(scope, first.scope)
            val reconfigured = evaluate(initial.copy(version = 8), "2020-06-01T10:00:00Z", imported).second.due.single()
            assertNotEquals(first.occurrenceId, reconfigured.occurrenceId)
            first.occurrenceId
        }
        assertEquals(scopes.size, ids.distinct().size)
    }

    @Test fun `pending sleep date correction routes to new date with unchanged available sleep`() {
        val initial = state(ReviewTrigger("trigger", "sleep_arrival"))
        val original = change(date = "2020-05-30", sleepChanged = true, sleepAvailable = true)
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        val oldIntent = evaluate(pending, "2020-06-01T10:02:00Z").second.due.single()
        for ((date, readyAt) in listOf("2020-05-31" to "2020-06-01T10:03:00Z", "2020-06-02" to "2020-06-02T00:00:00Z")) {
            val correction = original.copy(revision = 2, observedDate = date, receivedUtc = "2020-06-01T10:01:00Z", sleepChanged = false)
            val (rebound, waiting) = evaluate(pending, "2020-06-01T10:02:00Z", listOf(correction))
            assertTrue(waiting.due.isEmpty())
            assertEquals(date, memory(rebound).arrivals.single().observedDate)
            assertEquals(readyAt, waiting.triggers.single().nextReviewUtc)
            val (done, due) = evaluate(rebound, readyAt)
            assertEquals(date, due.due.single().oldest)
            assertEquals(date, due.due.single().newest)
            assertNull(due.due.single().activitySha256)
            assertNotEquals(oldIntent.occurrenceId, due.due.single().occurrenceId)
            assertTrue(evaluate(done, "2020-06-02T11:00:00Z").second.due.isEmpty())
        }
    }

    @Test fun `sleep disappearance cancels pending arrival and never schedules deletion as sleep`() {
        val initial = state(ReviewTrigger("trigger", "sleep_arrival"))
        val original = change(sleepChanged = true, sleepAvailable = true)
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        for (receipt in listOf("2020-06-01T10:01:00Z", "2020-06-01T10:10:00Z")) {
            val removed = original.copy(revision = 2, receivedUtc = receipt, sleepChanged = true, sleepAvailable = false)
            val (canceled, status) = evaluate(pending, "2020-06-01T10:02:00Z", listOf(removed))
            assertTrue(status.due.isEmpty())
            assertTrue(memory(canceled).arrivals.isEmpty())
            assertNull(status.triggers.single().nextReviewUtc)
            val (done, later) = evaluate(canceled, "2020-06-01T11:00:00Z")
            assertTrue(later.due.isEmpty())
            assertTrue(memory(done).arrivals.isEmpty())
        }
    }

    @Test fun `HRV only correction preserves original sleep settling and occurrence identity`() {
        val initial = state(ReviewTrigger("trigger", "sleep_arrival"))
        val original = change(sleepChanged = true, sleepAvailable = true)
        val (pending, _) = evaluate(initial, "2020-06-01T10:01:00Z", listOf(original))
        val expected = evaluate(pending, "2020-06-01T10:02:00Z").second.due.single()
        val hrvOnly = original.copy(revision = 2, receivedUtc = "2020-06-01T10:01:30Z", sleepChanged = false)
        val (unchanged, waiting) = evaluate(pending, "2020-06-01T10:01:30Z", listOf(hrvOnly))
        assertTrue(waiting.due.isEmpty())
        assertEquals(memory(pending).arrivals, memory(unchanged).arrivals)
        assertEquals("2020-06-01T10:02:00Z", waiting.triggers.single().nextReviewUtc)
        val (done, due) = evaluate(unchanged, "2020-06-01T10:02:00Z")
        assertEquals(expected.occurrenceId, due.due.single().occurrenceId)
        assertEquals(expected.newest, due.due.single().newest)
        assertTrue(evaluate(done, "2020-06-01T10:03:30Z").second.due.isEmpty())
    }

    @Test fun `different date wellness records in same receipt batch have distinct occurrence ids`() {
        for (kind in listOf("wellness_arrival", "sleep_arrival")) {
            val initial = state(ReviewTrigger("trigger", kind))
            val batch = listOf(
                change(date = "2020-05-30", sleepChanged = true, sleepAvailable = true),
                change(revision = 2, date = "2020-05-31", identity = "b".repeat(64), sleepChanged = true, sleepAvailable = true))
            val (pending, waiting) = evaluate(initial, "2020-06-01T10:01:00Z", batch)
            assertTrue(waiting.due.isEmpty())
            assertEquals(2, memory(pending).arrivals.size)
            val (done, due) = evaluate(pending, "2020-06-01T10:02:00Z")
            assertEquals(setOf("2020-05-30", "2020-05-31"), due.due.map { it.newest }.toSet())
            assertEquals(2, due.due.map { it.occurrenceId }.distinct().size)
            assertTrue(due.due.all { it.oldest == it.newest && it.activitySha256 == null })
            val reversed = evaluate(initial, "2020-06-01T10:02:00Z", batch.reversed()).second
            assertEquals(due.due.map { it.occurrenceId }.toSet(), reversed.due.map { it.occurrenceId }.toSet())
            assertTrue(evaluate(done, "2020-06-01T11:00:00Z").second.due.isEmpty())
        }
    }

    @Test fun `unrelated nonmatching sport import cannot change pending arrival or changed data id`() {
        for (kind in listOf("activity_arrival", "changed_data")) {
            val scope = if (kind == "activity_arrival") ReviewScope("activity", sport = "Ride") else ReviewScope(sport = "Ride")
            val initial = state(ReviewTrigger("trigger", kind, settleSeconds = 3600), scope)
            val relevant = change(category = "activities", sport = "Ride", received = "2020-06-01T00:30:00Z")
            val (pending, waiting) = evaluate(initial, "2020-06-01T01:00:00Z", listOf(relevant))
            assertTrue(waiting.due.isEmpty())
            val unrelated = relevant.copy(revision = 99, identitySha256 = "b".repeat(64), sport = "Run", receivedUtc = "2020-06-01T01:01:00Z")
            val (advanced, stillWaiting) = evaluate(pending, "2020-06-01T01:10:00Z", listOf(unrelated))
            assertTrue(stillWaiting.due.isEmpty())
            assertEquals(99L, advanced.cursor)
            assertEquals(memory(pending), memory(advanced))
            val expected = evaluate(pending, "2020-06-01T02:00:00Z").second.due.single()
            val actual = evaluate(advanced, "2020-06-01T02:00:00Z").second.due.single()
            assertEquals(expected.occurrenceId, actual.occurrenceId, kind)
            assertEquals(expected.scope, actual.scope)
            assertEquals(expected.activitySha256, actual.activitySha256)
        }
    }

    @Test fun `unfinished June two record count survives rollback and next record reaches threshold`() {
        val initial = state(ReviewTrigger("trigger", "record_count", threshold = 2))
        val (juneTwo, first) = evaluate(initial, "2020-06-02T01:00:00Z",
            listOf(change(date = "2020-06-02", received = "2020-06-02T00:30:00Z")))
        assertTrue(first.due.isEmpty())
        assertEquals(1, memory(juneTwo).count)
        val (rollback, oldDay) = evaluate(juneTwo, "2020-06-01T01:00:00Z",
            listOf(change(revision = 2, received = "2020-06-01T00:30:00Z", identity = "b".repeat(64))))
        assertTrue(oldDay.due.isEmpty())
        assertEquals(1, memory(rollback).countsByPeriod["2020-06-02"])
        assertEquals(1, memory(rollback).countsByPeriod["2020-06-01"])
        val (returned, noImport) = evaluate(rollback, "2020-06-02T01:00:00Z")
        assertTrue(noImport.due.isEmpty())
        assertEquals("2020-06-02", memory(returned).countPeriod)
        assertEquals(1, memory(returned).count)
        val nextRecord = change(revision = 3, date = "2020-06-02", received = "2020-06-02T01:30:00Z", identity = "c".repeat(64))
        val (done, due) = evaluate(returned, "2020-06-02T02:00:00Z", listOf(nextRecord))
        assertEquals(2, memory(done).count)
        assertEquals("2020-06-02", memory(done).thresholdPeriod)
        assertEquals("2020-06-02", due.due.single().newest)
        val (same, repeated) = evaluate(done, "2020-06-02T02:00:00Z", listOf(nextRecord))
        assertTrue(repeated.due.isEmpty())
        assertEquals(memory(done), memory(same))
    }

    @Test fun `executed record count threshold does not repeat after rollback and more records`() {
        val initial = state(ReviewTrigger("trigger", "record_count", threshold = 2))
        val batch = listOf(
            change(date = "2020-06-02", received = "2020-06-02T00:30:00Z"),
            change(revision = 2, date = "2020-06-02", received = "2020-06-02T00:30:00Z", identity = "b".repeat(64)))
        val (done, due) = evaluate(initial, "2020-06-02T01:00:00Z", batch)
        assertEquals(1, due.due.size)
        val (rollback, oldDay) = evaluate(done, "2020-06-01T01:00:00Z", listOf(
            change(revision = 3, received = "2020-06-01T00:30:00Z", identity = "c".repeat(64)),
            change(revision = 4, received = "2020-06-01T00:30:00Z", identity = "d".repeat(64))))
        assertTrue(oldDay.due.isEmpty())
        val (returned, repeated) = evaluate(rollback, "2020-06-02T02:00:00Z", listOf(
            change(revision = 5, date = "2020-06-02", received = "2020-06-02T01:30:00Z", identity = "e".repeat(64))))
        assertTrue(repeated.due.isEmpty())
        assertEquals(3, memory(returned).count)
        assertEquals("2020-06-02", memory(returned).thresholdPeriod)
        assertTrue(evaluate(returned, "2020-06-02T03:00:00Z").second.due.isEmpty())
    }

    @Test fun `invalid or missing verified daily record alongside valid one remains ambiguous`() {
        val initial = state(ReviewTrigger("trigger", "daily_steps", threshold = 100))
        val valid = syntheticWellness().copy(source = "synthetic-verified", sourceRecordId = "valid",
            date = "2020-06-01", measurements = mapOf("steps" to Measurement(200.0, "count")))
        val invalid = listOf(null, Measurement(Double.NaN, "count"), Measurement(-1.0, "count"),
            Measurement(1.5, "count"), Measurement(250_001.0, "count"), Measurement(200.0, "steps"))
        for (measurement in invalid) {
            val other = valid.copy(sourceRecordId = "other", measurements = measurement?.let { mapOf("steps" to it) } ?: emptyMap())
            for (records in listOf(listOf(valid, other), listOf(other, valid))) {
                val (next, status) = evaluate(initial, "2020-06-01T12:00:00Z",
                    history = HistoryResponse(emptyList(), records), verified = setOf("synthetic-verified"))
                assertTrue(status.due.isEmpty(), "Record identity must be checked before discarding invalid measurements")
                assertEquals("usable_daily_steps_unavailable", status.triggers.single().reason)
                assertNull(memory(next).thresholdPeriod)
            }
        }
        // A single valid record is usable: ambiguity, not lack of a qualifying value, blocks the batch.
        assertEquals(1, evaluate(initial, "2020-06-01T12:00:00Z",
            history = HistoryResponse(emptyList(), listOf(valid)), verified = setOf("synthetic-verified")).second.due.size)
    }

    @Test fun `daily and weekly next review stays beyond consumed June eight after clock rollback`() {
        val cases = listOf(
            ReviewTrigger("trigger", "daily_time", localTime = "12:00") to "2020-06-09T12:00:00Z",
            ReviewTrigger("trigger", "weekly_time", localTime = "12:00", dayOfWeek = 1) to "2020-06-15T12:00:00Z")
        for ((trigger, nextAt) in cases) {
            val (consumed, due) = evaluate(state(trigger), "2020-06-08T12:00:00Z")
            assertEquals("2020-06-08", due.due.single().newest)
            assertEquals(nextAt, due.triggers.single().nextReviewUtc)
            val (rollback, old) = evaluate(consumed, "2020-06-01T12:00:00Z")
            assertTrue(old.due.isEmpty())
            assertEquals(nextAt, old.triggers.single().nextReviewUtc, trigger.kind)
            val (returned, repeated) = evaluate(rollback, "2020-06-08T12:00:00Z")
            assertTrue(repeated.due.isEmpty())
            assertEquals(nextAt, repeated.triggers.single().nextReviewUtc)
            val (done, nextDue) = evaluate(returned, nextAt)
            assertEquals(nextAt.take(10), nextDue.due.single().newest)
            assertNotEquals(due.due.single().occurrenceId, nextDue.due.single().occurrenceId)
            assertTrue(evaluate(done, nextAt).second.due.isEmpty())
        }
    }
}

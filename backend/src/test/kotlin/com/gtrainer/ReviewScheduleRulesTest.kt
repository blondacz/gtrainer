package com.gtrainer

import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.*

class ReviewScheduleRulesTest {
    private val synthetic = SyntheticReviewModel()
    private val service = ReviewInterpretationService(listOf(synthetic), runtimeGuard = { true })
    private val selected = service.select(reviewSelection())
    private val armed = "2020-06-01T00:00:00Z"
    private val emptyHistory = HistoryResponse(emptyList(), emptyList())

    @AfterTest fun `rules never call inference`() {
        assertTrue(synthetic.packets.isEmpty())
        service.close()
    }

    private fun preset(trigger: ReviewTrigger, scope: ReviewScope = ReviewScope()) =
        ReviewPreset("synthetic", "daily_combined", enabled = true, scope = scope, triggers = listOf(trigger))

    private fun state(preset: ReviewPreset, zone: String = "UTC", armedUtc: String = armed) =
        ReviewSchedulerState(ReviewScheduleConfiguration(enabled = true, timeZone = zone, presets = listOf(preset)),
            version = 7, modelVersion = selected.selectionVersion, modelId = selected.selectedModelId, armedUtc = armedUtc)

    private fun change(revision: Long = 1, category: String = "wellness", date: String = "2020-06-01",
                       received: String = "2020-06-01T00:01:00Z", identity: String = "a".repeat(64),
                       sport: String? = null, sleep: Boolean = false) =
        ImportedReviewChange(revision, category, date, received, identity, sport, sleep, sleepAvailable = sleep)

    private fun evaluate(state: ReviewSchedulerState, now: String, changes: List<ImportedReviewChange> = emptyList(),
                         revision: Long = changes.maxOfOrNull { it.revision } ?: state.cursor,
                         model: ReviewModelStatus = selected, inProcess: Boolean = true,
                         history: HistoryResponse = emptyHistory, verified: Set<String> = emptySet()) =
        ReviewScheduleRules.evaluate(state, changes, revision, { _, _ -> history }, Instant.parse(now), model, inProcess, verified)

    @Test fun `default configuration and every preset are off without fixed clock times`() {
        val config = ReviewScheduleConfiguration()
        assertFalse(config.enabled)
        assertFalse(config.paused)
        assertTrue(config.presets.all { !it.enabled })
        assertTrue(config.presets.flatMap { it.triggers }.none { it.kind in setOf("daily_time", "weekly_time") })
        assertEquals(setOf("daily_combined", "after_activity", "weekly"), config.presets.map { it.reviewType }.toSet())
        val (_, status) = evaluate(ReviewSchedulerState(), "2020-06-02T23:59:00Z", listOf(change()))
        assertEquals("schedules_disabled", status.reason)
        assertTrue(status.due.isEmpty())
        assertFalse(status.executionAvailable)
    }

    @Test fun `disabled paused unarmed incompatible and stale model selections fail closed`() {
        val ready = state(preset(ReviewTrigger("clock", "daily_time", localTime = "01:00")))
        val cases = listOf(
            Triple(ready.copy(configuration = ready.configuration.copy(enabled = false)), selected, "schedules_disabled"),
            Triple(ready.copy(configuration = ready.configuration.copy(paused = true)), selected, "schedules_paused"),
            Triple(ready, selected.copy(enabled = false), "explicit_local_selection_required"),
            Triple(ready, selected.copy(selectedModelId = null), "explicit_local_selection_required"),
            Triple(ready, selected.copy(profile = "other-profile"), "explicit_local_selection_required"),
            Triple(ready, selected.copy(models = emptyList()), "local_model_unavailable"),
            Triple(ready, selected.copy(models = selected.models.map { it.copy(id = "other-model") }), "local_model_unavailable"),
            Triple(ready, selected.copy(selectionVersion = selected.selectionVersion + 1), "model_selection_changed"),
            Triple(ready.copy(modelId = "other-model"), selected, "model_selection_changed"),
        )
        for ((input, model, reason) in cases) {
            val (next, status) = evaluate(input, "2020-06-01T02:00:00Z", listOf(change()), model = model)
            assertEquals(reason, status.reason)
            assertTrue(status.due.isEmpty(), reason)
            assertTrue(next.memories.isEmpty())
            assertTrue(status.triggers.all { it.nextCheckUtc == null && it.nextReviewUtc == null })
        }
        val (_, unarmed) = evaluate(ready, "2020-06-01T02:00:00Z", inProcess = false)
        assertEquals("explicit_local_selection_required", unarmed.reason)
        assertTrue(unarmed.due.isEmpty())
        val disabled = ready.copy(configuration = ready.configuration.copy(presets = ready.configuration.presets.map { it.copy(enabled = false) }))
        val (_, status) = evaluate(disabled, "2020-06-01T02:00:00Z")
        assertEquals("preset_disabled", status.triggers.single().reason)
        assertTrue(status.due.isEmpty())
    }

    @Test fun `review type scope focus level and budget route independently without inference`() {
        val budget = ReviewExecutionPolicy(400, 900, true)
        for (type in listOf("daily_combined", "after_activity", "weekly")) {
            for (focus in FactualReviews.supportedFocuses) {
                for (level in listOf("interim", "thorough")) {
                    val configured = preset(ReviewTrigger("clock", "daily_time", localTime = "12:00"), ReviewScope("rolling", 3, "Ride"))
                        .copy(reviewType = type, focus = focus, level = level, budget = budget)
                    val (_, status) = evaluate(state(configured), "2020-06-03T12:00:00Z")
                    val due = status.due.single()
                    assertEquals(type, due.reviewType)
                    assertEquals(configured.scope, due.scope)
                    assertEquals("2020-06-01", due.oldest)
                    assertEquals("2020-06-03", due.newest)
                    assertEquals(focus, due.focus)
                    assertEquals(level, due.level)
                    assertEquals(budget, due.budget)
                    assertEquals(7L, due.configurationVersion)
                    assertEquals(selected.selectedModelId, due.modelId)
                    assertEquals(selected.selectionVersion, due.selectionVersion)
                    assertEquals(listOf("daily_time"), due.reasons)
                    assertNull(due.activitySha256)
                }
            }
        }
    }

    @Test fun `changed data checks exact armed two hour grid and only emits once after actual changes`() {
        val initial = state(preset(ReviewTrigger("changes", "changed_data")), armedUtc = "2020-06-01T00:17:00Z")
        val (pending, waiting) = evaluate(initial, "2020-06-01T01:00:00Z", listOf(change(received = "2020-06-01T00:30:00Z")))
        assertTrue(waiting.due.isEmpty())
        assertEquals("2020-06-01T02:17:00Z", waiting.triggers.single().nextCheckUtc)
        assertNull(waiting.triggers.single().nextReviewUtc)
        val (fired, due) = evaluate(pending, "2020-06-01T02:17:00Z")
        assertEquals(1, due.due.size)
        assertEquals("2020-06-01T04:17:00Z", due.triggers.single().nextCheckUtc)
        val (unchanged, repeated) = evaluate(fired, "2020-06-01T02:17:00Z")
        assertTrue(repeated.due.isEmpty())
        assertEquals(fired, unchanged)
        val (_, later) = evaluate(unchanged, "2020-06-01T08:18:00Z")
        assertTrue(later.due.isEmpty())
        assertEquals("2020-06-01T10:17:00Z", later.triggers.single().nextCheckUtc)
        assertNull(later.triggers.single().nextReviewUtc)
    }

    @Test fun `empty change list never treats revision or repeated status reads as changed data`() {
        val initial = state(preset(ReviewTrigger("changes", "changed_data")))
        val (next, first) = evaluate(initial, "2020-06-01T02:00:00Z", revision = 100)
        assertTrue(first.due.isEmpty())
        val (same, second) = evaluate(next, "2020-06-01T02:00:00Z", revision = 100)
        assertEquals(next, same)
        assertEquals(first, second)
        assertFalse(next.memories.getValue("synthetic/changes").changed)
    }

    @Test fun `old future and nonmatching sport changes do not arm periodic review`() {
        val initial = state(preset(ReviewTrigger("changes", "changed_data"), ReviewScope(sport = "Ride"))).copy(cursor = 1)
        val ignored = listOf(change(received = "2020-05-31T23:59:59Z"),
            change(revision = 2, received = "2020-06-01T03:00:00Z"),
            change(revision = 3, date = "2020-06-02"),
            change(revision = 4, category = "activities", sport = "Run"))
        val (_, status) = evaluate(initial, "2020-06-01T02:00:00Z", ignored)
        assertTrue(status.due.isEmpty())
        val (_, wellness) = evaluate(initial, "2020-06-01T02:00:00Z", listOf(change(revision = 5)))
        assertEquals(1, wellness.due.size)
    }

    @Test fun `unknown arrival has no promised date whereas periodic checks are known`() {
        val arrival = state(preset(ReviewTrigger("arrival", "sleep_arrival")))
        val (_, status) = evaluate(arrival, "2020-06-01T10:00:00Z")
        assertNull(status.triggers.single().nextCheckUtc)
        assertNull(status.triggers.single().nextReviewUtc)
        val (_, periodic) = evaluate(state(preset(ReviewTrigger("changes", "changed_data"))), "2020-06-01T01:00:00Z")
        assertEquals("2020-06-01T02:00:00Z", periodic.triggers.single().nextCheckUtc)
        assertNull(periodic.triggers.single().nextReviewUtc)
    }

    @Test fun `daily and weekly explicit zone clock times are not fixed morning defaults`() {
        val daily = state(preset(ReviewTrigger("clock", "daily_time", localTime = "21:47")), "Europe/Prague")
        val (waiting, before) = evaluate(daily, "2020-06-01T19:46:59Z")
        assertTrue(before.due.isEmpty())
        assertEquals("2020-06-01T19:47:00Z", before.triggers.single().nextReviewUtc)
        assertNull(before.triggers.single().nextCheckUtc)
        val (done, onTime) = evaluate(waiting, "2020-06-01T19:47:00Z")
        assertEquals(1, onTime.due.size)
        assertEquals("2020-06-02T19:47:00Z", onTime.triggers.single().nextReviewUtc)
        assertTrue(evaluate(done, "2020-06-01T23:00:00Z").second.due.isEmpty())
        val weekly = state(preset(ReviewTrigger("clock", "weekly_time", localTime = "18:23", dayOfWeek = 3), ReviewScope("week", 7)), "Europe/Prague")
        val (_, earlier) = evaluate(weekly, "2020-06-01T12:00:00Z")
        assertTrue(earlier.due.isEmpty())
        assertEquals("2020-06-03T16:23:00Z", earlier.triggers.single().nextReviewUtc)
        val (weekDone, weekDue) = evaluate(weekly, "2020-06-03T16:23:00Z")
        assertEquals("2020-06-01", weekDue.due.single().oldest)
        assertEquals("2020-06-03", weekDue.due.single().newest)
        assertEquals("2020-06-10T16:23:00Z", weekDue.triggers.single().nextReviewUtc)
        assertTrue(evaluate(weekDone, "2020-06-07T22:00:00Z").second.due.isEmpty())
    }

    @Test fun `arming at or after a clock occurrence does not backfill it`() {
        for (arm in listOf("2020-06-01T12:00:00Z", "2020-06-01T12:01:00Z")) {
            val (_, status) = evaluate(state(preset(ReviewTrigger("clock", "daily_time", localTime = "12:00")), armedUtc = arm), "2020-06-01T13:00:00Z")
            assertTrue(status.due.isEmpty())
            assertEquals("2020-06-02T12:00:00Z", status.triggers.single().nextReviewUtc)
        }
    }

    @Test fun `Prague spring gap shifts 0230 to 0330 and fires exactly once`() {
        val initial = state(preset(ReviewTrigger("clock", "daily_time", localTime = "02:30")), "Europe/Prague", "2020-03-28T23:00:00Z")
        val (_, before) = evaluate(initial, "2020-03-29T01:29:59Z")
        assertTrue(before.due.isEmpty())
        assertEquals("2020-03-29T01:30:00Z", before.triggers.single().nextReviewUtc)
        val (done, fired) = evaluate(initial, "2020-03-29T01:30:00Z")
        assertEquals("2020-03-29", fired.due.single().newest)
        assertEquals("2020-03-30T00:30:00Z", fired.triggers.single().nextReviewUtc)
        assertTrue(evaluate(done, "2020-03-29T02:30:00Z").second.due.isEmpty())
    }

    @Test fun `Prague autumn overlap uses earlier offset once not twice`() {
        val initial = state(preset(ReviewTrigger("clock", "daily_time", localTime = "02:30")), "Europe/Prague", "2020-10-24T22:00:00Z")
        val (_, before) = evaluate(initial, "2020-10-25T00:29:59Z")
        assertEquals("2020-10-25T00:30:00Z", before.triggers.single().nextReviewUtc)
        val (done, first) = evaluate(initial, "2020-10-25T00:30:00Z")
        assertEquals(1, first.due.size)
        val (_, repeatedLocalTime) = evaluate(done, "2020-10-25T01:30:00Z")
        assertTrue(repeatedLocalTime.due.isEmpty())
        assertEquals("2020-10-26T01:30:00Z", repeatedLocalTime.triggers.single().nextReviewUtc)
    }

    @Test fun `system zone travel never silently changes configured clock zone`() {
        val original = TimeZone.getDefault()
        try {
            val initial = state(preset(ReviewTrigger("clock", "daily_time", localTime = "21:47")), "Europe/Prague")
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val first = evaluate(initial, "2020-06-01T19:47:00Z")
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val afterTravel = evaluate(initial, "2020-06-01T19:47:00Z")
            assertEquals(first, afterTravel)
            assertEquals("Europe/Prague", afterTravel.first.configuration.timeZone)
            assertEquals(1, afterTravel.second.due.size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun `clock going backwards cannot repeat daily or weekly occurrences`() {
        for (trigger in listOf(ReviewTrigger("clock", "daily_time", localTime = "12:00"),
            ReviewTrigger("clock", "weekly_time", localTime = "12:00", dayOfWeek = 1))) {
            val (first, due) = evaluate(state(preset(trigger)), "2020-06-08T12:00:00Z")
            assertEquals(1, due.due.size)
            val (backwards, old) = evaluate(first, "2020-06-01T12:00:00Z")
            assertTrue(old.due.isEmpty())
            assertTrue(evaluate(backwards, "2020-06-08T12:00:00Z").second.due.isEmpty())
        }
    }

    @Test fun `rollback before first calendar occurrence never advertises a date before arming`() {
        for (trigger in listOf(ReviewTrigger("clock", "daily_time", localTime = "12:00"),
            ReviewTrigger("clock", "weekly_time", localTime = "12:00", dayOfWeek = 1))) {
            val initial = state(preset(trigger), armedUtc = "2020-06-08T00:00:00Z")
            val (_, status) = evaluate(initial, "2020-06-01T12:00:00Z")
            assertTrue(status.due.isEmpty())
            assertEquals("2020-06-08T12:00:00Z", status.triggers.single().nextReviewUtc)
        }
    }

    @Test fun `new future correction supersedes an old deferred revision before periodic eligibility`() {
        val initial = state(preset(ReviewTrigger("changes", "changed_data")))
        val (deferred, _) = evaluate(initial, "2020-06-01T02:00:00Z", listOf(change(date = "2020-06-02")))
        assertEquals(1L, deferred.deferredChanges.single().revision)
        val (updated, noObsoleteWork) = evaluate(deferred, "2020-06-02T02:00:00Z", listOf(
            change(revision = 2, date = "2020-06-03", received = "2020-06-02T00:01:00Z")))
        assertTrue(noObsoleteWork.due.isEmpty())
        assertFalse(updated.memories.values.single().changed)
        assertEquals(2L, updated.deferredChanges.single().revision)
        assertEquals(1, evaluate(updated, "2020-06-03T02:00:00Z").second.due.size)
    }

    @Test fun `sleep arrival followed by HRV before a tick is retained including deferred sleep`() {
        val initial = state(preset(ReviewTrigger("sleep", "sleep_arrival", settleSeconds = 0)))
        val (done, due) = evaluate(initial, "2020-06-01T01:00:00Z", listOf(change(sleep = true),
            change(revision = 2, received = "2020-06-01T00:02:00Z").copy(sleepAvailable = true)))
        assertEquals(1, due.due.size)
        assertTrue(evaluate(done, "2020-06-01T01:00:00Z").second.due.isEmpty())
        val (future, waiting) = evaluate(initial, "2020-06-01T01:00:00Z", listOf(change(date = "2020-06-02", sleep = true)))
        assertTrue(waiting.due.isEmpty())
        val (corrected, stillWaiting) = evaluate(future, "2020-06-01T02:00:00Z", listOf(
            change(revision = 2, date = "2020-06-02", received = "2020-06-01T00:02:00Z").copy(sleepAvailable = true)))
        assertTrue(stillWaiting.due.isEmpty())
        assertTrue(corrected.deferredChanges.single().sleepChanged)
        val (arrived, fired) = evaluate(corrected, "2020-06-02T01:00:00Z")
        assertEquals(1, fired.due.size)
        assertTrue(evaluate(arrived, "2020-06-02T01:00:00Z").second.due.isEmpty())
    }

    @Test fun `clock rewind with ineligible sleep and eligible HRV retains unhandled sleep once`() {
        val initial = state(preset(ReviewTrigger("sleep", "sleep_arrival", settleSeconds = 0)))
        val sleep = change(received = "2020-06-01T10:00:00Z", sleep = true)
        val hrv = change(revision = 2, received = "2020-06-01T09:01:00Z").copy(sleepAvailable = true)
        val (done, due) = evaluate(initial, "2020-06-01T09:02:00Z", listOf(sleep, hrv))
        assertEquals(1, due.due.size)
        assertTrue(done.deferredChanges.isEmpty())
        assertTrue(evaluate(done, "2020-06-01T10:00:00Z").second.due.isEmpty())
        val (deferred, waiting) = evaluate(initial, "2020-06-01T09:00:00Z", listOf(sleep))
        assertTrue(waiting.due.isEmpty())
        val (completed, recovered) = evaluate(deferred, "2020-06-01T09:02:00Z", listOf(hrv))
        assertEquals(1, recovered.due.size)
        assertTrue(completed.deferredChanges.isEmpty())
        assertTrue(evaluate(completed, "2020-06-01T10:00:00Z").second.due.isEmpty())
    }

    @Test fun `clock going backwards cannot repeat count arrival or changed data occurrences`() {
        for (period in listOf("day", "week")) {
            val (done, due) = evaluate(state(preset(ReviewTrigger("count", "record_count", threshold = 1, period = period))),
                "2020-06-08T12:00:00Z", listOf(change(date = "2020-06-08", received = "2020-06-08T11:00:00Z")))
            assertEquals(1, due.due.size)
            val (backwards, old) = evaluate(done, "2020-06-01T12:00:00Z", listOf(change(revision = 2)))
            assertTrue(old.due.isEmpty())
            assertTrue(evaluate(backwards, "2020-06-08T12:00:00Z").second.due.isEmpty())
        }
        for (trigger in listOf(ReviewTrigger("trigger", "wellness_arrival", settleSeconds = 0),
            ReviewTrigger("trigger", "changed_data"))) {
            val (done, due) = evaluate(state(preset(trigger)), "2020-06-01T02:00:00Z", listOf(change()))
            assertEquals(1, due.due.size)
            val (backwards, old) = evaluate(done, "2020-06-01T01:00:00Z")
            assertTrue(old.due.isEmpty())
            assertTrue(evaluate(backwards, "2020-06-01T02:00:00Z").second.due.isEmpty())
        }
    }

    @Test fun `sleep arrival filters wellness changes and settles from receipt not observed date`() {
        val initial = state(preset(ReviewTrigger("sleep", "sleep_arrival", settleSeconds = 120)))
        val changes = listOf(change(sleep = false), change(revision = 2, category = "activities", sleep = true),
            change(revision = 3, date = "2020-05-30", received = "2020-06-01T10:00:00Z", sleep = true))
        val (pending, waiting) = evaluate(initial, "2020-06-01T10:01:59Z", changes)
        assertTrue(waiting.due.isEmpty())
        assertEquals("2020-06-01T10:02:00Z", waiting.triggers.single().nextReviewUtc)
        assertEquals(1, pending.memories.getValue("synthetic/sleep").arrivals.size)
        val (done, fired) = evaluate(pending, "2020-06-01T10:02:00Z")
        assertEquals("2020-05-30", fired.due.single().oldest)
        assertEquals("2020-05-30", fired.due.single().newest)
        assertNull(fired.due.single().activitySha256)
        assertTrue(done.memories.getValue("synthetic/sleep").arrivals.isEmpty())
        assertTrue(evaluate(done, "2020-06-01T10:03:00Z").second.due.isEmpty())
    }

    @Test fun `wellness arrival accepts nonsleep wellness but not activities`() {
        val initial = state(preset(ReviewTrigger("wellness", "wellness_arrival", settleSeconds = 0)))
        val (_, status) = evaluate(initial, "2020-06-01T00:01:00Z",
            listOf(change(), change(revision = 2, category = "activities", date = "2020-05-30")))
        assertEquals(1, status.due.size)
        assertEquals("2020-06-01", status.due.single().newest)
    }

    @Test fun `arrival corrections extend settling and keep multiple late observed dates`() {
        val initial = state(preset(ReviewTrigger("sleep", "sleep_arrival")))
        val (one, _) = evaluate(initial, "2020-06-01T10:01:00Z",
            listOf(change(date = "2020-05-30", received = "2020-06-01T10:00:00Z", sleep = true)))
        val (two, waiting) = evaluate(one, "2020-06-01T10:02:00Z", listOf(
            change(revision = 2, date = "2020-05-30", received = "2020-06-01T10:01:30Z", sleep = true),
            change(revision = 3, date = "2020-05-31", received = "2020-06-01T10:01:45Z", identity = "b".repeat(64), sleep = true)))
        assertTrue(waiting.due.isEmpty())
        assertEquals("2020-06-01T10:03:30Z", waiting.triggers.single().nextReviewUtc)
        assertEquals(2, two.memories.getValue("synthetic/sleep").arrivals.size)
        val (done, due) = evaluate(two, "2020-06-01T10:04:00Z")
        assertEquals(setOf("2020-05-30", "2020-05-31"), due.due.map { it.newest }.toSet())
        assertEquals(2, due.due.map { it.occurrenceId }.distinct().size)
        assertTrue(evaluate(done, "2020-06-01T10:04:00Z").second.due.isEmpty())
    }

    @Test fun `activity arrivals preserve separate activity hashes and sport scopes`() {
        val initial = state(preset(ReviewTrigger("activity", "activity_arrival"), ReviewScope("activity", sport = "Ride")))
        val changes = listOf(
            change(category = "activities", received = "2020-06-01T10:00:00Z", sport = "Ride"),
            change(revision = 2, category = "activities", received = "2020-06-01T10:00:30Z", identity = "b".repeat(64), sport = "Ride"),
            change(revision = 3, category = "activities", received = "2020-06-01T10:00:00Z", identity = "c".repeat(64), sport = "Run"),
            change(revision = 4, received = "2020-06-01T10:00:00Z", sleep = true))
        val (pending, waiting) = evaluate(initial, "2020-06-01T10:01:00Z", changes)
        assertTrue(waiting.due.isEmpty())
        assertEquals(2, pending.memories.getValue("synthetic/activity").arrivals.size)
        val (done, due) = evaluate(pending, "2020-06-01T10:03:00Z")
        assertEquals(setOf("a".repeat(64), "b".repeat(64)), due.due.map { it.activitySha256 }.toSet())
        assertEquals(2, due.due.map { it.occurrenceId }.distinct().size)
        assertTrue(due.due.all { it.scope == initial.configuration.presets.single().scope })
        assertTrue(evaluate(done, "2020-06-01T10:03:00Z").second.due.isEmpty())
    }

    @Test fun `record count counts new entries and corrections not unchanged imports once per day`() {
        val initial = state(preset(ReviewTrigger("count", "record_count", threshold = 2)))
        val (one, first) = evaluate(initial, "2020-06-01T01:00:00Z", listOf(change()))
        assertTrue(first.due.isEmpty())
        val (unchanged, none) = evaluate(one, "2020-06-01T01:00:00Z", revision = 1)
        assertTrue(none.due.isEmpty())
        assertEquals(1, unchanged.memories.getValue("synthetic/count").count)
        val (crossed, due) = evaluate(unchanged, "2020-06-01T02:00:00Z",
            listOf(change(revision = 2, received = "2020-06-01T01:30:00Z")))
        assertEquals(1, due.due.size)
        val (more, noRepeat) = evaluate(crossed, "2020-06-01T03:00:00Z",
            listOf(change(revision = 3, received = "2020-06-01T02:30:00Z", identity = "b".repeat(64))))
        assertTrue(noRepeat.due.isEmpty())
        assertEquals(3, more.memories.getValue("synthetic/count").count)
        val (reset, tomorrow) = evaluate(more, "2020-06-02T01:00:00Z",
            listOf(change(revision = 4, received = "2020-06-02T00:30:00Z")))
        assertTrue(tomorrow.due.isEmpty())
        assertEquals(1, reset.memories.getValue("synthetic/count").count)
        val (_, tomorrowDue) = evaluate(reset, "2020-06-02T02:00:00Z",
            listOf(change(revision = 5, received = "2020-06-02T01:30:00Z")))
        assertEquals(1, tomorrowDue.due.size)
        assertNotEquals(due.due.single().occurrenceId, tomorrowDue.due.single().occurrenceId)
    }

    @Test fun `weekly record count resets Monday and follows received date in configured zone`() {
        val initial = state(preset(ReviewTrigger("count", "record_count", threshold = 2, period = "week")), "Europe/Prague")
        val (one, _) = evaluate(initial, "2020-06-01T01:00:00Z", listOf(change()))
        val (done, due) = evaluate(one, "2020-06-07T21:59:00Z",
            listOf(change(revision = 2, received = "2020-06-07T21:00:00Z")))
        assertEquals(1, due.due.size)
        assertTrue(evaluate(done, "2020-06-07T21:59:00Z").second.due.isEmpty())
        val (nextWeek, next) = evaluate(done, "2020-06-07T22:30:00Z", listOf(
            change(revision = 3, received = "2020-06-07T21:30:00Z"),
            change(revision = 4, received = "2020-06-07T22:10:00Z")))
        assertTrue(next.due.isEmpty())
        assertEquals("2020-06-08", nextWeek.memories.getValue("synthetic/count").countPeriod)
        assertEquals(1, nextWeek.memories.getValue("synthetic/count").count)
        val (_, nextDue) = evaluate(nextWeek, "2020-06-08T01:00:00Z",
            listOf(change(revision = 5, received = "2020-06-08T00:30:00Z")))
        assertEquals(1, nextDue.due.size)
    }

    private fun stepHistory(vararg measurements: Measurement?, date: String = "2020-06-01", source: String = "synthetic-verified") =
        HistoryResponse(emptyList(), measurements.mapIndexed { index, measurement -> syntheticWellness().copy(
            source = source, sourceRecordId = "synthetic-$index", date = date,
            measurements = measurement?.let { mapOf("steps" to it) } ?: emptyMap()) })

    @Test fun `daily steps unavailable by default even with a plausible source record`() {
        val initial = state(preset(ReviewTrigger("steps", "daily_steps", threshold = 100)))
        var read = false
        val (_, status) = ReviewScheduleRules.evaluate(initial, emptyList(), 0,
            { _, _ -> read = true; stepHistory(Measurement(200.0, "count")) },
            Instant.parse("2020-06-01T12:00:00Z"), selected, true)
        assertFalse(read)
        assertEquals("verified_daily_steps_unavailable", status.triggers.single().reason)
        assertTrue(status.due.isEmpty())
        val sport = state(preset(ReviewTrigger("steps", "daily_steps", threshold = 100), ReviewScope(sport = "Ride")))
        val (_, sportStatus) = evaluate(sport, "2020-06-01T12:00:00Z", history = stepHistory(Measurement(200.0, "count")), verified = setOf("synthetic-verified"))
        assertEquals("verified_daily_steps_unavailable", sportStatus.triggers.single().reason)
        assertTrue(sportStatus.due.isEmpty())
    }

    @Test fun `verified daily step candidate has exact count unit and fires once per local day`() {
        val initial = state(preset(ReviewTrigger("steps", "daily_steps", threshold = 100)))
        val verified = setOf("synthetic-verified")
        val below = evaluate(initial, "2020-06-01T10:00:00Z", history = stepHistory(Measurement(99.0, "count")), verified = verified)
        assertTrue(below.second.due.isEmpty())
        assertEquals("waiting_for_trigger", below.second.triggers.single().reason)
        var range: Pair<LocalDate, LocalDate>? = null
        val (done, due) = ReviewScheduleRules.evaluate(below.first, emptyList(), 0, { oldest, newest ->
            range = oldest to newest
            stepHistory(Measurement(100.0, "count"))
        }, Instant.parse("2020-06-01T12:00:00Z"), selected, true, verified)
        assertEquals(LocalDate.parse("2020-06-01") to LocalDate.parse("2020-06-01"), range)
        assertEquals(1, due.due.size)
        assertTrue(evaluate(done, "2020-06-01T13:00:00Z", history = stepHistory(Measurement(200.0, "count")), verified = verified).second.due.isEmpty())
        val (tomorrow, next) = evaluate(done, "2020-06-02T12:00:00Z", history = stepHistory(Measurement(100.0, "count"), date = "2020-06-02"), verified = verified)
        assertEquals(1, next.due.size)
        assertNotEquals(due.due.single().occurrenceId, next.due.single().occurrenceId)
        assertTrue(evaluate(tomorrow, "2020-06-01T12:00:00Z", history = stepHistory(Measurement(100.0, "count")), verified = verified).second.due.isEmpty())
    }

    @Test fun `nonfinite fractional missing wrong unit out of bounds and ambiguous steps never imply zero`() {
        val initial = state(preset(ReviewTrigger("steps", "daily_steps", threshold = 1)))
        val invalid = listOf(emptyHistory, stepHistory(null), stepHistory(Measurement(Double.NaN, "count")),
            stepHistory(Measurement(Double.POSITIVE_INFINITY, "count")), stepHistory(Measurement(Double.NEGATIVE_INFINITY, "count")),
            stepHistory(Measurement(1.5, "count")), stepHistory(Measurement(-1.0, "count")),
            stepHistory(Measurement(250_001.0, "count")), stepHistory(Measurement(100.0, "steps")),
            stepHistory(Measurement(100.0, "count"), source = "unverified"),
            stepHistory(Measurement(100.0, "count"), date = "2020-05-31"),
            stepHistory(Measurement(100.0, "count"), Measurement(200.0, "count")))
        for (history in invalid) {
            val (next, status) = evaluate(initial, "2020-06-01T12:00:00Z", history = history, verified = setOf("synthetic-verified"))
            assertTrue(status.due.isEmpty())
            assertEquals("usable_daily_steps_unavailable", status.triggers.single().reason)
            assertNull(next.memories.getValue("synthetic/steps").thresholdPeriod)
        }
        val (_, zero) = evaluate(initial, "2020-06-01T12:00:00Z", history = stepHistory(Measurement(0.0, "count")), verified = setOf("synthetic-verified"))
        assertEquals("waiting_for_trigger", zero.triggers.single().reason)
        assertTrue(zero.due.isEmpty())
    }

    @Test fun `advanced trigger validation rejects bad identifiers kinds bounds and combinations`() {
        val invalid = listOf<() -> Any>(
            { ReviewTrigger("", "changed_data") }, { ReviewTrigger("Bad/id", "changed_data") },
            { ReviewTrigger("a".repeat(41), "changed_data") }, { ReviewTrigger("valid", "unknown") },
            { ReviewTrigger("valid", "changed_data", intervalMinutes = 0) },
            { ReviewTrigger("valid", "changed_data", intervalMinutes = 10081) },
            { ReviewTrigger("valid", "sleep_arrival", settleSeconds = -1) },
            { ReviewTrigger("valid", "sleep_arrival", settleSeconds = 3601) },
            { ReviewTrigger("valid", "record_count", threshold = 0) },
            { ReviewTrigger("valid", "record_count", threshold = 1_000_001) },
            { ReviewTrigger("valid", "record_count") },
            { ReviewTrigger("valid", "record_count", threshold = 1, period = "month") },
            { ReviewTrigger("valid", "daily_steps", threshold = 1, period = "week") },
            { ReviewTrigger("valid", "daily_time") },
            { ReviewTrigger("valid", "daily_time", localTime = "12:00", dayOfWeek = 1) },
            { ReviewTrigger("valid", "weekly_time", localTime = "12:00") },
            { ReviewTrigger("valid", "weekly_time", localTime = "12:00", dayOfWeek = 0) },
            { ReviewTrigger("valid", "weekly_time", localTime = "12:00", dayOfWeek = 8) },
            { ReviewTrigger("valid", "weekly_time", localTime = "12:00", dayOfWeek = 1, threshold = 1) },
            { ReviewTrigger("valid", "changed_data", localTime = "12:00") },
            { ReviewTrigger("valid", "sleep_arrival", threshold = 1) },
            { ReviewTrigger("valid", "record_count", threshold = 1, localTime = "12:00") },
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        ReviewTrigger("valid", "changed_data", intervalMinutes = 1)
        ReviewTrigger("valid", "changed_data", intervalMinutes = 10080)
        ReviewTrigger("valid", "sleep_arrival", settleSeconds = 0)
        ReviewTrigger("valid", "sleep_arrival", settleSeconds = 3600)
        ReviewTrigger("valid", "record_count", threshold = 1_000_000)
    }

    @Test fun `clock schema accepts only valid zero padded hour minute local times`() {
        for (time in listOf("", "9:00", "09:0", "24:00", "29:00", "12:60", "-1:00", "12:00:00", "12:00Z", " 12:00", "12:00 ", "2020-06-01T12:00")) {
            assertFails("invalid local time $time") { ReviewTrigger("clock", "daily_time", localTime = time) }
        }
        for (time in listOf("00:00", "09:00", "23:59")) {
            assertEquals(time, ReviewTrigger("clock", "daily_time", localTime = time).localTime)
        }
    }

    @Test fun `advanced preset scope zone and budget validation rejects invalid schemas`() {
        val trigger = ReviewTrigger("changes", "changed_data")
        val valid = preset(trigger)
        val invalid = listOf<() -> Any>(
            { valid.copy(id = "Bad/id") }, { valid.copy(reviewType = "unknown") },
            { valid.copy(level = "extreme") }, { valid.copy(focus = "unknown") },
            { valid.copy(triggers = List(9) { trigger.copy(id = "change-$it") }) },
            { valid.copy(triggers = listOf(trigger, trigger)) },
            { ReviewScope("unknown") }, { ReviewScope("rolling", 0) }, { ReviewScope("rolling", 91) },
            { ReviewScope("day", 2) }, { ReviewScope("week", 1) }, { ReviewScope("activity", 2) },
            { ReviewScope(sport = "bad/sport") }, { ReviewScope(sport = "") },
            { ReviewScheduleConfiguration(presets = emptyList()) },
            { ReviewScheduleConfiguration(presets = List(13) { valid.copy(id = "preset-$it") }) },
            { ReviewScheduleConfiguration(presets = listOf(valid, valid)) },
            { ReviewExecutionPolicy(0, 100) }, { ReviewExecutionPolicy(240_001, 300_000) },
            { ReviewExecutionPolicy(100, 99) }, { ReviewExecutionPolicy(100, 600_001) },
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        assertFails { ReviewScheduleConfiguration(timeZone = "Not/AZone") }
        assertFails { ReviewScheduleConfiguration(timeZone = "") }
        for (kind in listOf("changed_data", "daily_time", "weekly_time", "sleep_arrival", "wellness_arrival", "record_count", "daily_steps")) {
            val incompatible = when (kind) {
                "daily_time" -> ReviewTrigger("trigger", kind, localTime = "12:00")
                "weekly_time" -> ReviewTrigger("trigger", kind, localTime = "12:00", dayOfWeek = 1)
                "record_count", "daily_steps" -> ReviewTrigger("trigger", kind, threshold = 1)
                else -> ReviewTrigger("trigger", kind)
            }
            assertFailsWith<IllegalArgumentException> { preset(incompatible, ReviewScope("activity")) }
        }
        preset(ReviewTrigger("activity", "activity_arrival"), ReviewScope("activity"))
        ReviewScope("rolling", 90)
        ReviewExecutionPolicy(1, 1)
        ReviewExecutionPolicy(240_000, 600_000, true)
        valid.copy(triggers = List(8) { trigger.copy(id = "change-$it") })
        ReviewScheduleConfiguration(presets = List(12) { valid.copy(id = "preset-$it") })
    }
}

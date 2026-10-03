package com.gtrainer

import java.time.Instant
import kotlin.test.*

class ReviewQueueRulesTest {
    private val now = Instant.parse("2020-06-02T10:00:00Z")
    private val policy = ReviewQueuePolicy(10, 300, 60)
    private fun intent(id: String = "first") = ReviewDue(AnalysisClaims.hash(id), "synthetic", 7,
        syntheticModelOption.id, 3, "daily_combined", ReviewScope("rolling", 2), "2020-06-01", "2020-06-02",
        null, "interim", "daily_combined", ReviewExecutionPolicy(100, 1000), listOf("changed_data"), now.toString())
    private fun enqueue(queue: ReviewQueueState = ReviewQueueState(), vararg due: ReviewDue,
                        at: Instant = now, manual: Boolean = false) =
        ReviewQueueRules.enqueue(queue, due.toList(), policy, at, manual)
    private fun outcome(job: ReviewQueueJob, status: String = "available") =
        ReviewQueueOutcome(job.id, job.intent, status, "synthetic_result", now.toString(), job.startedUtc)

    @Test fun `same bindings coalesce across presets with reason union and never downgrade thorough or budgets`() {
        val first = intent().copy(budget = ReviewExecutionPolicy(50, 900))
        val thorough = intent("second").copy(presetId = "other", level = "thorough",
            budget = ReviewExecutionPolicy(100, 1000, true), reasons = listOf("activity_arrival", "changed_data"))
        val interim = intent("third").copy(budget = ReviewExecutionPolicy(1, 1), reasons = listOf("wellness_arrival"))
        val queue = enqueue(due = arrayOf(first, thorough, interim))
        val job = queue.pending.single()
        assertEquals(first.occurrenceId, job.id)
        assertEquals("thorough", job.intent.level)
        assertEquals(ReviewExecutionPolicy(100, 1000, true), job.intent.budget)
        assertEquals(listOf("changed_data", "activity_arrival", "wellness_arrival"), job.reasons)
        assertEquals(listOf(first.occurrenceId, thorough.occurrenceId, interim.occurrenceId), job.occurrenceIds)
    }

    @Test fun `type scope sport window focus configuration and model bindings all stay separate`() {
        val original = intent()
        val variants = listOf(original,
            original.copy(reviewType = "weekly"), original.copy(scope = ReviewScope("day")),
            original.copy(scope = ReviewScope("week", 7)), original.copy(scope = ReviewScope("rolling", 3)),
            original.copy(scope = ReviewScope("rolling", 2, "Ride")),
            original.copy(oldest = "2020-05-31"), original.copy(newest = "2020-06-03"),
            original.copy(focus = "wellness"), original.copy(configurationVersion = 8),
            original.copy(modelId = "synthetic-other"), original.copy(selectionVersion = 4))
            .mapIndexed { index, due -> due.copy(occurrenceId = AnalysisClaims.hash("variant-$index")) }
        val queue = enqueue(due = variants.toTypedArray())
        assertEquals(variants.size, queue.pending.size)
        assertEquals(variants.size, queue.pending.map { it.key }.distinct().size)
        assertEquals(ReviewQueueRules.key(original), ReviewQueueRules.key(original.copy(presetId = "other",
            level = "thorough", reasons = listOf("manual_request"), dueUtc = now.plusSeconds(1).toString())))
    }

    @Test fun `different days month sized rolling windows and activity hashes never coalesce`() {
        val day = intent().copy(scope = ReviewScope("day"), oldest = "2020-06-02")
        val month = intent("month").copy(scope = ReviewScope("rolling", 30), oldest = "2020-05-04")
        val activity = intent("activity-a").copy(reviewType = "after_activity", scope = ReviewScope("activity"),
            oldest = "2020-06-02", activitySha256 = "a".repeat(64))
        val variants = listOf(day, day.copy(occurrenceId = AnalysisClaims.hash("tomorrow"), oldest = "2020-06-03", newest = "2020-06-03"),
            month, month.copy(occurrenceId = AnalysisClaims.hash("next-month"), oldest = "2020-06-03", newest = "2020-07-02"),
            month.copy(occurrenceId = AnalysisClaims.hash("long-month"), scope = ReviewScope("rolling", 31), oldest = "2020-05-03"),
            activity, activity.copy(occurrenceId = AnalysisClaims.hash("activity-b"), activitySha256 = "b".repeat(64)))
        assertEquals(variants.size, enqueue(due = variants.toTypedArray()).pending.size)
    }

    @Test fun `stable occurrence duplicate is ignored only while pending`() {
        val due = intent()
        val pending = enqueue(due = arrayOf(due))
        assertEquals(pending, enqueue(pending, due, at = now.plusSeconds(5)))
        val running = ReviewQueueRules.claim(pending, now.plusSeconds(10))
        val followup = enqueue(running, due)
        assertEquals(running.running, followup.running)
        assertEquals(due.occurrenceId, followup.pending.single().id)
        val done = ReviewQueueRules.finish(running, outcome(assertNotNull(running.running)), policy, now)
        assertEquals(1, enqueue(done, due).pending.size)
    }

    @Test fun `running is never cancelled by enqueue and matching arrivals make exactly one followup`() {
        val running = ReviewQueueRules.claim(enqueue(due = arrayOf(intent()), manual = true), now)
        val next = enqueue(running, intent("second"), intent("third"))
        assertEquals(running.running, next.running)
        assertEquals(2, next.pending.single().occurrenceIds.size)
        assertTrue(next.outcomes.isEmpty())
        assertEquals(next, ReviewQueueRules.claim(next, now.plusSeconds(1000)))
    }

    @Test fun `debounce extends from last arrival but fixed maximum deferral prevents starvation`() {
        var queue = enqueue(due = arrayOf(intent()))
        assertNull(ReviewQueueRules.claim(queue, now.plusSeconds(9)).running)
        queue = enqueue(queue, intent("second"), at = now.plusSeconds(8))
        assertEquals(now.plusSeconds(18).toString(), queue.pending.single().eligibleUtc)
        for (second in 16L..64L step 8) queue = enqueue(queue, intent("arrival-$second"), at = now.plusSeconds(second))
        val job = queue.pending.single()
        assertEquals(now.toString(), job.firstQueuedUtc)
        assertEquals(now.plusSeconds(64).toString(), job.lastQueuedUtc)
        assertEquals(now.plusSeconds(60).toString(), job.maximumDeferralUtc)
        assertEquals(job.maximumDeferralUtc, job.eligibleUtc)
        assertNotNull(ReviewQueueRules.claim(queue, now.plusSeconds(64)).running)
    }

    @Test fun `settling and cooldown are capped even when finish updates a pending followup`() {
        val due = intent().copy(notBeforeUtc = now.plusSeconds(5000).toString())
        val delayed = enqueue(due = arrayOf(due))
        assertEquals(now.plusSeconds(60).toString(), delayed.pending.single().eligibleUtc)
        val running = ReviewQueueRules.claim(enqueue(due = arrayOf(intent()), manual = true), now)
        val followup = enqueue(running, intent("followup"), at = now.plusSeconds(1))
        val done = ReviewQueueRules.finish(followup, outcome(assertNotNull(running.running)), policy, now.plusSeconds(2))
        assertEquals(now.plusSeconds(302).toString(), done.cooldownUntilUtc.getValue(ReviewQueueRules.key(intent())))
        assertEquals(now.plusSeconds(61).toString(), done.pending.single().eligibleUtc)
        assertNotNull(ReviewQueueRules.claim(done, now.plusSeconds(61)).running)
        val fresh = enqueue(done.copy(pending = emptyList()), intent("fresh"), at = now.plusSeconds(3))
        assertEquals(now.plusSeconds(63).toString(), fresh.pending.single().eligibleUtc)
    }

    @Test fun `manual request bypasses settling debounce and cooldown including a coalesced upgrade`() {
        val due = intent().copy(notBeforeUtc = now.plusSeconds(5000).toString())
        val cooling = ReviewQueueState(cooldownUntilUtc = mapOf(ReviewQueueRules.key(due) to now.plusSeconds(300).toString()))
        val automatic = enqueue(cooling, due)
        val manual = enqueue(automatic, due.copy(occurrenceId = AnalysisClaims.hash("manual")), at = now.plusSeconds(1), manual = true)
        assertTrue(manual.pending.single().manual)
        assertEquals(now.plusSeconds(1).toString(), manual.pending.single().eligibleUtc)
        assertNotNull(ReviewQueueRules.claim(manual, now.plusSeconds(1)).running)
        val later = enqueue(manual, intent("arrival"), at = now.plusSeconds(2))
        assertTrue(later.pending.single().manual)
        assertEquals(now.plusSeconds(2).toString(), later.pending.single().eligibleUtc)
    }

    @Test fun `finishing a running job never delays an immediate manual followup`() {
        val running = ReviewQueueRules.claim(enqueue(due = arrayOf(intent()), manual = true), now)
        val followup = enqueue(running, intent("manual-followup"), at = now.plusSeconds(1), manual = true)
        val done = ReviewQueueRules.finish(followup, outcome(assertNotNull(running.running)), policy, now.plusSeconds(2))
        assertEquals(now.plusSeconds(1).toString(), done.pending.single().eligibleUtc)
        assertNotNull(ReviewQueueRules.claim(done, now.plusSeconds(2)).running)
    }

    @Test fun `claim selects only one eligible earliest deadline and refuses a second running job`() {
        val first = intent()
        val other = intent("other").copy(focus = "wellness")
        val queue = enqueue(enqueue(due = arrayOf(first)), other, at = now.plusSeconds(1))
        assertNull(ReviewQueueRules.claim(queue, now.plusSeconds(9)).running)
        val claimed = ReviewQueueRules.claim(queue, now.plusSeconds(11))
        assertEquals(first.occurrenceId, assertNotNull(claimed.running).id)
        assertEquals(now.plusSeconds(11).toString(), claimed.running.startedUtc)
        assertEquals(other.occurrenceId, claimed.pending.single().id)
        assertEquals(claimed, ReviewQueueRules.claim(claimed, now.plusSeconds(100)))
    }

    @Test fun `capacity failures are atomic for job batch and occurrence growth`() {
        val original = enqueue(due = arrayOf(intent()))
        val tooMany = (1..256).map { index -> intent("job-$index").copy(modelId = "synthetic-$index") }
        assertFailsWith<ReviewQueueCapacity> { enqueue(original, *tooMany.toTypedArray()) }
        assertEquals(1, original.pending.size)
        val job = original.pending.single().copy(occurrenceIds = (0 until 50_000).map { AnalysisClaims.hash("occurrence-$it") })
        val full = original.copy(pending = listOf(job))
        assertFailsWith<ReviewQueueCapacity> { enqueue(full, intent("overflow")) }
        assertEquals(50_000, full.pending.single().occurrenceIds.size)
        assertFailsWith<IllegalArgumentException> { ReviewQueuePolicy(61, 0, 60) }
        assertFailsWith<IllegalArgumentException> { ReviewExecutionPolicy(101, 100) }
    }

    @Test fun `capacity includes running and reserves its slot for busy restoration`() {
        val intents = (0 until 256).map { index -> intent("reserved-$index").copy(modelId = "synthetic-$index") }
        val full = enqueue(due = intents.toTypedArray(), manual = true)
        val claimed = ReviewQueueRules.claim(full, now)
        val running = assertNotNull(claimed.running)
        assertEquals(255, claimed.pending.size)
        assertFailsWith<ReviewQueueCapacity> { enqueue(claimed, intent("overflow")) }
        assertEquals(255, claimed.pending.size)
        assertEquals(running, claimed.running)
        val merged = enqueue(claimed, claimed.pending.first().intent.copy(occurrenceId = AnalysisClaims.hash("coalesced-at-capacity")))
        assertEquals(255, merged.pending.size)
        assertEquals(2, merged.pending.first().occurrenceIds.size)
        val restored = enqueue(merged.copy(running = null), running.intent, manual = running.manual)
        assertNull(restored.running)
        assertEquals(256, restored.pending.size)
        assertEquals(intents.map { it.occurrenceId }.toSet(), restored.pending.map { it.id }.toSet())
        assertTrue(restored.outcomes.isEmpty())
    }

    @Test fun `producer capacity rejection cannot stop consumer claiming finishing and freeing capacity`() {
        val intents = (0 until 256).map { index -> intent("backpressure-$index").copy(modelId = "synthetic-$index") }
        val full = enqueue(due = intents.toTypedArray(), manual = true)
        val overflow = intent("overflow")
        assertFailsWith<ReviewQueueCapacity> { enqueue(full, overflow) }
        val claimed = ReviewQueueRules.claim(full, now)
        val running = assertNotNull(claimed.running)
        assertEquals(255, claimed.pending.size)
        val done = ReviewQueueRules.finish(claimed, outcome(running), policy, now)
        assertEquals(running.id, done.lastSuccess!!.id)
        assertEquals(256, enqueue(done, overflow).pending.size)
    }

    @Test fun `invalidated or wrong id result cannot resurrect cancelled work or replace last success`() {
        val running = ReviewQueueRules.claim(enqueue(due = arrayOf(intent()), manual = true), now)
        val result = outcome(assertNotNull(running.running))
        assertEquals(running, ReviewQueueRules.finish(running, result.copy(id = "wrong"), policy, now))
        val invalidated = ReviewQueueRules.invalidate(running, "model_selection_changed", now)
        assertEquals("cancelled", invalidated.outcomes.single().status)
        assertEquals(invalidated, ReviewQueueRules.finish(invalidated, result, policy, now))
        assertNull(invalidated.lastSuccess)
    }

    @Test fun `activity date and sport corrections reconcile pending only using newest revision`() {
        val due = intent().copy(scope = ReviewScope("activity", sport = "Ride"), oldest = "2020-06-02", activitySha256 = "a".repeat(64))
        val running = ReviewQueueRules.claim(enqueue(due = arrayOf(due), manual = true), now)
        val pending = enqueue(running, due.copy(occurrenceId = AnalysisClaims.hash("followup")))
        val original = ImportedReviewChange(1, "activities", "2020-06-02", now.toString(), "a".repeat(64), "Ride", false)
        val wellness = original.copy(revision = 2, category = "wellness", observedDate = "2020-06-03")
        assertEquals(pending, ReviewQueueRules.reconcileImports(pending, listOf(original, wellness), now))
        for (correction in listOf(original.copy(revision = 2, observedDate = "2020-06-03"), original.copy(revision = 2, sport = "Run"))) {
            val next = ReviewQueueRules.reconcileImports(pending, listOf(correction, original), now)
            assertEquals(running.running, next.running)
            assertTrue(next.pending.isEmpty())
            assertEquals("input_scope_changed", next.outcomes.single().reason)
            assertEquals("cancelled", next.outcomes.single().status)
        }
        val restored = original.copy(revision = 3)
        assertEquals(pending, ReviewQueueRules.reconcileImports(pending, listOf(original.copy(revision = 2, sport = "Run"), restored), now))
    }

    @Test fun `crash recovery marks running interrupted pending cancelled retains bounded outcomes and last success without retry`() {
        val running = ReviewQueueRules.claim(enqueue(due = arrayOf(intent()), manual = true), now)
        val success = outcome(assertNotNull(running.running)).copy(id = "historical")
        val old = (0..29).map { success.copy(id = "old-$it", status = "unavailable") }
        val queue = enqueue(running.copy(outcomes = old, lastSuccess = success), intent("followup"))
        val recovered = ReviewQueueRules.invalidate(queue, "restart_requires_explicit_request", now, recover = true)
        assertNull(recovered.running)
        assertTrue(recovered.pending.isEmpty())
        assertEquals(24, recovered.outcomes.size)
        assertEquals(listOf("cancelled", "interrupted"), recovered.outcomes.takeLast(2).map { it.status })
        assertEquals(success, recovered.lastSuccess)
        assertTrue(recovered.cooldownUntilUtc.isEmpty())
        assertEquals(recovered, ReviewQueueRules.claim(recovered, now.plusSeconds(86400)))
        assertEquals(recovered, ReviewQueueRules.invalidate(recovered, "restart_requires_explicit_request", now, recover = true))
    }
}

package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class ScopedQueueRulesTest {
    private val now = Instant.parse("2020-06-02T10:00:00Z")
    private val policy = ReviewQueuePolicy(10, 300, 60)

    @Test fun `mixed queue capacity is checked atomically`() {
        val legacy = ReviewDue("occurrence", "synthetic", 1, syntheticModelOption.id, 1, "daily_combined",
            ReviewScope("rolling", 2), "2020-06-01", "2020-06-02", null, "interim", "daily_combined",
            ReviewExecutionPolicy(100, 1000), listOf("changed_data"), now.toString())
        val full = ReviewQueueState(pending = List(256) { index ->
            val due = legacy.copy(occurrenceId = "legacy-$index")
            ReviewQueueJob(due.occurrenceId, ReviewQueueRules.key(due), due, emptyList(), listOf(due.occurrenceId),
                now.toString(), now.toString(), now.toString(), now.plusSeconds(60).toString())
        })
        assertFailsWith<ReviewQueueCapacity> {
            ReviewQueueRules.enqueueScoped(full,listOf(submission()),policy,now)
        }
        assertEquals(256, full.pending.size)
    }

    private fun submission(occurrence: String="first",source: Long=1,reason: Long=1,manual: Boolean=false): ReviewQueueRules.ScopedQueueSubmission {
        val scope=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate="2020-06-02"),now,"UTC")
        return ReviewQueueRules.ScopedQueueSubmission(ScopedQueueIntent(ScopedDescriptiveContractV1.PROFILE,"morning",1,scope,source,reason),
            occurrence,listOf("purpose_slot"),manual)
    }

    @Test fun `pending scoped revisions coalesce without resetting first time or deadline and manual requests bypass debounce`() {
        val first=ReviewQueueRules.enqueueScoped(ReviewQueueState(),listOf(submission()),policy,now)
        val next=ReviewQueueRules.enqueueScoped(first,listOf(submission("second",source=2,reason=3)),policy,now.plusSeconds(50))
        val job=next.pending.single()
        assertEquals(first.pending.single().id,job.id)
        assertEquals(now.toString(),job.firstQueuedUtc);assertEquals(now.plusSeconds(60).toString(),job.maximumDeferralUtc)
        assertEquals(2L,job.scopedIntent?.sourceRevision);assertEquals(3L,job.scopedIntent?.planReasonRevision)
        assertEquals(listOf("first","second"),job.occurrenceIds)
        val manual=ReviewQueueRules.enqueueScoped(next,listOf(submission("third",manual=true)),policy,now.plusSeconds(51))
        assertEquals(now.plusSeconds(51).toString(),manual.pending.single().eligibleUtc)
        val running=ReviewQueueRules.claim(manual,now.plusSeconds(51))
        val arrival=ReviewQueueRules.enqueueScoped(running,listOf(submission("fourth",source=3)),policy,now.plusSeconds(52))
        assertEquals(running.running,arrival.running);assertEquals(1,arrival.pending.size)
        assertTrue(arrival.pending.single().id!=running.running?.id)
        assertEquals(arrival,ReviewQueueRules.claim(arrival,now.plusSeconds(100)))
    }

    @Test fun `scoped outcomes cannot acquire legacy interpretation success or accept both payloads`() {
        val queued=ReviewQueueRules.enqueueScoped(ReviewQueueState(),listOf(submission(manual=true)),policy,now)
        val running=ReviewQueueRules.claim(queued,now);val job=running.running!!
        val result=ReviewQueueOutcome(job.id,null,"factual_prepared","profile_execution_closed_plan_unchanged",now.toString(),job.startedUtc,
            snapshotId="11111111-1111-4111-8111-111111111111",scopedIntent=job.scopedIntent)
        val done=ReviewQueueRules.finish(running,result,policy,now)
        assertNull(done.running);assertNull(done.lastSuccess);assertEquals(result,done.outcomes.single())
        assertFailsWith<IllegalArgumentException> { job.copy(scopedIntent=null) }
        assertFailsWith<IllegalArgumentException> { result.copy(scopedIntent=null) }
        assertEquals(queued,ReviewQueueRules.reconcileImports(queued,emptyList(),now))
    }
}

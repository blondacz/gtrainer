package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AdaptationQueueRulesTest {
    private val now = Instant.parse("2026-10-16T10:00:00Z")
    private val plan = PlanRef("11111111-1111-4111-8111-111111111111", 1)
    private val family = AdaptationFamily(plan, 2, "UTC", plan.planId)
    private val policy = ReviewQueuePolicy(120, 60, 600)

    private fun intent(work: String = "a".repeat(64), generation: Long = 1, ids: List<String> = listOf("b".repeat(64)), cutoff: String = "2026-10-16T10:00:00Z"):
        ScopedQueueIntent {
        val scope = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "day", anchorDate = "2026-10-16"), Instant.parse(cutoff), "UTC")
        return ScopedQueueIntent(ScopedQueueIntent.PROFILE, "adaptation", 1, scope, 2, 3,
            adaptation = AdaptationQueuePayload(family, work, generation, ids.sorted()))
    }

    private fun submit(i: ScopedQueueIntent, occurrence: String) = ReviewQueueRules.ScopedQueueSubmission(i, occurrence, listOf("ignored"))

    @Test fun familyKeyExcludesCutoffAndExactWorkButBindsFamily() {
        val a = intent()
        assertEquals(scopedQueueKey(a), scopedQueueKey(intent("c".repeat(64), 2, cutoff = "2026-10-16T11:00:00Z")))
        assertNotEquals(scopedQueueKey(a), scopedQueueKey(a.copy(adaptation = a.adaptation!!.copy(family = family.copy(horizonDays = 3)))))
        val normal = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "day", anchorDate = "2026-10-16"), now, "UTC")
        val morning = ScopedQueueIntent(ScopedQueueIntent.PROFILE, "morning", 1, normal, 0, 0)
        val evening = morning.copy(purpose = "evening")
        assertNotEquals(scopedQueueKey(morning), scopedQueueKey(evening))
    }

    @Test fun updatesReplaceActiveReasonsKeepPendingIdAndBoundDeferral() {
        val firstIntent = intent(ids = listOf("b".repeat(64), "c".repeat(64)))
        val first = ReviewQueueRules.enqueueScoped(ReviewQueueState(), listOf(submit(firstIntent, "one")), policy, now)
        val updated = ReviewQueueRules.enqueueScoped(first, listOf(submit(intent("d".repeat(64), 2, listOf("e".repeat(64)), now.plusSeconds(200).toString()), "two")), policy, now.plusSeconds(200))
        val old = first.pending.single(); val next = updated.pending.single()
        assertEquals(old.id, next.id)
        assertEquals(old.firstQueuedUtc, next.firstQueuedUtc)
        assertEquals(old.maximumDeferralUtc, next.maximumDeferralUtc)
        assertEquals(listOf("e".repeat(64)), next.reasons)
        assertTrue(next.eligibleUtc <= old.maximumDeferralUtc)
        var continual=updated
        for(offset in 300L..700L step 50) {
            continual=ReviewQueueRules.enqueueScoped(continual,listOf(submit(intent(generation=2,ids=listOf("e".repeat(64)),cutoff=now.plusSeconds(offset).toString()),"update-$offset")),policy,now.plusSeconds(offset))
        }
        assertEquals(old.id,continual.pending.single().id)
        assertEquals(old.maximumDeferralUtc,continual.pending.single().maximumDeferralUtc)
        assertEquals(now.plusSeconds(600).toString(),continual.pending.single().eligibleUtc)
        assertEquals(old.id,ReviewQueueRules.claim(continual,now.plusSeconds(700)).running!!.id)
    }

    @Test fun exactDuplicateDoesNotAdvanceQueueTimestamp() {
        val i = intent()
        val first = ReviewQueueRules.enqueueScoped(ReviewQueueState(), listOf(submit(i, "one")), policy, now)
        val duplicate = ReviewQueueRules.enqueueScoped(first, listOf(submit(i, "two")), policy, now.plusSeconds(20))
        assertEquals(first, duplicate)
    }

    @Test fun healthyRunningKeepsCapturedIntentAndOnlyOneSuccessorMerges() {
        val first=ReviewQueueRules.enqueueScoped(ReviewQueueState(),listOf(submit(intent(),"one")),policy,now)
        val running=ReviewQueueRules.claim(first,now.plusSeconds(120))
        val updated=ReviewQueueRules.enqueueScoped(running,listOf(submit(intent("c".repeat(64)),"two")),policy,now.plusSeconds(200))
        val again=ReviewQueueRules.enqueueScoped(updated,listOf(submit(intent("d".repeat(64),ids=listOf("f".repeat(64))),"three")),policy,now.plusSeconds(400))
        assertEquals(running.running,again.running);assertEquals(1,again.pending.size)
        assertNotEquals(running.running!!.id,again.pending.single().id)
        assertEquals(updated.pending.single().id,again.pending.single().id)
        assertEquals(updated.pending.single().firstQueuedUtc,again.pending.single().firstQueuedUtc)
        assertEquals(updated.pending.single().maximumDeferralUtc,again.pending.single().maximumDeferralUtc)
        assertEquals(listOf("f".repeat(64)),again.pending.single().reasons)
    }

    @Test fun staleSubmissionCannotMixOldWorkWithNewerRoutingRevisions() {
        val latest=intent(generation=2).copy(sourceRevision=5)
        val queue=ReviewQueueRules.enqueueScoped(ReviewQueueState(),listOf(submit(latest,"new")),policy,now)
        assertFailsWith<IllegalArgumentException> { ReviewQueueRules.enqueueScoped(queue,listOf(submit(intent(),"old")),policy,now.plusSeconds(10)) }
        assertFailsWith<IllegalArgumentException> { intent().copy(providerSelection=ConnectedReviewProviderSelection("synthetic","synthetic","hosted")) }
        val later=latest.copy(scope=ReviewScopeResolver.resolve(latest.scope.request,now.plusSeconds(3600),"UTC"))
        val fresh=ReviewQueueRules.enqueueScoped(ReviewQueueState(),listOf(submit(later,"later")),policy,now.plusSeconds(3600))
        assertFailsWith<IllegalArgumentException> { ReviewQueueRules.enqueueScoped(fresh,listOf(submit(latest,"earlier-cutoff")),policy,now.plusSeconds(3700)) }
        val running=ReviewQueueRules.claim(fresh,now.plusSeconds(3720))
        assertTrue(running.pending.isEmpty());assertTrue(running.running!=null)
        assertFailsWith<IllegalArgumentException> { ReviewQueueRules.enqueueScoped(running,listOf(submit(intent(),"old-again")),policy,now.plusSeconds(3800)) }
        assertFailsWith<IllegalArgumentException> { ReviewQueueRules.enqueueScoped(running,listOf(submit(latest,"old-cutoff-running")),policy,now.plusSeconds(3800)) }
        assertEquals(running,ReviewQueueRules.enqueueScoped(running,listOf(submit(later,"same-running-work")),policy,now.plusSeconds(3800)))
    }
}

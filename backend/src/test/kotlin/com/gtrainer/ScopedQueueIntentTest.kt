package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ScopedQueueIntentTest {
    private fun intent(
        scope: ResolvedReviewScope = ReviewScopeResolver.resolve(
            ScopedReviewRequest(scope = "day"), Instant.parse("2026-10-06T12:00:00Z"), "UTC"
        ),
        purpose: String = "morning",
        config: Long = 1,
        source: Long = 1,
        provider: ConnectedReviewProviderSelection? = null,
    ) = ScopedQueueIntent(ScopedQueueIntent.PROFILE, purpose, config, scope, source, 1, provider)

    @Test fun `key excludes mutable revisions but includes routing identity`() {
        val first = intent()
        assertEquals(scopedQueueKey(first), scopedQueueKey(intent(source = 99)))
        assertNotEquals(scopedQueueKey(first), scopedQueueKey(intent(purpose = "evening")))
        assertNotEquals(scopedQueueKey(first), scopedQueueKey(intent(config = 2)))
        assertNotEquals(scopedQueueKey(first), scopedQueueKey(intent(provider = ConnectedReviewProviderSelection("p", "m", "local"))))
        val laterCutoff = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "day"), Instant.parse("2026-10-06T12:01:00Z"), "UTC")
        assertNotEquals(scopedQueueKey(first), scopedQueueKey(intent(scope = laterCutoff)))
    }

    @Test fun `purpose and resolved scope invariants are enforced`() {
        assertFailsWith<IllegalArgumentException> { intent(purpose = "unknown") }
        assertFailsWith<IllegalArgumentException> { intent(config = -1) }
        val unavailable = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "night"), Instant.parse("2026-10-06T12:00:00Z"), "UTC")
        assertFailsWith<IllegalArgumentException> { intent(scope = unavailable) }
        val notMorning = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "rolling", days = 7), Instant.parse("2026-10-06T12:00:00Z"), "UTC")
        assertFailsWith<IllegalArgumentException> { intent(scope = notMorning) }
    }

    @Test fun `activity selection permits many activities on one date`() {
        val records = (0 until 368).associate { index ->
            val record = ActivityRecord("test", "record-$index", emptySet(), "run", "2026-10-05T08:00:00", null,
                null, null, null, "source_local", null, null, null, null, null, null)
            scopedActivityId(record.source, record.sourceRecordId) to record
        }
        val ids = records.keys.toList()
        val request = ScopedReviewRequest(mode = "activities", scope = "activities", activityIds = ids)
        val resolved = ReviewScopeResolver.resolve(request, Instant.parse("2026-10-06T12:00:00Z"), "UTC", records)
        assertEquals(368, intent(scope = resolved, purpose = "activity_reflection").scope.activityDates.size)
    }
}

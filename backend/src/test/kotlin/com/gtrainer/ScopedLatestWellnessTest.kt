package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopedLatestWellnessTest {
    private fun read(records: List<WellnessRecord>, date: String = "2025-01-10"): CapturedScopedRead {
        val scope = ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",anchorDate = date, focus = "daily_combined"),
            Instant.parse("${date}T12:00:00Z"), "UTC")
        return CapturedScopedRead(scope, HistoryResponse(emptyList(), records), emptyList(), emptyList())
    }
    private fun record(id: String, date: String, vararg metrics: Pair<String, Measurement>) =
        WellnessRecord("source", id, date, setOf("origin"), metrics.toMap())
    private fun m(field: String, value: Double, origin: String? = "vendor") = Measurement(value,
        when (field) { "sleepSecs" -> "seconds"; "restingHR" -> "beats/minute"; "sleepScore" -> "source sleep score"; else -> "ms" }, origin)

    @Test fun latestContextUsesSourceDatesAndKeepsFieldsAndOriginsSeparate() {
        val context = ScopedLatestWellness.assemble(read(listOf(
            record("sleep", "2025-01-09", "sleepSecs" to m("sleepSecs", 28800.0)),
            record("hrv", "2025-01-10", "hrv" to m("hrv", 42.0)))))
        val sleep = context.days.single { it.field == "sleepSecs" }
        assertEquals(1, sleep.ageDays)
        assertEquals("2025-01-09", sleep.sourceDate)
        assertTrue(context.temporalBasis.contains("not_verified_prior_night"))
        assertEquals("2025-01-10", context.days.single { it.field == "hrv" }.sourceDate)
    }

    @Test fun aNewerPartialRecordDoesNotMaskLatestSleepAndSameDateDuplicatesStayAmbiguous() {
        val context = ScopedLatestWellness.assemble(read(listOf(
            record("old", "2025-01-08", "sleepSecs" to m("sleepSecs", 28000.0)),
            record("new", "2025-01-09", "hrv" to m("hrv", 40.0)),
            record("dup1", "2025-01-09", "hrvSDNN" to m("hrvSDNN", 30.0)),
            record("dup2", "2025-01-09", "hrvSDNN" to m("hrvSDNN", 30.0)))))
        assertEquals("2025-01-08", context.days.single { it.field == "sleepSecs" }.sourceDate)
        assertEquals("ambiguous", context.days.single { it.field == "hrvSDNN" }.status)
        assertEquals(listOf("training_readiness_unsupported"), context.missing)
    }

    @Test fun futureFactsAreExcludedAndUnknownOriginsDoNotMerge() {
        val context = ScopedLatestWellness.assemble(read(listOf(
            record("future", "2025-01-11", "hrv" to m("hrv", 90.0)),
            record("a", "2025-01-09", "hrv" to m("hrv", 40.0, null)),
            record("b", "2025-01-09", "hrv" to m("hrv", 41.0, "vendor")))))
        assertEquals(2, context.days.size)
        assertTrue(context.days.all { it.sourceDate != "2025-01-11" })
        assertTrue(context.days.map { it.upstreamOrigin }.toSet().containsAll(listOf(null, "vendor")))
        assertTrue("training_readiness_unsupported" in context.missing)
    }
}

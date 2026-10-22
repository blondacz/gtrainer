package com.gtrainer

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedReviewEvidenceTest {
    private val asOf = Instant.parse("2026-03-31T12:00:00Z")

    private fun activity(id: String, date: String, sport: String = "Run", moving: Double? = 600.0,
                         elapsed: Double? = 660.0, distance: Measurement? = Measurement(1000.0, "metres")) =
        syntheticActivity().copy(sourceRecordId = id, sport = sport, startLocal = "${date}T09:00:00",
            startInstant = "${date}T09:00:00Z", movingTime = moving?.let { Measurement(it, "seconds") },
            elapsedTime = elapsed?.let { Measurement(it, "seconds") }, distance = distance)

    private fun scope(request: ScopedReviewRequest, records: List<ActivityRecord> = emptyList()) =
        ReviewScopeResolver.resolve(request, asOf, "UTC", records.associateBy(::scopedActivityId))

    private fun assemble(request: ScopedReviewRequest, history: HistoryResponse): Pair<ScopedTrendReport, ScopedEvidenceFacts> {
        val resolved = scope(request, history.activities)
        val report = ScopedTrends.report(resolved, history, emptyList())
        val provenance = history.activities.map { ScopedImportProvenance("activities", it.source, it.sourceRecordId, 7, "2026-03-31T11:00:00Z") } +
            history.wellness.map { ScopedImportProvenance("wellness", it.source, it.sourceRecordId, 8, "2026-03-31T11:00:00Z") }
        return report to ScopedReviewEvidence.assemble(CapturedScopedRead(resolved, history, emptyList(), provenance), report)
    }

    @Test fun `capture and session comparison contain only selected identities and compatible measures`() {
        val first = activity("first", "2026-03-01", moving = 600.0, elapsed = 660.0)
            .copy(averageHeartRate = Measurement(145.0, "beats/minute", "hr-origin"),
                intervalsTrainingLoad = Measurement(987.0, "Intervals.icu load", "Intervals.icu"))
        val intervening = activity("intervening", "2026-03-02", sport = "Run", moving = 9999.0)
        val second = activity("second", "2026-03-03", sport="Ride", moving = 720.0, elapsed = 900.0,
            distance = Measurement(2.0, "kilometres"))
        val records = listOf(first, intervening, second)
        val request = ScopedReviewRequest(mode = "activities", scope = "activities", intent = "compare",
            activityIds = listOf(scopedActivityId(first), scopedActivityId(second)))
        val (report, facts) = assemble(request, HistoryResponse(records, emptyList()))
        assertEquals(listOf(scopedActivityId(first), scopedActivityId(second)), facts.sessions.map { it.activityId })
        assertFalse(facts.sessions.any { it.sourceRecordId == "intervening" })
        assertEquals(report.sessionComparisons, facts.sessionComparisons)
        assertEquals(setOf("movingTime", "elapsedTime"), facts.sessionComparisons.map { it.key }.toSet())
        assertEquals(listOf(scopedActivityId(first), scopedActivityId(second)), facts.sessionComparisons.first { it.key == "movingTime" }.values.map { it.activityId })
        assertTrue(facts.sessionComparisons.all { it.flags.contains("physical_duration_not_effort") })
        assertEquals("2026-03-01T09:00:00", facts.sessions.first().startLocal)
        assertEquals("145.0", facts.sessions.first().metrics.single { it.key == "averageHeartRate" }.value.toString())
        assertEquals("987.0", facts.sessions.first().metrics.single { it.key == "intervalsTrainingLoad" }.value.toString())
        assertEquals("activities", facts.sessions.first().received.category)
        assertEquals("hr-origin",facts.sessions.first().metrics.single { it.key=="averageHeartRate" }.upstreamSource)
        assertEquals("2026-03-31T11:00:00Z",facts.sessions.first().received.receivedUtc)
        val (_, sameSport)=assemble(request.copy(activityIds=listOf(scopedActivityId(first),scopedActivityId(second.copy(sport="Run")))),
            HistoryResponse(listOf(first,second.copy(sport="Run")),emptyList()))
        assertTrue(sameSport.sessionComparisons.any { it.key=="distance" && it.sport=="Run" })
    }

    @Test fun `period aggregates are exact selected report values and omit display-only wellness`() {
        val run = activity("run", "2026-03-01", moving = null, elapsed = null, distance = null)
        val wellness = syntheticWellness().copy(sourceRecordId = "marker", date = "2026-03-01",
            upstreamSources = setOf("private-origin-marker"), measurements = mapOf(
                "sleepSecs" to Measurement(987654.321, "seconds", "private-metric-marker")))
        val history = HistoryResponse(listOf(run), listOf(wellness))
        val request = ScopedReviewRequest(scope = "custom", oldest = "2026-03-01", newest = "2026-03-01", wellnessPolicy = "display_only")
        val (report, facts) = assemble(request, history)
        assertEquals(report.selected!!.sports.flatMap { it.metrics }.map { it.key to it.value }.toSet(),
            facts.aggregates.filter { it.period == "selected" }.map { it.key to it.value }.toSet())
        assertTrue(facts.aggregates.none { it.key == "sleepSecs" })
        assertNull(facts.aggregates.single { it.key=="movingTime" }.value)
        assertTrue(facts.sessions.isEmpty())
        assertTrue(facts.unavailable.any { it.key == "trainingReadiness" })
        assertFalse(facts.sessions.any { it.metrics.any { metric -> metric.key.contains("readiness", true) } })
        assertTrue(facts.aggregates.none { it.key.contains("point", true) })
        val encoded = Json { encodeDefaults = true }.encodeToString(facts)
        assertFalse(encoded.contains("private-origin-marker"))
        assertFalse(encoded.contains("private-metric-marker"))
        assertFalse(encoded.contains("987654.321"))
        assertEquals(987654.321, report.selected!!.wellness.single { it.key == "sleepSecs" }.value)
    }

    @Test fun `assessment wellness is selected-date only and shared observation names selected activities`() {
        val chosenA = activity("chosen-a", "2026-03-01")
        val intervening = activity("not-chosen", "2026-03-02")
        val chosenB = activity("chosen-b", "2026-03-01", sport = "Ride")
        val onDate = syntheticWellness().copy(sourceRecordId = "on-date", date = "2026-03-01")
        val between = syntheticWellness().copy(sourceRecordId = "between", date = "2026-03-02")
        val request = ScopedReviewRequest(mode = "activities", scope = "activities", intent = "compare",
            activityIds = listOf(scopedActivityId(chosenA), scopedActivityId(chosenB)), wellnessPolicy = "assessment")
        val (_, facts) = assemble(request, HistoryResponse(listOf(chosenA, intervening, chosenB), listOf(onDate, between)))
        assertTrue(facts.wellness.all { it.sourceRecordId == "on-date" })
        assertEquals(listOf(scopedActivityId(chosenA), scopedActivityId(chosenB)).sorted(), facts.wellness.first().activityIds)
    }

    @Test fun `uncertain session time remains null and period review has selected aggregates only`() {
        val unknown = activity("unknown", "2026-03-01").copy(startInstant = null)
        val history = HistoryResponse(listOf(unknown, activity("prior", "2026-02-28")), emptyList())
        val request = ScopedReviewRequest(scope = "custom", oldest = "2026-03-01", newest = "2026-03-01", wellnessPolicy="off")
        val (report, facts) = assemble(request, history)
        assertTrue(facts.sessions.isEmpty())
        assertTrue(facts.aggregates.all { it.period == "selected" })
        assertEquals(report.selected!!.sports.flatMap { it.metrics }.map { it.key to it.value }.toSet(),
            facts.aggregates.map { it.key to it.value }.toSet())
        assertTrue(facts.periodComparisons.isEmpty())
        val (_,session)=assemble(ScopedReviewRequest(mode="activities",scope="activities",activityIds=listOf(scopedActivityId(unknown))),history)
        assertNull(session.sessions.single().startInstant)
    }
}

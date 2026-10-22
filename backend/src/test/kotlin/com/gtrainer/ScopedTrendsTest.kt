package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedTrendsTest {
    private val cutoff = Instant.parse("2026-03-30T12:00:00Z")
    private fun activity(id: String, date: String, sport: String = "Run", minutes: Double = 10.0) = syntheticActivity().copy(
        sourceRecordId = id, sport = sport, startLocal = "${date}T09:00:00", startInstant = "${date}T09:00:00Z",
        movingTime = Measurement(minutes * 60, "seconds"), elapsedTime = Measurement(minutes * 60 + 60, "seconds"),
        distance = Measurement(1000.0, "metres"))
    private fun wellness(date: String, sleep: Double = 28_800.0) = syntheticWellness().copy(
        sourceRecordId = date, date = date, measurements = mapOf("sleepSecs" to Measurement(sleep, "seconds"), "hrv" to Measurement(50.0, "ms")))
    private fun prepare(request: ScopedReviewRequest, history: HistoryResponse): ScopedTrendReport {
        val scope = ReviewScopeResolver.resolve(request, cutoff, "UTC", history.activities.associateBy(::scopedActivityId))
        return ScopedTrends.report(scope, history, emptyList())
    }

    @Test fun `calendar arithmetic distinguishes full totals from comparable prefix`() {
        val dates = listOf("2026-03-01", "2026-03-28", "2026-03-29", "2026-03-30", "2026-02-01", "2026-02-28")
        val history = HistoryResponse(dates.mapIndexed { index, date -> activity("a$index", date) }, dates.map { wellness(it) })
        val result = prepare(ScopedReviewRequest(scope = "calendar_month"), history)
        assertEquals("2026-03-30", result.selected!!.newest)
        assertEquals(2400.0, result.selected.sports.single().metrics.single { it.key == "movingTime" }.value)
        assertEquals(1200.0, result.comparableSelected!!.sports.single().metrics.single { it.key == "movingTime" }.value)
        assertEquals(0.0, result.comparisons.single { it.sport == "Run" && it.key == "movingTime" }.absoluteChange)
        val fact = result.factualReview!!.groups.flatMap { it.facts }.single { it.comparison.sport == "Run" && it.comparison.key == "movingTime" }
        assertEquals(listOf(1200.0, 1200.0), fact.supportingMetrics.map { it.value })
        assertEquals(listOf("previous/Run/movingTime", "current/Run/movingTime"), fact.supportingMetrics.map { it.evidenceId })
        assertEquals("2026-03-28", fact.supportingMetrics.last().newest)
        assertEquals(result.comparisons.size, result.factualReview.groups.sumOf { it.facts.size })
        assertEquals(listOf("2026-03-29", "2026-03-30"), result.scope.comparison.unmatchedSelectedDates)
        assertEquals("2026-02-28", result.priorCalendarFull!!.newest)
        assertTrue(result.scope.soFar)
    }

    @Test fun `completed February keeps full January totals and equal prefix comparison separately`() {
        val history = HistoryResponse(listOf(activity("feb", "2026-02-01"), activity("jan", "2026-01-01"),
            activity("jan-remainder", "2026-01-31")), emptyList())
        val result = prepare(ScopedReviewRequest(scope = "calendar_month", anchorDate = "2026-02-20"), history)
        assertEquals("2026-02-28", result.selected!!.newest)
        assertEquals(1200.0, result.priorCalendarFull!!.sports.single().metrics.single { it.key == "movingTime" }.value)
        assertEquals(600.0, result.comparablePrevious!!.sports.single().metrics.single { it.key == "movingTime" }.value)
        val fact = result.factualReview!!.groups.flatMap { it.facts }.single { it.comparison.sport == "Run" && it.comparison.key == "movingTime" }
        assertEquals(600.0, fact.supportingMetrics.first().value)
        assertEquals("2026-01-28", fact.supportingMetrics.first().newest)
        assertFalse(result.scope.soFar)
        assertTrue(result.scope.comparison.unequalDuration)
    }

    @Test fun `explicit ranges retain unequal arithmetic zero baseline and overlap labels`() {
        val records = HistoryResponse(listOf(activity("zero", "2026-03-02", minutes = 0.0), activity("ten", "2026-03-01")), emptyList())
        val result = prepare(ScopedReviewRequest(scope = "custom", oldest = "2026-03-01", newest = "2026-03-01",
            comparison = ScopedDateRange("2026-03-02", "2026-03-03")), records)
        val moving = result.comparisons.single { it.sport == "Run" && it.key == "movingTime" }
        assertEquals(600.0, moving.absoluteChange)
        assertNull(moving.percentChange)
        assertTrue(result.scope.comparison.unequalDuration)
        assertFalse(result.scope.comparison.overlapping)
    }

    @Test fun `selected mixed sessions exclude intervening activities and never fabricate a baseline`() {
        val swim = activity("swim", "2026-03-01", "Swim")
        val ride = activity("ride", "2026-03-03", "Ride")
        val extra = activity("extra", "2026-03-02", "Run")
        val history = HistoryResponse(listOf(swim, extra, ride), listOf(wellness("2026-03-01"), wellness("2026-03-02"), wellness("2026-03-03")))
        val result = prepare(ScopedReviewRequest(mode = "activities", scope = "activities", intent = "compare",
            activityIds = listOf(scopedActivityId(swim), scopedActivityId(ride)), wellnessPolicy = "display_only"), history)
        assertEquals(listOf(scopedActivityId(swim), scopedActivityId(ride)), result.sessions.map { it.activityId })
        assertEquals(listOf("2026-03-01", "2026-03-03"), result.wellnessContext.map { it.record.date })
        assertTrue(result.comparisons.isEmpty())
        assertNull(result.selected)
        assertNull(result.comparablePrevious)
        assertEquals(setOf("movingTime", "elapsedTime"), result.sessionComparisons.map { it.key }.toSet())
        assertTrue(result.sessionComparisons.all { "physical_duration_not_effort" in it.flags })
        assertTrue(result.wellnessContext.all { it.usage == "display_only" && "prior_night_mapping_unverified" in it.flags })
    }

    @Test fun `defaults wellness subjects and shared observations retain intended meanings`() {
        val run = activity("run", "2026-03-01")
        val ride = activity("ride", "2026-03-01", "Ride")
        val history = HistoryResponse(listOf(run, ride), listOf(wellness("2026-03-01")))
        val request = ScopedReviewRequest(mode = "activities", scope = "activities", activityIds = listOf(scopedActivityId(run), scopedActivityId(ride)))
        assertTrue(prepare(request, history).wellnessContext.isEmpty())
        val assessment = prepare(request.copy(wellnessPolicy = "assessment"), history)
        assertEquals(1, assessment.wellnessContext.size)
        assertEquals(2, assessment.wellnessContext.single().activityIds.size)
        assertTrue(assessment.sessionComparisons.isEmpty()) // Review intent is not session comparison.
        val period = prepare(ScopedReviewRequest(scope = "day", anchorDate = "2026-03-01"), history)
        assertTrue(period.comparisons.any { it.key == "sleepSecs" })
        val display = prepare(ScopedReviewRequest(scope = "day", anchorDate = "2026-03-01", wellnessPolicy = "display_only"), history)
        assertTrue(display.selected!!.wellness.any { it.key == "sleepSecs" && it.value != null })
        assertFalse(display.comparisons.any { it.sport == null })
        assertTrue(period.unavailable.any { it.key == "trainingReadiness" })
    }

    @Test fun `unverified night and future timing keep factual subjects empty`() {
        val history = HistoryResponse(listOf(activity("run", "2026-03-01")), listOf(wellness("2026-03-01")))
        val night = prepare(ScopedReviewRequest(scope = "night", anchorDate = "2026-03-01"), history)
        assertEquals("needs_confirmation", night.scope.status)
        assertNull(night.selected)
        assertTrue(night.comparisons.isEmpty())
        val future = activity("future", "2026-03-31")
        val result = prepare(ScopedReviewRequest(mode = "activities", scope = "activities", activityIds = listOf(scopedActivityId(future))),
            HistoryResponse(listOf(future), emptyList()))
        assertEquals("needs_confirmation", result.scope.status)
        assertTrue(result.sessions.isEmpty())
    }

    @Test fun `period excludes verified future instants but retains unknown instant history`() {
        val past = activity("past", "2026-03-30")
        val future = activity("future-today", "2026-03-30").copy(startInstant = "2026-03-30T13:00:00Z")
        val unknown = activity("unknown-time", "2026-03-29").copy(startInstant = null)
        val result = prepare(ScopedReviewRequest(scope = "custom", oldest = "2026-03-29", newest = "2026-03-30", wellnessPolicy = "off"),
            HistoryResponse(listOf(past, future, unknown), emptyList()))
        val selected = result.selected!!
        val moving = selected.sports.single().metrics.single { it.key == "movingTime" }
        assertEquals(1200.0, moving.value) // past-today and historical unknown-time record only
        assertTrue(result.limitations.any { it.contains("Excluded 1 imported activities") })
        assertTrue(selected.wellness.isEmpty())
        assertTrue("optional_wellness_excluded" in selected.flags)
        assertFalse("no_wellness_records" in selected.flags)
        assertFalse("incomplete_wellness_date_coverage" in selected.flags)
    }

    @Test fun `same sport distance comparison does not substitute unlike units or intensity`() {
        val run = activity("first-run", "2026-03-01").copy(averageHeartRate = Measurement(145.0, "beats/minute"))
        val otherRun = activity("second-run", "2026-03-02").copy(distance = Measurement(1.0, "kilometres"))
        val ride = activity("ride", "2026-03-03", "Ride")
        val records = listOf(run, otherRun, ride)
        val report = prepare(ScopedReviewRequest(mode = "activities", intent = "compare", scope = "activities",
            activityIds = records.map(::scopedActivityId)), HistoryResponse(records, emptyList()))
        val distance = report.sessionComparisons.single { it.key == "distance" }
        assertEquals("Run", distance.sport)
        assertEquals("distance", distance.basis)
        assertEquals("metres", distance.unit)
        assertEquals(listOf(scopedActivityId(run), scopedActivityId(otherRun)), distance.values.map { it.activityId })
        assertEquals(listOf(1000.0, null), distance.values.map { it.value })
        assertEquals(listOf("movingTime", "elapsedTime", "distance"), report.sessionComparisons.map { it.key })
        assertEquals(145.0, report.sessions.first().metrics.single { it.key == "averageHeartRate" }.value)
        assertTrue(report.comparisons.isEmpty())
    }
}

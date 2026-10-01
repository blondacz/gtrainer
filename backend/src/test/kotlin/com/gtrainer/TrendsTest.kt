package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendsTest {
    private val range = TrendRange(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-02"))
    private val today = LocalDate.parse("2020-06-10")
    private fun activity(id: String, date: String, sport: String = "Ride", moving: Double? = 1200.0,
                         calories: Double? = 100.0): ActivityRecord = syntheticActivity().copy(
        sourceRecordId = id, sport = sport, startLocal = "${date}T23:30", startInstant = "2020-06-02T03:30:00Z",
        timeZone = "America/New_York", timeContext = "named_zone", upstreamSources = setOf("GARMIN"),
        movingTime = moving?.let { Measurement(it, "seconds") }, calories = calories?.let { Measurement(it, "kcal") })
    private fun wellness(id: String, weight: Double? = 70.0, sleep: Double? = 28800.0): WellnessRecord =
        syntheticWellness().copy(sourceRecordId = id, date = id, measurements = buildMap {
            weight?.let { put("weight", Measurement(it, "kg", "GARMIN")) }
            sleep?.let { put("sleepSecs", Measurement(it, "seconds")) }
        })
    private fun report(activities: List<ActivityRecord> = emptyList(), wellness: List<WellnessRecord> = emptyList(),
                       sport: String? = null) = Trends.report(HistoryResponse(activities, wellness), range, sport, today, emptyList())

    @Test
    fun `sport totals separate moving elapsed calories and source load with local date comparisons`() {
        val result = report(listOf(activity("a", "2020-06-01"), activity("b", "2020-06-02", moving = 600.0, calories = 50.0),
            activity("c", "2020-05-31", moving = 900.0, calories = 75.0), activity("d", "2020-06-01", "Run")))
        assertEquals("2020-05-30", result.previous.oldest)
        assertEquals("2020-05-31", result.previous.newest)
        val metrics = result.current.sports.first { it.sport == "Ride" }.metrics.associateBy { it.key }
        assertEquals(2.0, metrics.getValue("activityCount").value)
        assertEquals(1800.0, metrics.getValue("movingTime").value)
        assertEquals(3000.0, metrics.getValue("elapsedTime").value)
        assertEquals(150.0, metrics.getValue("calories").value)
        val delta = result.comparisons.single { it.sport == "Ride" && it.key == "movingTime" }
        assertEquals(900.0, delta.absoluteChange)
        assertEquals(100.0, delta.percentChange)
        val point = metrics.getValue("movingTime").points.first()
        assertEquals("2020-06-01", point.date) // Not shifted to the UTC day.
        assertEquals("America/New_York", point.timeZone)
        assertEquals(listOf("GARMIN"), point.recordOrigins)
        assertNull(point.upstreamSource) // Activity origin does not invent metric origin.
        assertTrue(metrics.getValue("movingTime").label.contains("Recorded activity time"))
    }

    @Test
    fun `missing values do not become zeros and actual zero baseline has no percentage`() {
        val result = report(listOf(activity("a", "2020-06-01", moving = null, calories = null),
            activity("b", "2020-06-02", moving = 0.0, calories = 0.0), activity("c", "2020-05-31", moving = 0.0)))
        val moving = result.current.sports.single().metrics.single { it.key == "movingTime" }
        assertEquals(0.0, moving.value)
        assertEquals(1, moving.sampleCount)
        assertEquals(1, moving.missingRecordValues)
        val comparison = result.comparisons.single { it.sport == "Ride" && it.key == "movingTime" }
        assertNull(comparison.percentChange)
        assertEquals(0.0, comparison.absoluteChange)
        assertTrue("partial_metric_coverage" in comparison.flags)
        assertTrue("zero_baseline_no_percentage" in comparison.flags)
        val empty = report()
        assertTrue(empty.current.sports.isEmpty())
        assertTrue(empty.current.wellness.all { it.value == null && it.points.isEmpty() })
        assertTrue(empty.comparisons.all { it.absoluteChange == null })
    }

    @Test
    fun `wellness means retain source dates units missing days and independent sport filtering`() {
        val result = report(listOf(activity("a", "2020-06-01", "Run")), listOf(
            wellness("2020-06-01", 70.0), wellness("2020-06-02", 72.0, null), wellness("2020-05-31", 68.0)), "Ride")
        assertEquals(0, result.current.activityRecords)
        assertEquals(71.0, result.current.wellness.single { it.key == "weight" }.value)
        val sleep = result.current.wellness.single { it.key == "sleepSecs" }
        assertEquals(28800.0, sleep.value)
        assertEquals(1, sleep.missingRecordValues)
        assertEquals(1, sleep.observedDays)
        assertEquals("seconds", sleep.unit)
        assertEquals(3.0, result.comparisons.single { it.sport == null && it.key == "weight" }.absoluteChange)
        assertTrue(result.unavailable.any { it.key == "fitnessAge" })
        assertTrue(result.current.wellness.single { it.key == "atl" }.label.startsWith("Intervals.icu"))
    }

    @Test
    fun `invalid persisted metric unit and nonfinite values are excluded from arithmetic`() {
        val result = report(listOf(activity("a", "2020-06-01").copy(movingTime = Measurement(Double.NaN, "seconds")),
            activity("b", "2020-06-02").copy(calories = Measurement(42.0, "unknown"))))
        val metrics = result.current.sports.single().metrics.associateBy { it.key }
        assertEquals(1, metrics.getValue("movingTime").rejectedValues)
        assertEquals(1, metrics.getValue("calories").rejectedValues)
        assertFalse(Json.encodeToString(result).contains("NaN"))
    }

    @Test
    fun `summary is reproducible minimized and linked to private source evidence`() {
        val history = HistoryResponse(listOf(activity("synthetic-private-id", "2020-06-01"), activity("b", "2020-05-31")),
            listOf(wellness("2020-06-01"), wellness("2020-05-31")))
        val first = Trends.report(history, range, null, today, emptyList())
        val second = Trends.report(history.copy(activities = history.activities.reversed(), wellness = history.wellness.reversed()), range, null, today, emptyList())
        val input = Trends.analysisInput(first)
        assertEquals(Json.encodeToString(input), Json.encodeToString(Trends.analysisInput(second)))
        assertTrue(input.evidenceReportSha256.matches(Regex("[a-f0-9]{64}")))
        val fact = input.facts.single { it.evidenceId == "current/Ride/movingTime" }
        assertEquals(1200.0, fact.value)
        assertEquals("seconds", fact.unit)
        assertEquals(listOf("intervals.icu"), fact.sources)
        assertEquals(listOf("GARMIN"), fact.recordOrigins)
        assertEquals(1, fact.unknownMetricOriginCount)
        assertEquals("2020-06-01", fact.firstObservedDate)
        assertTrue("sparse_metric" in fact.flags)
        assertFalse(Json.encodeToString(input).contains("synthetic-private-id"))
        assertTrue(first.current.sports.single().metrics.single { it.key == "movingTime" }.points.any { it.sourceRecordId == "synthetic-private-id" })
        assertEquals("TrendReport([REDACTED])", first.toString())
        assertEquals("AnalysisInput([REDACTED])", input.toString())
        assertEquals("SummaryFact([REDACTED])", fact.toString())
    }

    @Test
    fun `old selected periods use observed date gaps not an illness or sync cause`() {
        val longRange = TrendRange(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-30"))
        val result = Trends.report(HistoryResponse(listOf(activity("a", "2020-06-01")), emptyList()), longRange, null, today.plusMonths(1), emptyList())
        assertTrue("selected_period_observed_gap_over_7_days" in result.current.flags)
        assertTrue("upstream_freshness_unknown" in result.current.flags)
        assertTrue("no_wellness_records" in result.current.flags)
    }

    @Test
    fun `metric gaps remain visible when another category has recent records and different sources are not merged`() {
        val longRange = TrendRange(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-30"))
        val first = wellness("2020-06-01", 70.0)
        val second = first.copy(source = "synthetic.other", measurements = mapOf("weight" to Measurement(76.0, "kg", "STRAVA")))
        val status = CategoryStatus("wellness", 2, "2020-07-01T00:00:00Z", "2020-06-01T00:00:00Z", "KEY_REJECTED", 0, 0,
            "2020-06-01", 30)
        val report = Trends.report(HistoryResponse(listOf(activity("a", "2020-06-30")), listOf(first, second)),
            longRange, null, LocalDate.parse("2020-07-01"), listOf(status))
        assertFalse("selected_period_observed_gap_over_7_days" in report.current.flags)
        val fact = Trends.analysisInput(report).facts.single { it.evidenceId == "current/wellness/weight" }
        assertEquals(73.0, fact.value)
        assertEquals(2, fact.sampleCount)
        assertEquals(1, fact.observedDays)
        assertEquals(listOf("intervals.icu", "synthetic.other"), fact.sources)
        assertTrue("metric_observed_gap_over_7_days" in fact.flags)
        assertTrue("multiple_record_sources" in fact.flags)
        assertTrue("mixed_metric_origins" in fact.flags)
        assertTrue("latest_category_read_not_successful" in fact.flags)
        assertEquals("KEY_REJECTED", Trends.analysisInput(report).sourceStatus.single().readStatus)
    }

    @Test
    fun `range bounds are inclusive handle leap year and reject future invalid or oversized requests`() {
        assertEquals(2, TrendRange(LocalDate.parse("2020-02-28"), LocalDate.parse("2020-02-29")).days)
        assertEquals(366, TrendRange(LocalDate.parse("2020-01-01"), LocalDate.parse("2020-12-31")).days)
        assertFailsWith<IllegalArgumentException> { TrendRange(range.newest, range.oldest) }
        assertFailsWith<IllegalArgumentException> { TrendRange(LocalDate.parse("2020-01-01"), LocalDate.parse("2021-01-01")) }
        assertFailsWith<IllegalArgumentException> { Trends.report(HistoryResponse(emptyList(), emptyList()), range, "bad/selector", today, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Trends.report(HistoryResponse(emptyList(), emptyList()), range, null, range.oldest, emptyList()) }
    }
}

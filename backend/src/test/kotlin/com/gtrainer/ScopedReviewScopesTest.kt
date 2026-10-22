package com.gtrainer

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ScopedReviewScopesTest {
    private val asOf = Instant.parse("2026-10-06T12:00:00Z")
    private fun resolve(scope: String, anchor: String? = null, days: Int? = null, oldest: String? = null, newest: String? = null,
                       comparison: ScopedDateRange? = null, mode: String = "period", intent: String = "review",
                       ids: List<String> = emptyList(), activities: Map<String, ActivityRecord> = emptyMap(), night: String? = null) =
        ReviewScopeResolver.resolve(ScopedReviewRequest(mode=mode, intent=intent, scope=scope, anchorDate=anchor, days=days,
            oldest=oldest, newest=newest, comparison=comparison, activityIds=ids), asOf, "America/New_York", activities, night)

    @Test fun `rolling and legacy semantics preserve requested dates`() {
        assertEquals(ScopedDateRange("2026-09-07", "2026-10-06"), resolve("rolling", days=30).evidenceRange)
        assertEquals(ScopedDateRange("2026-09-30", "2026-10-06"), resolve("rolling", days=7).evidenceRange)
        assertEquals(ScopedDateRange("2025-10-07", "2026-10-06"), resolve("rolling", days=365).evidenceRange)
        val historic = ReviewScopeResolver.legacy(ReviewScope("week", 7), LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-06"))
        assertEquals(LocalDate.parse("2026-09-28") to LocalDate.parse("2026-10-04"), historic)
        assertEquals(LocalDate.parse("2026-09-28") to LocalDate.parse("2026-10-04"),
            ReviewScopeResolver.legacy(ReviewScope("week", 7), LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-06")))
        assertEquals(LocalDate.parse("2026-07-09") to LocalDate.parse("2026-10-06"),
            ReviewScopeResolver.legacy(ReviewScope("rolling", 90), LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-06")))
    }

    @Test fun `calendar windows are partial and leap aware`() {
        assertEquals(ScopedDateRange("2026-10-05", "2026-10-06"), resolve("calendar_week").evidenceRange)
        assertEquals(ScopedDateRange("2026-10-05", "2026-10-11"), resolve("calendar_week").fullCalendarRange)
        assertEquals(ScopedDateRange("2026-10-01", "2026-10-06"), resolve("calendar_month").evidenceRange)
        val leap = ReviewScopeResolver.resolve(ScopedReviewRequest(scope="calendar_year", anchorDate="2024-12-31"), asOf, "UTC")
        assertEquals(366, leap.evidenceRange!!.range().days)
        assertEquals("2024-01-01", leap.fullCalendarRange!!.oldest)
    }

    @Test fun `calendar ordinal keeps actual prior window and remainder`() {
        val partial = ReviewScopeResolver.resolve(ScopedReviewRequest(scope="calendar_month", anchorDate="2026-03-28"), asOf, "UTC")
        assertEquals(ScopedDateRange("2026-02-01", "2026-02-28"), partial.comparison.comparablePrevious)
        val completed = ReviewScopeResolver.resolve(ScopedReviewRequest(scope="calendar_month", anchorDate="2026-02-28"), asOf, "UTC")
        assertEquals("2026-01-31", completed.comparison.priorCalendarFull!!.newest)
        assertTrue(completed.comparison.unequalDuration)
        assertEquals(listOf("2026-01-29", "2026-01-30", "2026-01-31"), completed.comparison.priorCalendarRemainderDates)
    }

    @Test fun `explicit comparison exposes overlap and duration`() {
        val result=resolve("custom", oldest="2026-09-01", newest="2026-09-10", comparison=ScopedDateRange("2026-09-05", "2026-09-20"))
        assertTrue(result.comparison.overlapping)
        assertTrue(result.comparison.unequalDuration)
        val later=resolve("custom",oldest="2026-09-01",newest="2026-09-10",comparison=ScopedDateRange("2026-09-20","2026-09-25"))
        assertFalse(later.comparison.overlapping)
        assertEquals("2026-09-25",later.comparison.comparisonFull!!.newest)
        assertEquals(366,ScopedDateRange("2025-01-01","2026-01-01").range().days)
    }

    @Test fun `strict ranges ids and contradictory fields reject`() {
        assertFailsWith<IllegalArgumentException> { ScopedDateRange("0000-01-01", "0000-01-02").range() }
        assertFailsWith<IllegalArgumentException> { ScopedDateRange("2026-01-01", "2027-01-02").range() }
        assertFailsWith<IllegalArgumentException> { resolve("rolling", days=31) }
        assertFailsWith<IllegalArgumentException> { resolve("activities", mode="activities", ids=listOf("bad")) }
        assertFailsWith<IllegalArgumentException> { resolve("day", days=7) }
        assertFailsWith<IllegalArgumentException> { resolve("rolling") }
        assertFailsWith<IllegalArgumentException> { resolve("night", days=7) }
        assertFailsWith<IllegalArgumentException> { resolve("custom", oldest="2026-01-01", newest="2026-01-02", anchor="2026-01-02") }
        assertFailsWith<IllegalArgumentException> { resolve("activities", mode="activities", ids=listOf("a".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { resolve("activities", mode="activities", ids=listOf("A".repeat(64))) }
        val duplicate="a".repeat(64)
        assertFailsWith<IllegalArgumentException> { resolve("activities", mode="activities", intent="compare", ids=listOf(duplicate,duplicate)) }
        assertFailsWith<IllegalArgumentException> { resolve("activities", mode="activities", ids=List(1001) { it.toString(16).padStart(64,'0') }) }
    }

    @Test fun `night requires trusted mapping`() {
        val missing=resolve("night")
        assertEquals("needs_confirmation", missing.status); assertEquals("night_mapping_unverified", missing.reasons.single()); assertNull(missing.evidenceRange)
        assertEquals(ScopedDateRange("2026-10-05", "2026-10-05"), resolve("night", anchor="2026-10-05", night="2026-10-05").evidenceRange)
        val wakeDate = resolve("night", anchor="2026-10-04", night="2026-10-05")
        assertEquals("2026-10-04", wakeDate.anchorDate)
        assertEquals("2026-10-05", wakeDate.evidenceRange!!.oldest)
    }

    @Test fun `year boundary preceding period is unavailable not clipped`() {
        val result=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day", anchorDate="0001-01-01"), Instant.parse("0001-01-01T12:00:00Z"), "UTC")
        assertEquals("comparison_unavailable", result.comparison.reason)
        assertFailsWith<IllegalArgumentException> { resolve("calendar_year", anchor="9999-12-31") }
        assertFailsWith<IllegalArgumentException> {
            ReviewScopeResolver.resolve(ScopedReviewRequest(scope="calendar_week",anchorDate="9999-12-31"), Instant.parse("9999-12-31T12:00:00Z"), "UTC")
        }
    }

    private fun activity(date:String, instant:String?=null) = ActivityRecord("source","record", emptySet(),"Run",date,instant,
        "UTC",null,null,"source_local",null,null,null,null,null,null)

    @Test fun `activities preserve exact dates, enforce spans and confirm future timestamps`() {
        val a="a".repeat(64); val b="b".repeat(64)
        val records=mapOf(a to activity("2026-10-05T23:00:00", "2026-10-06T03:00:00Z"), b to activity("2026-10-06T08:00:00", "2026-10-06T12:00:00Z"))
        val resolved=resolve("activities",mode="activities",intent="compare",ids=listOf(a,b),activities=records)
        assertEquals(listOf("2026-10-05","2026-10-06"),resolved.activityDates)
        assertEquals("selected_sessions",resolved.comparison.policy)
        assertEquals("available",resolved.status)
        val future=resolve("activities",mode="activities",ids=listOf(a),activities=mapOf(a to activity("2026-10-05T23:00:00","2026-10-07T00:00:00Z")))
        assertEquals("needs_confirmation",future.status)
        assertEquals("activity_time_needs_confirmation",future.reasons.single())
        val noInstant=resolve("activities",mode="activities",ids=listOf(a),activities=mapOf(a to activity("2026-10-07T00:00:00")))
        assertEquals("needs_confirmation",noInstant.status)
        val verified=resolve("activities",mode="activities",ids=listOf(a),activities=mapOf(a to activity("2026-10-07T00:00:00","2026-10-06T12:00:00Z")))
        assertEquals("available",verified.status)
        assertEquals(listOf("activity_source_date_ahead_of_schedule_date"), verified.reasons)
        assertFailsWith<IllegalArgumentException> { resolve("activities",mode="activities",ids=listOf(a),activities=emptyMap()) }
        val wideIds=listOf(a,b)
        val wide=mapOf(a to activity("2025-10-05T12:00:00"),b to activity("2026-10-06T12:00:00"))
        assertFailsWith<IllegalArgumentException> { resolve("activities",mode="activities",intent="compare",ids=wideIds,activities=wide) }
        val maxSpan=mapOf(a to activity("2025-10-06T12:00:00"),b to activity("2026-10-06T12:00:00"))
        resolve("activities",mode="activities",intent="compare",ids=wideIds,activities=maxSpan)
    }

    @Test fun `so far uses today cutoff within current window`() {
        assertTrue(resolve("calendar_month",anchor="2026-10-05").soFar)
        assertTrue(resolve("day").soFar)
        assertTrue(resolve("custom",oldest="2026-10-01",newest="2026-10-06").soFar)
        assertTrue(resolve("rolling",days=7,anchor="2026-10-06").soFar)
    }

    @Test fun `comparison policy names and calendar leap remainder are stable`() {
        assertEquals("preceding_equal_length",resolve("day").comparison.policy)
        assertEquals("explicit",resolve("custom",oldest="2026-09-01",newest="2026-09-02",comparison=ScopedDateRange("2026-08-30","2026-08-31")).comparison.policy)
        val leap=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="calendar_year",anchorDate="2024-12-31"),asOf,"UTC")
        assertEquals("preceding_calendar",leap.comparison.policy)
        assertEquals("2024-12-31",leap.comparison.unmatchedSelectedDates.single())
        val march=ReviewScopeResolver.resolve(ScopedReviewRequest(scope="calendar_month",anchorDate="2026-03-30"),asOf,"UTC")
        assertEquals("2026-02-28",march.comparison.comparablePrevious!!.newest)
    }

    @Test fun `valid year endpoints and exact activity capacity remain supported`() {
        val last = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "calendar_year", anchorDate = "9999-12-31"),
            Instant.parse("9999-12-31T12:00:00Z"), "UTC")
        assertEquals(ScopedDateRange("9999-01-01", "9999-12-31"), last.evidenceRange)
        val ids = List(1000) { it.toString(16).padStart(64, '0') }
        val records = ids.associateWith { activity("2026-10-05T09:00:00") }
        val selected = resolve("activities", mode = "activities", ids = ids, activities = records)
        assertEquals(ids, selected.activityIds)
        assertEquals(1000, selected.activityDates.size)
        assertFailsWith<IllegalArgumentException> {
            resolve("activities", mode = "activities", intent = "compare", ids = ids.take(1), activities = records)
        }
        assertFailsWith<IllegalArgumentException> {
            resolve("activities", mode = "activities", anchor = "2026-10-05", ids = ids.take(1), activities = records)
        }
        assertEquals("2026-10-05", resolve("day", anchor = "2026-10-05").anchorDate)
    }
}

package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PlansTest {
    private fun schedule(day: String = "2024-01-10", zone: String = "UTC") =
        PlanSchedule("date_only", date = day, timeZone = zone)

    private fun session(
        schedule: PlanSchedule = schedule(), targets: List<PlannedTarget> = emptyList(),
        permissions: AmendmentPermissions = AmendmentPermissions()
    ) = PlannedSession("00000000-0000-0000-0000-000000000001", PlanAuthor("user", "Athlete"), "run", "Easy run", schedule, targets, permissions = permissions)

    private fun rejected(block: () -> Any?) {
        val error=assertFails(block=block)
        assertTrue(error is IllegalArgumentException || error is java.time.DateTimeException)
    }

    @Test fun authoredProvenanceIsBoundedAndNotPresentedAsMedicalClearance() {
        assertEquals("PlanAuthor([REDACTED])", PlanAuthor("user", "Private Name").toString())
        assertEquals("PlanAuthor([REDACTED])", PlanAuthor("coach", "Private Name").toString())
        rejected { PlanAuthor("system", "Author") }
        rejected { PlanAuthor("medical", "Clinician") }
        val request = AuthoredPlanRequest("Plan", PlanAuthor("user", "Athlete"), listOf(session()))
        assertTrue(request.toString().contains("REDACTED"))
        assertFalse(request.toString().contains("Athlete"))
        assertFalse(request.toString().contains("medical clearance"))
    }

    @Test fun sessionsDefaultToNoAmendmentPermissions() {
        val p = session().permissions
        assertEquals(null, p.move)
        assertEquals(null, p.reduceDuration)
        assertFalse(p.omit)
        assertEquals(AmendmentPermissions(omit = true), AmendmentPermissions(omit = true))
    }

    @Test fun targetMetricUnitBasisAndBoundsAreStrict() {
        assertEquals(0.0, PlannedTarget("distance", "metres", "session_total", 0.0, 0.0).minimum)
        assertEquals(300.0, PlannedTarget("average_heart_rate", "beats/minute", "session_average", 100.0, 300.0).maximum)
        assertEquals(10000.0, PlannedTarget("intervals_training_load", "Intervals.icu load", "intervals_session_load", 1.0, 10000.0).maximum)
        listOf(
            { PlannedTarget("average_heart_rate", "beats/minute", "intensity", 100.0, 150.0) },
            { PlannedTarget("intervals_training_load", "Intervals.icu load", "effort", 1.0, 20.0) },
            { PlannedTarget("distance", "km", "session_total", 1.0, 5.0) },
            { PlannedTarget("unknown", "units", "unknown", 0.0, 1.0) },
            { PlannedTarget("duration", "seconds", "moving", 10.0, 2678401.0) },
            { PlannedTarget("duration", "seconds", "moving", -1.0, 10.0) },
            { PlannedTarget("duration", "seconds", "moving", 2.0, 1.0) },
            { PlannedTarget("duration", "seconds", "moving", Double.NaN, 1.0) },
            { PlannedTarget("duration", "seconds", "moving", 1.0, Double.POSITIVE_INFINITY) },
            { PlannedTarget("duration", "seconds", "moving", 1.5, 2.0) }
        ).forEach { rejected(it) }
    }

    @Test fun movingAndElapsedDurationsRemainDistinctAndTargetsCannotDuplicate() {
        val moving = PlannedTarget("duration", "seconds", "moving", 300.0, 600.0)
        val elapsed = PlannedTarget("duration", "seconds", "elapsed", 300.0, 600.0)
        assertEquals("moving", moving.basis)
        assertEquals("elapsed", elapsed.basis)
        rejected { session(targets = listOf(moving, moving)) }
    }

    @Test fun reportedEffortNeedsExplicitWellFormedScaleAndAlignedValues() {
        val scaleId="00000000-0000-0000-0000-000000000002"
        val scale = EffortScale(scaleId, "RPE", 1.0, 10.0, 1.0, true)
        assertEquals(scale, PlannedTarget("reported_effort", "scale_points", "reported", 2.0, 8.0, scale).effortScale)
        rejected { PlannedTarget("reported_effort", "scale_points", "reported", 2.0, 8.0) }
        rejected { PlannedTarget("reported_effort", "scale_points", "reported", 2.5, 8.0, scale) }
        rejected { PlannedTarget("reported_effort", "scale_points", "reported", 2.0, 11.0, scale) }
        rejected { EffortScale(scaleId, "RPE", 1.0, 10.0, 1.0, false) }
        rejected { EffortScale(scaleId, "RPE", 1.0, 10.0, 0.0, true) }
        rejected { EffortScale(scaleId, "RPE", 1.0, Double.POSITIVE_INFINITY, 1.0, true) }
    }

    @Test fun durationReductionRequiresMatchingTargetContainingFullBounds() {
        val target = PlannedTarget("duration", "seconds", "moving", 1200.0, 1800.0)
        val bounds = DurationReductionBounds("moving", 600, 2400)
        assertEquals(bounds, session(targets = listOf(target), permissions = AmendmentPermissions(reduceDuration = bounds)).permissions.reduceDuration)
        rejected { session(targets = listOf(target), permissions = AmendmentPermissions(reduceDuration = DurationReductionBounds("elapsed", 600, 2400))) }
        rejected { session(targets = listOf(target), permissions = AmendmentPermissions(reduceDuration = DurationReductionBounds("moving", 1300, 2400))) }
        rejected { DurationReductionBounds("moving", 0, 100) }
        rejected { DurationReductionBounds("moving", 1, 2678401) }
    }

    @Test fun dateOnlyMoveWindowUsesDatesAndRejectsMalformedOrOversizedSpans() {
        val window = PlanMoveWindow("2024-01-09", "2024-02-01")
        assertEquals(window, session(permissions = AmendmentPermissions(move = window)).permissions.move)
        rejected { session(permissions = AmendmentPermissions(move = PlanMoveWindow("2024-02-02", "2024-02-01"))) }
        rejected { session(permissions = AmendmentPermissions(move = PlanMoveWindow("2024-01-01T00:00", "2024-01-02"))) }
        rejected { session(permissions = AmendmentPermissions(move = PlanMoveWindow("2024-01-01", "2025-01-03"))) }
    }

    @Test fun timedSchedulesValidateOffsetsSpanAndPrecision() {
        val valid = PlanSchedule("timed", startsLocal = "2024-01-10T10:00", endsLocal = "2024-01-10T11:00", timeZone = "UTC")
        assertTrue(valid.endInstant().isAfter(valid.startInstant()))
        rejected { PlanSchedule("timed", startsLocal = "2024-01-10T10:00:00", endsLocal = "2024-01-10T11:00", timeZone = "UTC") }
        rejected { PlanSchedule("timed", startsLocal = "2024-01-10T10:00", endsLocal = "2025-01-12T10:01", timeZone = "UTC") }
        rejected { PlanSchedule("timed", startsLocal = "2024-01-10T11:00", endsLocal = "2024-01-10T10:00", timeZone = "UTC") }
        rejected { PlanSchedule("date_only", date = "2023-02-29", timeZone = "UTC") }
        rejected { PlanSchedule("date_only", date = "2024-01-10", startsLocal = "2024-01-10T10:00", timeZone = "UTC") }
        rejected { PlanSchedule("date_only", date = "2011-12-30", timeZone = "Pacific/Apia") }
    }

    @Test fun policyHorizonAndGraceAreBoundedAndInclusive() {
        val policy = PlanPolicy("UTC", 1, 0)
        assertEquals(LocalDate.parse("2024-02-29"), policy.horizon(Instant.parse("2024-02-29T12:00:00Z")).second)
        val full = PlanPolicy("UTC", 366, 31622400)
        assertEquals(LocalDate.parse("2025-02-28"), full.horizon(Instant.parse("2024-02-29T12:00:00Z")).second)
        rejected { PlanPolicy("UTC", 0, 0) }
        rejected { PlanPolicy("UTC", 367, 0) }
        rejected { PlanPolicy("UTC", 1, -1) }
        rejected { PlanPolicy("UTC", 1, 31622401) }
        rejected { PlanPolicy("+02:00", 1, 0) }
    }

    @Test fun midnightTravelCutoffUsesEarlierSessionOrAdaptationMidnight() {
        val s = session(schedule = schedule("2024-01-10", "Pacific/Kiritimati"))
        val policy = PlanPolicy("Pacific/Honolulu", 1, 0)
        assertEquals(
            minOf(s.schedule.startInstant(), Instant.parse("2024-01-10T10:00:00Z")),
            amendmentStartCutoff(s, policy)
        )
        assertEquals(s.schedule.startInstant(), amendmentStartCutoff(s, PlanPolicy("UTC", 1, 0)))
    }

    @Test fun unmatchedDeadlineIncludesNextDateEndAndConfiguredGrace() {
        val s = session(schedule = schedule("2024-01-10"))
        assertEquals(Instant.parse("2024-01-12T00:00:00Z"), unmatchedAfter(s, PlanPolicy("UTC", 1, 86400)))
    }

    @Test fun timedCutoffIsScheduledStartAndOverlapLastDateIsExclusiveAtMidnight() {
        val s = session(schedule = PlanSchedule("timed", startsLocal = "2024-01-10T23:00", endsLocal = "2024-01-12T00:00", timeZone = "UTC"))
        assertEquals(Instant.parse("2024-01-10T23:00:00Z"), amendmentStartCutoff(s, PlanPolicy("UTC", 1, 0)))
        assertEquals(LocalDate.parse("2024-01-11"), s.schedule.lastDate())
        assertEquals(Instant.parse("2024-01-12T00:00:00Z"), unmatchedAfter(s, PlanPolicy("UTC", 1, 0)))
    }

    @Test fun daylightSavingGapsAreRejectedAndAmbiguousTimesRequireValidOffsets() {
        rejected { PlanSchedule("timed", startsLocal = "2024-03-10T02:30", endsLocal = "2024-03-10T03:30", timeZone = "America/New_York") }
        rejected { PlanSchedule("timed", startsLocal = "2024-11-03T01:30", endsLocal = "2024-11-03T02:30", timeZone = "America/New_York") }
        val chosen = PlanSchedule("timed", startsLocal = "2024-11-03T01:30", endsLocal = "2024-11-03T02:30", timeZone = "America/New_York", startOffset = "-04:00", endOffset = "-05:00")
        assertTrue(chosen.endInstant().isAfter(chosen.startInstant()))
        rejected { PlanSchedule("timed", startsLocal = "2024-11-03T01:30", endsLocal = "2024-11-03T02:30", timeZone = "America/New_York", startOffset = "+02:00", endOffset = "-05:00") }
    }

    @Test fun strictJsonRejectsUnknownFieldsAndDefinitionsStayRedacted() {
        val strict = Json { ignoreUnknownKeys = false }
        assertFails { strict.decodeFromString<AuthoredPlanRequest>("""{"title":"x","author":{"authorKind":"user","authorAttribution":"A"},"sessions":[],"surprise":true}""") }
        val target = PlannedTarget("duration", "seconds", "moving", 60.0, 120.0)
        val s = session(targets = listOf(target)).copy(notes = "private note")
        assertFalse(s.toString().contains("private note"))
        assertFalse(target.toString().contains("moving"))
        assertFalse(schedule().toString().contains("2024-01-10"))
    }
}

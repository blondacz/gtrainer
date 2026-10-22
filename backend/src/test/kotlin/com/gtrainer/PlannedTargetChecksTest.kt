package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlannedTargetChecksTest {
    private val scale = EffortScale("00000000-0000-4000-8000-000000000001", "Synthetic effort", 1.0, 10.0, 1.0, true)
    private val planRef = PlanRef("00000000-0000-4000-8000-000000000002", 1)

    private fun target(metric: String = "duration", unit: String = "seconds", basis: String = "moving",
                       minimum: Double = 10.0, maximum: Double = 20.0, effort: EffortScale? = null) =
        PlannedTarget(metric, unit, basis, minimum, maximum, effort)

    private fun record(moving: Measurement? = Measurement(15.0, "seconds"),
                       elapsed: Measurement? = Measurement(30.0, "seconds"), distance: Measurement? = null,
                       heartRate: Measurement? = null, load: Measurement? = null, source: String = "synthetic") =
        ActivityRecord(source, "synthetic-record", emptySet(), "Run", "2026-01-01T10:00", null, null, null,
            null, "unknown", moving, elapsed, null, distance, heartRate, load)

    private fun evaluate(t: PlannedTarget, r: ActivityRecord = record(), state: String = "matched",
                         identityOverride: ReconciliationActivity? = null,
                         reports: List<ActivityEffortReport> = emptyList(),
                         objective: String = "Run easy", notes: String? = null): PlannedTargetCheck {
        val recordSha = AnalysisClaims.hash(Json { allowSpecialFloatingPointValues=true }.encodeToString(r))
        val id = scopedActivityId(r)
        val identity = identityOverride ?: ReconciliationActivity(id, 1, recordSha, r.sport, r.startLocal,
            r.startInstant, r.timeContext)
        val plan = AuthoredPlan(planRef, null,
            AuthoredPlanRequest("Synthetic", PlanAuthor("user", "test"), listOf(session(t, objective, notes))),
            "authenticated_user", "2026-01-01T00:00:00Z")
        val s = plan.definition.sessions.single()
        val finding = PlanSessionFinding(s.sessionId, s.sport, s.objective, s.schedule, state,
            "synthetic", if (state == "matched") id else null, emptyList(), null, null)
        return PlannedTargetChecks.evaluate(plan, s, finding, identity, r, reports).single()
    }

    private fun session(t: PlannedTarget, objective: String = "Run easy", notes: String? = null) =
        PlannedSession("00000000-0000-4000-8000-000000000004", PlanAuthor("user", "test"), "Run", objective,
            PlanSchedule("date_only", date = "2026-01-01", timeZone = "UTC"), listOf(t), notes)

    private fun report(value: Double = 5.0, scaleValue: EffortScale = scale, revision: Int = 1,
                       previous: Int? = null, withdrawn: Boolean = false, activityRevision: Long? = 1,
                       activitySha: String? = null): ActivityEffortReport {
        val r = record()
        return ActivityEffortReport("00000000-0000-4000-8000-000000000003", revision, previous, scopedActivityId(r), activityRevision,
            activitySha ?: AnalysisClaims.hash(Json.encodeToString(r)), scaleValue, value, "2026-01-01",
            "Athlete", "authenticated_user", "2026-01-01T00:00:00Z", withdrawn)
    }

    @Test fun movingAndElapsedAreIndependentAndMissingMovingDoesNotFallback() {
        val missing = evaluate(target(), record(moving = null, elapsed = Measurement(15.0, "seconds")))
        assertEquals("unevaluable", missing.status); assertEquals("measurement_missing", missing.reason)
        assertEquals("moving", missing.actualBasis)
        val elapsed = evaluate(target(basis = "elapsed"), record(moving = null, elapsed = Measurement(15.0, "seconds")))
        assertEquals("within_range", elapsed.status); assertEquals("elapsedTime", elapsed.actualField)
        assertEquals("elapsed", elapsed.actualBasis)
    }

    @Test fun authoredEndpointsAreInclusiveAndPointTargetHasNoTolerance() {
        assertEquals("within_range", evaluate(target(minimum = 15.0, maximum = 15.0)).status)
        assertEquals("below_range", evaluate(target(minimum = 15.0, maximum = 15.0),
            record(moving = Measurement(14.999999, "seconds"))).status)
        assertEquals("above_range", evaluate(target(minimum = 15.0, maximum = 15.0),
            record(moving = Measurement(15.000001, "seconds"))).status)
    }

    @Test fun zeroActualIsValidForPositivePlannedRange() {
        val result = evaluate(target("distance", "metres", "session_total", 1.0, 2.0),
            record(distance = Measurement(0.0, "metres")))
        assertEquals("below_range", result.status); assertEquals(0.0, result.actualValue)
    }

    @Test fun invalidUnitsNonFiniteAndUnsupportedLoadSourceFailClosed() {
        assertEquals("unit_mismatch", evaluate(target(), record(moving = Measurement(15.0, "minutes"))).reason)
        assertEquals("activity_evidence_unavailable", evaluate(target(), record(moving = Measurement(Double.NaN, "seconds"))).reason)
        val loadTarget = target("intervals_training_load", "Intervals.icu load", "intervals_session_load", 1.0, 10.0)
        val unsupported = evaluate(loadTarget, record(load = Measurement(5.0, "Intervals.icu load"), source = "synthetic"))
        assertEquals("unsupported_source_basis", unsupported.reason)
    }

    @Test fun intervalsLoadMayExceedAuthoredTargetMaximum() {
        val result = evaluate(target("intervals_training_load", "Intervals.icu load", "intervals_session_load", 1.0, 10.0),
            record(load = Measurement(20_000.0, "Intervals.icu load"), source = "intervals.icu"))
        assertEquals("above_range", result.status); assertEquals(20_000.0, result.actualValue)
    }

    @Test fun reportedEffortUsesOnlyAuthenticatedScaleReportNotOtherMetricsOrProse() {
        val effort = target("reported_effort", "scale_points", "reported", 4.0, 6.0, scale)
        val r = record(heartRate = Measurement(300.0, "beats/minute"), load = Measurement(100_000.0, "Intervals.icu load"))
        val result = evaluate(effort, r, reports = listOf(report(activitySha=AnalysisClaims.hash(Json.encodeToString(r)))), objective = "hard interval", notes = "I felt 10/10")
        assertEquals("within_range", result.status); assertEquals(5.0, result.actualValue)
        assertEquals("authenticated_user", result.source); assertEquals("reported", result.actualBasis)
        assertNull(evaluate(effort, r, reports = emptyList(), objective = "effort 5", notes = "RPE 5").actualValue)
    }

    @Test fun ambiguousOrStaleConfirmationNeverEvaluatesCandidates() {
        val r = record()
        val stale = ReconciliationActivity("a".repeat(64), 1, "b".repeat(64), r.sport, r.startLocal, null, "unknown")
        val ambiguous = evaluate(target(), r, state = "ambiguous")
        assertEquals("unevaluable", ambiguous.status); assertEquals("match_unresolved", ambiguous.reason)
        val changed = evaluate(target(), r, state = "needs_confirmation", identityOverride = stale)
        assertEquals("unevaluable", changed.status); assertEquals("confirmation_evidence_changed", changed.reason)
        assertNull(changed.actualValue)
    }

    @Test fun effortScaleDefinitionAndActivityEvidenceMustMatch() {
        val targetScale = scale.copy(name = "Different definition")
        val mismatch = evaluate(target("reported_effort", "scale_points", "reported", 4.0, 6.0, targetScale),
            reports = listOf(report()))
        assertEquals("effort_scale_mismatch", mismatch.reason)
        val changedRecord = evaluate(target("reported_effort", "scale_points", "reported", 4.0, 6.0, scale),
            reports = listOf(report(activitySha = "c".repeat(64))))
        assertEquals("activity_evidence_unavailable", changedRecord.reason)
    }

    @Test fun withdrawnHeadDoesNotResurrectPreviousReport() {
        val effort = target("reported_effort", "scale_points", "reported", 4.0, 6.0, scale)
        val prior = report()
        val withdrawnHead = report(value = 5.0, revision = 2, previous = 1, withdrawn = true)
        val result = evaluate(effort, reports = listOf(prior, withdrawnHead))
        assertEquals("effort_report_withdrawn", result.reason)
    }

    @Test fun changedActivityRevisionStalesEffortReport() {
        val effort = target("reported_effort", "scale_points", "reported", 4.0, 6.0, scale)
        val stale = report(activityRevision = 2)
        val result = evaluate(effort, reports = listOf(stale))
        assertEquals("activity_evidence_unavailable", result.reason)
    }
}

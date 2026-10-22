package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtraWorkloadRulesTest {
    private val at = Instant.parse("2026-03-10T12:00:00Z")
    private val plan = PlanRef("11111111-1111-1111-1111-111111111111", 1)

    private fun config(aggregation: String = "per_record", comparator: String = "greater_than",
                       threshold: Double = 1000.0, metric: String = "duration", unit: String = "seconds",
                       basis: String = "moving", source: String = "intervals.icu", origin: String? = null,
                       days: Int = 10) = ExtraWorkloadConfiguration(aggregation, metric, unit, basis,
        "ride", source, origin, "UTC", days, comparator, threshold)

    private fun rule(c: ExtraWorkloadConfiguration? = config(), enabled: Boolean = c != null) =
        ExtraWorkloadRule("22222222-2222-4222-8222-222222222222", 1, enabled, c, "2026-03-01T00:00:00Z")

    private fun record(id: String, start: String, duration: Double = 1200.0, source: String = "intervals.icu") =
        syntheticActivity().copy(sourceRecordId = id, source = source, sport = "ride",
            startLocal = start.removeSuffix("Z"), startInstant = start, timeContext="upstream_instant", timeZone="UTC",
            movingTime = Measurement(duration, "seconds"))

    private fun core(records: List<ActivityRecord>, states: List<String> = List(records.size) { "extra_relative_to_selected_plan" }): PlanReconciliationCore {
        val findings = records.mapIndexed { i, record ->
            val identity = ReconciliationActivity(scopedActivityId(record), null,
                AnalysisClaims.hash(Json.encodeToString(record)), record.sport, record.startLocal, record.startInstant, record.timeContext)
            PlanActivityFinding(identity, states[i], emptyList())
        }
        return PlanReconciliationCore(PlanSelection(1, plan), emptyList(), findings, emptyList(), "a".repeat(64))
    }

    private fun evaluate(c: ExtraWorkloadConfiguration?, records: List<ActivityRecord>,
                         states: List<String> = List(records.size) { "extra_relative_to_selected_plan" },
                         retained: Set<String> = emptySet()) = ExtraWorkloadRules.evaluate(
        rule(c), at, core(records, states), records.associateBy(::scopedActivityId), retained)

    @Test fun disabledRuleHasNoFindingsOrThresholdDefaults() {
        val result = evaluate(null, listOf(record("a", "2026-03-10T10:00:00Z")))
        assertEquals("disabled", result.status)
        assertEquals(emptyList(), result.items)
    }

    @Test fun perRecordComparatorDistinguishesStrictAndInclusiveEquality() {
        val activity = record("a", "2026-03-10T10:00:00Z", 1000.0)
        assertEquals("clear", evaluate(config(threshold = 1000.0), listOf(activity)).status)
        assertEquals("triggered", evaluate(config(comparator = "at_least", threshold = 1000.0), listOf(activity)).status)
        assertEquals("clear", evaluate(config(threshold = 1001.0), listOf(activity)).status)
    }

    @Test fun cumulativeSumsOnlyCompatibleExtraEvidenceAndEqualityHonorsComparator() {
        val records = listOf(record("a", "2026-03-09T10:00:00Z", 500.0), record("b", "2026-03-10T10:00:00Z", 500.0))
        val result = evaluate(config("cumulative", "at_least", 1000.0), records)
        assertEquals("triggered", result.status)
        assertEquals(1000.0, result.value)
        assertEquals("clear", evaluate(config("cumulative", "greater_than", 1000.0), records).status)
        assertEquals(500.0,evaluate(config("cumulative"), records, listOf("extra_relative_to_selected_plan", "matched")).value)
    }

    @Test fun incompatibleSourceAndChangedRecordFingerprintAreUnevaluable() {
        val activity = record("a", "2026-03-10T10:00:00Z", source = "other")
        val sourceResult = evaluate(config(source = "intervals.icu"), listOf(activity))
        assertTrue(sourceResult.items.isEmpty()) // Other explicitly configured sources are not blended.
        val original = record("b", "2026-03-10T10:00:00Z")
        val changed = original.copy(movingTime = Measurement(1400.0, "seconds"))
        val identityCore = core(listOf(original))
        val mismatch = ExtraWorkloadRules.evaluate(rule(), at, identityCore, mapOf(scopedActivityId(original) to changed))
        assertEquals("unevaluable", mismatch.items.single().status)
        assertEquals("activity_evidence_unavailable", mismatch.items.single().reason)
    }

    @Test fun futureAndUnknownTimingRemainConfirmationStatesAndOutsideWindowClears() {
        val future = record("a", "2026-03-11T10:00:00Z")
        assertEquals("needs_confirmation", evaluate(config(), listOf(future),retained=setOf(scopedActivityId(future))).items.single().status)
        val outside = record("b", "2026-02-01T10:00:00Z")
        assertEquals("clear", evaluate(config(), listOf(outside),retained=setOf(scopedActivityId(outside))).items.single().status)
        val untimed = future.copy(startInstant = null)
        assertEquals("needs_confirmation", evaluate(config(), listOf(untimed), retained = setOf(scopedActivityId(untimed))).items.single().status)
    }

    @Test fun workloadDoesNotInventEffortOrConvertMetricsAndUnknownSupportBlocksSums() {
        assertFailsWith<IllegalArgumentException> { config(metric="average_heart_rate",unit="beats/minute",basis="session_average") }
        assertFailsWith<IllegalArgumentException> { config(metric="duration",unit="minutes") }
        assertFailsWith<IllegalArgumentException> { config(metric="intervals_training_load",unit="Intervals.icu load",basis="intervals_session_load",source="other") }
        val a=record("a","2026-03-10T10:00:00Z",600.0)
        val missing=record("b","2026-03-10T11:00:00Z",600.0).copy(movingTime=null,elapsedTime=Measurement(600.0,"seconds"))
        val result=evaluate(config("cumulative"),listOf(a,missing))
        assertEquals("unevaluable",result.status);assertNull(result.value)
        val origin=a.copy(movingTime=Measurement(600.0,"seconds","different"))
        assertEquals("origin_mismatch",evaluate(config(),listOf(origin)).items.single().reason)
        val unknown=a.copy(startInstant=null,timeContext="local_time_only")
        assertEquals("unevaluable",evaluate(config("cumulative"),listOf(unknown)).status)
        assertEquals("matched_to_selected_plan",evaluate(config(),listOf(unknown),listOf("matched")).items.single().reason)
        assertEquals("clear",evaluate(config("cumulative",comparator="at_least",threshold=0.0),emptyList()).status)
        val records=listOf(a,record("c","2026-03-09T10:00:00Z",600.0))
        assertEquals(evaluate(config("cumulative"),records),evaluate(config("cumulative"),records.reversed()))
    }
}

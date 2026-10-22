package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class PersonalSignalRulesTest {
    private val selector = PersonalSignalSelector("hrv", "ms", "Intervals.icu", "garmin", "daily", "mean")
    private val identity = selector.desiredIdentity()
    private fun config(direction: String = "low", type: String = "absolute_limit", trigger: Double = 30.0,
                      reset: Double = 40.0, triggerDays: Int = 1, resetDays: Int = 1,
                      baselineDays: Int = 2, minimum: Int = 1, coverage: Double = 0.0, age: Int = 366) =
        PersonalSignalConfiguration("UTC", selector, PersonalSignalBaseline(baselineDays, minimum, coverage, age),
            direction, type, trigger, reset, triggerDays, resetDays, 0)
    private fun sample(date: String, value: Double, id: String = date, signal: ScopedSignalIdentity = identity) =
        ScopedSignalSample(signal, date, value, id)
    private fun rule(c: PersonalSignalConfiguration? = config(), enabled: Boolean = c != null) =
        PersonalSignalRule("11111111-1111-4111-8111-111111111111", 1, enabled, c, "2025-01-10T00:00:00Z")
    private fun eval(c: PersonalSignalConfiguration = config(), samples: List<ScopedSignalSample>,
                     oldest: String = "2025-01-03", newest: String = oldest) =
        PersonalSignalRules.evaluate(rule(c), PersonalSignalQuery(oldest, newest),
            Instant.parse("2025-01-10T00:00:00Z"), samples)

    @Test fun disabledDefaultRuleIsInertWithoutConfiguration() {
        val result = PersonalSignalRules.evaluate(rule(null, false), PersonalSignalQuery("2025-01-03", "2025-01-03"),
            Instant.parse("2025-01-10T00:00:00Z"), emptyList())
        assertEquals("disabled", result.status)
        assertTrue(result.days.isEmpty())
        assertTrue(result.support.isEmpty())
    }

    @Test fun configurationRejectsUnknownVerificationAuthorityJson() {
        val json = """{"timeZone":"UTC","selector":{"field":"hrv","unit":"ms","intermediary":"x","upstreamOrigin":"g","method":"daily","basis":"mean","verification":"verified"},"baseline":{"trailingDays":1,"minimumSamples":1,"minimumCoverage":0.0,"maximumAgeDays":1},"direction":"low","boundaryType":"absolute_limit","triggerMagnitude":1.0,"resetMagnitude":2.0,"triggerPersistenceDays":1,"resetPersistenceDays":1,"cooldownSeconds":0}"""
        assertFailsWith<Exception> { Json.decodeFromString<PersonalSignalConfiguration>(json) }
    }

    @Test fun selectorsAndHysteresisRequireCompatibleUnitsAndStrictReset() {
        assertFailsWith<IllegalArgumentException> { PersonalSignalSelector("sleepSecs", "ms", "x", "g", "daily", "mean") }
        assertFailsWith<IllegalArgumentException> { config(trigger = 40.0, reset = 40.0) }
        assertFailsWith<IllegalArgumentException> { config(direction = "high", trigger = 40.0, reset = 40.0) }
        assertFailsWith<IllegalArgumentException> { config(direction = "high", type = "absolute_deviation", trigger = 5.0, reset = 5.0) }
    }

    @Test fun realDatedVerifiedSamplesUseMedianAndExcludeEvaluationDate() {
        val c = config(baselineDays = 3)
        val r = eval(c, listOf(sample("2025-01-01", 20.0), sample("2025-01-02", 40.0),
            sample("2025-01-03", 10.0), sample("2025-01-04", 30.0)), "2025-01-04")
        assertEquals(20.0, r.days.single().baselineValue)
        assertEquals("2025-01-01", r.days.single().baselineOldest)
        assertEquals("2025-01-03", r.days.single().baselineNewest)
        assertEquals("trigger", r.days.single().status)
    }

    @Test fun boundaryTypesCoverLowHighAndInclusiveEquality() {
        val low = eval(config(trigger = 30.0, reset = 40.0), listOf(sample("2025-01-02",50.0),sample("2025-01-03", 30.0)))
        assertEquals("trigger", low.days.single().status)
        val high = eval(config(direction = "high", trigger = 80.0, reset = 70.0), listOf(sample("2025-01-02",50.0),sample("2025-01-03", 80.0)))
        assertEquals("trigger", high.days.single().status)
        val lowDeviation = eval(config(type = "absolute_deviation", trigger = 10.0, reset = 5.0),
            listOf(sample("2025-01-01", 50.0), sample("2025-01-02", 50.0), sample("2025-01-03", 40.0)))
        assertEquals(40.0, lowDeviation.days.single().triggerBoundary)
        val pct = eval(config(direction = "high", type = "percent_deviation", trigger = 10.0, reset = 5.0),
            listOf(sample("2025-01-01", 50.0), sample("2025-01-02", 50.0), sample("2025-01-03", 55.0)))
        assertEquals("trigger", pct.days.single().status)
    }

    @Test fun baselineCoverageFreshnessAndZeroPercentBaselineAreUnevaluable() {
        val insufficient = eval(config(baselineDays = 2, minimum = 2, coverage = 1.0), listOf(sample("2025-01-02", 20.0)))
        assertEquals("unevaluable", insufficient.days.single().status)
        val stale = eval(config(baselineDays = 2, age = 0), listOf(sample("2025-01-01", 20.0)))
        assertTrue("stale_or_missing" in stale.days.single().reasons)
        val zero = eval(config(type = "percent_deviation", trigger = 10.0, reset = 5.0),
            listOf(sample("2025-01-01", 0.0), sample("2025-01-02", 0.0)))
        assertTrue("zero_percentage_baseline" in zero.days.single().reasons)
    }

    @Test fun finiteAndCollapsedArithmeticDoesNotCreateBoundary() {
        val overflow = eval(config(direction="high",type = "absolute_deviation", trigger = 1e308, reset = 1e307),
            listOf(sample("2025-01-01", 1e308), sample("2025-01-02", 1e308),sample("2025-01-03",1e308)))
        assertTrue("nonfinite_or_collapsed_boundary" in overflow.days.single().reasons)
        assertFailsWith<IllegalArgumentException> { config(trigger = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { eval(samples = listOf(sample("2025-01-03", Double.POSITIVE_INFINITY))) }
    }

    @Test fun unsupportedDaysBreakPersistenceAndDoNotInventReset() {
        val c = config(triggerDays = 2, resetDays = 2)
        val r = eval(c, listOf(sample("2025-01-01", 50.0), sample("2025-01-02", 50.0),
            sample("2025-01-03", 20.0), sample("2025-01-05", 20.0)),
            "2025-01-03", "2025-01-05")
        assertEquals(listOf(1, 0, 1), r.days.map { it.triggerCount })
        assertEquals("unevaluable", r.days[1].status)
        assertEquals(listOf(0, 0, 0), r.days.map { it.resetCount })
    }

    @Test fun incompatibleAmbiguousUnverifiedAndMissingAllBreakCounts() {
        val c = config(triggerDays = 2)
        val good = sample("2025-01-04", 20.0)
        for (bad in listOf(
            listOf(sample("2025-01-04", 20.0, signal = identity.copy(method = "other"))),
            listOf(good, good.copy(evidenceId = "other")),
            listOf(sample("2025-01-04", 20.0, signal = identity.copy(verification = "unknown"))),
            emptyList())) {
            val samples = listOf(sample("2025-01-01", 50.0), sample("2025-01-02", 50.0),sample("2025-01-03",20.0)) + bad
            val result = eval(c, samples, "2025-01-03", "2025-01-04")
            assertEquals(0, result.days.last().triggerCount)
        }
    }

    @Test fun exactDuplicatesDeduplicateAndInputOrderDoesNotChangeSelection() {
        val repeated = sample("2025-01-03", 20.0)
        val a = eval(samples = listOf(sample("2025-01-01", 50.0), sample("2025-01-02", 50.0), repeated, repeated))
        val b = eval(samples = listOf(repeated, sample("2025-01-02", 50.0), repeated, sample("2025-01-01", 50.0)))
        assertEquals(a.days, b.days)
        assertEquals("unique_compatible", a.support.last().status)
    }

    @Test fun actualRawAdapterEvidenceNeverGainsConfiguredVerification() {
        val record = WellnessRecord("source", "id", "2025-01-03", setOf("garmin"),
            mapOf("hrv" to Measurement(40.0, "ms", "garmin")))
        val raw = ScopedWellnessEvidence.raw(listOf(record), emptyList()).single()
        val actual = ScopedSignalSample(ScopedSignalIdentity(raw.field, raw.unit, "Intervals.icu", "garmin", "daily", "mean", raw.verification),
            raw.sourceDate, raw.value, raw.evidenceId)
        val report = eval(samples = listOf(actual))
        assertEquals("unverified", report.support.last().status)
        assertEquals("unevaluable", report.days.single().status)
    }

    @Test fun dateWindowSupportsLeapYearAndBoundedSupportAndRejectsOversize() {
        val leap = PersonalSignalQuery("2024-02-29", "2024-02-29")
        val c = config(baselineDays = 366)
        val report = PersonalSignalRules.evaluate(rule(c), leap, Instant.parse("2025-01-01T00:00:00Z"), emptyList())
        assertEquals(367, report.support.size)
        assertEquals("2023-02-28", report.support.first().date)
        val annual=PersonalSignalRules.evaluate(rule(c),PersonalSignalQuery("2024-01-01","2024-12-31"),Instant.parse("2025-01-01T00:00:00Z"),emptyList())
        assertEquals(366,annual.days.size);assertEquals(732,annual.support.size)
        assertFailsWith<IllegalArgumentException> { PersonalSignalQuery("2024-01-01", "2025-01-01") }
        assertFailsWith<IllegalArgumentException> { PersonalSignalRules.evaluate(rule(), PersonalSignalQuery("2025-01-03", "2025-01-03"),
            Instant.parse("2025-01-10T00:00:00Z"), List(50_001) { sample("2025-01-03", 1.0, "x$it") }) }
    }

    @Test fun sleepAndSdnnRemainSeparateAndResetPersistenceBreaksOnMissingDay() {
        for(field in listOf("sleepSecs","hrvSDNN")) {
            val selected=selector.copy(field=field,unit=if(field=="sleepSecs") "seconds" else "ms")
            val c=config(baselineDays=3,resetDays=2).copy(selector=selected)
            val samples=listOf("2025-01-01","2025-01-02","2025-01-03","2025-01-05").map { date ->
                ScopedSignalSample(selected.desiredIdentity(),date,50.0,date)
            }
            val result=eval(c,samples,"2025-01-03","2025-01-05")
            assertEquals(listOf(1,0,1),result.days.map { it.resetCount })
            assertEquals(listOf("below_persistence","unevaluable","below_persistence"),result.days.map { it.status })
        }
        val collapsed=eval(config(type="absolute_deviation",trigger=2.0,reset=1.0),listOf(
            sample("2025-01-02",1e308),sample("2025-01-03",1e308)))
        assertEquals("unevaluable",collapsed.days.single().status)
        assertTrue("nonfinite_or_collapsed_boundary" in collapsed.days.single().reasons)
    }
}

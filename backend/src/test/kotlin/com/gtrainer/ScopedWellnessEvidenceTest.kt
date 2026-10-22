package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ScopedWellnessEvidenceTest {
    private val identity = ScopedSignalIdentity("hrv", "ms", "Intervals.icu", "garmin", "daily", "mean", "verified")
    private fun sample(date: String, value: Double, id: String = date, signal: ScopedSignalIdentity = identity) =
        ScopedSignalSample(signal, date, value, id)
    private fun config(eval: String = "2025-01-05", days: Int = 4, minimum: Int = 1,
                       coverage: Double = 0.0, age: Int = 366) =
        ScopedBaselineConfig(eval, days, minimum, coverage, age)

    @Test fun baselineUsesTrailingDaysAndEvenMedianIncludingZero() {
        val result = ScopedWellnessEvidence.baseline(identity, config(), listOf(
            sample("2025-01-01", 0.0), sample("2025-01-02", 2.0),
            sample("2025-01-03", 8.0), sample("2025-01-05", 100.0)))
        assertEquals(2.0, result.value)
        assertEquals(3, result.selectedSamples)
        assertEquals(listOf("unique_compatible", "unique_compatible", "unique_compatible", "missing"), result.days.map { it.status })
    }

    @Test fun duplicateDaysAreAmbiguousEvenWhenValuesEqual() {
        val result = ScopedWellnessEvidence.baseline(identity, config(days = 1), listOf(
            sample("2025-01-04", 2.0, "a"), sample("2025-01-04", 2.0, "b")))
        assertNull(result.value)
        assertEquals("ambiguous", result.days.single().status)
    }

    @Test fun mismatchedMethodAndOriginAreNotBlended() {
        val otherMethod = identity.copy(method = "nightly")
        val otherOrigin = identity.copy(upstreamOrigin = "other")
        for (other in listOf(otherMethod, otherOrigin)) {
            val result = ScopedWellnessEvidence.baseline(identity, config(days = 1), listOf(sample("2025-01-04", 4.0, signal = other)))
            assertNull(result.value)
            assertEquals("incompatible", result.days.single().status)
        }
    }

    @Test fun rawEvidenceIsStableAndActivityReferencesAreDeduplicated() {
        val record = WellnessRecord("source", "record", "2025-01-01", setOf("garmin"), mapOf("hrv" to Measurement(0.0, "ms", "garmin")))
        val refs = mapOf("2025-01-01" to listOf("activity", "activity"))
        val first = ScopedWellnessEvidence.raw(listOf(record), emptyList(), refs).single()
        val reimport = ScopedWellnessEvidence.raw(listOf(record.copy(upstreamSources = setOf("different"))), emptyList(), refs).single()
        assertEquals(first.evidenceId, reimport.evidenceId)
        assertNotEquals(first.recordOrigins, reimport.recordOrigins)
        assertEquals(listOf("activity"), first.activityIds)
        assertEquals(0.0, first.value)
    }

    @Test fun insufficientCoverageAndFreshnessNeverBecomeZero() {
        val stale = ScopedWellnessEvidence.baseline(identity, config(days = 3, minimum = 1, coverage = 0.5, age = 1),
            listOf(sample("2025-01-01", 0.0)))
        assertNull(stale.value)
        assertEquals("not_computed", stale.status)
        assertTrue("stale_or_missing" in stale.reasons)
    }

    @Test fun evenMedianFreshnessAndReceiptDatesKeepTheirSeparateMeanings() {
        val first=sample("2025-01-01", 0.0).copy(receivedUtc="2025-02-01T00:00:00Z",revision=1)
        val last=sample("2025-01-04", 8.0).copy(receivedUtc="2025-02-02T00:00:00Z",revision=2)
        val result=ScopedWellnessEvidence.baseline(identity,config(age=1),listOf(first,last))
        assertEquals(4.0,result.value)
        assertEquals("2025-02-01T00:00:00Z",result.days.first().samples.single().receivedUtc)
        assertEquals("2025-01-01",result.days.first().date)
        assertEquals(1L,result.days.first().samples.single().revision)
    }

    @Test fun unverifiedIdentityAndCandidatesRemainInspectableWithoutAnInventedBaseline() {
        val unknown=identity.copy(method=null,verification="unknown")
        val samples=listOf(sample("2025-01-04",4.0,signal=unknown))
        val unknownResult=ScopedWellnessEvidence.baseline(unknown,config(days=1),samples)
        assertNull(unknownResult.value)
        assertEquals("unverified",unknownResult.days.single().status)
        assertTrue("semantics_unverified" in unknownResult.reasons)
        assertEquals("unverified",ScopedWellnessEvidence.baseline(identity,config(days=1),samples).days.single().status)
        assertEquals("incompatible",ScopedWellnessEvidence.baseline(identity,config(days=1),
            listOf(sample("2025-01-04",4.0,signal=identity.copy(field="hrvSDNN")))).days.single().status)
    }

    @Test fun sharedObservationDoesNotAcquireRepeatedWeightButConflictingSameIdentityIsAmbiguous() {
        val observation=sample("2025-01-04",0.0)
        assertEquals(0.0,ScopedWellnessEvidence.baseline(identity,config(days=1),listOf(observation,observation)).value)
        assertEquals("ambiguous",ScopedWellnessEvidence.baseline(identity,config(days=1),
            listOf(observation,observation.copy(value=4.0))).days.single().status)
    }

    @Test fun invalidConfigurationAndYearUnderflowFailClosed() {
        assertFailsWith<IllegalArgumentException> { ScopedWellnessEvidence.baseline(identity,config(days=367),emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScopedWellnessEvidence.baseline(identity,config(eval="0001-01-01"),emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScopedWellnessEvidence.baseline(identity,config(coverage=Double.NaN),emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScopedWellnessEvidence.baseline(identity,config(eval="2025-02-30"),emptyList()) }
    }
}

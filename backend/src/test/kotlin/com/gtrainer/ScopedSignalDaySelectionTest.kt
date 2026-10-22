package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScopedSignalDaySelectionTest {
    private val identity = ScopedSignalIdentity("hrv", "ms", "Intervals.icu", "garmin", "daily", "mean", "verified")

    private fun sample(id: String, signal: ScopedSignalIdentity = identity, value: Double = 2.0) =
        ScopedSignalSample(signal, "2025-01-04", value, id)

    @Test fun deduplicatesExactSamplesButRetainsSortedCandidateOrder() {
        val second = sample("b")
        val first = sample("a")
        val selected = ScopedWellnessEvidence.selectDay(identity, "2025-01-04", listOf(second, first, second))
        assertEquals("ambiguous", selected.status)
        assertEquals(listOf("a", "b"), selected.samples.map { it.evidenceId })
        assertEquals(listOf("a", "b"), selected.evidenceIds)
        assertEquals(2, selected.candidateCount)
    }

    @Test fun conflictingSameIdentityIsAmbiguousWhileMethodMismatchIsIncompatible() {
        val conflict = ScopedWellnessEvidence.selectDay(identity, "2025-01-04", listOf(sample("a"), sample("b", value = 3.0)))
        assertEquals("ambiguous", conflict.status)
        assertNull(conflict.value)
        val mismatch = ScopedWellnessEvidence.selectDay(identity, "2025-01-04", listOf(sample("x", identity.copy(method = "nightly"))))
        assertEquals("incompatible", mismatch.status)
    }

    @Test fun unverifiedPriorityAndMissingDayArePreserved() {
        val unknown = identity.copy(method = null, verification = "unknown")
        assertEquals("unverified", ScopedWellnessEvidence.selectDay(identity, "2025-01-04", listOf(sample("u", unknown))).status)
        assertEquals("ambiguous", ScopedWellnessEvidence.selectDay(unknown, "2025-01-04", listOf(sample("a", unknown), sample("b", unknown))).status)
        assertEquals("unique_compatible", ScopedWellnessEvidence.selectDay(identity, "2025-01-04", listOf(sample("a"),sample("a"))).status)
        assertEquals("missing", ScopedWellnessEvidence.selectDay(identity, "2025-01-04", emptyList()).status)
    }
}

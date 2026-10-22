package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitmentProjectionTest {
    private fun item(id: String = "c1", revision: Int = 1, start: String = "2024-01-01T09:00", end: String = "2024-01-01T10:00",
                     recurrence: CommitmentRecurrence? = null, exceptions: List<CommitmentException> = emptyList(), zone: String = "UTC",
                     stale: Boolean = false): ConfirmedCommitment {
        val ref = CommitmentEvidenceRef(id, revision, "intake", revision, null, null)
        return ConfirmedCommitment(ref, CommitmentDefinition("test", "commitment", zone, start, end, recurrence, exceptions),
            null, "author", "source", "user", "2024-01-01T00:00:00Z", false, false, stale)
    }

    private fun project(vararg items: ConfirmedCommitment, from: String = "2024-01-01", to: String = from, zone: String = "UTC") =
        projectCommitments(items.toList(), from, to, zone)

    @Test fun overnightAndMultiDayOverlapBoundaries() {
        val overnight = item(start = "2024-01-01T23:00", end = "2024-01-02T01:00")
        assertEquals(1, project(overnight, from = "2024-01-02").occurrences.size)
        assertEquals(0, project(overnight, from = "2024-01-03").occurrences.size)
        val multi = item(start = "2024-01-01T09:00", end = "2024-01-03T10:00")
        assertEquals(1, project(multi, from = "2024-01-03").occurrences.size)
        assertEquals(0, project(multi, from = "2024-01-04").occurrences.size)
    }

    @Test fun maxDurationAcceptedAndOverMaxRejected() {
        assertEquals(1, project(item(start = "2024-01-01T09:00", end = "2025-01-01T09:00"), from = "2024-12-31").occurrences.size)
        assertFailsWith<IllegalArgumentException> { item(start = "2024-01-01T09:00", end = "2025-01-02T09:00") }
    }

    @Test fun dailyAndWeeklyIntervalsHonorInclusiveEndOn() {
        val daily = item(recurrence = CommitmentRecurrence("daily", 2, "2024-01-05"))
        assertEquals(listOf("2024-01-01", "2024-01-03", "2024-01-05"), project(daily, to = "2024-01-06").occurrences.map { it.originalDate })
        val weekly = item(recurrence = CommitmentRecurrence("weekly", 2, "2024-02-01"))
        assertEquals(listOf("2024-01-01", "2024-01-15", "2024-01-29"), project(weekly, to = "2024-02-01").occurrences.map { it.originalDate })
    }

    @Test fun cancelAndRescheduleIncludingOriginalOutsideQuery() {
        val rec = CommitmentRecurrence("daily")
        val exceptions = listOf(CommitmentException("2024-01-02", "cancel"),
            CommitmentException("2024-01-01", "reschedule", "2024-01-04T12:00", "2024-01-04T13:00"))
        assertEquals(listOf("2024-01-04","2024-01-01"), project(item(recurrence = rec, exceptions = exceptions), from = "2024-01-04").occurrences.map { it.originalDate })
        val movedIn = CommitmentException("2023-01-01", "reschedule", "2024-01-04T12:00", "2024-01-04T13:00")
        assertEquals(1, project(item(start="2023-01-01T09:00",end="2023-01-01T10:00",recurrence = CommitmentRecurrence("daily", endOn = "2023-01-01"), exceptions = listOf(movedIn)), from = "2024-01-04").occurrences.size)
        assertEquals(1, project(item(start="2023-01-01T09:00",end="2023-01-01T10:00",exceptions=listOf(movedIn)),from="2024-01-04").occurrences.size)
        val movedOut = CommitmentException("2024-01-01", "reschedule", "2024-02-01T12:00", "2024-02-01T13:00")
        assertEquals(0, project(item(recurrence = rec, exceptions = listOf(movedOut))).occurrences.size)
    }

    @Test fun dstGapOverlapAndInversion() {
        val gap = project(item(start = "2024-03-10T02:30", end = "2024-03-10T04:00", zone = "America/New_York"), from="2024-03-10",zone = "America/New_York")
            .occurrences.single()
        assertTrue("dst_gap_shifted" in gap.flags)
        assertEquals("2024-03-10T07:30:00Z", gap.startsUtc)
        val overlap = project(item(start = "2024-11-03T01:30", end = "2024-11-03T02:30", zone = "America/New_York"), from = "2024-11-03", zone = "America/New_York").occurrences.single()
        assertTrue("dst_overlap_earlier_offset" in overlap.flags)
        assertEquals("2024-11-03T05:30:00Z", overlap.startsUtc)
        assertFailsWith<CommitmentTimeUncertain> { project(item(start = "2024-03-10T02:30", end = "2024-03-10T03:00", zone = "America/New_York"),from="2024-03-10",zone = "America/New_York") }
    }

    @Test fun horizonAndDefinitionZonesAreIndependent() {
        val result = project(item(start = "2024-01-01T23:30", end = "2024-01-02T00:30", zone = "America/Los_Angeles"), from = "2024-01-02", zone = "Pacific/Kiritimati")
        assertEquals(1, result.occurrences.size)
        val extremes=project(item(start="2024-01-03T01:00",end="2024-01-03T01:30",zone="Pacific/Kiritimati"),from="2024-01-01",zone="Etc/GMT+12")
        assertEquals(1,extremes.occurrences.size)
    }

    @Test fun staleIsVisibleButNonAuthoritativeAndBlocksCoverage() {
        val result = project(item(stale = true))
        assertFalse(result.occurrences.single().authoritative)
        assertTrue(result.constraintsBlocked)
        assertEquals(listOf(result.occurrences.single().reference), result.needsReconfirmation)
    }

    @Test fun correctionsKeepOccurrenceIdAndOnlyLatestRevision() {
        val result = project(item(revision = 1), item(revision = 2, start = "2024-01-01T11:00", end = "2024-01-01T12:00"))
        assertEquals(1, result.occurrences.size)
        assertEquals("c1:2024-01-01", result.occurrences.single().occurrenceId)
        assertEquals(2, result.occurrences.single().reference.revision)
        assertEquals("2024-01-01T11:00", result.occurrences.single().startsLocal)
    }

    @Test fun validatesHorizonDatesOrderAndBound() {
        assertFailsWith<IllegalArgumentException> { project(item(), from = "2024-02-01", to = "2024-01-01") }
        assertFailsWith<IllegalArgumentException> { project(item(), from = "2023-01-01", to = "2024-01-02") }
        assertFailsWith<Exception> { project(item(), zone = "GMT+02:00") }
    }

    @Test fun refusesExpansionOverFiftyThousandCandidates() {
        val many = (0 until 140).map { index -> item("c$index", start = "2024-01-01T09:00", end = "2024-01-02T10:00", recurrence = CommitmentRecurrence("daily")) }
        assertFailsWith<CommitmentProjectionLimit> { projectCommitments(many, "2024-01-01", "2024-12-31", "UTC") }
    }

    @Test fun invalidAndDuplicateExceptionShapesFailBeforeConfirmation() {
        assertFailsWith<IllegalArgumentException> { CommitmentRecurrence("monthly") }
        assertFailsWith<IllegalArgumentException> { CommitmentException("2024-01-01","cancel","2024-01-01T09:00","2024-01-01T10:00") }
        assertFailsWith<IllegalArgumentException> { item(exceptions=listOf(CommitmentException("2024-01-02","cancel"))) }
        assertFailsWith<IllegalArgumentException> { item(exceptions=List(2) { CommitmentException("2024-01-01","cancel") }) }
        assertFailsWith<IllegalArgumentException> { item(recurrence=CommitmentRecurrence("weekly"),exceptions=listOf(CommitmentException("2024-01-02","cancel"))) }
    }

    @Test fun sharedBoundariesAreHalfOpenAndWallClockRecurrenceSurvivesDST() {
        assertTrue(project(item(end="2024-01-02T00:00"),from="2024-01-02").occurrences.isEmpty())
        assertTrue(project(item(start="2024-01-02T00:00",end="2024-01-02T01:00"),from="2024-01-01").occurrences.isEmpty())
        val recurring=item(start="2024-03-03T09:00",end="2024-03-03T10:00",zone="America/New_York",recurrence=CommitmentRecurrence("weekly",endOn="2024-03-10"))
        val result=project(recurring,from="2024-03-03",to="2024-03-10",zone="America/New_York")
        assertEquals(listOf("2024-03-03T14:00:00Z","2024-03-10T13:00:00Z"),result.occurrences.map { it.startsUtc })
    }

    @Test fun skippedCivilDateHasNoOccurrencesButRetainsUncertainCoverage() {
        val spanning=item(start="2011-12-29T00:00",end="2012-01-02T00:00",stale=true)
        val result=project(spanning,from="2011-12-30",zone="Pacific/Apia")
        assertTrue(result.occurrences.isEmpty())
        assertTrue(result.constraintsBlocked)
        assertEquals(listOf(spanning.reference),result.needsReconfirmation)
    }

    @Test fun generatedDatesAtInputDomainBoundaryDoNotReparseAsInvalidUserInput() {
        val recurring=item(start="9999-12-30T09:00",end="9999-12-31T10:00",recurrence=CommitmentRecurrence("daily"))
        val result=project(recurring,from="9999-12-31")
        assertEquals(listOf("9999-12-30","9999-12-31"),result.occurrences.map { it.originalDate })
        assertTrue(result.occurrences.last().endsUtc.startsWith("+10000-01-01"))
    }

    @Test fun staleAvailabilityBlocksEvenWhenNoOccurrenceIsInTheHorizon() {
        val unavailable=item(stale=true).let { it.copy(definition=it.definition.copy(kind="availability")) }
        val result=project(unavailable,from="2024-02-01")
        assertTrue(result.occurrences.isEmpty())
        assertTrue(result.constraintsBlocked)
        assertEquals(listOf(unavailable.reference),result.needsReconfirmation)
    }

    @Test fun exactCandidateBudgetIsAcceptedAndNextCandidateFailsClosed() {
        val originals=(0 until 1000).map { index -> item(id="c$index",recurrence=CommitmentRecurrence("daily",endOn="2024-02-19")) }
        // Fifty dates each: exactly 50,000 candidates and occurrences.
        assertEquals(50_000,projectCommitments(originals,"2024-01-01","2024-02-19","UTC").occurrences.size)
        val extra=originals.mapIndexed { i,entry -> if(i!=0) entry else entry.copy(definition=entry.definition.copy(
            exceptions=listOf(CommitmentException("2024-01-01","reschedule","2024-03-01T09:00","2024-03-01T10:00")),
            recurrence=CommitmentRecurrence("daily",endOn="2024-02-20"))) }
        assertFailsWith<CommitmentProjectionLimit> { projectCommitments(extra,"2024-01-01","2024-02-20","UTC") }
    }
}

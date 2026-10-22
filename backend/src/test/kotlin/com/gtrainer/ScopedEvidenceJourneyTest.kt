package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class ScopedEvidenceJourneyTest {
    private val at = Instant.parse("2020-06-10T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    private class NoReadSource : HistorySource {
        override val sourceId = "intervals.icu"
        var calls = 0
        override suspend fun activities(range: ReadRange): ReadResult<ActivityRecord> { calls++; error("unexpected upstream activity read") }
        override suspend fun wellness(range: ReadRange): ReadResult<WellnessRecord> { calls++; error("unexpected upstream wellness read") }
    }

    private fun journey(atClock:Clock=clock,block: (HistoryStore, HistoryService, NoReadSource) -> Unit) {
        val directory = Files.createTempDirectory("scoped-evidence-journey-")
        try {
            HistoryStore(directory.resolve("history.sqlite3"),atClock).use { store ->
                store.updateReviewSchedule(0, ReviewScheduleConfiguration(timeZone = "UTC"), atClock.instant(), null, null)
                val source = NoReadSource()
                val service = HistoryService(store, source, atClock)
                try { block(store, service, source) } finally { service.close() }
            }
        } finally {
            Files.list(directory).use { it.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }

    private fun save(store: HistoryStore, activities: List<ActivityRecord>, wellness: List<WellnessRecord> = emptyList()) {
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, activities), at)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS, wellness), at)
    }

    @Test fun `selected triathlon sessions exclude intervening activity and display-only wellness from packet`() = journey { store, service, source ->
        val swim = syntheticActivity().copy(sourceRecordId = "swim", sport = "swim", startLocal = "2020-06-01T08:00:00", startInstant = "2020-06-01T08:00:00Z")
        val excluded = swim.copy(sourceRecordId = "intervening", sport = "ride", startLocal = "2020-06-02T08:00:00", startInstant = "2020-06-02T08:00:00Z")
        val run = swim.copy(sourceRecordId = "run", sport = "run", startLocal = "2020-06-03T08:00:00", startInstant = "2020-06-03T08:00:00Z")
        val ride=swim.copy(sourceRecordId="selected-ride",sport="ride",startLocal="2020-06-01T10:00:00",startInstant="2020-06-01T10:00:00Z")
        val daily = syntheticWellness().copy(sourceRecordId = "display-only-unique", date = "2020-06-01")
        save(store, listOf(swim, excluded, run,ride), listOf(daily))
        val plans=store.plans()
        val ids = listOf(swim, ride,run).map(::scopedActivityId)
        val request = ScopedEvidenceRequest(selection = ScopedReviewRequest(mode = "activities", scope = "activities", intent = "compare",
            activityIds = ids, wellnessPolicy = "display_only"), purpose = "activity_reflection")
        val result = runBlocking { service.prepareEvidence(request) }
        val snapshot = result.snapshot
        val packet = assertNotNull(snapshot.packet)
        assertEquals(ids.toSet(), packet.facts.sessions.map { it.activityId }.toSet())
        assertEquals(setOf("swim", "ride", "run"), packet.facts.sessions.map { it.sport }.toSet())
        assertTrue(packet.facts.sessionComparisons.isNotEmpty())
        assertEquals(setOf("movingTime","elapsedTime"),packet.facts.sessionComparisons.map { it.key }.toSet())
        assertTrue(packet.facts.sessionComparisons.all { it.sport==null && "physical_duration_not_effort" in it.flags && it.values.map { value->value.activityId }.toSet()==ids.toSet() })
        assertTrue(packet.facts.wellness.isEmpty())
        assertTrue(snapshot.manifest.displayOnly.isNotEmpty())
        assertFalse(ScopedEvidencePreparation.json.encodeToString(packet).contains("display-only-unique"))
        val rows = store.scopedEvidenceRecords(snapshot.snapshotId, 0, 50).items
        assertEquals(setOf("swim", "selected-ride", "run"), rows.filter { it.category=="activities" }.map { it.sourceRecordId }.toSet())
        assertTrue(rows.none { it.sourceRecordId == "intervening" })
        assertEquals(snapshot, runBlocking { service.inspectEvidence(snapshot.snapshotId) })
        val reviewed=runBlocking { service.prepareEvidence(request.copy(selection=request.selection.copy(intent="review"))) }.snapshot
        assertEquals(ids.toSet(),assertNotNull(reviewed.packet).facts.sessions.map { it.activityId }.toSet())
        assertTrue(reviewed.packet.facts.sessionComparisons.isEmpty())
        val assessed=runBlocking { service.prepareEvidence(request.copy(selection=request.selection.copy(wellnessPolicy="assessment"))) }.snapshot
        val observations=assertNotNull(assessed.packet).facts.wellness
        assertTrue(observations.isNotEmpty());assertEquals(observations.size,observations.map { it.evidenceId }.distinct().size)
        assertTrue(observations.all { it.activityIds.toSet()==setOf(scopedActivityId(swim),scopedActivityId(ride)) })
        assertNotEquals(snapshot.packetSha256,assessed.packetSha256)
        assertEquals(plans,store.plans())
        assertEquals(0, source.calls)
    }

    @Test fun `period defaults wellness and explicit overlapping later comparison remains disclosed`() = journey { store, service, source ->
        val daily = syntheticWellness().copy(date = "2020-06-03")
        save(store, emptyList(), listOf(daily))
        val request = ScopedEvidenceRequest(selection = ScopedReviewRequest(mode = "period", scope = "custom", oldest = "2020-06-01", newest = "2020-06-05",
            intent = "compare", comparison = ScopedDateRange("2020-06-04", "2020-06-06")), purpose = "period_reflection")
        val snapshot = runBlocking { service.prepareEvidence(request) }.snapshot
        val packet = assertNotNull(snapshot.packet, snapshot.manifest.blockedReasons.joinToString())
        assertTrue(packet.facts.wellness.isNotEmpty())
        assertEquals(ScopedDateRange("2020-06-01", "2020-06-05"), snapshot.manifest.scope.evidenceRange)
        assertEquals("explicit", snapshot.manifest.scope.comparison.policy)
        assertTrue(snapshot.manifest.scope.comparison.overlapping)
        assertEquals(ScopedDateRange("2020-06-04", "2020-06-06"), snapshot.manifest.scope.comparison.comparisonFull)
        assertTrue(store.scopedEvidenceRecords(snapshot.snapshotId, 0, 50).items.any { it.category == "wellness" })
        val planBefore = packet.plan.selection
        assertEquals(snapshot, runBlocking { service.inspectEvidence(snapshot.snapshotId) })
        assertEquals(planBefore, assertNotNull(runBlocking { service.inspectEvidence(snapshot.snapshotId).packet }).plan.selection)
        assertEquals(0, source.calls)
        assertEquals("closed_not_qualified_not_execution_bound", snapshot.admission)
        val later=runBlocking { service.prepareEvidence(request.copy(selection=request.selection.copy(oldest="2020-06-01",newest="2020-06-02",
            comparison=ScopedDateRange("2020-06-05","2020-06-06")))) }.snapshot
        assertEquals(ScopedDateRange("2020-06-05","2020-06-06"),later.manifest.scope.comparison.comparisonFull)
        assertFalse(later.manifest.scope.comparison.overlapping)
        assertEquals("explicit",later.manifest.scope.comparison.policy)
    }

    @Test fun `source-local travel date at captured cutoff remains selected with saved timezone disclosed`() = journey { store, service, source ->
        val travel = syntheticActivity().copy(sourceRecordId = "travel-run", sport = "run", startLocal = "2020-06-11T01:00:00",
            startInstant = "2020-06-10T11:00:00Z", timeZone = "Pacific/Auckland", sourceTimeZoneLabel = "Pacific/Auckland")
        save(store, listOf(travel))
        val request = ScopedEvidenceRequest(selection = ScopedReviewRequest(mode = "activities", scope = "activities", activityIds = listOf(scopedActivityId(travel))),
            purpose = "activity_reflection")
        val snapshot = runBlocking { service.prepareEvidence(request) }.snapshot
        val session = assertNotNull(snapshot.packet).facts.sessions.single()
        assertEquals("2020-06-11", session.startLocal.take(10))
        assertEquals("2020-06-10T11:00:00Z", session.startInstant)
        assertEquals("UTC", snapshot.manifest.scope.timeZone)
        assertEquals("PREPARED", runBlocking { service.inspectEvidence(snapshot.snapshotId) }.state)
        assertEquals(0, source.calls)
    }

    @Test fun `morning missing sleep unknown night and evening late imports preserve receipt chronology without readiness inference`() {
        val mutable=ReviewTestClock(Instant.parse("2020-06-10T06:00:00Z"))
        journey(mutable) { store,service,source ->
            val first=syntheticWellness().copy(sourceRecordId="hrv-device-a-report",date="2020-06-09",
                measurements=mapOf("hrv" to Measurement(42.0,"ms","synthetic-device-a")))
            val third=first.copy(sourceRecordId="hrv-device-b-report",measurements=mapOf("hrv" to Measurement(64.0,"ms","synthetic-device-b")))
            val second=first.copy(sourceRecordId="hrv-sdnn-report",measurements=mapOf("hrvSDNN" to Measurement(85.0,"ms","synthetic-device-b")))
            val oldSleep=first.copy(sourceRecordId="older-sleep",date="2020-06-08",measurements=mapOf("sleepSecs" to Measurement(27000.0,"seconds")))
            store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(first,second,third,oldSleep)),Instant.parse("2020-06-09T22:00:00Z"))
            val unchanged=store.plans()
            val selection=ScopedReviewRequest(scope="custom",oldest="2020-06-10",newest="2020-06-10")
            val baselines=listOf("synthetic-device-a","synthetic-device-b").map { origin->ScopedBaselineSelection("hrv",first.source,origin,
                ScopedBaselineConfig("2020-06-10",1,1,1.0,1)) }
            val morning=runBlocking { service.prepareEvidence(ScopedEvidenceRequest(selection=selection,purpose="morning",baselines=baselines)) }.snapshot
            val morningPacket=assertNotNull(morning.packet)
            val latest=assertNotNull(morningPacket.latestAvailableWellness)
            assertTrue(latest.days.all { it.sourceDate<"2020-06-10" })
            assertEquals("2020-06-08",latest.days.single { it.field=="sleepSecs" }.sourceDate)
            assertEquals(setOf("hrv","hrvSDNN"),latest.days.filter { it.field.startsWith("hrv") }.map { it.field }.toSet())
            val sameField=latest.days.filter { it.field=="hrv" }
            assertEquals(2,sameField.size)
            assertEquals(setOf(42.0,64.0),sameField.flatMap { it.observations }.map { it.value }.toSet())
            // The real adapter cannot verify methods/bases. Equal fields and units never grant compatibility.
            assertEquals(2,morningPacket.baselines.size)
            assertTrue(morningPacket.baselines.all { it.value==null && it.status=="not_computed" && "semantics_unverified" in it.reasons })
            assertTrue(latest.days.flatMap { it.observations }.all { it.method=="unknown" && it.verification=="unknown" })
            assertTrue("training_readiness_unsupported" in latest.missing)
            val night=runBlocking { service.prepareEvidence(ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="night",anchorDate="2020-06-09"),purpose="period_reflection")) }.snapshot
            assertEquals("BLOCKED",night.state);assertNull(night.packet);assertTrue("night_mapping_unverified" in night.manifest.blockedReasons)
            mutable.now=Instant.parse("2020-06-10T20:00:00Z")
            val evening=runBlocking { service.prepareEvidence(ScopedEvidenceRequest(selection=selection,purpose="evening")) }.snapshot
            assertTrue(runBlocking { service.evidenceRecords(evening.snapshotId,0,50) }.items.none { it.category=="activities" })
            val late=syntheticActivity().copy(sourceRecordId="late-evening-run",sport="run",startLocal="2020-06-10T18:00:00",startInstant="2020-06-10T18:00:00Z")
            mutable.now=Instant.parse("2020-06-10T22:00:00Z")
            store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(late)),mutable.now)
            val current=runBlocking { service.prepareEvidence(ScopedEvidenceRequest(selection=selection,purpose="evening")) }.snapshot
            val records=runBlocking { service.evidenceRecords(current.snapshotId,0,50) }.items
            assertEquals(listOf("late-evening-run"),records.filter { it.category=="activities" }.map { it.sourceRecordId })
            assertEquals("2020-06-10T20:00:00Z",evening.manifest.scope.asOf)
            assertEquals("2020-06-10T22:00:00Z",current.manifest.scope.asOf)
            assertEquals("STALE",runBlocking { service.inspectEvidence(evening.snapshotId) }.state)
            assertEquals(unchanged,store.plans());assertEquals(0,source.calls)
        }
    }
}

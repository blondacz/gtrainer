package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ScopedEvidenceStoreTest {
    private val at = Instant.parse("2020-06-10T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    private fun withStore(block: (java.nio.file.Path, HistoryStore) -> Unit) {
        val directory = Files.createTempDirectory("scoped-evidence-store-")
        try { HistoryStore(directory.resolve("history.sqlite3")).use { block(directory.resolve("history.sqlite3"), it) } }
        finally { Files.list(directory).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(directory) }
    }

    private fun configured(store: HistoryStore) {
        store.updateReviewSchedule(store.reviewScheduleState().version,
            ReviewScheduleConfiguration(timeZone = "UTC"), at, null, null)
        assertEquals("UTC", store.scopedReviewOptions(at, null, null, 0).timeZone)
    }

    private fun request() = ScopedEvidenceRequest(selection = ScopedReviewRequest(
        scope = "custom", oldest = "2020-06-01", newest = "2020-06-10", wellnessPolicy = "off"), purpose = "period_reflection")

    private class NoReadSource : HistorySource {
        override val sourceId = "intervals.icu"
        var calls = 0
        override suspend fun activities(range: ReadRange): ReadResult<ActivityRecord> { calls++; error("unexpected read") }
        override suspend fun wellness(range: ReadRange): ReadResult<WellnessRecord> { calls++; error("unexpected read") }
    }

    private fun seed(store: HistoryStore, record: ActivityRecord = syntheticActivity()) {
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(record)), at)
    }

    @Test fun `preparation is inert and packet survives fresh store decoding`() = withStore { path, store ->
        configured(store); seed(store)
        val source = NoReadSource()
        val service = HistoryService(store, source, clock)
        val prepared = runBlocking { service.prepareEvidence(request()) }
        assertEquals(0, source.calls)
        assertEquals("PREPARED", prepared.snapshot.state)
        val packet = assertNotNull(prepared.snapshot.packet)
        assertEquals(AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(packet)), prepared.snapshot.packetSha256)
        service.close()
        HistoryStore(path).use { reopened ->
            val stored = reopened.storedScopedEvidence(prepared.snapshot.snapshotId)
            assertEquals(packet, stored.snapshot.packet)
            assertEquals(prepared.snapshot.packetSha256, stored.snapshot.packetSha256)
            assertEquals("unchanged", reopened.scopedEvidenceRecords(prepared.snapshot.snapshotId, 0, 50).items.single().status)
        }
    }

    @Test fun `only captured identities are listed and corrected import keeps original receipt`() = withStore { _, store ->
        configured(store)
        val selected = syntheticActivity()
        val outside = selected.copy(sourceRecordId = "outside-window", startLocal = "2020-05-20T10:00:00Z")
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(selected, outside)), at)
        val prepared = runBlocking { HistoryService(store, NoReadSource(), clock).prepareEvidence(request()) }.snapshot
        val captured = store.scopedEvidenceRecords(prepared.snapshotId, 0, 50).items
        assertEquals(listOf(selected.sourceRecordId), captured.map { it.sourceRecordId })
        val receipt = captured.single().importedRevision
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(selected.copy(calories = Measurement(456.0, "kcal")), outside)), at.plusSeconds(60))
        val corrected = store.scopedEvidenceRecords(prepared.snapshotId, 0, 50).items.single()
        assertEquals(receipt, corrected.importedRevision)
        assertEquals("changed_since_snapshot", corrected.status)
        assertNull(corrected.activity)
    }

    @Test fun `intervening import prevents publication`() = withStore { path, store ->
        configured(store); seed(store)
        val req = request()
        val captured = store.captureScopedEvidence(req, at)
        val report = ScopedTrends.report(captured.read.scope, captured.read.history, captured.read.statuses)
        val prepared = prepareScopedEvidence(req, captured, report)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,
            listOf(syntheticActivity(), syntheticActivity().copy(sourceRecordId = "new-applicable"))), at.plusSeconds(1))
        assertFailsWith<ScopedEvidenceConflict> { store.saveScopedEvidence(prepared, at.plusSeconds(2)) }
        assertEquals(0, tableCount(path, "scoped_evidence_snapshots"))
    }

    @Test fun `record insertion failure rolls back snapshot and dependencies`() = withStore { path, store ->
        configured(store); seed(store)
        val req = request()
        val captured = store.captureScopedEvidence(req, at)
        val prepared = prepareScopedEvidence(req, captured,
            ScopedTrends.report(captured.read.scope, captured.read.history, captured.read.statuses))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use {
            it.execute("CREATE TRIGGER reject_evidence_record BEFORE INSERT ON scoped_evidence_records BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        } }
        assertFailsWith<Exception> { store.saveScopedEvidence(prepared, at) }
        assertEquals(0, tableCount(path, "scoped_evidence_snapshots"))
        assertEquals(0, tableCount(path, "scoped_evidence_dependencies"))
        assertEquals(0, tableCount(path, "scoped_evidence_records"))
    }

    private fun tableCount(path: java.nio.file.Path, table: String): Int {
        require(table.matches(Regex("scoped_evidence_[a-z]+")))
        return java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) } }
        }
    }

    @Test fun `context deletion purges scoped text and final inspection refuses cached deleted snapshots`() = withStore { path,store ->
        configured(store);seed(store)
        val context=store.createContext(AthleteContextRequest(category="note",sourceCategory="user_report",authorAttribution="synthetic author",
            observedOn="2020-05-01",content="SCOPED_PRIVATE_DELETE_MARKER"))
        val independent=store.createContext(AthleteContextRequest(category="note",sourceCategory="user_report",authorAttribution="synthetic author",
            observedOn="2020-05-01",content="independent input"))
        val service=HistoryService(store,NoReadSource(),clock)
        val req=request().copy(question="SCOPED_PRIVATE_DELETE_MARKER")
        val prepared=runBlocking { service.prepareEvidence(req) }
        val cached=store.storedScopedEvidence(prepared.snapshot.snapshotId)
        val execution=ScopedExecutionService(store,clock).prepare(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=prepared.snapshot.snapshotId),ConnectedReviewProviderSelection("synthetic","synthetic-model","local"))
        val cachedExecution=store.captureScopedExecution(execution.binding.executionId)
        assertTrue(execution.binding.executionId in store.previewContextDeletion(context.contextId,at).executionIds)
        val capture=store.captureScopedEvidence(req,at)
        val beforeDeletion=prepareScopedEvidence(req,capture,ScopedTrends.report(capture.read.scope,capture.read.history,capture.read.statuses))
        assertTrue(prepared.snapshot.manifest.dependencies.any { it.identity==context.contextId })
        assertTrue("context/${context.contextId}/${context.revision}" in prepared.snapshot.manifest.included)
        assertTrue(store.deleteContext(context.contextId))
        assertFailsWith<ScopedEvidenceNotFound> { store.finishScopedEvidenceInspection(cached.snapshot.snapshotId,capture.totalChanges,true) }
        assertFailsWith<ConnectedReviewNotFound> { store.finishScopedExecutionCheck(cachedExecution,true) }
        assertFailsWith<ScopedEvidenceConflict> { store.saveScopedEvidence(beforeDeletion,at) }
        assertEquals(0,tableCount(path,"scoped_evidence_snapshots"))
        assertEquals(0,tableCount(path,"scoped_evidence_dependencies"))
        assertEquals(0,tableCount(path,"scoped_evidence_records"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { statement ->
            for(table in listOf("scoped_execution_bindings","scoped_execution_state")) statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next();assertEquals(0,rows.getInt(1)) }
        } }
        assertEquals(independent,store.contextRevisions(independent.contextId).single())
        assertEquals(1,store.history(java.time.LocalDate.parse("2020-06-01"),java.time.LocalDate.parse("2020-06-10")).activities.size)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val tables=db.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows ->
                buildList { while(rows.next()) add(rows.getString(1)) }
            } }
            for(table in tables) db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"$table\"").use { rows ->
                while(rows.next()) for(index in 1..rows.metaData.columnCount)
                    assertFalse(rows.getString(index).orEmpty().contains("SCOPED_PRIVATE_DELETE_MARKER"))
            } }
        }
    }

    @Test fun `aggregate preserving source correction is stale and comparison inputs are not review membership`() = withStore { _,store ->
        configured(store)
        val original=syntheticActivity()
        val prior=original.copy(sourceRecordId="prior-only",startLocal="2020-05-31T09:00:00",startInstant="2020-05-31T09:00:00Z")
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(original,prior)),at)
        val service=HistoryService(store,NoReadSource(),clock)
        val snap=runBlocking { service.prepareEvidence(request()) }.snapshot
        assertEquals(listOf(original.sourceRecordId),store.scopedEvidenceRecords(snap.snapshotId,0,50).items.map { it.sourceRecordId })
        // Distance is not a period aggregate: this correction preserves every displayed period total.
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(original.copy(distance=Measurement(1234.0,"metres")))),at)
        assertEquals("STALE",runBlocking { service.inspectEvidence(snap.snapshotId) }.state)
        assertEquals("changed_since_snapshot",store.scopedEvidenceRecords(snap.snapshotId,0,50).items.single().status)
        val cached=store.storedScopedEvidence(snap.snapshotId)
        store.createContext(AthleteContextRequest(category="note",sourceCategory="user_report",authorAttribution="synthetic",observedOn="2020-05-01",content="new applicable context"))
        assertFailsWith<ScopedEvidenceConflict> { store.finishScopedEvidenceInspection(snap.snapshotId,cached.totalChanges,true) }
    }

    @Test fun `explicit baseline windows stay separate and unknown adapter semantics cannot compute medians`() = withStore { _,store ->
        configured(store);seed(store)
        val wellness=syntheticWellness()
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(wellness)),at)
        val baseline=ScopedBaselineSelection("hrv",wellness.source,null,ScopedBaselineConfig("2020-06-02",2,1,0.0,2))
        val req=request().copy(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-01",newest="2020-06-01"),baselines=listOf(baseline))
        val service=HistoryService(store,NoReadSource(),clock)
        val snapshot=runBlocking { service.prepareEvidence(req) }.snapshot
        val packet=assertNotNull(snapshot.packet)
        val selected=packet.facts.wellness.single { it.field=="hrv" }
        val result=packet.baselines.single()
        assertNull(result.value);assertTrue("semantics_unverified" in result.reasons)
        assertEquals(baseline.configuration,result.configuration)
        assertEquals(selected.evidenceId,result.days.last().samples.single().evidenceId)
        val membership=store.scopedEvidenceRecords(snapshot.snapshotId,0,50).items.single { it.category=="wellness" }
        assertEquals(listOf("assessment_context","baseline"),membership.roles)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(wellness.copy(sourceRecordId="second-day-candidate"))),at)
        assertEquals("STALE",runBlocking { service.inspectEvidence(snapshot.snapshotId) }.state)
        val fresh=runBlocking { service.prepareEvidence(req) }.snapshot
        assertEquals("ambiguous",fresh.manifest.baselines.single().days.last().status)
        assertTrue("semantics_unverified" in fresh.manifest.baselines.single().reasons)
        assertFailsWith<IllegalArgumentException> { req.copy(baselines=listOf(baseline,baseline)) }
        assertFailsWith<IllegalArgumentException> { req.copy(selection=req.selection.copy(wellnessPolicy="display_only")) }
    }

    @Test fun `new matching records on previously missing baseline dates stale the capture without entering selected evidence`() = withStore { _,store ->
        configured(store);seed(store)
        val baseline=ScopedBaselineSelection("sleepSecs",syntheticWellness().source,null,ScopedBaselineConfig("2020-06-02",1,1,1.0,1))
        val req=request().copy(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-09",newest="2020-06-09"),baselines=listOf(baseline))
        val service=HistoryService(store,NoReadSource(),clock)
        val before=runBlocking { service.prepareEvidence(req) }.snapshot
        assertEquals("missing",before.manifest.baselines.single().days.single().status)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(syntheticWellness())),at)
        assertEquals("STALE",runBlocking { service.inspectEvidence(before.snapshotId) }.state)
        val after=runBlocking { service.prepareEvidence(req) }.snapshot
        assertTrue(assertNotNull(after.packet).facts.wellness.isEmpty())
        assertEquals(listOf("baseline"),store.scopedEvidenceRecords(after.snapshotId,0,50).items.single().roles)
        assertEquals("2020-06-01",after.manifest.baselines.single().days.single().date)
        val packetSample=assertNotNull(assertNotNull(after.packet).baselines.single().days.single().samples.singleOrNull())
        assertEquals(at.toString(),packetSample.receivedUtc)
        val summary=after.manifest.baselines.single().days.single()
        assertEquals(1,summary.candidateCount)
        assertEquals("authenticated_record_pages",summary.candidateDetails)
        assertTrue(summary.samples.isEmpty())
        assertTrue(summary.evidenceIds.isEmpty())
        assertTrue(packetSample.evidenceId in after.manifest.included)
        assertEquals(1,after.manifest.included.count { it==packetSample.evidenceId })
    }

    @Test fun `baseline matching requires measurement but accepts present unknown origin`() = withStore { _,store ->
        configured(store)
        val plain=syntheticWellness()
        val weightOnly=plain.copy(measurements=mapOf("weight" to plain.measurements.getValue("weight")))
        val unrelated=plain.copy(sourceRecordId="sleep-only",measurements=mapOf("sleepSecs" to plain.measurements.getValue("sleepSecs")))
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(weightOnly,unrelated)),at)
        val selection=ScopedBaselineSelection("hrv",plain.source,null,ScopedBaselineConfig("2020-06-02",1,1,1.0,1))
        val req=request().copy(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-09",newest="2020-06-09"),baselines=listOf(selection))
        val service=HistoryService(store,NoReadSource(),clock)
        val before=runBlocking { service.prepareEvidence(req) }.snapshot
        assertTrue(store.scopedEvidenceRecords(before.snapshotId,0,50).items.isEmpty())
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(weightOnly.copy(measurements=mapOf("weight" to Measurement(71.0,"kg"))))),at)
        assertTrue(store.scopedEvidenceRecords(before.snapshotId,0,50).items.isEmpty())
        assertEquals("PREPARED",runBlocking { service.inspectEvidence(before.snapshotId) }.state)

        val unknownOrigin=plain.copy(measurements=mapOf("hrv" to plain.measurements.getValue("hrv").copy(upstreamSource=null)))
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(unknownOrigin)),at.plusSeconds(2))
        val present=runBlocking { service.prepareEvidence(req) }.snapshot
        val member=store.scopedEvidenceRecords(present.snapshotId,0,50).items.single()
        assertEquals(listOf("baseline"),member.roles)
        assertTrue("semantics_unverified" in assertNotNull(present.packet).baselines.single().reasons)
    }

    @Test fun `activity receipt change and restoration remains changed since snapshot`() = withStore { _,store ->
        configured(store);seed(store)
        val service=HistoryService(store,NoReadSource(),clock)
        val snapshot=runBlocking { service.prepareEvidence(request()) }.snapshot
        val original=syntheticActivity()
        val capturedReceipt=store.scopedEvidenceRecords(snapshot.snapshotId,0,50).items.single().importedRevision
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(original.copy(calories=Measurement(456.0,"kcal")))),at.plusSeconds(1))
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(original)),at.plusSeconds(2))
        val record=store.scopedEvidenceRecords(snapshot.snapshotId,0,50).items.single()
        assertEquals("changed_since_snapshot",record.status)
        assertNull(record.activity)
        assertEquals(capturedReceipt,record.importedRevision)
    }

    @Test fun `baseline-only packet sample is included once in manifest`() = withStore { _,store ->
        configured(store);seed(store)
        val wellness=syntheticWellness()
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(wellness)),at)
        val baseline=ScopedBaselineSelection("hrv",wellness.source,null,ScopedBaselineConfig("2020-06-02",1,1,1.0,1))
        val req=request().copy(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-02",newest="2020-06-02",wellnessPolicy="assessment"),baselines=listOf(baseline))
        val snapshot=runBlocking { HistoryService(store,NoReadSource(),clock).prepareEvidence(req) }.snapshot
        val evidenceId=assertNotNull(snapshot.packet).baselines.single().days.single().samples.single().evidenceId
        assertEquals(1,snapshot.manifest.included.count { it==evidenceId })
    }
}

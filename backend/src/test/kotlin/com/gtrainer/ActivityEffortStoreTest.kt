package com.gtrainer

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityEffortStoreTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private val sessionId="22222222-2222-4222-8222-222222222222"
    private val scale=EffortScale("33333333-3333-4333-8333-333333333333","Synthetic effort",1.0,10.0,1.0,true)
    private val query=PlanReconciliationQuery("2026-10-15","2026-10-15","UTC")
    private fun record()=syntheticActivity().copy(sourceRecordId="effort-synthetic",sport="Run",startLocal="2026-10-15T10:15:00",startInstant="2026-10-15T10:15:00Z",timeZone=null,utcOffset=null,timeContext="upstream_instant")
    private fun fixture(pathBlock: ((Path)->Unit)?=null,block:(HistoryStore,PlanService,ReviewTestClock)->Unit) {
        val dir=Files.createTempDirectory("activity-effort-")
        try { HistoryStore(dir.resolve("history.db")).use { store ->
            val clock=ReviewTestClock(at);val service=PlanService(store,clock)
            val target=PlannedTarget("reported_effort","scale_points","reported",4.0,6.0,scale)
            val plan=service.create(AuthoredPlanRequest("Effort plan",PlanAuthor("user","Synthetic author"),listOf(
                PlannedSession(sessionId,PlanAuthor("coach","Synthetic coach"),"Run","Synthetic session",
                    PlanSchedule("date_only",date="2026-10-15",timeZone="UTC"),listOf(target))),null))
            service.select(PlanSelectionRequest(0,plan.reference))
            store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at)
            pathBlock?.invoke(dir.resolve("history.db"))
            block(store,service,clock)
        } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun request(store:HistoryStore,op:String=UUID.randomUUID().toString(),value:Double=5.0,origin:EffortReportOrigin?=null):ActivityEffortRequest {
        val identity=store.capturePlanReconciliation(at).activities.single()
        return ActivityEffortRequest(op,identity.activityId,scale.scaleId,null,0,0,identity.revision,identity.recordSha256,"report",value,"2026-10-15","Athlete",origin)
    }
    private fun report(store:HistoryStore,service:PlanService,value:Double=5.0,origin:EffortReportOrigin?=null)=service.saveEffortReport(request(store,value=value,origin=origin))

    @Test fun `create replay and operation conflict preserve domain revisions`()=fixture { store,service,_ ->
        val before=store.scopedRoutingRevisions();val input=request(store)
        val first=service.saveEffortReport(input);assertFalse(first.replayed);assertEquals(1,first.report.revision)
        val after=store.scopedRoutingRevisions();assertEquals(before.source,after.source);assertEquals(before.planReason+1,after.planReason)
        assertTrue(service.saveEffortReport(input).replayed);assertEquals(after,store.scopedRoutingRevisions())
        assertFailsWith<PlanConflict> { service.saveEffortReport(input.copy(value=6.0)) }
        assertEquals(listOf(1),service.effortHistory(input.activityId,scale.scaleId).reports.map { it.revision })
    }

    @Test fun `stale activity identity is refused and correction makes recorded effort stale`()=fixture { store,service,_ ->
        val input=request(store);val receipt=service.saveEffortReport(input)
        assertFailsWith<PlanConflict> { service.saveEffortReport(request(store).copy(expectedRevision=0)) }
        val corrected=record().copy(calories=Measurement(124.0,"kcal"))
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(corrected)),at.plusSeconds(1))
        val latest=service.effortHistory(input.activityId,scale.scaleId)
        assertEquals(receipt.report,latest.reports.single())
        assertNotEquals(receipt.report.activityRecordSha256,latest.currentActivity?.recordSha256)
        val current=assertNotNull(store.capturePlanReconciliation(at.plusSeconds(1)).activities.singleOrNull { it.activityId==input.activityId })
        assertFailsWith<PlanConflict> { service.saveEffortReport(input.copy(operationId=UUID.randomUUID().toString(),expectedReportId=receipt.report.reportId,expectedRevision=1,
            expectedActivityRevision=current.revision,expectedRecordSha256=receipt.report.activityRecordSha256)) }
        val withdrawn=service.saveEffortReport(ActivityEffortRequest(UUID.randomUUID().toString(),input.activityId,scale.scaleId,receipt.report.reportId,1,0,null,null,"withdraw",null,null,null))
        assertTrue(withdrawn.report.withdrawn);assertEquals(receipt.report.value,withdrawn.report.value);assertEquals(receipt.report.observedOn,withdrawn.report.observedOn)
        assertEquals(receipt.report.activityRecordSha256,withdrawn.report.activityRecordSha256)
        assertTrue(service.effortHistory(input.activityId,scale.scaleId).reports.last().withdrawn)
    }

    @Test fun `removed import preserves typed lineage detached and requires explicit reaffirmation`()=fixture { store,service,_ ->
        val input=request(store);val first=service.saveEffortReport(input).report
        store.removeImports()
        val removed=service.effortHistory(input.activityId,scale.scaleId)
        assertNull(removed.currentActivity);assertTrue(removed.reports.single().detached);assertEquals(1,removed.reports.single().detachmentGeneration)
        val beforeRead=store.capturePlanReconciliation(at).totalChanges
        service.effortHistory(input.activityId,scale.scaleId)
        assertEquals(beforeRead,store.capturePlanReconciliation(at).totalChanges)
        val old=input.copy(operationId=UUID.randomUUID().toString(),expectedReportId=first.reportId,expectedRevision=1,expectedDetachmentGeneration=0)
        assertFailsWith<PlanConflict> { service.saveEffortReport(old) }
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record())),at.plusSeconds(2))
        val reimport=service.effortHistory(input.activityId,scale.scaleId)
        assertTrue(reimport.reports.single().detached);assertEquals(1,reimport.reports.single().detachmentGeneration)
        assertTrue(service.saveEffortReport(input).replayed)
        assertTrue(service.effortHistory(input.activityId,scale.scaleId).reports.single().detached)
        val identity=store.capturePlanReconciliation(at.plusSeconds(2)).activities.single()
        val affirmed=service.saveEffortReport(old.copy(operationId=UUID.randomUUID().toString(),expectedDetachmentGeneration=1,
            expectedActivityRevision=identity.revision,expectedRecordSha256=identity.recordSha256))
        assertFalse(affirmed.report.detached);assertEquals(2,affirmed.report.revision);assertEquals(1,affirmed.report.detachmentGeneration)
    }

    @Test fun `reporting requires current activity revision and future known start is refused`()=fixture { store,service,clock ->
        val identity=store.capturePlanReconciliation(at).activities.single()
        assertFailsWith<PlanConflict> { service.saveEffortReport(request(store).copy(expectedActivityRevision=null)) }
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record().copy(startLocal="2026-10-15T13:00:00",startInstant="2026-10-15T13:00:00Z"))),at)
        val future=store.capturePlanReconciliation(at).activities.single()
        assertFailsWith<PlanConflict> { service.saveEffortReport(request(store).copy(activityId=future.activityId,expectedActivityRevision=future.revision,expectedRecordSha256=future.recordSha256)) }
        assertTrue(assertNotNull(identity.revision)>0);clock.now=at.plusSeconds(3600)
    }

    @Test fun `reported range check is inspectable and corrections need explicit reaffirmation`()=fixture { store,service,_ ->
        val input=request(store,value=7.0);val receipt=service.saveEffortReport(input)
        var check=service.reconciliation(query).sessions.single().targetChecks.single()
        assertEquals("above_range",check.status);assertEquals(7.0,check.actualValue)
        assertEquals("reported",check.actualBasis);assertEquals(receipt.report,check.effortReport)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(record().copy(calories=Measurement(124.0,"kcal")))),at.plusSeconds(1))
        check=service.reconciliation(query).sessions.single().targetChecks.single()
        assertEquals("unevaluable",check.status);assertEquals("activity_evidence_unavailable",check.reason)
        val identity=store.capturePlanReconciliation(at).activities.single()
        service.saveEffortReport(input.copy(operationId=UUID.randomUUID().toString(),expectedReportId=receipt.report.reportId,expectedRevision=1,
            expectedActivityRevision=identity.revision,expectedRecordSha256=identity.recordSha256,value=4.0))
        assertEquals("within_range",service.reconciliation(query).sessions.single().targetChecks.single().status)
    }

    @Test fun `optional plan origin binds exact match digest generation and final write counter`()=fixture { store,service,clock ->
        val view=service.reconciliation(query)
        val origin=EffortReportOrigin(assertNotNull(view.selection.selected),sessionId,view.selection.generation,view.digest,view.asOf,query)
        val input=request(store,origin=origin)
        assertFailsWith<PlanConflict> { service.saveEffortReport(input.copy(origin=origin.copy(expectedDigest="0".repeat(64)))) }
        assertFailsWith<PlanConflict> { service.saveEffortReport(input.copy(origin=origin.copy(expectedSelectionGeneration=0))) }
        val capture=store.capturePlanReconciliation(at)
        store.updateReviewQueue { it to Unit }
        assertFailsWith<PlanConflict> { store.saveEffortReport(input,capture,clock) }
        val current=service.reconciliation(query)
        assertFalse(service.saveEffortReport(input.copy(origin=origin.copy(expectedDigest=current.digest))).replayed)
    }

    @Test fun `detached reports can be withdrawn without source data and survive reopen`() {
        var path: Path?=null
        fixture(pathBlock={path=it}) { store,service,clock ->
            val input=request(store);val first=service.saveEffortReport(input).report
            store.removeImports()
            val withdrawn=service.saveEffortReport(ActivityEffortRequest(UUID.randomUUID().toString(),input.activityId,scale.scaleId,first.reportId,1,1,
                null,null,"withdraw",null,null,null)).report
            assertTrue(withdrawn.detached&&withdrawn.withdrawn);assertEquals(first.value,withdrawn.value)
            assertEquals(first.activityRecordSha256,withdrawn.activityRecordSha256)
            store.close()
            HistoryStore(assertNotNull(path)).use { reopened ->
                val history=PlanService(reopened,clock).effortHistory(input.activityId,scale.scaleId)
                assertNull(history.currentActivity);assertEquals(listOf(1,2),history.reports.map { it.revision })
                assertTrue(history.reports.last().detached&&history.reports.last().withdrawn)
            }
        }
    }

    @Test fun `lineage reserves the thousandth revision for withdrawal`() {
        var path: Path?=null
        fixture(pathBlock={path=it}) { store,service,_ ->
            val input=request(store);val first=service.saveEffortReport(input).report
            DriverManager.getConnection("jdbc:sqlite:${assertNotNull(path)}").use { db ->
                db.autoCommit=false
                db.prepareStatement("INSERT INTO activity_effort_reports(report_id,revision,report_json) VALUES(?,?,?)").use { s ->
                    for(revision in 2..999) {
                        s.setString(1,first.reportId);s.setInt(2,revision);s.setString(3,ScopedEvidencePreparation.json.encodeToString(first.copy(revision=revision,previousRevision=revision-1)));s.addBatch()
                    };s.executeBatch()
                };db.commit()
            }
            val full=input.copy(operationId=UUID.randomUUID().toString(),expectedReportId=first.reportId,expectedRevision=999)
            assertFailsWith<IllegalArgumentException> { service.saveEffortReport(full) }
            val withdrawn=service.saveEffortReport(ActivityEffortRequest(UUID.randomUUID().toString(),input.activityId,scale.scaleId,first.reportId,999,0,
                null,null,"withdraw",null,null,null)).report
            assertEquals(1000,withdrawn.revision);assertTrue(withdrawn.withdrawn)
        }
    }

    @Test fun `global capacity reserves one withdrawal row for every nonwithdrawn lineage`() {
        var path: Path?=null
        fixture(pathBlock={path=it}) { store,service,_ ->
            val input=request(store);val first=service.saveEffortReport(input).report
            DriverManager.getConnection("jdbc:sqlite:${assertNotNull(path)}").use { db ->
                db.autoCommit=false
                db.prepareStatement("INSERT INTO activity_effort_lineages(report_id,activity_id,scale_id,detached,detachment_generation) VALUES(?,?,?,0,0)").use { lineage ->
                    db.prepareStatement("INSERT INTO activity_effort_reports(report_id,revision,report_json) VALUES(?,1,?)").use { row ->
                        for(i in 1..4999) {
                            val id=UUID.randomUUID().toString();val activity=AnalysisClaims.hash("synthetic-capacity-$i")
                            lineage.setString(1,id);lineage.setString(2,activity);lineage.setString(3,scale.scaleId);lineage.addBatch()
                            row.setString(1,id);row.setString(2,ScopedEvidencePreparation.json.encodeToString(first.copy(reportId=id,activityId=activity)));row.addBatch()
                        };lineage.executeBatch();row.executeBatch()
                    }
                };db.commit()
            }
            assertFailsWith<IllegalArgumentException> { service.saveEffortReport(input.copy(operationId=UUID.randomUUID().toString(),expectedReportId=first.reportId,expectedRevision=1)) }
            assertTrue(service.saveEffortReport(ActivityEffortRequest(UUID.randomUUID().toString(),input.activityId,scale.scaleId,first.reportId,1,0,
                null,null,"withdraw",null,null,null)).report.withdrawn)
        }
    }
}

package com.gtrainer

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtraWorkloadOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private fun config(aggregation:String="per_record",threshold:Double=100.0)=ExtraWorkloadConfiguration(
        aggregation,"duration","seconds","moving","run","synthetic",null,"UTC",10,"greater_than",threshold)
    private fun request(id:String,revision:Int,enabled:Boolean=true,configuration:ExtraWorkloadConfiguration?=config())=
        ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),id,revision,enabled,configuration)

    @Test fun `configured workload receipt survives reopen and owner finish fences changed rule`() {
        val dir=Files.createTempDirectory("extra-workload-owner-");val path=dir.resolve("history.db")
        try {
            val id=UUID.randomUUID().toString();val initial=request(id,0,true,config("cumulative",900.0))
            HistoryStore(path).use { store ->
                val first=store.saveWorkloadRule(initial,at)
                assertEquals(false,first.replayed);assertEquals(initial.configuration,first.rule.configuration)
                assertTrue(store.saveWorkloadRule(initial,at).replayed)
                val capture=store.captureExtraWorkload(id,at)
                val report=PlanService(store,java.time.Clock.fixed(at,java.time.ZoneOffset.UTC)).inspectWorkloadRule(id)
                assertEquals("base_plan_required",report.status)
                val changed=store.saveWorkloadRule(request(id,1,true,config("per_record",120.0)),at).rule
                assertEquals(2,changed.revision)
                assertFailsWith<PlanConflict> { store.finishExtraWorkload(capture,report) }
            }
            HistoryStore(path).use { reopened ->
                val restored=reopened.workloadRules().single()
                assertEquals(2,restored.revision);assertEquals(config("per_record",120.0),restored.configuration)
                assertTrue(reopened.saveWorkloadRule(initial,at).replayed)
                assertEquals(2,reopened.workloadRules().single().revision)
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    @Test fun `workload inspection rejects corrections through a second connection`() {
        val dir=Files.createTempDirectory("extra-workload-second-owner-");val path=dir.resolve("history.db")
        try { HistoryStore(path).use { first -> HistoryStore(path).use { second ->
            val id=UUID.randomUUID().toString()
            first.saveWorkloadRule(request(id,0),at)
            val capture=first.captureExtraWorkload(id,at)
            val report=ExtraWorkloadRules.evaluate(capture.rule,at,PlanReconciler.core(at,capture.reconciliation),capture.reconciliation.records)
            second.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity())),at)
            assertFailsWith<PlanConflict> { first.finishExtraWorkload(capture,report) }
            val fresh=first.captureExtraWorkload(id,at)
            assertEquals(capture.totalChanges,fresh.totalChanges)
            assertTrue(capture.dataVersion!=fresh.dataVersion)
            assertTrue(capture.sourceRevision<fresh.sourceRevision)
        } } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

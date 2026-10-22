package com.gtrainer

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersonalSignalOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private fun config()=PersonalSignalConfiguration("UTC",PersonalSignalSelector("hrv","ms","synthetic","synthetic","measurement","daily"),
        PersonalSignalBaseline(3,1,0.0,3),"low","absolute_limit",20.0,30.0,1,1,0)
    private fun request(id:String,revision:Int,enabled:Boolean,configuration:PersonalSignalConfiguration?)=
        PersonalSignalRuleRequest(UUID.randomUUID().toString(),id,revision,enabled,configuration)

    @Test fun `owner capture finish and internal projection commit fence stale foreign and local day owners`() {
        val dir=Files.createTempDirectory("personal-signal-owner-");val store=HistoryStore(dir.resolve("history.db"))
        val otherDir=Files.createTempDirectory("personal-signal-other-");val other=HistoryStore(otherDir.resolve("history.db"))
        val clock=Clock.fixed(at,ZoneOffset.UTC)
        try {
            val id=UUID.randomUUID().toString()
            val saved=store.saveSignalRule(request(id,0,true,config()),at).rule
            val query=PersonalSignalQuery("2026-10-15","2026-10-15")
            val captured=store.capturePersonalSignal(id,query,at)
            val report=PersonalSignalRules.evaluate(captured.rule,query,at,captured.samples)
            assertEquals(report,store.finishPersonalSignal(captured,report))
            assertFailsWith<PlanConflict> { other.finishPersonalSignal(captured,report) }
            val changed=store.saveSignalRule(request(id,saved.revision,false,null),at)
            assertEquals(2,changed.rule.revision)
            assertFailsWith<PlanConflict> { store.finishPersonalSignal(captured,report) }

            val fresh=store.capturePersonalSignal(id,query,at)
            // Disabled rules cannot be projected; stale configuration is fenced by rule revision.
            assertFailsWith<IllegalArgumentException> {
                store.commitPersonalSignalProjection(fresh,clock)
            }
            val configured=store.saveSignalRule(request(id,2,true,config()),at).rule
            val current=store.capturePersonalSignal(id,query,at)
            val currentReport=PersonalSignalRules.evaluate(configured,query,at,current.samples)
            assertEquals("unevaluable",currentReport.status)
            assertFailsWith<PlanConflict> { store.commitPersonalSignalProjection(current,Clock.fixed(at.plusSeconds(86400),ZoneOffset.UTC)) }
            assertFailsWith<PlanConflict> { store.commitPersonalSignalProjection(current,Clock.fixed(at.minusSeconds(1),ZoneOffset.UTC)) }
            val committed=store.commitPersonalSignalProjection(current,clock)
            assertEquals(configured.revision,committed.ruleRevision)
            assertFailsWith<PlanConflict> { store.commitPersonalSignalProjection(current,Clock.fixed(at.plusSeconds(86400),ZoneOffset.UTC)) }
            val disabled=store.saveSignalRule(request(id,3,false,null),at).rule
            assertEquals(4,disabled.revision)
            assertFailsWith<PlanConflict> { store.commitPersonalSignalProjection(current,clock) }
        } finally {
            store.close();other.close()
            Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir)
            Files.list(otherDir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(otherDir)
        }
    }

    @Test fun `configured selector cannot verify imported evidence and repeated inspection writes nothing`() {
        val dir=Files.createTempDirectory("personal-signal-inspect-");val path=dir.resolve("history.db")
        try {
            val id=UUID.randomUUID().toString()
            val query=PersonalSignalQuery("2026-10-15","2026-10-15")
            var generation=0L
            HistoryStore(path).use { store ->
                store.saveSignalRule(request(id,0,true,config()),at)
                store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(
                    WellnessRecord("synthetic","baseline","2026-10-14",setOf("synthetic"),mapOf("hrv" to Measurement(40.0,"ms","synthetic"))),
                    WellnessRecord("synthetic","today","2026-10-15",setOf("synthetic"),mapOf("hrv" to Measurement(10.0,"ms","synthetic"))))),at)
                val before=store.capturePersonalSignal(id,query,at)
                val service=PlanService(store,Clock.fixed(at,ZoneOffset.UTC))
                repeat(3) {
                    val report=service.inspectSignalRule(id,query)
                    assertEquals("unevaluable",report.status)
                    assertEquals("unverified",report.support.last().status)
                    assertEquals("not_integrated",report.admission)
                    assertTrue(report.sourceStatus.isNotEmpty())
                }
                val after=store.capturePersonalSignal(id,query,at)
                assertEquals(before.totalChanges,after.totalChanges)
                assertEquals(before.sourceRevision,after.sourceRevision)
                assertEquals(before.stateGeneration,after.stateGeneration)
                store.commitPersonalSignalProjection(after,Clock.fixed(at,ZoneOffset.UTC))
                val projected=store.capturePersonalSignal(id,query,at)
                assertEquals(false,projected.state!!.projected.known)
                assertEquals(null,projected.state.projected.episodeId)
                generation=projected.stateGeneration
                assertEquals(1L,generation)
            }
            HistoryStore(path).use { reopened ->
                val restored=reopened.capturePersonalSignal(id,query,at)
                assertEquals(generation,restored.stateGeneration)
                assertEquals(id,restored.state!!.ruleId)
                val view=PersonalSignalRules.evaluate(restored.rule,query,at,restored.samples)
                reopened.removeImports()
                assertFailsWith<PlanConflict> { reopened.finishPersonalSignal(restored,view) }
                val removed=reopened.capturePersonalSignal(id,query,at)
                assertTrue(removed.samples.isEmpty())
                assertEquals("needs_reevaluation",PersonalSignalTransitions.advance(
                    PersonalSignalRules.evaluate(removed.rule,query,at,removed.samples),removed.state,removed.sourceRevision,at).status)
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    @Test fun `another connection cannot publish or commit a captured stale projection`() {
        val dir=Files.createTempDirectory("personal-signal-connections-");val path=dir.resolve("history.db")
        try { HistoryStore(path).use { first -> HistoryStore(path).use { second ->
            val id=UUID.randomUUID().toString()
            first.saveSignalRule(request(id,0,true,config()),at)
            val query=PersonalSignalQuery("2026-10-15","2026-10-15")
            val capture=first.capturePersonalSignal(id,query,at)
            val view=PersonalSignalRules.evaluate(capture.rule,query,at,capture.samples)
            second.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("synthetic","late","2026-10-15",setOf("synthetic"),
                mapOf("hrv" to Measurement(5.0,"ms","synthetic"))))),at)
            assertFailsWith<PlanConflict> { first.finishPersonalSignal(capture,view) }
            assertFailsWith<PlanConflict> { first.commitPersonalSignalProjection(capture,Clock.fixed(at,ZoneOffset.UTC)) }
            val fresh=first.capturePersonalSignal(id,query,at)
            assertEquals(capture.totalChanges,fresh.totalChanges) // Connection-local token alone missed the import.
            assertTrue(capture.sourceRevision<fresh.sourceRevision)
            assertTrue(capture.dataVersion!=fresh.dataVersion)
        } } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

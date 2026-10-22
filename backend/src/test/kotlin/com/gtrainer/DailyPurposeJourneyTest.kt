package com.gtrainer

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Both enrolled daily slots use the real scheduler/queue without a model or a plan mutation. */
class DailyPurposeJourneyTest {
    @Test fun bothEnrolledSlotsPrepareOnceWithDistinctCutoffsAndRequireRearmAfterRestart()=runBlocking {
        val directory=Files.createTempDirectory("synthetic-daily-purpose-journey-")
        val path=directory.resolve("history.sqlite3")
        val clock=ReviewTestClock(Instant.parse("2026-10-15T07:00:00Z"))
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val slots=listOf(ReviewPurposeSlot(UUID.randomUUID().toString(),"morning",true,"08:00"),
            ReviewPurposeSlot(UUID.randomUUID().toString(),"evening",true,"20:00"))
        try {
            HistoryStore(path,clock).use { store ->
                val author=PlanAuthor("user","Synthetic athlete")
                val base=store.createPlan(AuthoredPlanRequest("Synthetic unchanged plan",author,listOf(
                    PlannedSession(UUID.randomUUID().toString(),author,"run","Existing easy session",
                        PlanSchedule("date_only",date="2026-10-16",timeZone="UTC"),permissions=AmendmentPermissions(omit=true))),
                    PlanPolicy("UTC",7,0)),clock.instant())
                store.selectPlan(PlanSelectionRequest(0,base.reference))
                val original=store.plans()
                ReviewInterpretationService(emptyList(),ReviewExecutionPolicy(),null).use { reviews ->
                    val scheduler=ReviewScheduler(store,reviews,clock,durableQueue=true)
                    val queue=ReviewQueueService(store,reviews,clock)
                    try {
                        scheduler.configure(ReviewScheduleUpdate(store.reviewScheduleState().version,ReviewScheduleConfiguration(
                            enabled=true,timeZone="UTC",queuePolicy=ReviewQueuePolicy(0,0,60),purposeSlots=slots)))
                        for((index,purpose,cutoff) in listOf(Triple(1,"morning","2026-10-15T08:01:00Z"),Triple(2,"evening","2026-10-15T20:01:00Z"))) {
                            clock.now=Instant.parse(cutoff)
                            val due=scheduler.tick().purposeDue
                            assertEquals(listOf(purpose),due.map { it.purpose })
                            queue.pump(worker)
                            withTimeout(5000) {
                                while(store.reviewScheduleState().queue.outcomes.size<index)delay(1)
                            }
                            val outcomes=queue.status().outcomes
                            assertEquals(index,outcomes.size)
                            val outcome=outcomes.single { it.scopedIntent?.purpose==purpose }
                            assertEquals("factual_prepared",outcome.status)
                            assertEquals(cutoff,outcome.scopedIntent!!.scope.asOf)
                            val snapshot=store.storedScopedEvidence(assertNotNull(outcome.snapshotId)).snapshot
                            assertEquals("PREPARED",snapshot.state)
                            assertEquals(purpose,snapshot.packet!!.request.purpose)
                            assertEquals("closed_not_qualified_not_execution_bound",snapshot.packet.admission)
                            assertEquals(base.reference,snapshot.packet.plan.plan)
                            assertTrue(snapshot.packet.facts.scope.soFar)
                            repeat(3) { assertTrue(scheduler.tick().purposeDue.isEmpty());queue.pump(worker) }
                            assertEquals(index,queue.status().outcomes.size)
                            assertEquals(original,store.plans())
                            assertTrue(reviews.status().models.isEmpty())
                        }
                        assertFalse(ScopedProfileAdmission.inspect(ScopedDescriptiveContractV1.PROFILE).executionAllowed)
                    } finally { queue.close() }
                }
            }
            HistoryStore(path,clock).use { store ->
                ReviewInterpretationService(emptyList(),ReviewExecutionPolicy(),null).use { reviews ->
                    val scheduler=ReviewScheduler(store,reviews,clock,durableQueue=true)
                    val statuses=scheduler.status().purposeSlots
                    assertEquals(setOf("morning","evening"),statuses.map { it.purpose }.toSet())
                    assertTrue(statuses.all { it.state=="awaiting_explicit_rearm" })
                    clock.now=Instant.parse("2026-10-16T20:01:00Z")
                    assertTrue(scheduler.tick().purposeDue.isEmpty())
                    assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
                    assertEquals(1,store.plans().plans.single().reference.revision)
                }
            }
        } finally {
            worker.cancel()
            Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }
}

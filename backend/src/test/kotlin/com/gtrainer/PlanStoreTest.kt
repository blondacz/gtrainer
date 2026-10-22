package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PlanStoreTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private fun fixture(f:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("synthetic-plans-"); val path=dir.resolve("history.db")
        try { HistoryStore(path).use { f(path,it) } } finally {
            Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir)
        }
    }
    private fun request(title:String="plan", day:String="2026-10-20", objective:String="easy") = AuthoredPlanRequest(title,
        PlanAuthor("user","synthetic athlete"),listOf(PlannedSession(UUID.randomUUID().toString(),PlanAuthor("user","synthetic athlete"),"run",objective,
            PlanSchedule("date_only",date=day,timeZone="UTC"),listOf(PlannedTarget("duration","seconds","moving",1200.0,1800.0)),notes="historical payload")))
    private fun selected(store:HistoryStore,p:AuthoredPlan,g:Long=0)=store.selectPlan(PlanSelectionRequest(g,p.reference))

    @Test fun `creation is inert selection is explicit and correction preserves identity and history`()=fixture { _,s->
        val first=s.createPlan(request(),at); assertEquals(null,s.plans().selection.selected); assertEquals(0,s.plans().selection.generation)
        val session=first.definition.sessions.single(); selected(s,first)
        val corrected=first.definition.copy(title="corrected",sessions=listOf(session.copy(objective="hard",
            schedule=session.schedule.copy(date="2026-10-22"))))
        val changed=s.correctPlan(first.reference.planId,PlanCorrection(1,1,corrected),at.plusSeconds(1))
        assertEquals(first.reference.planId,changed.reference.planId); assertEquals(2,changed.reference.revision)
        assertEquals(session.sessionId,changed.definition.sessions.single().sessionId)
        assertEquals("historical payload",s.plan(first.reference.planId,1).definition.sessions.single().notes)
        assertEquals(changed.reference,s.plans().selection.selected); assertEquals(2,s.plans().selection.generation)
        val other=s.createPlan(request("other"),at); assertNotEquals(changed.reference.planId,other.reference.planId)
        assertEquals(changed.reference,s.plans().selection.selected)
        assertFailsWith<PlanNotFound>{s.plan(UUID.randomUUID().toString())}
    }

    @Test fun `stale revision and selection generation conflict without writes and ABA detected`()=fixture { _,s->
        val a=s.createPlan(request(),at); val b=s.createPlan(request("b"),at); selected(s,a)
        val before=s.plans()
        assertFailsWith<PlanConflict>{s.correctPlan(a.reference.planId,PlanCorrection(9,1,request()),at)}
        assertFailsWith<PlanConflict>{s.correctPlan(a.reference.planId,PlanCorrection(1,0,request()),at)}
        assertEquals(before,s.plans())
        val g=s.plans().selection.generation; s.selectPlan(PlanSelectionRequest(g,b.reference)); s.selectPlan(PlanSelectionRequest(g+1,a.reference))
        assertFailsWith<PlanConflict>{s.correctPlan(a.reference.planId,PlanCorrection(1,g,request()),at)}
        assertEquals(1,s.plan(a.reference.planId).reference.revision)
    }

    @Test fun `scale identity conflict rolls back and revision payloads are immutable`()=fixture { path,s->
        val scale=EffortScale(UUID.randomUUID().toString(),"effort",1.0,10.0,1.0,true)
        fun scaled(scaleArg:EffortScale, title:String)=AuthoredPlanRequest(title,PlanAuthor("user","synthetic"),listOf(
            PlannedSession(UUID.randomUUID().toString(),PlanAuthor("user","synthetic"),"run","easy",PlanSchedule("date_only",date="2026-10-20",timeZone="UTC"),
                listOf(PlannedTarget("reported_effort","scale_points","reported",2.0,5.0,scaleArg)))))
        val p=s.createPlan(scaled(scale,"one"),at)
        val added=EffortScale(UUID.randomUUID().toString(),"new",0.0,10.0,1.0,true)
        val conflicting=scaled(scale.copy(name="changed"),"bad").copy(sessions=listOf(
            scaled(added,"new").sessions.single(),scaled(scale.copy(name="changed"),"bad").sessions.single()))
        assertFailsWith<PlanConflict>{s.correctPlan(p.reference.planId,PlanCorrection(1,0,conflicting),at)}
        assertEquals(1,s.plan(p.reference.planId).reference.revision)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                assertTrue(st.executeQuery("SELECT count(*) FROM plan_effort_scales WHERE scale_id='${added.scaleId}'").use { it.next();it.getInt(1)==0 })
                assertFailsWith<java.sql.SQLException>{st.executeUpdate("UPDATE plan_revisions SET definition_json='{}' WHERE plan_id='${p.reference.planId}' AND revision=1")}
            }
        }
    }

    @Test fun `reopen preserves selection and calendar reads selected interval overlap only`()=fixture { path,s->
        val clock=Clock.fixed(at,ZoneOffset.UTC)
        val outside=request("outside","2026-10-19"); val p=s.createPlan(outside,at)
        val overlap=request("selected","2026-10-20").copy(sessions=listOf(
            request().sessions.single().copy(sessionId=UUID.randomUUID().toString(),schedule=PlanSchedule("date_only",date="2026-10-20",timeZone="UTC")),
            request().sessions.single().copy(sessionId=UUID.randomUUID().toString(),schedule=PlanSchedule("date_only",date="2026-10-21",timeZone="UTC"))))
        val q=s.createPlan(overlap,at); val selection=selected(s,q)
        HistoryStore(path).use { reopened ->
            assertEquals(selection,reopened.plans().selection)
            val calendar=PlanService(reopened,clock).calendar("2026-10-20","2026-10-20","UTC")
            assertEquals(listOf(q.reference),calendar.items.map { it.plan }.distinct())
            assertEquals(1,calendar.items.size); assertEquals(false,calendar.items.single().amendmentEligible)
        }
    }

    @Test fun `selected pointer failure rolls back plan revision and concurrent correction has one winner`()=fixture { path,s ->
        val plan=s.createPlan(request(),at); selected(s,plan)
        val input=PlanCorrection(1,1,plan.definition.copy(title="changed"))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_pointer_failure BEFORE UPDATE ON plan_selection BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<java.sql.SQLException> { s.correctPlan(plan.reference.planId,input,at) }
            assertEquals(plan,s.plan(plan.reference.planId)); assertEquals(PlanSelection(1,plan.reference),s.plans().selection)
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_pointer_failure") }
        }
        val pool=Executors.newFixedThreadPool(2); val gate=CountDownLatch(1)
        try {
            val calls=(1..2).map { pool.submit<Boolean> {gate.await();try {s.correctPlan(plan.reference.planId,input,at);true} catch(_:PlanConflict){false}} }
            gate.countDown();assertEquals(1,calls.count {it.get()})
            assertEquals(2,s.plan(plan.reference.planId).reference.revision)
            assertEquals(2L,s.plans().selection.generation)
        } finally {pool.shutdownNow()}
    }

    @Test fun `event identity cannot be selected as a base plan`()=fixture { _,s ->
        val eventId=UUID.randomUUID().toString()
        s.saveEvent(ManualEvent(eventId,"2026-10-20","2026-10-20","run","goal","not a training plan"))
        assertFailsWith<PlanNotFound> {s.selectPlan(PlanSelectionRequest(0,PlanRef(eventId,1)))}
        assertEquals(PlanSelection(0,null),s.plans().selection)
        assertEquals(eventId,s.events().single().id)
    }

    @Test fun `encoded size and exhausted revision limits fail without changing selected plan`()=fixture { _,s ->
        val plan=s.createPlan(request(),at);selected(s,plan)
        val large=plan.definition.copy(sessions=(1..9).map {plan.definition.sessions.single().copy(sessionId=UUID.randomUUID().toString(),notes="x".repeat(4000))})
        assertFailsWith<IllegalArgumentException> {s.correctPlan(plan.reference.planId,PlanCorrection(1,1,large),at)}
        assertEquals(plan,s.plan(plan.reference.planId));assertEquals(PlanSelection(1,plan.reference),s.plans().selection)
        assertFailsWith<IllegalArgumentException> {PlanCorrection(1000,1,plan.definition)}
    }

    @Test fun `timed offsets are persisted and future permissions do not classify missing activity`()=fixture { _,s ->
        val session=request().sessions.single().copy(schedule=PlanSchedule("timed",startsLocal="2026-10-20T09:00",endsLocal="2026-10-20T10:00",timeZone="Europe/Prague"),
            permissions=AmendmentPermissions(omit=true))
        val plan=s.createPlan(request().copy(sessions=listOf(session),policy=PlanPolicy("Europe/Prague",10,3600)),at)
        assertEquals("+02:00",plan.definition.sessions.single().schedule.startOffset)
        selected(s,plan)
        val service=PlanService(s,Clock.fixed(at,ZoneOffset.UTC));val before=s.plans()
        val projection=service.calendar("2026-10-20","2026-10-20","Europe/Prague")
        assertEquals(true,projection.items.single().amendmentEligible)
        assertEquals(before,s.plans());assertEquals(false,projection.items.single().omitted)
        assertTrue(service.calendar("2026-10-19","2026-10-19","UTC").items.isEmpty())
        val later=PlanService(s,Clock.fixed(Instant.parse("2026-10-20T07:00:00Z"),ZoneOffset.UTC))
        assertEquals(false,later.calendar("2026-10-20","2026-10-20","UTC").items.single().amendmentEligible)
    }
}

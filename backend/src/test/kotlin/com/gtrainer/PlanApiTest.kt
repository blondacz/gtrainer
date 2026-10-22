package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanApiTest {
    @Test fun `plans API enforces private writes and exposes immutable selected revisions`() {
        val dir=Files.createTempDirectory("synthetic-plan-api-"); val store=HistoryStore(dir.resolve("history.db"))
        val source=SyntheticSource(); val origin="http://127.0.0.1:8080"
        val reviews=ReviewInterpretationService(emptyList())
        val at=Instant.parse("2026-10-15T12:00:00Z")
        val service=PlanService(store,Clock.fixed(at,ZoneOffset.UTC))
        val request=AuthoredPlanRequest("synthetic plan",PlanAuthor("user","synthetic athlete"),listOf(
            PlannedSession(UUID.randomUUID().toString(),PlanAuthor("user","synthetic athlete"),"run","easy",
                PlanSchedule("date_only",date="2026-10-20",timeZone="UTC"),listOf(PlannedTarget("duration","seconds","moving",1200.0,1800.0)))))
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,source,Clock.fixed(at,ZoneOffset.UTC)),reviews,
                schedules=null,reviewQueue=null,plans=service) }
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/plans").status)
            assertEquals(HttpStatusCode.Unauthorized,client.post("/api/plans").status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path:String)=client.get(path){header(HttpHeaders.Cookie,cookie)}
            suspend fun post(path:String,body:String,token:String?=csrf,from:String=origin)=client.post(path){
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from);token?.let{header("X-CSRF-Token",it)}
                contentType(ContentType.Application.Json);setBody(body)
            }
            suspend fun put(path:String,body:String,token:String?=csrf)=client.put(path){
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);token?.let{header("X-CSRF-Token",it)}
                contentType(ContentType.Application.Json);setBody(body)
            }
            val body=Json.encodeToString(request)
            assertEquals(HttpStatusCode.Forbidden,post("/api/plans",body,null).status)
            assertEquals(HttpStatusCode.Forbidden,post("/api/plans",body,from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.BadRequest,post("/api/plans",body.dropLast(1)+",\"unknown\":1}").status)
            assertEquals(HttpStatusCode.BadRequest,post("/api/plans","{\"title\":1,\"title\":2}").status)
            assertEquals(HttpStatusCode.BadRequest,post("/api/plans",String(byteArrayOf(0xc3.toByte(),0x28),Charsets.UTF_8)).status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post("/api/plans"," ".repeat(32_769)).status)
            val created=post("/api/plans",body); assertEquals(HttpStatusCode.Created,created.status)
            val plan=Json.decodeFromString<AuthoredPlan>(created.bodyAsText())
            val listed=Json.decodeFromString<PlansView>(get("/api/plans").bodyAsText())
            assertEquals(null,listed.selection.selected); assertEquals(plan.reference,listed.plans.single().reference)
            assertEquals(plan,Json.decodeFromString<AuthoredPlan>(get("/api/plans/${plan.reference.planId}/revisions/1").bodyAsText()))
            assertEquals(HttpStatusCode.BadRequest,get("/api/plans?x=1&x=2").status)
            val selected=put("/api/plans/selection",Json.encodeToString(PlanSelectionRequest(0,plan.reference)))
            assertEquals(HttpStatusCode.OK,selected.status)
            val correction=PlanCorrection(1,1,request.copy(title="corrected",sessions=request.sessions.map { it.copy(objective="steady") }))
            assertEquals(HttpStatusCode.Conflict,put("/api/plans/${plan.reference.planId}",Json.encodeToString(correction.copy(expectedSelectionGeneration=0))).status)
            val corrected=put("/api/plans/${plan.reference.planId}",Json.encodeToString(correction))
            assertEquals(HttpStatusCode.OK,corrected.status)
            val saved=Json.decodeFromString<AuthoredPlan>(corrected.bodyAsText())
            assertEquals(2,saved.reference.revision)
            assertEquals(saved.reference,Json.decodeFromString<PlansView>(get("/api/plans").bodyAsText()).selection.selected)
            val invalidMetric=Json.encodeToString(correction).replace("\"duration\"","\"distance\"")
            assertEquals(HttpStatusCode.BadRequest,put("/api/plans/${plan.reference.planId}",invalidMetric).status)
            for(end in listOf("2026-10-14","2027-10-17")) assertEquals(HttpStatusCode.BadRequest,get("/api/plans/calendar?oldest=2026-10-15&newest=$end&timeZone=UTC").status)
            assertEquals(HttpStatusCode.BadRequest,get("/api/plans/calendar?oldest=2026-10-15&newest=2026-10-15").status)
            assertEquals(HttpStatusCode.OK,get("/api/plans/calendar?oldest=2026-10-15&newest=2026-10-15&timeZone=UTC").status)
            assertEquals(0,source.calls); assertTrue(reviews != null)
        } } finally { store.close();reviews.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

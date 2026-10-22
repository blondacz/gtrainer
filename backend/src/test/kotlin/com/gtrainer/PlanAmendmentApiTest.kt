package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanAmendmentApiTest {
    @Test fun `candidate inspection requires auth is inert and is not approval route`() {
        val dir=Files.createTempDirectory("plan-amendment-api-"); val store=HistoryStore(dir.resolve("history.db"))
        val source=SyntheticSource(); val origin="http://127.0.0.1:8080"; val at=Instant.parse("2026-10-15T12:00:00Z")
        val endpoint="/api/plans/amendment-candidates/inspection"
        val author=PlanAuthor("user","synthetic")
        val plan=store.createPlan(AuthoredPlanRequest("synthetic",author,listOf(PlannedSession(UUID.randomUUID().toString(),author,"run","synthetic",
            PlanSchedule("date_only",date="2026-10-20",timeZone="UTC"),permissions=AmendmentPermissions(omit=true))),PlanPolicy("UTC",14,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        var providerCalls=0
        val model=object:ReviewFocusModel {
            override val option=syntheticModelOption
            override suspend fun generate(packet:ReviewFocusPacket,feedback:String?):String { providerCalls++;error("No inference is expected") }
        }
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,source,Clock.fixed(at,ZoneOffset.UTC)),
                ReviewInterpretationService(listOf(model)),schedules=null,reviewQueue=null,plans=PlanService(store,Clock.fixed(at,ZoneOffset.UTC))) }
            assertEquals(HttpStatusCode.Unauthorized,client.get(endpoint).status)
            assertEquals(HttpStatusCode.Unauthorized,client.post(endpoint).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path:String)=client.get(path){header(HttpHeaders.Cookie,cookie)}
            suspend fun post(path:String)=client.post(path){header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf);contentType(ContentType.Application.Json);setBody("{}")}
            val before=store.plans(); val queue=store.reviewScheduleState().queue
            val response=get(endpoint)
            assertEquals(HttpStatusCode.OK,response.status); assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.BadRequest,get("$endpoint?extra=1").status)
            assertEquals(HttpStatusCode.BadRequest,get("$endpoint?asOf=2026-10-15T12:00:00Z").status)
            assertEquals(HttpStatusCode.NotImplemented,post(endpoint).status)
            assertEquals(before,store.plans()); assertEquals(queue,store.reviewScheduleState().queue)
            assertEquals(0,source.calls)
            assertEquals(0,providerCalls)
            val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("factual_inspection_only_no_inference_proposal_or_plan_change",body.getValue("admission").jsonPrimitive.content)
            assertEquals("available",body.getValue("status").jsonPrimitive.content)
            assertEquals(1,body.getValue("candidates").jsonArray.size)
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

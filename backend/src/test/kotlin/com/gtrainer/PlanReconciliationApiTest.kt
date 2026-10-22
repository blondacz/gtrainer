package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID

class PlanReconciliationApiTest {
    @Test fun `inspection validates exact query and is private read only`() {
        val dir=Files.createTempDirectory("reconciliation-api-"); val store=HistoryStore(dir.resolve("history.db"))
        val origin="http://127.0.0.1:8080"; val at=Instant.parse("2026-10-15T12:00:00Z")
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,SyntheticSource(),Clock.fixed(at,ZoneOffset.UTC)),
                ReviewInterpretationService(emptyList()),schedules=null,reviewQueue=null,plans=PlanService(store,Clock.fixed(at,ZoneOffset.UTC))) }
            val path="/api/plans/reconciliation?oldest=2026-10-15&newest=2026-10-15&timeZone=UTC"
            assertEquals(HttpStatusCode.Unauthorized,client.get(path).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            suspend fun get(url:String)=client.get(url){header(HttpHeaders.Cookie,cookie)}
            assertEquals(HttpStatusCode.BadRequest,get(path+"&extra=1").status)
            assertEquals(HttpStatusCode.BadRequest,get(path.replace("oldest=2026-10-15","oldest=2026-10-15&oldest=2026-10-15")).status)
            val result=get(path); assertEquals(HttpStatusCode.OK,result.status)
            assertEquals("no-store",result.headers[HttpHeaders.CacheControl])
            assertEquals("2026-10-15",Json.parseToJsonElement(result.bodyAsText()).jsonObject.getValue("query").jsonObject.getValue("oldest").jsonPrimitive.content)
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    @Test fun `confirmation endpoint authenticates exact bounded writes and never edits the selected plan`() {
        val dir=Files.createTempDirectory("reconciliation-confirm-api-");val store=HistoryStore(dir.resolve("history.db"))
        val origin="http://127.0.0.1:8080";val at=Instant.parse("2026-10-15T12:00:00Z")
        val clock=Clock.fixed(at,ZoneOffset.UTC);val service=PlanService(store,clock);val source=SyntheticSource()
        val sessionId=UUID.randomUUID().toString()
        val plan=service.create(AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","Synthetic author"),listOf(
            PlannedSession(sessionId,PlanAuthor("user","Synthetic author"),"Run","Synthetic objective",
                PlanSchedule("timed",startsLocal="2026-10-15T10:00",endsLocal="2026-10-15T11:00",timeZone="UTC"))),PlanPolicy("UTC",30,3600)))
        service.select(PlanSelectionRequest(0,plan.reference))
        val endpoint="/api/plans/reconciliation/confirmations"
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,source,clock),
                ReviewInterpretationService(emptyList()),schedules=null,reviewQueue=null,plans=service) }
            assertEquals(HttpStatusCode.Unauthorized,client.post(endpoint).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun post(body: String,token: String?=csrf,from: String=origin)=client.post(endpoint) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from);token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json);setBody(body)
            }
            val query=PlanReconciliationQuery("2026-10-15","2026-10-15","UTC")
            val view=service.reconciliation(query)
            val input=PlanReconciliationConfirmationRequest(UUID.randomUUID().toString(),plan.reference,sessionId,"skip",null,
                view.selection.generation,null,view.digest,view.asOf,query)
            val encoded=Json.encodeToString(input)
            assertEquals(HttpStatusCode.Forbidden,post(encoded,null).status)
            assertEquals(HttpStatusCode.Forbidden,post(encoded,from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.BadRequest,post(encoded.dropLast(1)+",\"unknown\":1}").status)
            assertEquals(HttpStatusCode.BadRequest,post(encoded.dropLast(1)+",\"action\":\"match\"}").status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(" ".repeat(8193)).status)
            assertEquals(HttpStatusCode.Conflict,post(Json.encodeToString(input.copy(expectedDigest="0".repeat(64)))).status)
            val saved=post(encoded);assertEquals(HttpStatusCode.OK,saved.status)
            assertEquals("no-store",saved.headers[HttpHeaders.CacheControl])
            val receipt=Json.decodeFromString<PlanReconciliationReceipt>(saved.bodyAsText())
            assertEquals("authenticated_user",receipt.confirmation.recordedBy)
            val replay=Json.decodeFromString<PlanReconciliationReceipt>(post(encoded).bodyAsText());assertTrue(replay.replayed)
            assertEquals(receipt.confirmation,replay.confirmation)
            assertEquals(HttpStatusCode.Conflict,post(Json.encodeToString(input.copy(action="clear"))).status)
            assertEquals(plan,service.get(plan.reference.planId));assertEquals(view.selection,service.list().selection)
            assertEquals("confirmed_skipped",service.reconciliation(query).sessions.single().state)
            assertEquals(0,source.calls);assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

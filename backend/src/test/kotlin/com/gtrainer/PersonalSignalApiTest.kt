package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalSignalApiTest {
    @Test fun `signal rules API is private strict bounded and inspection is inert`() {
        val dir=Files.createTempDirectory("personal-signal-api-"); val store=HistoryStore(dir.resolve("history.db"))
        val origin="http://127.0.0.1:8080"; val clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC)
        val service=PlanService(store,clock); val endpoint="/api/plans/signal-rules"
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,SyntheticSource(),clock),
                ReviewInterpretationService(emptyList()),schedules=null,reviewQueue=null,plans=service) }
            assertEquals(HttpStatusCode.Unauthorized,client.get(endpoint).status)
            assertEquals(HttpStatusCode.Unauthorized,client.post(endpoint).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path:String)=client.get(path){header(HttpHeaders.Cookie,cookie)}
            suspend fun post(body:String,token:String?=csrf,from:String=origin)=client.post(endpoint) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from);token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json);setBody(body)
            }
            val empty=get(endpoint); assertEquals(HttpStatusCode.OK,empty.status);assertEquals("no-store",empty.headers[HttpHeaders.CacheControl])
            assertEquals("[]",empty.bodyAsText())
            assertEquals(HttpStatusCode.Forbidden,post("{}",null).status)
            assertEquals(HttpStatusCode.Forbidden,post("{}",from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.BadRequest,post("{}&extra=1").status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(" ".repeat(8193)).status)
            val id=UUID.randomUUID().toString()
            val disabled="""{"operationId":"${UUID.randomUUID()}","ruleId":"$id","expectedRevision":0,"enabled":false,"configuration":null}"""
            assertEquals(HttpStatusCode.OK,post(disabled).status)
            assertEquals(HttpStatusCode.OK,post(disabled).status) // exact operation replay
            assertEquals(HttpStatusCode.Conflict,post(disabled.replace("\"expectedRevision\":0","\"expectedRevision\":1")).status)
            assertEquals(HttpStatusCode.BadRequest,post(disabled.dropLast(1)+",\"unexpected\":1}").status)
            val injected=disabled.replace("\"operationId\":\"${Json.parseToJsonElement(disabled).jsonObject.getValue("operationId").jsonPrimitive.content}\"", "\"operationId\":\"${UUID.randomUUID()}\"")
                .replace("\"enabled\":false","\"enabled\":true")
                .replace("\"configuration\":null","\"configuration\":{\"selector\":{\"field\":\"hrv\",\"unit\":\"ms\",\"intermediary\":\"x\",\"upstreamOrigin\":\"x\",\"method\":\"x\",\"basis\":\"x\",\"verification\":\"verified\"},\"timeZone\":\"UTC\",\"baseline\":{\"trailingDays\":1,\"minimumSamples\":1,\"minimumCoverage\":0,\"maximumAgeDays\":1},\"direction\":\"low\",\"boundaryType\":\"absolute_limit\",\"triggerMagnitude\":1,\"resetMagnitude\":2,\"triggerPersistenceDays\":1,\"resetPersistenceDays\":1,\"cooldownSeconds\":0}")
            assertEquals(HttpStatusCode.BadRequest,post(injected).status)
            val before=store.reviewScheduleState().queue
            val tablesBefore=store.signalRules()
            val inspection=get("$endpoint/$id/inspection?oldest=2026-10-15&newest=2026-10-15")
            assertEquals(HttpStatusCode.OK,inspection.status);assertEquals("no-store",inspection.headers[HttpHeaders.CacheControl])
            repeat(2) { assertEquals(HttpStatusCode.OK,get("$endpoint/$id/inspection?oldest=2026-10-15&newest=2026-10-15").status) }
            assertEquals(tablesBefore,store.signalRules());assertEquals(before,store.reviewScheduleState().queue)
            assertTrue(Json.parseToJsonElement(inspection.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content=="disabled")
            assertEquals(HttpStatusCode.BadRequest,get("$endpoint/$id/inspection?oldest=2026-10-15&newest=2026-10-15&extra=1").status)
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

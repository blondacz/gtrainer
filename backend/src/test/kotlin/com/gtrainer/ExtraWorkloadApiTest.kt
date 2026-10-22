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

class ExtraWorkloadApiTest {
    @Test fun `workload rules API is private strict bounded and inspection is inert`() {
        val dir=Files.createTempDirectory("extra-workload-api-");val store=HistoryStore(dir.resolve("history.db"))
        val origin="http://127.0.0.1:8080";val at=Instant.parse("2026-10-15T12:00:00Z")
        val clock=Clock.fixed(at,ZoneOffset.UTC);val endpoint="/api/plans/workload-rules";val id=UUID.randomUUID().toString()
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,SyntheticSource(),clock),
                ReviewInterpretationService(emptyList()),schedules=null,reviewQueue=null,plans=PlanService(store,clock)) }
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
            val empty=get(endpoint);assertEquals(HttpStatusCode.OK,empty.status);assertEquals("no-store",empty.headers[HttpHeaders.CacheControl]);assertEquals("[]",empty.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest,get("$endpoint?extra=1").status)
            assertEquals(HttpStatusCode.Forbidden,post("{}",null).status)
            assertEquals(HttpStatusCode.Forbidden,post("{}",from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(" ".repeat(8193)).status)
            val disabled="""{"operationId":"${UUID.randomUUID()}","ruleId":"$id","expectedRevision":0,"enabled":false,"configuration":null}"""
            assertEquals(HttpStatusCode.BadRequest,post(disabled.dropLast(1)+",\"unknown\":1}").status)
            val saved=post(disabled);assertEquals(HttpStatusCode.OK,saved.status);assertEquals("no-store",saved.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.OK,post(disabled).status)
            assertEquals(HttpStatusCode.Conflict,post(disabled.replace("\"expectedRevision\":0","\"expectedRevision\":1")).status)
            val before=store.captureExtraWorkload(id,at);val queue=store.reviewScheduleState().queue
            val inspection=get("$endpoint/$id/inspection")
            assertEquals(HttpStatusCode.OK,inspection.status);assertEquals("no-store",inspection.headers[HttpHeaders.CacheControl])
            repeat(2) { assertEquals(HttpStatusCode.OK,get("$endpoint/$id/inspection").status) }
            assertEquals(HttpStatusCode.BadRequest,get("$endpoint/$id/inspection?extra=1").status)
            val after=store.captureExtraWorkload(id,at)
            assertEquals(before.totalChanges,after.totalChanges);assertEquals(queue,store.reviewScheduleState().queue)
            assertEquals("disabled",Json.parseToJsonElement(inspection.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

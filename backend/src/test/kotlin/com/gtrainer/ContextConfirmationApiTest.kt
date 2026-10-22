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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class ContextConfirmationApiTest {
    @Test fun `confirmation API is authenticated atomic explicit and replay safe`() {
        val dir=Files.createTempDirectory("synthetic-confirmation-api-")
        val store=HistoryStore(dir.resolve("history.db")); val source=SyntheticSource()
        val at=Instant.parse("2026-10-15T12:00:00Z"); val history=HistoryService(store,source,Clock.fixed(at,ZoneOffset.UTC))
        val model=SyntheticReviewModel(); val reviews=ReviewInterpretationService(listOf(model))
        val origin="http://127.0.0.1:8080"
        val noteInput=ContextIntakeRequest("Synthetic private Wednesday note", "user_report", "athlete", "2026-10-15")
        val ctx=AthleteContextRequest("symptom","user_report","athlete","2026-10-15",content="Synthetic symptom")
        val def=CommitmentDefinition("Synthetic appointment","commitment","America/New_York","2026-10-20T09:00","2026-10-20T10:00")
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),history,reviews,schedules=null,reviewQueue=null) }
            assertEquals(HttpStatusCode.Unauthorized,client.post("/api/context-intakes/00000000-0000-4000-8000-000000000001/confirm").status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun send(path:String,body:Any?=null,token:String?=csrf,from:String=origin)=client.post(path) {
                header(HttpHeaders.Cookie,cookie); header(HttpHeaders.Origin,from); token?.let { header("X-CSRF-Token",it) }
                if(body!=null){contentType(ContentType.Application.Json);setBody(body)}
            }
            val created=client.post("/api/context-intakes") { header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf);contentType(ContentType.Application.Json);setBody(Json.encodeToString(noteInput)) }
            assertEquals(HttpStatusCode.Created,created.status)
            val note=Json.decodeFromString<ContextIntake>(created.bodyAsText()); val path="/api/context-intakes/${note.intakeId}/confirm"
            val before=store.reviewScheduleState()
            val request=ContextConfirmationRequest(1,listOf(ContextConfirmationItem(ctx)),listOf(CommitmentConfirmationItem(def)),UUID.randomUUID().toString())
            val body=Json.encodeToString(request)
            assertEquals(HttpStatusCode.Forbidden,send(path,body,token=null).status)
            assertEquals(HttpStatusCode.Forbidden,send(path,body,from="https://untrusted.invalid").status)
            assertTrue(store.contexts(LocalDate.parse("2026-10-15")).isEmpty()); assertTrue(store.commitments().isEmpty())
            assertEquals(before,store.reviewScheduleState()); assertEquals(0,source.calls); assertTrue(model.packets.isEmpty())
            val initial=send(path,body); assertEquals(HttpStatusCode.OK,initial.status)
            val saved=Json.decodeFromString<ContextConfirmationResult>(initial.bodyAsText()); assertFalse(saved.replayed)
            val replay=send(path,body); val first=Json.decodeFromString<ContextConfirmationResult>(replay.bodyAsText())
            assertTrue(first.replayed); assertEquals(saved.contexts,first.contexts); assertEquals(saved.commitments,first.commitments)
            assertEquals(1,store.contextRevisions(first.contexts.single().contextId).size)
            assertEquals(1,store.commitmentHistory(first.commitments.single().reference.commitmentId).size)
            assertEquals(note.intakeId,first.commitments.single().reference.intakeId)
            assertEquals("authenticated_user",first.contexts.single().enteredBy); assertEquals("athlete",first.contexts.single().authorAttribution)
            assertEquals(listOf(first.contexts.single().contextId),store.intakeLinks(note.intakeId).map { it.contextId })
            assertEquals(HttpStatusCode.Conflict,send(path,Json.encodeToString(request.copy(contexts=listOf(ContextConfirmationItem(ctx.copy(content="changed")))))).status)
            assertEquals(HttpStatusCode.Conflict,send(path,Json.encodeToString(request.copy(expectedRevision=2))).status)
            assertEquals(before,store.reviewScheduleState())
            val invalid=body.dropLast(1)+",\"actor\":\"clinician\"}"
            for(bad in listOf(invalid,body.dropLast(1)+",\"confirmationId\":\"duplicate\"}")) {
                val response=send(path,bad); assertEquals(HttpStatusCode.BadRequest,response.status); assertEquals("{\"error\":\"invalid_request\"}",response.bodyAsText()); assertFalse(response.bodyAsText().contains("Synthetic"))
            }
            assertEquals(HttpStatusCode.BadRequest,send(path,byteArrayOf(0xc3.toByte(),0x28)).status)
            assertEquals(HttpStatusCode.PayloadTooLarge,send(path," ".repeat(33_000)).status)
            val monthly="""{"expectedRevision":1,"contexts":[],"commitments":[{"definition":{"label":"synthetic","kind":"commitment","timeZone":"UTC","startsLocal":"2026-10-20T09:00","endsLocal":"2026-10-20T10:00","recurrence":{"frequency":"monthly","interval":1}},"commitmentId":null,"expectedCommitmentRevision":null,"context":null}],"confirmationId":"${UUID.randomUUID()}"}"""
            assertEquals(HttpStatusCode.BadRequest,send(path,monthly).status)
            val relative="""{"expectedRevision":1,"contexts":[],"commitments":[{"definition":{"label":"synthetic","kind":"commitment","timeZone":"UTC","startsLocal":"Wednesday","endsLocal":"2026-10-20T10:00"},"commitmentId":null,"expectedCommitmentRevision":null,"context":null}],"confirmationId":"${UUID.randomUUID()}"}"""
            assertEquals(HttpStatusCode.BadRequest,send(path,relative).status)
            assertEquals(1,store.contexts(LocalDate.parse("2026-10-15")).size)
            assertEquals(1,store.commitments().size)
            val beforeSchedule=store.reviewScheduleState()
            assertEquals(beforeSchedule,store.reviewScheduleState()); assertEquals(0,source.calls); assertTrue(model.packets.isEmpty())
        } } finally { history.close();reviews.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

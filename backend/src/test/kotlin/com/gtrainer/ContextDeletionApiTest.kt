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
import kotlin.test.*

class ContextDeletionApiTest {
    @Test fun `deletion API authenticates previews read only and redacts atomically`() {
        val dir=Files.createTempDirectory("synthetic-deletion-api-")
        val store=HistoryStore(dir.resolve("history.db")); val source=SyntheticSource()
        val at=Instant.parse("2026-10-15T12:00:00Z"); val history=HistoryService(store,source,Clock.fixed(at,ZoneOffset.UTC))
        val model=SyntheticReviewModel(); val reviews=ReviewInterpretationService(listOf(model)); val origin="http://127.0.0.1:8080"
        val note=ContextIntakeRequest("MARKER synthetic private note","user_report","athlete","2026-10-15")
        val target=AthleteContextRequest("symptom","user_report","athlete","2026-10-15",content="MARKER target")
        val sibling=target.copy(category="note",content="MARKER sibling")
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),history,reviews,schedules=null,reviewQueue=null) }
            val id=UUID.randomUUID().toString()
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/contexts/$id/deletion-preview").status)
            assertEquals(HttpStatusCode.Unauthorized,client.post("/api/contexts/$id/redact-and-delete").status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun post(path:String,body:String,token:String?=csrf,from:String=origin)=client.post(path) {
                header(HttpHeaders.Cookie,cookie); header(HttpHeaders.Origin,from); token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json); setBody(body)
            }
            suspend fun get(path:String)=client.get(path) { header(HttpHeaders.Cookie,cookie) }
            val created=client.post("/api/context-intakes") { header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf);contentType(ContentType.Application.Json);setBody(Json.encodeToString(note)) }
            assertEquals(HttpStatusCode.Created,created.status)
            val intake=Json.decodeFromString<ContextIntake>(created.bodyAsText()); val confirmPath="/api/context-intakes/${intake.intakeId}/confirm"
            val confirmation=ContextConfirmationRequest(1,listOf(ContextConfirmationItem(target),ContextConfirmationItem(sibling)),emptyList(),UUID.randomUUID().toString())
            val confirmed=client.post(confirmPath) { header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf);contentType(ContentType.Application.Json);setBody(Json.encodeToString(confirmation)) }
            assertEquals(HttpStatusCode.OK,confirmed.status)
            val saved=Json.decodeFromString<ContextConfirmationResult>(confirmed.bodyAsText())
            val targetId=saved.contexts[0].contextId; val siblingId=saved.contexts[1].contextId
            val previewPath="/api/contexts/$targetId/deletion-preview"; val deletePath="/api/contexts/$targetId/redact-and-delete"
            val schedule=store.reviewScheduleState(); val intakeHistory=store.intakeHistory(intake.intakeId); val siblingHistory=store.contextRevisions(siblingId)
            val p1=get(previewPath); val p2=get(previewPath)
            assertEquals(HttpStatusCode.OK,p1.status); assertTrue(p1.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
            val preview=Json.decodeFromString<ContextDeletionPreview>(p1.bodyAsText())
            assertEquals(preview.digest,Json.decodeFromString<ContextDeletionPreview>(p2.bodyAsText()).digest)
            assertEquals(intakeHistory,store.intakeHistory(intake.intakeId)); assertEquals(siblingHistory,store.contextRevisions(siblingId)); assertEquals(schedule,store.reviewScheduleState())
            val request=ContextDeletionRequest(preview.expectedRevision,preview.digest,
                preview.sources.map { SourceRedaction(it.intakeId,it.revision,note.copy(content="approved replacement without marker")) },
                preview.survivors.map { v -> val c=v.context; ContextSurvivor(ContextConfirmationItem(AthleteContextRequest(c.category,c.sourceCategory,c.authorAttribution,c.observedOn,c.applicableFrom,c.applicableUntil,c.sport,c.activityId,c.reviewId,"approved sibling without marker",c.restrictionKind,c.restrictionValue,c.restrictionUnit),v.lifecycle,c.contextId,c.revision),intake.intakeId) },emptyList())
            val body=Json.encodeToString(request)
            assertEquals(HttpStatusCode.Forbidden,post(deletePath,body,token=null).status)
            assertEquals(HttpStatusCode.Forbidden,post(deletePath,body,from="https://untrusted.invalid").status)
            assertEquals(HttpStatusCode.Conflict,post(deletePath,Json.encodeToString(request.copy(survivors=emptyList()))).status)
            assertEquals(HttpStatusCode.Conflict,post(deletePath,Json.encodeToString(request.copy(previewDigest="0".repeat(64)))).status)
            assertEquals(intakeHistory,store.intakeHistory(intake.intakeId)); assertEquals(siblingHistory,store.contextRevisions(siblingId))
            assertEquals(HttpStatusCode.BadRequest,post(deletePath,body.dropLast(1)+",\"extra\":1}").status)
            assertEquals(HttpStatusCode.BadRequest,post(deletePath,"{\"expectedRevision\":1,\"expectedRevision\":1}").status)
            assertEquals(HttpStatusCode.BadRequest,post(deletePath,String(byteArrayOf(0xc3.toByte(),0x28),Charsets.UTF_8)).status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(deletePath," ".repeat(32_769)).status)
            val resultResponse=post(deletePath,body); assertEquals(HttpStatusCode.OK,resultResponse.status)
            val result=Json.decodeFromString<ContextDeletionResult>(resultResponse.bodyAsText()); assertTrue(result.notice.contains("encrypted_backups_follow_retention"))
            assertFailsWith<ContextNotFound> { store.contextRevisions(targetId) }
            assertEquals(listOf(2),store.contextRevisions(siblingId).map { it.revision })
            assertEquals(listOf(2),store.intakeHistory(intake.intakeId).map { it.revision })
            assertFalse(store.intakeHistory(intake.intakeId).any { it.content.contains("MARKER") })
            assertFalse(store.contextRevisions(siblingId).any { it.content.contains("MARKER") })
            assertEquals(schedule,store.reviewScheduleState()); assertEquals(0,source.calls); assertTrue(model.packets.isEmpty())
            assertEquals(HttpStatusCode.NotFound,get(previewPath).status)
            assertEquals(HttpStatusCode.Conflict,client.post(confirmPath) { header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf);contentType(ContentType.Application.Json);setBody(Json.encodeToString(confirmation)) }.status)
        } } finally { history.close();reviews.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}

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
import kotlin.test.*

class CommitmentApiTest {
    @Test fun `commitments require authenticated explicit source-fenced confirmation`() {
        val dir=Files.createTempDirectory("synthetic-commitment-api-")
        val store=HistoryStore(dir.resolve("history.db")); val source=SyntheticSource()
        val at=Instant.parse("2026-10-15T12:00:00Z"); val clock=Clock.fixed(at,ZoneOffset.UTC)
        val history=HistoryService(store,source,clock); val model=SyntheticReviewModel()
        val reviews=ReviewInterpretationService(listOf(model))
        val intake=store.createIntake(ContextIntakeRequest("Private source wording", "user_report", "athlete", "2026-10-15"),at)
        val before=store.reviewScheduleState(); val origin="http://127.0.0.1:8080"
        val definition=CommitmentDefinition("Synthetic private label","commitment","UTC","2026-10-16T23:00","2026-10-17T01:00",
            CommitmentRecurrence("daily",1,"2026-10-18"))
        val confirmation=CommitmentConfirmation(intake.intakeId,1,definition)
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),history,reviews,schedules=null,reviewQueue=null) }
                for(path in listOf("/api/commitments","/api/commitments/${"0".repeat(8)}-0000-4000-8000-000000000000/history","/api/commitments/calendar"))
                    assertEquals(HttpStatusCode.Unauthorized,client.get(path).status)
                assertEquals(HttpStatusCode.Unauthorized,client.post("/api/commitments/confirm").status)
                val login=client.post("/api/login") { header(HttpHeaders.Origin,origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun send(method:HttpMethod,path:String,body:Any?=null,token:String?=csrf,from:String=origin)=client.request(path) {
                    this.method=method; header(HttpHeaders.Cookie,cookie); header(HttpHeaders.Origin,from)
                    token?.let { header("X-CSRF-Token",it) }
                    if(body!=null) { contentType(ContentType.Application.Json); setBody(body) }
                }
                val encoded=Json.encodeToString(confirmation)
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"/api/commitments/confirm",encoded,null).status)
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"/api/commitments/confirm",encoded,from="https://untrusted.invalid").status)
                val created=send(HttpMethod.Post,"/api/commitments/confirm",encoded)
                assertEquals(HttpStatusCode.Created,created.status); assertEquals("no-store",created.headers[HttpHeaders.CacheControl])
                val confirmed=Json.decodeFromString<ConfirmedCommitment>(created.bodyAsText())
                assertEquals("authenticated_user",confirmed.enteredBy); assertEquals(at.toString(),confirmed.confirmedUtc)
                assertEquals("athlete",confirmed.authorAttribution); assertEquals("user_report",confirmed.sourceCategory)
                assertEquals(intake.intakeId,confirmed.reference.intakeId)
                val path="/api/commitments/${confirmed.reference.commitmentId}"
                assertEquals(listOf(confirmed),Json.decodeFromString<List<ConfirmedCommitment>>(send(HttpMethod.Get,"/api/commitments").bodyAsText()))
                assertEquals(listOf(confirmed),Json.decodeFromString<List<ConfirmedCommitment>>(send(HttpMethod.Get,"$path/history").bodyAsText()))
                val query="?oldest=2026-10-16&newest=2026-10-18&timeZone=UTC"
                val calendarResponse=send(HttpMethod.Get,"/api/commitments/calendar$query")
                assertEquals("no-store",calendarResponse.headers[HttpHeaders.CacheControl])
                val calendar=Json.decodeFromString<CommitmentCalendar>(calendarResponse.bodyAsText())
                assertEquals(3,calendar.occurrences.size); assertEquals(3,calendar.occurrences.map { it.occurrenceId }.distinct().size)
                assertEquals("2026-10-16T23:00",calendar.occurrences.first().startsLocal)
                assertEquals("2026-10-17T01:00",calendar.occurrences.first().endsLocal)
                assertTrue(calendar.occurrences.all { it.authoritative && it.reference.revision==1 })
                val correction=Json.encodeToString(CommitmentCorrection(1,confirmation.copy(definition=definition.copy(label="Corrected label"))))
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"$path/confirm",correction,null).status)
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"$path/confirm",correction,from="https://untrusted.invalid").status)
                assertEquals(HttpStatusCode.OK,send(HttpMethod.Post,"$path/confirm",correction).status)
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Post,"$path/confirm",correction).status)
                val changed=Json.decodeFromString<CommitmentCalendar>(send(HttpMethod.Get,"/api/commitments/calendar$query").bodyAsText())
                assertEquals(calendar.occurrences.map { it.occurrenceId },changed.occurrences.map { it.occurrenceId })
                assertTrue(changed.occurrences.all { it.reference.revision==2 })
                val sourceRevision=store.correctIntake(intake.intakeId,ContextIntakeCorrection(1,
                    ContextIntakeRequest("Changed private source","coach_guidance","coach","2026-10-15")),at)
                val stale=Json.decodeFromString<CommitmentCalendar>(send(HttpMethod.Get,"/api/commitments/calendar$query").bodyAsText())
                assertTrue(stale.constraintsBlocked)
                assertTrue(stale.occurrences.all { !it.authoritative })
                assertEquals(listOf(2),stale.needsReconfirmation.map { it.revision })
                assertEquals("athlete",store.commitmentHistory(confirmed.reference.commitmentId).first().authorAttribution)
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Post,"$path/confirm",Json.encodeToString(CommitmentCorrection(2,confirmation))).status)
                val reconfirm=CommitmentCorrection(2,confirmation.copy(expectedIntakeRevision=sourceRevision.revision))
                assertEquals(HttpStatusCode.OK,send(HttpMethod.Post,"$path/confirm",Json.encodeToString(reconfirm)).status)
                val fresh=Json.decodeFromString<CommitmentCalendar>(send(HttpMethod.Get,"/api/commitments/calendar$query").bodyAsText())
                assertFalse(fresh.constraintsBlocked)
                assertTrue(fresh.occurrences.all { it.authoritative && it.reference.revision==3 })
                assertEquals("coach",store.commitmentHistory(confirmed.reference.commitmentId).first().authorAttribution)
                for(bad in listOf("?oldest=2026-10-16&newest=2027-10-18&timeZone=UTC","?oldest=2026-10-16&newest=2026-10-18",
                    "$query&timeZone=UTC","?oldest=2026-10-16&newest=2026-10-18&timeZone=bad-zone"))
                    assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Get,"/api/commitments/calendar$bad").status)
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Get,"/api/commitments/not-an-id/history").status)
                assertEquals(HttpStatusCode.NotFound,send(HttpMethod.Get,"/api/commitments/00000000-0000-4000-8000-000000000000/history").status)
                val spoof=encoded.dropLast(1)+",\"enteredBy\":\"clinician\"}"
                val rejected=send(HttpMethod.Post,"/api/commitments/confirm",spoof)
                assertEquals(HttpStatusCode.BadRequest,rejected.status); assertFalse(rejected.bodyAsText().contains("Private source wording"))
                for(body in listOf(encoded.dropLast(1)+",\"intakeId\":\"${intake.intakeId}\"}",
                    encoded.replace("daily","monthly"),encoded.replace("2026-10-17T01:00","2026-10-15T01:00"),
                    encoded.dropLast(1)+",\"authorAttribution\":\"spoofed\"}")) {
                    val invalid=send(HttpMethod.Post,"/api/commitments/confirm",body)
                    assertEquals(HttpStatusCode.BadRequest,invalid.status)
                    assertEquals("{\"error\":\"invalid_request\"}",invalid.bodyAsText())
                }
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Post,"/api/commitments/confirm",byteArrayOf(0xc3.toByte(),0x28)).status)
                assertEquals(HttpStatusCode.PayloadTooLarge,send(HttpMethod.Post,"/api/commitments/confirm","x".repeat(32_769)).status)
                assertEquals(1,store.commitments().size); assertEquals(before,store.reviewScheduleState())
                assertEquals(0,source.calls); assertTrue(model.packets.isEmpty())
            }
        } finally {
            history.close(); reviews.close()
            Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir)
        }
    }
}

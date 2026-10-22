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
import kotlin.test.*

class ContextIntakeApiTest {
    @Test fun `intake provenance is bounded authenticated inert and revision fenced`() {
        val dir = Files.createTempDirectory("synthetic-intake-api-")
        val store = HistoryStore(dir.resolve("history.db"))
        val source = SyntheticSource()
        val at = Instant.parse("2026-10-15T12:00:00Z")
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val history = HistoryService(store, source, clock)
        val model = SyntheticReviewModel()
        val reviews = ReviewInterpretationService(listOf(model))
        val input = ContextIntakeRequest("Synthetic private marker: improving illness and Wednesday climbing.", "user_report", "athlete", "2026-10-15")
        val context = store.createContext(AthleteContextRequest("symptom", "user_report", "athlete", input.observedOn, content="Synthetic improving illness"))
        val before = store.reviewScheduleState()
        val origin = "http://127.0.0.1:8080"
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, reviews,
                    schedules=null, reviewQueue=null) }
                for (path in listOf("/api/context-intakes", "/api/context-intakes/${context.contextId}/history", "/api/context-intakes/${context.contextId}/links")) {
                    assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
                    assertEquals(HttpStatusCode.Unauthorized, client.post(path).status)
                }
                val login = client.post("/api/login") { header(HttpHeaders.Origin,origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun send(method: HttpMethod, path: String, body: Any? = null, token: String? = csrf, from: String = origin) = client.request(path) {
                    this.method=method; header(HttpHeaders.Cookie,cookie); header(HttpHeaders.Origin,from)
                    token?.let { header("X-CSRF-Token",it) }
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }
                val encoded = Json.encodeToString(input)
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"/api/context-intakes",encoded,token=null).status)
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"/api/context-intakes",encoded,from="https://untrusted.invalid").status)
                assertTrue(store.intakes().isEmpty())
                val created = send(HttpMethod.Post,"/api/context-intakes",encoded)
                assertEquals(HttpStatusCode.Created,created.status)
                assertEquals("no-store",created.headers[HttpHeaders.CacheControl])
                val note = Json.decodeFromString<ContextIntake>(created.bodyAsText())
                assertEquals("authenticated_user",note.enteredBy)
                assertEquals(at.toString(),note.recordedUtc)
                assertEquals(input.content,note.content)
                assertEquals(listOf(note),Json.decodeFromString<List<ContextIntake>>(send(HttpMethod.Get,"/api/context-intakes").bodyAsText()))
                val path = "/api/context-intakes/${note.intakeId}"
                val association = Json.encodeToString(ContextIntakeAssociation(1,context.contextId,context.revision))
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Post,"$path/links",association,token=null).status)
                assertEquals(HttpStatusCode.OK,send(HttpMethod.Post,"$path/links",association).status)
                assertEquals(HttpStatusCode.OK,send(HttpMethod.Post,"$path/links",association).status)
                assertEquals(1,store.intakeLinks(note.intakeId).size)
                assertEquals(context,store.contextRevisions(context.contextId).single())
                fun snapshot(name: String, contexts: List<AthleteContext>): ConnectedReviewStatus {
                    val evidence = AnalysisInput(1,"a".repeat(64),at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
                    return store.createConnectedReviewSnapshot(ConnectedReviewBinding(name,evidence,
                        retrieveAthleteContext(contexts,ContextRetrievalQuery(LocalDate.parse(input.observedOn),hardPacketLimit=10)),
                        input.observedOn,input.observedOn,null,ConnectedReviewProviderSelection("synthetic","synthetic","local"),InterpretationContractV1.PROFILE),at)
                }
                val dependent = snapshot("intake-dependent",listOf(context))
                val unrelated = snapshot("intake-unrelated",emptyList())
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Put,"/api/contexts/${context.contextId}",
                    Json.encodeToString(AthleteContextRequest("symptom","user_report","athlete",input.observedOn,content="bypass"))).status)
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Delete,"/api/contexts/${context.contextId}").status)
                val correction = Json.encodeToString(ContextIntakeCorrection(1,input.copy(content="Synthetic corrected private marker")))
                assertEquals(HttpStatusCode.Forbidden,send(HttpMethod.Put,path,correction,token=null).status)
                assertEquals(HttpStatusCode.OK,send(HttpMethod.Put,path,correction).status)
                assertEquals("STALE",store.connectedReviewStatus(dependent.snapshotId).state)
                assertEquals(unrelated,store.connectedReviewStatus(unrelated.snapshotId))
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Put,path,correction).status)
                assertEquals(HttpStatusCode.Conflict,send(HttpMethod.Post,"$path/links",association).status)
                val links=Json.decodeFromString<List<ContextIntakeLink>>(send(HttpMethod.Get,"$path/links").bodyAsText())
                assertTrue(links.single().sourceChanged)
                assertEquals(listOf(2,1),Json.decodeFromString<List<ContextIntake>>(send(HttpMethod.Get,"$path/history").bodyAsText()).map { it.revision })
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Get,"/api/context-intakes/not-an-id/history").status)
                assertEquals(HttpStatusCode.NotFound,send(HttpMethod.Get,"/api/context-intakes/00000000-0000-4000-8000-000000000000/history").status)

                val invalid = listOf(encoded.dropLast(1)+",\"enteredBy\":\"clinician\"}",
                    encoded.dropLast(1)+",\"content\":\"duplicate\"}",
                    encoded.replace("2026-10-15","2026-02-30"),
                    Json.encodeToString(input).replace("user_report","review_feedback"),
                    encoded.replace("athlete"," "), encoded.replace(input.content,"x".repeat(4001)))
                for (body in invalid) {
                    val result=send(HttpMethod.Post,"/api/context-intakes",body)
                    assertEquals(HttpStatusCode.BadRequest,result.status)
                    assertEquals("{\"error\":\"invalid_request\"}",result.bodyAsText())
                    assertFalse(result.bodyAsText().contains("private marker"))
                }
                assertEquals(HttpStatusCode.BadRequest,send(HttpMethod.Post,"/api/context-intakes",byteArrayOf(0xc3.toByte(),0x28)).status)
                // The raw UTF-8 byte limit is enforced before field validation.
                val oversized = encoded.replace(input.content,"界".repeat(6000))
                assertEquals(HttpStatusCode.PayloadTooLarge,send(HttpMethod.Post,"/api/context-intakes",oversized).status)
                assertEquals(1,store.intakes().size)
                assertEquals(2,store.intakeHistory(note.intakeId).size)
                assertEquals(before,store.reviewScheduleState())
                assertEquals(listOf(context),store.contexts(LocalDate.parse(input.observedOn)))
                assertEquals(0,source.calls)
                assertTrue(model.packets.isEmpty())
            }
        } finally {
            history.close(); reviews.close()
            Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir)
        }
    }
}

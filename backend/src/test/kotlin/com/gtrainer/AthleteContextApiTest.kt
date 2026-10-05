package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*

class AthleteContextApiTest {
    @Test fun `context CRUD attributes supersedes filters and validates privately`() {
        val directory = Files.createTempDirectory("synthetic-context-api-")
        val store = HistoryStore(directory.resolve("context.sqlite3"))
        val origin = "http://127.0.0.1:8080"
        val history = HistoryService(store, SyntheticSource())
        val today = LocalDate.now()
        val evidence = AnalysisInput(1, "a".repeat(64), today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
            null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val snapshot = store.createConnectedReviewSnapshot(
            ConnectedReviewBinding("api-feedback", evidence,
                retrieveAthleteContext(emptyList(), ContextRetrievalQuery(today, hardPacketLimit = 10)),
                today.toString(), today.toString(), null,
                ConnectedReviewProviderSelection("synthetic", "synthetic", "local"), InterpretationContractV1.PROFILE),
            java.time.Instant.now())
        store.publishConnectedReview(snapshot.snapshotId, ValidatedConnectedReviewOutput("synthetic review"),
            snapshot.evidenceDigest, snapshot.context, snapshot.provider, snapshot.model, snapshot.contractVersion)
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, analysis = AnalysisService(emptyList())) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/contexts").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/contexts") { header(HttpHeaders.Origin, origin) }.status)
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun request(method: HttpMethod, path: String, body: String? = null, token: String? = csrf) = client.request(path) {
                    this.method = method; header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin)
                    token?.let { header("X-CSRF-Token", it) }; if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }
                val input = AthleteContextRequest("restriction", "clinician_guidance", "clinician, as reported by user", today.toString(), applicableFrom = today.toString(),
                    applicableUntil = today.plusDays(10).toString(), sport = "Run", content = "synthetic private marker",
                    restrictionKind = "blocked_sport", restrictionValue = "Run")
                assertEquals(HttpStatusCode.Forbidden, request(HttpMethod.Post,"/api/contexts",Json.encodeToString(input),null).status)
                val createdResponse = request(HttpMethod.Post,"/api/contexts",Json.encodeToString(input))
                assertEquals(HttpStatusCode.Created,createdResponse.status)
                val created = Json.decodeFromString<AthleteContext>(createdResponse.bodyAsText())
                assertEquals("authenticated_user",created.enteredBy)
                assertEquals("clinician_guidance", created.sourceCategory)
                assertFalse(created.toString().contains("synthetic private marker"))
                assertEquals(listOf(created.contextId), history.athleteContexts().retrieve(
                    ContextRetrievalQuery(today, sport = "Run", optionalLimit = 0)).mandatoryRestrictions.map { it.contextId })
                val preview = request(HttpMethod.Get, "/api/context-retrieval?asOf=$today&sport=Run&optionalLimit=0")
                assertEquals(HttpStatusCode.OK, preview.status)
                val previewBody = Json.decodeFromString<ContextRetrievalResult>(preview.bodyAsText())
                assertEquals(listOf(created.contextId), previewBody.mandatoryRestrictions.map { it.contextId })
                assertEquals(listOf(1), previewBody.mandatoryRestrictions.map { it.revision })
                assertEquals(listOf("clinician_guidance"), previewBody.mandatoryRestrictions.map { it.sourceCategory })
                assertTrue(previewBody.reviewBlocked) // No final packet budget exists yet.
                assertEquals(listOf(created), Json.decodeFromString<List<AthleteContext>>(request(HttpMethod.Get,"/api/contexts").bodyAsText()))
                val correction = input.copy(content="corrected synthetic marker")
                assertEquals(HttpStatusCode.OK,request(HttpMethod.Put,"/api/contexts/${created.contextId}",Json.encodeToString(correction)).status)
                assertEquals(2,store.contexts(today).single().revision)
                val revisions = Json.decodeFromString<List<AthleteContext>>(
                    request(HttpMethod.Get, "/api/contexts/${created.contextId}/history").bodyAsText())
                assertEquals(listOf(2, 1), revisions.map { it.revision })
                assertEquals("synthetic private marker", revisions.last().content)
                assertTrue(store.contexts(today.plusDays(11)).isEmpty())
                val expired = request(HttpMethod.Post,"/api/contexts",Json.encodeToString(input.copy(content="expired",applicableFrom=null,applicableUntil=today.minusDays(1).toString())))
                val expiredId=Json.decodeFromString<AthleteContext>(expired.bodyAsText()).contextId
                assertEquals(1,store.contexts(today).size)
                assertEquals(HttpStatusCode.NoContent,request(HttpMethod.Post,"/api/contexts/${created.contextId}/retire").status)
                assertTrue(store.contexts(today).isEmpty())
                assertEquals(HttpStatusCode.NoContent,request(HttpMethod.Delete,"/api/contexts/$expiredId").status)
                assertFalse(store.contexts(today).any { it.contextId == expiredId })
                val invalid = Json.encodeToString(input).replace(today.toString(), "not-a-date")
                assertEquals(HttpStatusCode.BadRequest,request(HttpMethod.Post,"/api/contexts",invalid).status)
                val feedback = request(HttpMethod.Post, "/api/connected-reviews/${snapshot.snapshotId}/feedback",
                    Json.encodeToString(ReviewFeedbackRequest(rating = "useful", correction = "synthetic correction")))
                assertEquals(HttpStatusCode.Created, feedback.status)
                val savedFeedback = Json.decodeFromString<AthleteContext>(feedback.bodyAsText())
                assertEquals("review_feedback", savedFeedback.sourceCategory)
                assertEquals(snapshot.snapshotId, savedFeedback.reviewId)
            }
        } finally {
            history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

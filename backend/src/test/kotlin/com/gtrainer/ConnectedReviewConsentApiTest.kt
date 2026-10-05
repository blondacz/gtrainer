package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectedReviewConsentApiTest {
    @Test fun `hosted preview and consent are authenticated packet-bound and revoke before transport`() {
        val directory = Files.createTempDirectory("synthetic-connected-consent-")
        val store = HistoryStore(directory.resolve("connected.sqlite3"))
        val origin = "http://127.0.0.1:8080"
        val today = LocalDate.parse("2025-01-07")
        val input = AnalysisInput(1, "a".repeat(64), "2025-01-07T00:00:00Z", null,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val selection = ConnectedReviewProviderSelection("synthetic-hosted", "synthetic-model", "hosted")
        val packet = InterpretationContractV1.packet(input,
            retrieveAthleteContext(emptyList(), ContextRetrievalQuery(today, hardPacketLimit = 10)))
        val snapshot = store.createConnectedReviewSnapshot(ConnectedReviewBinding("consent-api", input,
            retrieveAthleteContext(emptyList(), ContextRetrievalQuery(today, hardPacketLimit = 10)), today.toString(),
            today.toString(), null, selection, InterpretationContractV1.PROFILE), Instant.parse("2025-01-07T00:00:00Z"))
        var networkRequests = 0
        val model = object : ConnectedReviewProvider {
            override val providerId = selection.providerId
            override val modelId = selection.modelId
            override val hosted = true
            override val enforcedMaximumCostUsd = BigDecimal("0.25")
            override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                                          costLimitUsd: BigDecimal?) = run {
                networkRequests++
                check(costLimitUsd == BigDecimal("0.25"))
                ConnectedReviewProviderReply("""{"profile":"connected-review-v1","interpretations":[],"questions":[]}""",
                    BigDecimal("0.01"))
            }
        }
        val connected = ConnectedReviewService(store, ConnectedReviewProviderCatalog(listOf(model)),
            qualificationAndHealth = ConnectedReviewExecutionGate { _, _ -> null })
        val history = HistoryService(store, SyntheticSource())
        try {
            testApplication {
                application {
                    module(SingleUserAuth(syntheticVerifier(), origin, false, true), history,
                        analysis = AnalysisService(emptyList()), reviews = ReviewInterpretationService(),
                        schedules = null, reviewQueue = null, events = null, contexts = null, connectedReviews = connected)
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/connected-review/providers").status)
                assertEquals(HttpStatusCode.Unauthorized,
                    client.get("/api/connected-reviews/${snapshot.snapshotId}/preview").status)
                val login = client.post("/api/login") {
                    header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
                }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun request(method: HttpMethod, path: String, body: String? = null) = client.request(path) {
                    this.method = method
                    header(HttpHeaders.Cookie, cookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }

                val providers = request(HttpMethod.Get, "/api/connected-review/providers")
                assertEquals(HttpStatusCode.OK, providers.status)
                assertTrue(providers.bodyAsText().contains("synthetic-hosted"))
                val previewResponse = request(HttpMethod.Get, "/api/connected-reviews/${snapshot.snapshotId}/preview")
                assertEquals(HttpStatusCode.OK, previewResponse.status)
                val preview = Json.decodeFromString<ConnectedReviewPreview>(previewResponse.bodyAsText())
                assertEquals(selection, preview.providerSelection)
                assertEquals(snapshot.packetDigest, preview.packetDigest)
                assertEquals(InterpretationContractV1.promptSha256(packet), preview.packetDigest)
                assertTrue(preview.exactPrompt.userPacketJson.contains("evidenceReportSha256"))
                val inspectionResponse = request(HttpMethod.Get, "/api/connected-reviews/${snapshot.snapshotId}")
                assertEquals(HttpStatusCode.OK, inspectionResponse.status)
                val inspection = Json.decodeFromString<ConnectedReviewInspection>(inspectionResponse.bodyAsText())
                assertTrue(inspection.packetAvailable)
                assertEquals(snapshot.snapshotId, inspection.status.snapshotId)
                assertEquals(preview.exactPrompt, inspection.exactPrompt)

                val runBody = Json.encodeToString(ConnectedReviewRunRequest(selection))
                val withoutConsent = Json.decodeFromString<ConnectedReviewExecutionResult>(
                    request(HttpMethod.Post, "/api/connected-reviews/${snapshot.snapshotId}/run", runBody).bodyAsText())
                assertEquals("consent_required", withoutConsent.reason)
                assertEquals(0, networkRequests)

                val invalidConsent = ConnectedReviewConsentRequest(selection, "b".repeat(64), true)
                assertEquals(HttpStatusCode.BadRequest, request(HttpMethod.Post,
                    "/api/connected-reviews/${snapshot.snapshotId}/consent", Json.encodeToString(invalidConsent)).status)
                assertEquals(0, networkRequests)

                val validConsent = ConnectedReviewConsentRequest(selection, preview.packetDigest, true)
                assertEquals(HttpStatusCode.OK, request(HttpMethod.Post,
                    "/api/connected-reviews/${snapshot.snapshotId}/consent", Json.encodeToString(validConsent)).status)
                assertEquals(HttpStatusCode.NoContent, request(HttpMethod.Delete,
                    "/api/connected-reviews/${snapshot.snapshotId}/consent").status)
                val revoked = Json.decodeFromString<ConnectedReviewExecutionResult>(
                    request(HttpMethod.Post, "/api/connected-reviews/${snapshot.snapshotId}/run", runBody).bodyAsText())
                assertEquals("consent_required", revoked.reason)
                assertEquals(0, networkRequests)

                assertEquals(HttpStatusCode.OK, request(HttpMethod.Post,
                    "/api/connected-reviews/${snapshot.snapshotId}/consent", Json.encodeToString(validConsent)).status)
                val completed = Json.decodeFromString<ConnectedReviewExecutionResult>(
                    request(HttpMethod.Post, "/api/connected-reviews/${snapshot.snapshotId}/run", runBody).bodyAsText())
                assertEquals("PUBLISHED", completed.state)
                assertEquals(1, networkRequests)
            }
        } finally {
            history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConnectedReviewPrivacyTest {
    @Test fun `context prompt credential and rejected output markers stay out of logs and API failures`() {
        val contextMarker = "synthetic-context-privacy-marker"
        val credentialMarker = "synthetic-credential-privacy-marker"
        val rejectedMarker = "synthetic-rejected-output-privacy-marker"
        val directory = Files.createTempDirectory("synthetic-connected-privacy-")
        val store = HistoryStore(directory.resolve("privacy.sqlite3"))
        val date = LocalDate.parse("2025-01-07")
        val origin = "http://127.0.0.1:8080"
        val entry = store.createContext(AthleteContextRequest("note", "user_report", "synthetic user", date.toString(),
            content = contextMarker))
        val evidence = AnalysisInput(1, "a".repeat(64), "2025-01-07T00:00:00Z", null,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val retrieval = retrieveAthleteContext(listOf(entry), ContextRetrievalQuery(date, hardPacketLimit = 10))
        val selection = ConnectedReviewProviderSelection("privacy-hosted", "synthetic-model", "hosted")
        val snapshot = store.createConnectedReviewSnapshot(ConnectedReviewBinding("privacy-review", evidence, retrieval,
            date.toString(), date.toString(), null, selection, InterpretationContractV1.PROFILE),
            Instant.parse("2025-01-07T00:00:00Z"))
        var attempts = 0
        var lastPrompt: InterpretationPromptV1? = null
        val provider = object : ConnectedReviewProvider {
            override val providerId = selection.providerId
            override val modelId = selection.modelId
            override val hosted = true
            override val enforcedMaximumCostUsd = BigDecimal("0.25")
            override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                                          costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
                lastPrompt = prompt
                attempts++
                if (attempts == 1) return ConnectedReviewProviderReply(rejectedMarker, BigDecimal("0.01"))
                throw IllegalStateException(credentialMarker)
            }
        }
        val connected = ConnectedReviewService(store, ConnectedReviewProviderCatalog(listOf(provider)),
            qualificationAndHealth = ConnectedReviewExecutionGate { _, _ -> null })
        val preview = connected.preview(snapshot.snapshotId)
        connected.consent(snapshot.snapshotId,
            ConnectedReviewConsentRequest(selection, preview.packetDigest, approved = true))
        val history = HistoryService(store, SyntheticSource())
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
            testApplication {
                application {
                    module(SingleUserAuth(syntheticVerifier(), origin, false, true), history,
                        analysis = AnalysisService(emptyList()), reviews = ReviewInterpretationService(),
                        schedules = null, reviewQueue = null, events = null, contexts = null, connectedReviews = connected)
                }
                val login = client.post("/api/login") {
                    header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
                }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                val result = client.post("/api/connected-reviews/${snapshot.snapshotId}/run") {
                    header(HttpHeaders.Cookie, cookie)
                    header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(ConnectedReviewRunRequest(selection)))
                }
                assertEquals(HttpStatusCode.OK, result.status)
                assertFalse(result.bodyAsText().contains(rejectedMarker))
                assertFalse(result.bodyAsText().contains(credentialMarker))
            }
        } finally {
            System.setErr(originalErr)
            history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
        val logs = capturedErr.toString(Charsets.UTF_8)
        assertFalse(logs.contains(contextMarker))
        assertFalse(logs.contains(credentialMarker))
        assertFalse(logs.contains(rejectedMarker))
        assertEquals(2, attempts)
        assertFalse(lastPrompt.toString().contains(contextMarker))
        assertFalse(lastPrompt.toString().contains(credentialMarker))
    }
}

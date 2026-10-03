package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ReviewInterpretationApiTest {
    private val origin = "http://127.0.0.1:8080"
    private val trendPath = "/api/trends?oldest=2020-06-01&newest=2020-06-02"

    @Test fun `explicit separate profile requires authentication Origin CSRF and immutable binding while facts remain independent`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-review-api-")
        val source = SyntheticSource().apply {
            activityResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().activities)
            wellnessResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().wellness)
        }
        val history = HistoryService(HistoryStore(directory.resolve("synthetic.sqlite3")), source,
            Clock.fixed(Instant.parse("2020-06-10T00:00:00Z"), ZoneOffset.UTC))
        runBlocking { history.sync(ReadRange(LocalDate.parse("2020-05-30"), LocalDate.parse("2020-06-02"))) }
        val model = SyntheticReviewModel { packet, feedback ->
            if (feedback == null) """{"focusIds":["${packet.candidates.first().id}"],"text":"private rejected medical claim"}"""
            else relevantFocus(packet)
        }
        val prototype = AnalysisService()
        val reviews = ReviewInterpretationService(listOf(model), ReviewExecutionPolicy(100, 1000, true), { true }, prototype.inferenceGate())
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, prototype, reviews) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/review-models").status)
                assertEquals(HttpStatusCode.Unauthorized, client.put("/api/review-models").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/review-interpretation").status)
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun write(path: String, body: String) = client.request(path) {
                    method = if (path == "/api/review-models") HttpMethod.Put else HttpMethod.Post
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json); setBody(body)
                }
                val initial = client.get("/api/review-models") { header(HttpHeaders.Cookie, cookie) }
                assertEquals("no-store", initial.headers[HttpHeaders.CacheControl])
                assertFalse(Json.decodeFromString<ReviewModelStatus>(initial.bodyAsText()).enabled)
                val reportBody = client.get(trendPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
                val report = Json.decodeFromString<TrendReport>(reportBody)
                val factualPath = "/api/review-facts?oldest=2020-06-01&newest=2020-06-02&evidenceReportSha256=${Trends.analysisInput(report).evidenceReportSha256}"
                val factsBefore = client.get(factualPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
                val off = write("/api/review-interpretation", ReviewFocusProtocol.json.encodeToString(reviewRequest(report, reviews.status())))
                assertEquals("interpretation_disabled", Json.decodeFromString<ReviewInterpretationResponse>(off.bodyAsText()).reason)
                for (path in listOf("/api/review-models", "/api/review-interpretation")) {
                    for (requestOrigin in listOf(null, "https://hosted.invalid")) {
                        assertEquals(HttpStatusCode.Forbidden, client.request(path) {
                            method = if (path == "/api/review-models") HttpMethod.Put else HttpMethod.Post
                            header(HttpHeaders.Cookie, cookie); requestOrigin?.let { header(HttpHeaders.Origin, it) }
                            header("X-CSRF-Token", csrf); setBody("{}")
                        }.status)
                    }
                }
                assertTrue(model.packets.isEmpty())
                assertEquals(HttpStatusCode.BadRequest, write("/api/review-models", """{"profile":"prepared-focus-v1","modelId":"${model.option.id}","enabled":true}""").status)
                val selection = write("/api/review-models", ReviewFocusProtocol.json.encodeToString(reviewSelection(correction = true)))
                val selected = Json.decodeFromString<ReviewModelStatus>(selection.bodyAsText())
                assertTrue(selected.enabled && selected.correctionEnabled)
                assertEquals(0, model.packets.size)
                assertNull(prototype.status().selectedModelId) // No cross-profile selection or silent contract replacement.
                val request = reviewRequest(report, selected)
                assertEquals(HttpStatusCode.BadRequest, write("/api/review-interpretation", "{\"profile\":\"x\",\"profile\":\"y\"}").status)
                assertEquals(HttpStatusCode.PayloadTooLarge, write("/api/review-interpretation", " ".repeat(4097)).status)
                val stale = write("/api/review-interpretation", ReviewFocusProtocol.json.encodeToString(request.copy(evidenceReportSha256 = "b".repeat(64))))
                assertEquals("evidence_changed", Json.decodeFromString<ReviewInterpretationResponse>(stale.bodyAsText()).reason)
                assertTrue(model.packets.isEmpty())
                val generated = write("/api/review-interpretation", ReviewFocusProtocol.json.encodeToString(request))
                assertEquals(HttpStatusCode.OK, generated.status)
                assertEquals("no-store", generated.headers[HttpHeaders.CacheControl])
                val result = Json.decodeFromString<ReviewInterpretationResponse>(generated.bodyAsText())
                assertEquals("review-focus-app-v1", result.profile)
                assertEquals("available", result.status)
                assertEquals(listOf("rejected", "accepted"), result.attempts.map { it.status })
                assertEquals(listOf(null, "invalid_shape"), model.feedback)
                assertEquals(request.evidenceReportSha256, result.evidenceReportSha256)
                assertTrue(result.interpretations.single().supportingFacts.any { it.comparison.key == "hrv" })
                assertFalse(generated.bodyAsText().contains("private rejected medical claim"))
                assertFalse(generated.bodyAsText().contains(SYNTHETIC_PASSWORD))
                assertFalse(generated.bodyAsText().contains("sourceRecordId"))
                assertEquals(factsBefore, client.get(factualPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
                assertEquals(reportBody, client.get(trendPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
                assertEquals(2, source.calls)
                write("/api/logout", "{}")
                assertEquals(HttpStatusCode.Unauthorized, write("/api/review-interpretation", ReviewFocusProtocol.json.encodeToString(request)).status)
            }
        } finally {
            history.close(); reviews.close(); prototype.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(directory)
        }
    }

    @Test fun `logout during interpretation denies publication without preventing factual reads`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-review-logout-")
        val source = SyntheticSource().apply {
            activityResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().activities)
            wellnessResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().wellness)
        }
        val history = HistoryService(HistoryStore(directory.resolve("synthetic.sqlite3")), source)
        runBlocking { history.sync(ReadRange(LocalDate.parse("2020-05-30"), LocalDate.parse("2020-06-02"))) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = SyntheticReviewModel { packet, _ -> started.complete(Unit); release.await(); relevantFocus(packet) }
        val reviews = ReviewInterpretationService(listOf(model), runtimeGuard = { true })
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, reviews = reviews) }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                val report = Json.decodeFromString<TrendReport>(client.get(trendPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
                val request = reviewRequest(report, reviews.select(reviewSelection()))
                coroutineScope {
                    val pending = async { client.post("/api/review-interpretation") {
                        header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                        setBody(ReviewFocusProtocol.json.encodeToString(request))
                    } }
                    withTimeout(5000) { started.await() }
                    assertEquals(HttpStatusCode.OK, withTimeout(5000) { client.get(trendPath) { header(HttpHeaders.Cookie, cookie) } }.status)
                    client.post("/api/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf); setBody("{}") }
                    release.complete(Unit)
                    val denied = pending.await()
                    assertEquals(HttpStatusCode.Unauthorized, denied.status)
                    assertFalse(denied.bodyAsText().contains("interpretations"))
                }
            }
        } finally {
            release.complete(Unit); history.close(); reviews.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(directory)
        }
    }
}

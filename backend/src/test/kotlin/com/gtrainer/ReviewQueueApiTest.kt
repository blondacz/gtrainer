package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ReviewQueueApiTest {
    @Test fun `private immediate queue accepts explicit requests without enabling schedules or bypassing guards`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-queue-api-")
        val store = HistoryStore(directory.resolve("synthetic.sqlite3"))
        val clock = ReviewTestClock(Instant.parse("2020-06-02T12:00:00Z"))
        val source = SyntheticSource()
        val history = HistoryService(store, source, clock)
        val model = SyntheticReviewModel()
        val reviews = ReviewInterpretationService(listOf(model)) // No guard: API cannot bypass the missing qualification.
        val queue = ReviewQueueService(store, reviews, clock)
        val json = ReviewFocusProtocol.json
        val origin = "http://127.0.0.1:8080"
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, reviews = reviews, reviewQueue = queue) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/review-queue").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/review-now").status)
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun write(path: String, body: String, token: String? = csrf, requestOrigin: String? = origin) = client.request(path) {
                    method = if (path == "/api/review-models" || path == "/api/review-schedules") HttpMethod.Put else HttpMethod.Post
                    header(HttpHeaders.Cookie, cookie); token?.let { header("X-CSRF-Token", it) }; requestOrigin?.let { header(HttpHeaders.Origin, it) }
                    contentType(ContentType.Application.Json); setBody(body)
                }
                suspend fun read() = client.get("/api/review-queue") { header(HttpHeaders.Cookie, cookie) }
                val request = json.encodeToString(ReviewNowRequest(0, "daily-combined"))
                assertEquals(HttpStatusCode.BadRequest, write("/api/review-now", request).status)
                assertEquals(HttpStatusCode.Forbidden, write("/api/review-now", request, token = null).status)
                assertEquals(HttpStatusCode.Forbidden, write("/api/review-now", request, requestOrigin = "https://external.invalid").status)
                val selected = write("/api/review-models", json.encodeToString(reviewSelection()))
                assertEquals(HttpStatusCode.OK, selected.status)
                assertEquals(HttpStatusCode.BadRequest, write("/api/review-now", """{"expectedVersion":0,"expectedVersion":0,"presetId":"daily-combined"}""").status)
                assertEquals(HttpStatusCode.PayloadTooLarge, write("/api/review-now", " ".repeat(4097)).status)
                assertEquals(HttpStatusCode.BadRequest, write("/api/review-now", json.encodeToString(ReviewNowRequest(0, "after-activity"))).status)
                val accepted = write("/api/review-now", request)
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertEquals("no-store", accepted.headers[HttpHeaders.CacheControl])
                val queued = json.decodeFromString<ReviewQueueStatus>(accepted.bodyAsText())
                assertEquals("runtime_guard_unavailable", queued.reason)
                assertFalse(queued.executionAvailable)
                assertEquals("interim", queued.pending.single().intent.level)
                assertTrue(queued.pending.single().manual)
                assertEquals(listOf("manual_request"), queued.pending.single().reasons)
                assertFalse(store.reviewScheduleState().configuration.enabled)
                val upgrade = write("/api/review-now", json.encodeToString(ReviewNowRequest(0, "daily-combined", "thorough")))
                assertEquals("thorough", json.decodeFromString<ReviewQueueStatus>(upgrade.bodyAsText()).pending.single().intent.level)
                assertTrue(model.packets.isEmpty())
                assertEquals(0, source.calls)
                val disabled = write("/api/review-models", json.encodeToString(reviewSelection(enabled = false)))
                assertEquals(HttpStatusCode.OK, disabled.status)
                val cancelled = json.decodeFromString<ReviewQueueStatus>(read().bodyAsText())
                assertTrue(cancelled.pending.isEmpty())
                assertEquals("model_selection_changed", cancelled.outcomes.last().reason)
                assertFalse(read().bodyAsText().contains(SYNTHETIC_PASSWORD))
                assertFalse(read().bodyAsText().contains("sourceRecordId"))
                client.post("/api/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf); setBody("{}") }
                assertEquals(HttpStatusCode.Unauthorized, read().status)
            }
        } finally {
            queue.close(); reviews.close(); history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(directory)
        }
    }
}

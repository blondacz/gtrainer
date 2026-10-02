package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ReviewScheduleApiTest {
    @Test fun `private preset API validates auth csrf strict bounded configuration and version without model calls`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-schedule-api-")
        val store = HistoryStore(directory.resolve("synthetic.sqlite3"))
        val source = SyntheticSource()
        val clock = ReviewTestClock(Instant.parse("2020-06-01T10:00:00Z"))
        val history = HistoryService(store, source, clock)
        val model = SyntheticReviewModel()
        val reviews = ReviewInterpretationService(listOf(model), runtimeGuard = { true })
        val scheduler = ReviewScheduler(store, reviews, clock)
        val json = Json { encodeDefaults = true }
        val origin = "http://127.0.0.1:8080"
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, reviews = reviews, schedules = scheduler) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/review-schedules").status)
                assertEquals(HttpStatusCode.Unauthorized, client.put("/api/review-schedules").status)
                val login = client.post("/api/login") {
                    header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
                }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun status() = client.get("/api/review-schedules") { header(HttpHeaders.Cookie, cookie) }
                suspend fun write(body: String, requestOrigin: String? = origin, csrfToken: String? = csrf) = client.put("/api/review-schedules") {
                    header(HttpHeaders.Cookie, cookie)
                    requestOrigin?.let { header(HttpHeaders.Origin, it) }; csrfToken?.let { header("X-CSRF-Token", it) }
                    contentType(ContentType.Application.Json); setBody(body)
                }
                val initialResponse = status()
                assertEquals(HttpStatusCode.OK, initialResponse.status)
                assertEquals("no-store", initialResponse.headers[HttpHeaders.CacheControl])
                val initial = json.decodeFromString<ReviewScheduleStatus>(initialResponse.bodyAsText())
                assertEquals(0, initial.configurationVersion)
                assertEquals("schedules_disabled", initial.reason)
                assertEquals(listOf("daily-combined", "after-activity", "weekly"), initial.configuration.presets.map { it.id })
                assertTrue(initial.configuration.presets.all { !it.enabled })
                val preset = ReviewPreset("custom", "daily_combined", enabled = true, scope = ReviewScope("rolling", 14), level = "thorough",
                    triggers = listOf(ReviewTrigger("evening", "daily_time", localTime = "20:00"), ReviewTrigger("steps", "daily_steps", threshold = 5000)))
                val config = ReviewScheduleConfiguration(enabled = true, timeZone = "Europe/Prague", presets = listOf(preset))
                val body = json.encodeToString(ReviewScheduleUpdate(0, config))
                assertEquals(HttpStatusCode.BadRequest, write(body).status) // Choosing only old /api/models would not qualify either.
                assertEquals(HttpStatusCode.Forbidden, write(body, requestOrigin = null).status)
                assertEquals(HttpStatusCode.Forbidden, write(body, requestOrigin = "https://remote.invalid").status)
                assertEquals(HttpStatusCode.Forbidden, write(body, csrfToken = null).status)
                assertEquals(HttpStatusCode.Forbidden, write(body, csrfToken = "invalid").status)
                reviews.select(reviewSelection())
                val saved = write(body)
                assertEquals(HttpStatusCode.OK, saved.status)
                assertEquals("no-store", saved.headers[HttpHeaders.CacheControl])
                val configured = json.decodeFromString<ReviewScheduleStatus>(saved.bodyAsText())
                assertEquals(1, configured.configurationVersion)
                assertEquals(config, configured.configuration)
                assertEquals("queue_not_configured", configured.reason)
                assertFalse(configured.executionAvailable)
                assertEquals("verified_daily_steps_unavailable", configured.triggers.single { it.kind == "daily_steps" }.reason)
                assertNull(configured.triggers.single { it.kind == "daily_steps" }.nextReviewUtc)
                assertEquals("2020-06-01T18:00:00Z", configured.triggers.single { it.kind == "daily_time" }.nextReviewUtc)
                assertEquals(HttpStatusCode.Conflict, write(body).status)
                for (invalid in listOf(
                    """{"expectedVersion":1,"expectedVersion":1,"configuration":{}}""",
                    """{"expectedVersion":1,"configuration":{"timeZone":"Invalid/Zone"}}""",
                    """{"expectedVersion":1,"configuration":{"extra":"unsupported"}}""",
                    body.replace("\"daily_time\"", "\"google_calendar\"").replace("\"expectedVersion\":0", "\"expectedVersion\":1"),
                    body.replace("\"callTimeoutMillis\":120000", "\"callTimeoutMillis\":240001").replace("\"expectedVersion\":0", "\"expectedVersion\":1"),
                )) assertEquals(HttpStatusCode.BadRequest, write(invalid).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, write(" ".repeat(32_769)).status)
                assertEquals(1, json.decodeFromString<ReviewScheduleStatus>(status().bodyAsText()).configurationVersion)
                clock.now = Instant.parse("2020-06-01T18:00:00Z")
                val due = json.decodeFromString<ReviewScheduleStatus>(status().bodyAsText())
                assertEquals("thorough", due.due.single().level)
                assertEquals("2020-05-19", due.due.single().oldest)
                assertEquals("2020-06-01", due.due.single().newest)
                assertEquals("queue_not_configured", due.reason)
                assertTrue(model.packets.isEmpty())
                assertEquals(0, source.calls)
                val paused = write(json.encodeToString(ReviewScheduleUpdate(1, config.copy(paused = true))))
                assertEquals("schedules_paused", json.decodeFromString<ReviewScheduleStatus>(paused.bodyAsText()).reason)
                assertTrue(json.decodeFromString<ReviewScheduleStatus>(status().bodyAsText()).due.isEmpty())
                client.post("/api/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf); setBody("{}") }
                assertEquals(HttpStatusCode.Unauthorized, status().status)
                assertTrue(model.packets.isEmpty())
            }
        } finally {
            history.close(); reviews.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

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

class ManualEventApiTest {
    @Test fun `event CRUD is private bounded strict and independent of source and models`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-event-api-")
        val store = HistoryStore(directory.resolve("synthetic.sqlite3"))
        val clock = Clock.fixed(Instant.parse("2020-06-10T12:00:00Z"), ZoneOffset.UTC)
        val source = SyntheticSource()
        val history = HistoryService(store, source, clock)
        val origin = "http://127.0.0.1:8080"
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, analysis = AnalysisService(emptyList())) }
                for (method in listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete)) {
                    assertEquals(HttpStatusCode.Unauthorized, client.request("/api/events") { this.method = method }.status)
                }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun read() = client.get("/api/events") { header(HttpHeaders.Cookie, cookie) }
                suspend fun write(method: HttpMethod, path: String = "/api/events", body: String = "{}", token: String? = csrf, requestOrigin: String? = origin) = client.request(path) {
                    this.method = method; header(HttpHeaders.Cookie, cookie)
                    token?.let { header("X-CSRF-Token", it) }; requestOrigin?.let { header(HttpHeaders.Origin, it) }
                    contentType(ContentType.Application.Json); setBody(body)
                }
                val initial = read()
                assertEquals("no-store", initial.headers[HttpHeaders.CacheControl])
                assertTrue(Json.decodeFromString<ManualEventList>(initial.bodyAsText()).events.isEmpty())
                val request = ManualEventRequest("2020-07-01", "2020-07-06", "SUP", "  synthetic 'trip'\n水  ", "\tSynthetic notes  ")
                val body = Json.encodeToString(request)
                assertEquals(HttpStatusCode.Forbidden, write(HttpMethod.Post, body = body, token = null).status)
                assertEquals(HttpStatusCode.Forbidden, write(HttpMethod.Post, body = body, requestOrigin = "https://external.invalid").status)
                val createdResponse = write(HttpMethod.Post, body = body)
                assertEquals(HttpStatusCode.Created, createdResponse.status)
                assertEquals("no-store", createdResponse.headers[HttpHeaders.CacheControl])
                val created = Json.decodeFromString<ManualEvent>(createdResponse.bodyAsText())
                assertEquals(request.goal, created.goal)
                assertEquals(request.notes, created.notes)
                val upcoming = Json.decodeFromString<ManualEventList>(read().bodyAsText())
                assertEquals(created, upcoming.nextUpcoming)
                assertEquals("2020-06-10", upcoming.evaluatedOn)
                val invalid = listOf(
                    body.replace("2020-07-01", "2020-02-30"), body.replace("2020-07-06", "2020-06-01"),
                    body.replace("\"sport\":\"SUP\"", "\"sport\":\"SUP\",\"sport\":\"Walk\""),
                    body.dropLast(1) + ",\"modelId\":\"unapproved\"}",
                    body.replace("\"sport\":\"SUP\"", "\"sport\":\"SUP\\u0000\""),
                    Json.encodeToString(request).replace("2020-07-01", "0000-07-01"),
                )
                for (value in invalid) {
                    val response = write(HttpMethod.Post, body = value)
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertFalse(response.bodyAsText().contains(request.goal))
                    assertFalse(response.bodyAsText().contains("Synthetic notes"))
                }
                assertEquals(HttpStatusCode.PayloadTooLarge, write(HttpMethod.Post, body = " ".repeat(32_769)).status)
                assertEquals(1, store.events().size)
                val edit = request.copy(startDate = "2020-06-09", endDate = "2020-06-12", notes = null)
                assertEquals(HttpStatusCode.OK, write(HttpMethod.Put, "/api/events/${created.id}", Json.encodeToString(edit)).status)
                val ongoing = Json.decodeFromString<ManualEventList>(read().bodyAsText())
                assertNull(ongoing.nextUpcoming)
                assertEquals(created.id, ongoing.ongoing.single().id)
                assertNull(ongoing.ongoing.single().notes)
                val missing = "/api/events/00000000-0000-0000-0000-000000000000"
                assertEquals(HttpStatusCode.NotFound, write(HttpMethod.Put, missing, body).status)
                assertEquals(HttpStatusCode.NotFound, write(HttpMethod.Delete, missing).status)
                assertEquals(HttpStatusCode.BadRequest, write(HttpMethod.Delete, "/api/events/not-an-id").status)
                assertEquals(HttpStatusCode.NoContent, write(HttpMethod.Delete, "/api/events/${created.id}").status)
                assertTrue(Json.decodeFromString<ManualEventList>(read().bodyAsText()).events.isEmpty())
                assertEquals(0, source.calls)
                assertEquals(0L, store.reviewScheduleState().cursor)
                assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
                write(HttpMethod.Post, "/api/logout")
                assertEquals(HttpStatusCode.Unauthorized, read().status)
            }
        } finally {
            history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

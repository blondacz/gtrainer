package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

class ReviewInterpretationApiTest {
    private val origin = "http://127.0.0.1:8080"

    @Test fun `legacy generation routes are refused while private review model selection remains guarded`() {
        val directory = Files.createTempDirectory("gtrainer-retired-review-api-")
        val source = SyntheticSource()
        val history = HistoryService(HistoryStore(directory.resolve("synthetic.sqlite3")), source)
        val model = SyntheticReviewModel { _, _ -> error("retired HTTP routes must never invoke a provider") }
        val reviews = ReviewInterpretationService(listOf(model), runtimeGuard = { true })
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, reviews = reviews) }
                for (path in listOf("/api/review-models", "/api/reviews/prepare", "/api/review-interpretation", "/api/review-facts")) {
                    val response = if (path == "/api/review-models" || path == "/api/review-facts") client.get(path) else client.post(path)
                    assertEquals(HttpStatusCode.Unauthorized, response.status, path)
                    assertTrue(response.headers["X-Content-Type-Options"] != null)
                }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun request(path: String, method: HttpMethod, body: String = "{}") = client.request(path) {
                    this.method = method; header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin)
                    header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json); setBody(body)
                }
                for (path in listOf("/api/trends", "/api/analysis-input", "/api/review-facts", "/api/scoped-trends/v1",
                    "/api/scoped-trends/v1/options", "/api/models", "/api/analysis", "/api/review-interpretation")) {
                    val response = request(path, if (path.endsWith("/v1") || path == "/api/review-interpretation") HttpMethod.Post else HttpMethod.Get)
                    assertEquals(HttpStatusCode.NotImplemented, response.status, path)
                }
                val status = request("/api/review-models", HttpMethod.Get)
                assertEquals(HttpStatusCode.OK, status.status)
                assertEquals("no-store", status.headers[HttpHeaders.CacheControl])
                assertFalse(Json.decodeFromString<ReviewModelStatus>(status.bodyAsText()).enabled)
                assertEquals(HttpStatusCode.BadRequest, request("/api/review-models", HttpMethod.Put, """{"profile":"unsupported","enabled":true}""").status)
                assertEquals(HttpStatusCode.PayloadTooLarge, request("/api/review-models", HttpMethod.Put, " ".repeat(4097)).status)
                assertEquals(HttpStatusCode.Forbidden, client.put("/api/review-models") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); contentType(ContentType.Application.Json); setBody("{}")
                }.status)
                assertEquals(0, model.packets.size)
                assertEquals(0, source.calls)
            }
        } finally {
            history.close(); reviews.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(directory)
        }
    }
}

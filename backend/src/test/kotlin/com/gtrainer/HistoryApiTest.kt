package com.gtrainer

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryApiTest {
    @Test
    fun `private imports require session CSRF confirmation and expose normalized records only`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-api-")
        val source = SyntheticSource()
        try {
            testApplication {
                application {
                    module(SingleUserAuth(syntheticVerifier(), "http://127.0.0.1:8080", false, true),
                           HistoryService(HistoryStore(directory.resolve("synthetic.sqlite3")), source))
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/imports").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/history?oldest=2020-06-01&newest=2020-06-30").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/trends?oldest=2020-06-01&newest=2020-06-30").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/analysis-input?oldest=2020-06-01&newest=2020-06-30").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/sync").status)
                assertEquals(0, source.calls)
                val login = client.post("/api/login") {
                    header(HttpHeaders.Origin, "http://127.0.0.1:8080")
                    setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrfToken"]!!.jsonPrimitive.content
                assertEquals(HttpStatusCode.Forbidden, client.post("/api/sync") {
                    header(HttpHeaders.Cookie, cookie)
                    header(HttpHeaders.Origin, "http://127.0.0.1:8080")
                    setBody("""{"oldest":"2020-06-01","newest":"2020-06-30"}""")
                }.status)
                assertEquals(0, source.calls)
                val sync = client.post("/api/sync") {
                    header(HttpHeaders.Cookie, cookie)
                    header(HttpHeaders.Origin, "http://127.0.0.1:8080")
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"oldest":"2020-06-01","newest":"2020-06-30"}""")
                }
                assertEquals(HttpStatusCode.OK, sync.status)
                assertEquals(2, source.calls)
                assertTrue(sync.bodyAsText().contains("\"upstreamFreshness\":\"unknown\""))
                val history = client.get("/api/history?oldest=2020-06-01&newest=2020-06-30") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, history.status)
                assertEquals("no-store", history.headers[HttpHeaders.CacheControl])
                assertTrue(history.bodyAsText().contains("synthetic-store-a1"))
                assertTrue(history.bodyAsText().contains("\"unit\":\"seconds\""))
                assertFalse(history.bodyAsText().contains(SYNTHETIC_PASSWORD))
                val trends = client.get("/api/trends?oldest=2020-06-01&newest=2020-06-30") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, trends.status)
                assertEquals("no-store", trends.headers[HttpHeaders.CacheControl])
                assertTrue(trends.bodyAsText().contains("Recorded activity time (moving)"))
                assertTrue(trends.bodyAsText().contains("synthetic-store-a1"))
                val summary = client.get("/api/analysis-input?oldest=2020-06-01&newest=2020-06-30") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, summary.status)
                assertEquals("no-store", summary.headers[HttpHeaders.CacheControl])
                assertFalse(summary.bodyAsText().contains("synthetic-store-a1"))
                assertTrue(summary.bodyAsText().contains("evidenceReportSha256"))
                val digest = MessageDigest.getInstance("SHA-256").digest(trends.bodyAsText().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(digest, Json.parseToJsonElement(summary.bodyAsText()).jsonObject["evidenceReportSha256"]!!.jsonPrimitive.content)
                assertTrue(trends.bodyAsText().contains("dateBasis"))
                assertEquals(2, source.calls) // Charts and input summaries never retrieve upstream.
                for (query in listOf("", "?oldest=2020-06-01&newest=2019-01-01", "?oldest=2020-01-01&newest=2021-01-01",
                    "?oldest=2020-06-01&newest=2020-06-30&sport=bad/selector", "?oldest=9999-01-01&newest=9999-01-02")) {
                    assertEquals(HttpStatusCode.BadRequest, client.get("/api/trends$query") { header(HttpHeaders.Cookie, cookie) }.status)
                }
                assertEquals(HttpStatusCode.BadRequest, client.delete("/api/imports") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, "http://127.0.0.1:8080"); header("X-CSRF-Token", csrf)
                    setBody("""{"confirmation":"wrong-confirmation"}""")
                }.status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/history") { header(HttpHeaders.Cookie, cookie) }.status)
                assertEquals(HttpStatusCode.OK, client.delete("/api/imports") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, "http://127.0.0.1:8080"); header("X-CSRF-Token", csrf)
                    setBody("""{"confirmation":"remove-local-imports"}""")
                }.status)
                val removed = client.get("/api/history?oldest=2020-06-01&newest=2020-06-30") { header(HttpHeaders.Cookie, cookie) }
                assertEquals("""{"activities":[],"wellness":[]}""", removed.bodyAsText())
                assertEquals(2, source.calls)
            }
        } finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

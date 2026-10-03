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

class AnalysisApiTest {
    private val origin = "http://127.0.0.1:8080"
    private val trendPath = "/api/trends?oldest=2020-06-01&newest=2020-06-02"

    @Test fun `private model selection and generation require auth Origin CSRF evidence hash and bounded requests`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-analysis-api-")
        val source = SyntheticSource().apply {
            activityResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().activities)
            wellnessResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().wellness)
        }
        val history = HistoryService(HistoryStore(directory.resolve("synthetic.sqlite3")), source,
            Clock.fixed(Instant.parse("2020-06-10T00:00:00Z"), ZoneOffset.UTC))
        runBlocking { history.sync(ReadRange(LocalDate.parse("2020-05-30"), LocalDate.parse("2020-06-02"))) }
        val model = SyntheticAnalysisModel()
        val service = AnalysisService(listOf(model))
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, service) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/models").status)
                assertEquals(HttpStatusCode.Unauthorized, client.put("/api/models").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/analysis").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/review-facts?oldest=2020-06-01&newest=2020-06-02").status)
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrfToken"]!!.jsonPrimitive.content
                val status = client.get("/api/models") { header(HttpHeaders.Cookie, cookie) }
                assertEquals("no-store", status.headers[HttpHeaders.CacheControl])
                assertEquals("model_not_selected", Json.decodeFromString<ModelStatus>(status.bodyAsText()).reason)
                for (path in listOf("/api/models", "/api/analysis")) {
                    val method = if (path == "/api/models") HttpMethod.Put else HttpMethod.Post
                    for ((requestOrigin, token) in listOf(origin to null, "https://hosted.invalid" to csrf, null to csrf)) {
                        val forbidden = client.request(path) {
                            this.method = method; header(HttpHeaders.Cookie, cookie)
                            requestOrigin?.let { header(HttpHeaders.Origin, it) }; token?.let { header("X-CSRF-Token", it) }
                            setBody("{}")
                        }
                        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
                    }
                }
                assertEquals(0, model.calls)
                val selected = client.put("/api/models") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                    setBody("""{"modelId":"${model.option.id}"}""")
                }
                assertEquals(HttpStatusCode.OK, selected.status)
                assertEquals(0, model.calls)
                val reportResponse = client.get(trendPath) { header(HttpHeaders.Cookie, cookie) }
                val reportBody = reportResponse.bodyAsText()
                val report = Json.decodeFromString<TrendReport>(reportBody)
                assertEquals(AnalysisClaims.hash(reportBody), reportResponse.headers["X-Evidence-Report-Sha256"])
                val request = analysisRequest(report, Json.decodeFromString<ModelStatus>(selected.bodyAsText()))
                val factualPath = "/api/review-facts?oldest=2020-06-01&newest=2020-06-02&evidenceReportSha256=${request.evidenceReportSha256}"
                suspend fun facts(path: String = factualPath) = client.get(path) { header(HttpHeaders.Cookie, cookie) }
                val factual = facts()
                assertEquals(HttpStatusCode.OK, factual.status)
                assertEquals("no-store", factual.headers[HttpHeaders.CacheControl])
                assertEquals(request.evidenceReportSha256, factual.headers["X-Evidence-Report-Sha256"])
                val prepared = Json.decodeFromString<FactualReview>(factual.bodyAsText())
                val rawFactual = Json.parseToJsonElement(factual.bodyAsText()).jsonObject
                assertEquals("factual-review-v1", rawFactual.getValue("profile").jsonPrimitive.content)
                assertTrue(rawFactual.getValue("applicationGenerated").jsonPrimitive.boolean)
                assertTrue(prepared.applicationGenerated)
                assertEquals("factual-review-v1", prepared.profile)
                assertEquals(report.comparisons.size, prepared.groups.sumOf { it.facts.size })
                assertFalse(factual.bodyAsText().contains("sourceRecordId"))
                assertFalse(factual.bodyAsText().contains(SYNTHETIC_PASSWORD))
                assertEquals(0, model.calls)
                assertEquals(HttpStatusCode.BadRequest, facts(factualPath.substringBefore("&evidenceReportSha256")).status)
                assertEquals(HttpStatusCode.BadRequest, facts(factualPath + "&focus=training_plan").status)
                assertEquals(HttpStatusCode.BadRequest, facts(factualPath.replace(request.evidenceReportSha256, "bad")).status)
                val changed = facts(factualPath.replace(request.evidenceReportSha256, "b".repeat(64)))
                assertEquals(HttpStatusCode.Conflict, changed.status)
                assertEquals("evidence_changed", Json.decodeFromString<ApiError>(changed.bodyAsText()).error)
                assertFalse(changed.bodyAsText().contains("supportingMetrics"))
                suspend fun post(body: String) = client.post("/api/analysis") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json); setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, post("{\"unexpected\":true}").status)
                assertEquals(HttpStatusCode.BadRequest, post("{\"oldest\":\"2020-06-01\",\"oldest\":\"2020-05-01\"}").status)
                assertEquals(HttpStatusCode.PayloadTooLarge, post(" ".repeat(4097)).status)
                val stale = post(Json.encodeToString(request.copy(evidenceReportSha256 = "b".repeat(64))))
                assertEquals("evidence_changed", Json.decodeFromString<AnalysisResponse>(stale.bodyAsText()).reason)
                assertEquals(0, model.calls)
                val result = post(Json.encodeToString(request))
                assertEquals(HttpStatusCode.OK, result.status)
                assertEquals("no-store", result.headers[HttpHeaders.CacheControl])
                val data = Json.decodeFromString<AnalysisResponse>(result.bodyAsText())
                assertEquals("available", data.status)
                assertEquals(request.evidenceReportSha256, data.evidenceReportSha256)
                assertFalse(result.bodyAsText().contains(SYNTHETIC_PASSWORD))
                assertFalse(result.bodyAsText().contains("synthetic-analysis-"))
                assertEquals(1, model.calls)
                assertEquals(factual.bodyAsText(), facts().bodyAsText())
                assertEquals(1, model.calls)
                assertEquals(reportBody, client.get(trendPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
                assertEquals(2, source.calls) // Model changes and generation never read or modify upstream/storage.
                assertEquals(HttpStatusCode.BadRequest, client.put("/api/models") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                    setBody("{\"modelId\":\"hosted-unknown\"}")
                }.status)
                client.post("/api/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
                assertEquals(HttpStatusCode.Unauthorized, post(Json.encodeToString(request)).status)
                assertEquals(HttpStatusCode.Unauthorized, facts().status)
            }
        } finally {
            history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `logout while inference runs denies its result and charts respond independently`() {
        val directory = Files.createTempDirectory("gtrainer-synthetic-analysis-logout-")
        val source = SyntheticSource().apply {
            activityResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().activities)
            wellnessResult = ReadResult(ReadStatus.SUCCESS, analysisHistory().wellness)
        }
        val history = HistoryService(HistoryStore(directory.resolve("synthetic.sqlite3")), source)
        runBlocking { history.sync(ReadRange(LocalDate.parse("2020-05-30"), LocalDate.parse("2020-06-02"))) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = SyntheticAnalysisModel(generate = { started.complete(Unit); release.await(); validClaims() })
        val service = AnalysisService(listOf(model))
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history, service) }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrfToken"]!!.jsonPrimitive.content
                val report = Json.decodeFromString<TrendReport>(client.get(trendPath) { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
                val request = analysisRequest(report, service.select(model.option.id))
                coroutineScope {
                    val pending = async { client.post("/api/analysis") {
                        header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                        setBody(Json.encodeToString(request))
                    } }
                    withTimeout(5000) { started.await() }
                    assertEquals(HttpStatusCode.OK, withTimeout(5000) { client.get(trendPath) { header(HttpHeaders.Cookie, cookie) } }.status)
                    val facts = withTimeout(5000) { client.get("/api/review-facts?oldest=2020-06-01&newest=2020-06-02&evidenceReportSha256=${request.evidenceReportSha256}") {
                        header(HttpHeaders.Cookie, cookie)
                    } }
                    assertEquals(HttpStatusCode.OK, facts.status)
                    assertEquals(report.comparisons.size, Json.decodeFromString<FactualReview>(facts.bodyAsText()).groups.sumOf { it.facts.size })
                    assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
                    client.post("/api/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf) }
                    release.complete(Unit)
                    val denied = pending.await()
                    assertEquals(HttpStatusCode.Unauthorized, denied.status)
                    assertFalse(denied.bodyAsText().contains("observations"))
                }
            }
        } finally {
            release.complete(Unit); history.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

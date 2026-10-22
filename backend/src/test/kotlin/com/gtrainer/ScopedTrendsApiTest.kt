package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ScopedTrendsApiTest {
    @Test fun `imports deletion leaves scoped options unconfirmed until schedule is saved again`() {
        val dir = Files.createTempDirectory("scoped-options-after-removal-")
        val store = HistoryStore(dir.resolve("history.sqlite3"))
        val source = SyntheticSource()
        val clock = ReviewTestClock(Instant.parse("2020-06-10T12:00:00Z"))
        val history = HistoryService(store, source, clock)
        val origin = "http://127.0.0.1:8080"
        try {
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history) }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun options() = client.get("/api/reviews/options") { header(HttpHeaders.Cookie, cookie) }
                assertEquals("null", Json.parseToJsonElement(options().bodyAsText()).jsonObject.getValue("timeZone").toString())
                assertEquals(HttpStatusCode.OK, client.delete("/api/imports") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json); setBody("""{"confirmation":"remove-local-imports"}""")
                }.status)
                val afterRemoval = Json.parseToJsonElement(options().bodyAsText()).jsonObject
                assertEquals("null", afterRemoval.getValue("timeZone").toString())
                assertEquals("null", afterRemoval.getValue("today").toString())
                assertEquals("null", afterRemoval.getValue("browseRange").toString())
                assertTrue(afterRemoval.getValue("activities").jsonArray.isEmpty())
                suspend fun report() = client.post("/api/reviews/prepare") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json); setBody(Json.encodeToString(ScopedReviewRequest(scope = "rolling", days = 7)))
                }
                assertEquals(HttpStatusCode.BadRequest, report().status)
                val state = store.reviewScheduleState()
                store.updateReviewSchedule(state.version, ReviewScheduleConfiguration(timeZone = "Europe/Prague"), clock.instant(), null, null)
                assertEquals("Europe/Prague", Json.parseToJsonElement(options().bodyAsText()).jsonObject.getValue("timeZone").jsonPrimitive.content)
                assertEquals(HttpStatusCode.OK, report().status)
                assertEquals(0, source.calls)
            }
        } finally { history.close(); Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `authenticated scoped trends are read only strict and source independent`() {
        val dir = Files.createTempDirectory("scoped-trends-api-")
        val store = HistoryStore(dir.resolve("history.sqlite3"))
        val source = SyntheticSource()
        val clock = ReviewTestClock(Instant.parse("2020-06-10T12:00:00Z"))
        val history = HistoryService(store, source, clock)
        val origin = "http://127.0.0.1:8080"
        try {
            val configured = store.updateReviewSchedule(0, ReviewScheduleConfiguration(timeZone = "Europe/Prague"), clock.instant(), null, null)
            store.begin("activities", clock.instant())
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.instant())
            store.begin("wellness", clock.instant())
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(syntheticWellness())), clock.instant())
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/reviews/options").status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/reviews/prepare").status)
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                suspend fun options() = client.get("/api/reviews/options") { header(HttpHeaders.Cookie, cookie) }
                val opts = options()
                assertEquals(HttpStatusCode.OK, opts.status)
                assertEquals("no-store", opts.headers[HttpHeaders.CacheControl])
                assertEquals("no-referrer", opts.headers["Referrer-Policy"])
                val initial = Json.parseToJsonElement(opts.bodyAsText()).jsonObject
                assertEquals(1, initial.getValue("schemaVersion").jsonPrimitive.content.toInt())
                assertEquals(configured.version, initial.getValue("configurationVersion").jsonPrimitive.content.toLong())
                assertEquals("Europe/Prague", initial.getValue("timeZone").jsonPrimitive.content)
                assertEquals("2020-06-10", initial.getValue("today").jsonPrimitive.content)
                val activityId = scopedActivityId(syntheticActivity())
                val request = ScopedReviewRequest(scope = "custom", oldest = "2020-06-01", newest = "2020-06-10")
                assertEquals("daily_combined", request.focus)
                suspend fun sendReport(body: String, originHeader: String? = origin, token: String? = csrf) = client.post("/api/reviews/prepare") {
                    header(HttpHeaders.Cookie, cookie); originHeader?.let { header(HttpHeaders.Origin, it) }
                    token?.let { header("X-CSRF-Token", it) }; contentType(ContentType.Application.Json); setBody(body)
                }
                assertEquals(HttpStatusCode.Forbidden, sendReport(Json.encodeToString(request), token = null).status)
                assertEquals(HttpStatusCode.Forbidden, sendReport(Json.encodeToString(request), originHeader = "https://elsewhere.invalid").status)
                val response = sendReport(Json.encodeToString(request))
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("no-referrer", response.headers["Referrer-Policy"])
                assertTrue(response.bodyAsText().contains("\"activityIds\":[]"))
                assertTrue(response.bodyAsText().contains("\"policy\":\"preceding_equal_length\""))
                val periodReport = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertFalse(periodReport.getValue("factualReview").toString() == "null")
                assertEquals("daily_combined", periodReport.getValue("scope").jsonObject.getValue("request").jsonObject.getValue("focus").jsonPrimitive.content)
                val displayOnly = sendReport(Json.encodeToString(request.copy(wellnessPolicy = "display_only", focus = "wellness")))
                assertEquals(HttpStatusCode.OK, displayOnly.status)
                val displayJson = Json.parseToJsonElement(displayOnly.bodyAsText()).jsonObject
                assertTrue(displayJson.getValue("selected").jsonObject.getValue("wellness").jsonArray.isNotEmpty())
                val facts = displayJson.getValue("factualReview").jsonObject.getValue("groups").jsonArray.flatMap { it.jsonObject.getValue("facts").jsonArray }
                assertTrue(facts.isNotEmpty())
                assertTrue(facts.all { it.jsonObject.getValue("comparison").jsonObject.getValue("sport").toString() != "null" })
                assertTrue(facts.flatMap { it.jsonObject.getValue("supportingMetrics").jsonArray }.all { it.jsonObject.getValue("sport").toString() != "null" })
                assertEquals("wellness", displayJson.getValue("factualReview").jsonObject.getValue("requestedFocus").jsonPrimitive.content)
                val selected = sendReport(Json.encodeToString(ScopedReviewRequest(mode = "activities", scope = "activities",
                    activityIds = listOf(activityId), wellnessPolicy = "display_only")))
                assertEquals(HttpStatusCode.OK, selected.status)
                val selectedJson = Json.parseToJsonElement(selected.bodyAsText()).jsonObject
                assertEquals("null", selectedJson.getValue("factualReview").toString())
                assertEquals(1, selectedJson.getValue("sessions").jsonArray.size)
                val metrics = selectedJson.getValue("sessions").jsonArray.single().jsonObject.getValue("metrics").jsonArray
                val moving = metrics.single { it.jsonObject.getValue("key").jsonPrimitive.content == "movingTime" }.jsonObject
                assertEquals(1200.0, moving.getValue("value").jsonPrimitive.content.toDouble())
                assertEquals("seconds", moving.getValue("unit").jsonPrimitive.content)
                assertEquals("display_only", selectedJson.getValue("wellnessContext").jsonArray.single().jsonObject.getValue("usage").jsonPrimitive.content)
                assertTrue(selectedJson.getValue("wellnessContext").jsonArray.single().jsonObject.getValue("flags").toString().contains("prior_night_mapping_unverified"))
                val night = sendReport(Json.encodeToString(ScopedReviewRequest(scope = "night")))
                assertEquals(HttpStatusCode.OK, night.status)
                assertEquals("needs_confirmation", Json.parseToJsonElement(night.bodyAsText()).jsonObject.getValue("scope").jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals(HttpStatusCode.BadRequest, sendReport(Json.encodeToString(ScopedReviewRequest(mode = "activities", scope = "activities", activityIds = listOf("0".repeat(64))))).status)
                assertEquals(HttpStatusCode.BadRequest, sendReport("""{"mode":"period","scope":"rolling","days":7,"unsupported":true}""").status)
                assertEquals(HttpStatusCode.BadRequest, sendReport("""{"mode":"activities","scope":"activities","activityIds":["$activityId","$activityId"]}""").status)
                assertEquals(HttpStatusCode.BadRequest, sendReport("""{"mode":"period","scope":"future-scope","days":7}""").status)
                assertEquals(HttpStatusCode.BadRequest, sendReport(Json.encodeToString(ScopedReviewRequest(scope = "custom", oldest = "2020-06-11", newest = "2020-06-11"))).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, sendReport(" ".repeat(32_769)).status)
                for (query in listOf("?wat=1", "?offset=no", "?offset=0&offset=1", "?oldest=2020-06-01"))
                    assertEquals(HttpStatusCode.BadRequest, client.get("/api/reviews/options$query") { header(HttpHeaders.Cookie, cookie) }.status)
                assertEquals(0, source.calls)
                assertEquals(configured, store.reviewScheduleState())
                for (path in listOf("/api/trends", "/api/analysis-input", "/api/review-facts", "/api/scoped-trends/v1",
                    "/api/scoped-trends/v1/options", "/api/models", "/api/analysis", "/api/review-interpretation")) {
                    val response = if (path.endsWith("/v1") || path == "/api/review-interpretation") client.post(path) {
                        header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
                        contentType(ContentType.Application.Json); setBody("{}")
                    } else client.get(path) { header(HttpHeaders.Cookie, cookie) }
                    assertEquals(HttpStatusCode.NotImplemented, response.status, path)
                }
                assertEquals(0, source.calls)
            }
        } finally {
            history.close()
            Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir)
        }
    }

    @Test fun `options version zero is nullable and night without verified association asks confirmation`() {
        val dir = Files.createTempDirectory("scoped-trends-default-")
        val store = HistoryStore(dir.resolve("history.sqlite3"))
        val history = HistoryService(store, SyntheticSource(), ReviewTestClock(Instant.parse("2020-06-10T12:00:00Z")))
        try {
            val options = store.scopedReviewOptions(Instant.parse("2020-06-10T12:00:00Z"), null, null, 0)
            assertNull(options.timeZone); assertNull(options.today); assertEquals(0, options.configurationVersion)
            assertTrue(options.activities.isEmpty())
            val night = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "night"), Instant.parse("2020-06-10T12:00:00Z"), "Europe/Prague")
            assertEquals("needs_confirmation", night.status)
            assertEquals(listOf("night_mapping_unverified"), night.reasons)
            assertNull(night.evidenceRange)
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), "http://127.0.0.1:8080", false, true), history) }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, "http://127.0.0.1:8080"); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                assertEquals(HttpStatusCode.BadRequest, client.post("/api/reviews/prepare") {
                    header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, "http://127.0.0.1:8080"); header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json); setBody(Json.encodeToString(ScopedReviewRequest(scope = "rolling", days = 7)))
                }.status)
            }
        } finally { history.close(); Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `options pages at two hundred choices and reports a continuation offset`() {
        val dir = Files.createTempDirectory("scoped-options-page-")
        val store = HistoryStore(dir.resolve("history.sqlite3"))
        val origin = "http://127.0.0.1:8080"
        val history = HistoryService(store, SyntheticSource(), ReviewTestClock(Instant.parse("2020-06-10T12:00:00Z")))
        try {
            store.updateReviewSchedule(0, ReviewScheduleConfiguration(timeZone = "Europe/Prague"), Instant.parse("2020-06-10T12:00:00Z"), null, null)
            val records = (0 until 201).map { n -> syntheticActivity().copy(sourceRecordId = "page-$n") }
            store.begin("activities", Instant.parse("2020-06-10T12:00:00Z"))
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, records), Instant.parse("2020-06-10T12:00:00Z"))
            testApplication {
                application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history) }
                val login = client.post("/api/login") { header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
                val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                 val first = Json.parseToJsonElement(client.get("/api/reviews/options") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()).jsonObject
                assertEquals(200, first.getValue("activities").jsonArray.size)
                assertEquals(200, first.getValue("nextOffset").jsonPrimitive.content.toInt())
                 val second = Json.parseToJsonElement(client.get("/api/reviews/options?offset=200") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()).jsonObject
                assertEquals(1, second.getValue("activities").jsonArray.size)
                assertEquals("null", second.getValue("nextOffset").toString())
                assertTrue(first.getValue("activities").jsonArray.map { it.jsonObject.getValue("activityId").jsonPrimitive.content }.toSet()
                    .intersect(second.getValue("activities").jsonArray.map { it.jsonObject.getValue("activityId").jsonPrimitive.content }.toSet()).isEmpty())
            }
        } finally { history.close(); Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `calendar and explicit comparisons retain full and comparable ranges without matching dates`() {
        val asOf = Instant.parse("2020-03-10T12:00:00Z")
        val february = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "calendar_month", anchorDate = "2020-02-15"), asOf, "UTC")
        assertEquals(ScopedDateRange("2020-02-01", "2020-02-29"), february.evidenceRange)
        assertEquals(ScopedDateRange("2020-02-01", "2020-02-29"), february.comparison.comparableSelected)
        assertEquals(ScopedDateRange("2020-01-01", "2020-01-29"), february.comparison.comparablePrevious)
        assertEquals(ScopedDateRange("2020-01-01", "2020-01-31"), february.comparison.priorCalendarFull)
        assertEquals(listOf("2020-01-30", "2020-01-31"), february.comparison.priorCalendarRemainderDates)
        assertTrue(february.comparison.unmatchedSelectedDates.isEmpty())

        val explicit = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "custom", oldest = "2020-02-01", newest = "2020-02-10",
            comparison = ScopedDateRange("2020-02-05", "2020-02-20")), asOf, "UTC")
        assertEquals("explicit", explicit.comparison.policy)
        assertTrue(explicit.comparison.overlapping)
        assertTrue(explicit.comparison.unequalDuration)
        assertEquals(ScopedDateRange("2020-02-05", "2020-02-20"), explicit.comparison.comparablePrevious)
        val later = ReviewScopeResolver.resolve(ScopedReviewRequest(scope = "custom", oldest = "2020-02-01", newest = "2020-02-10",
            comparison = ScopedDateRange("2020-02-11", "2020-02-20")), asOf, "UTC")
        assertFalse(later.comparison.overlapping)

        val futureLocal = syntheticActivity().copy(startLocal = "2020-06-11T10:00:00Z", startInstant = "2020-06-10T13:00:00Z")
        val id = scopedActivityId(futureLocal)
        val future = ReviewScopeResolver.resolve(ScopedReviewRequest(mode = "activities", scope = "activities", activityIds = listOf(id)),
            Instant.parse("2020-06-10T12:00:00Z"), "UTC", mapOf(id to futureLocal))
        assertEquals("needs_confirmation", future.status)
        assertNull(future.evidenceRange)
    }
}

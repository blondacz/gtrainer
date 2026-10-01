package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "synthetic-source-credential-not-a-real-key"
private const val ACTIVITY = """{"id":"synthetic-a1","type":"Run","start_date_local":"2020-06-01T10:00:00",
    "start_date":"2020-06-01T09:00:00Z","timezone":"(GMT+01:00) Europe/London","source":"GARMIN",
    "moving_time":1200,"elapsed_time":1500,"calories":123,"icu_training_load":42}"""
private const val WELLNESS = """{"id":"2020-06-01","weight":70,"hrv":42,"sleepSecs":28800,"vo2max":45,"ctl":12}"""

class IntervalsSourceTest {
    private val range = ReadRange(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-30"))
    private fun row(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    @Test
    fun `source retrieves both categories with GET only and provider JSON stays internal`() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("intervals.icu", request.url.host)
            assertEquals("https", request.url.protocol.name)
            assertEquals(SourceCredential(KEY).authorization(), request.headers[HttpHeaders.Authorization])
            assertEquals(range.oldest.toString(), request.url.parameters["oldest"])
            assertEquals(range.newest.toString(), request.url.parameters["newest"])
            paths += request.url.encodedPath
            val record = if (request.url.encodedPath.endsWith("/activities")) ACTIVITY else WELLNESS
            respond("[$record]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        IntervalsSource(HttpClient(engine), { SourceCredential(KEY) }).use { source ->
            val activity = source.activities(range)
            val wellness = source.wellness(range)
            assertEquals(ReadStatus.SUCCESS, activity.status)
            assertEquals(ReadStatus.SUCCESS, wellness.status)
            assertEquals("Run", activity.records.single().sport)
            assertEquals("kg", wellness.records.single().measurements["weight"]?.unit)
            assertEquals("unknown", activity.upstreamFreshness)
            assertEquals("unknown", wellness.upstreamFreshness)
            assertFalse(activity.toString().contains("synthetic-a1"))
            assertEquals(listOf("/api/v1/athlete/0/activities", "/api/v1/athlete/0/wellness"), paths)
        }
    }

    @Test
    fun `revoked key auth filtering and upstream failures have fixed independent statuses`() = runBlocking {
        for ((code, expected) in listOf(401 to ReadStatus.KEY_REJECTED, 403 to ReadStatus.ACCESS_DENIED,
                                       429 to ReadStatus.RATE_LIMITED, 500 to ReadStatus.UPSTREAM_ERROR)) {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/activities")) {
                    respond("synthetic-private-error $KEY", HttpStatusCode.fromValue(code))
                } else respond("[$WELLNESS]")
            }
            IntervalsSource(HttpClient(engine), { SourceCredential(KEY) }).use { source ->
                val failure = source.activities(range)
                assertEquals(expected, failure.status)
                assertTrue(failure.records.isEmpty())
                assertFalse(failure.toString().contains(KEY))
                assertEquals(ReadStatus.SUCCESS, source.wellness(range).status)
            }
        }
    }

    @Test
    fun `redirect never forwards credential or follows upstream Location`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://attacker.example/collect"))
        }
        IntervalsSource(HttpClient(engine) { followRedirects = true }, { SourceCredential(KEY) }).use { source ->
            assertEquals(ReadStatus.REDIRECT_BLOCKED, source.activities(range).status)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `empty reads do not mean upstream sync failed and invalid rows are counted`() = runBlocking {
        val engine = MockEngine { request ->
            respond(if (request.url.encodedPath.endsWith("/activities")) "[$ACTIVITY,{},$ACTIVITY]" else "[]")
        }
        IntervalsSource(HttpClient(engine), { SourceCredential(KEY) }).use { source ->
            val result = source.activities(range)
            assertEquals(2, result.records.size) // Deduplication belongs to local-store task 3.3.
            assertEquals(1, result.rejected)
            val empty = source.wellness(range)
            assertEquals(ReadStatus.SUCCESS, empty.status)
            assertTrue(empty.records.isEmpty())
            assertEquals("unknown", empty.upstreamFreshness)
        }
    }

    @Test
    fun `malformed unexpected and oversized payloads expose no data`() = runBlocking {
        for (body in listOf("synthetic-invalid $KEY", "{}", "[123]")) {
            IntervalsSource(HttpClient(MockEngine { respond(body) }), { SourceCredential(KEY) }).use { source ->
                assertEquals(ReadStatus.INVALID_RESPONSE, source.activities(range).status)
            }
        }
        IntervalsSource(HttpClient(MockEngine { respond("x".repeat(100)) }), { SourceCredential(KEY) }, 50).use { source ->
            assertEquals(ReadStatus.RESPONSE_TOO_LARGE, source.activities(range).status)
        }
    }

    @Test
    fun `transport errors are sanitized but cancellation remains cancellation`() = runBlocking {
        IntervalsSource(HttpClient(MockEngine { throw IllegalStateException("synthetic-private-payload $KEY") }),
                        { SourceCredential(KEY) }).use { source ->
            val result = source.activities(range)
            assertEquals(ReadStatus.TRANSPORT_ERROR, result.status)
            assertFalse(result.toString().contains(KEY))
        }
        IntervalsSource(HttpClient(MockEngine { respond("[]") }), { throw CancellationException("synthetic cancellation") }).use { source ->
            assertFailsWith<CancellationException> { source.activities(range) }
        }
        Unit
    }

    @Test
    fun `key file rotation is read at each request and missing keys fail closed`() = runBlocking {
        val directory = Files.createTempDirectory("gtrainer-synthetic-key-")
        val path = directory.resolve("synthetic.key")
        try {
            val headers = mutableListOf<String?>()
            val source = IntervalsSource(HttpClient(MockEngine { request ->
                headers += request.headers[HttpHeaders.Authorization]
                respond("[]")
            }), KeyFileProvider(path))
            source.use {
                assertEquals(ReadStatus.NOT_CONFIGURED, it.activities(range).status)
                Files.writeString(path, KEY)
                assertEquals(ReadStatus.SUCCESS, it.activities(range).status)
                Files.writeString(path, "synthetic-rotated-key")
                assertEquals(ReadStatus.SUCCESS, it.wellness(range).status)
                Files.writeString(path, "synthetic:malformed-key")
                assertEquals(ReadStatus.NOT_CONFIGURED, it.activities(range).status)
                assertEquals<List<String?>>(listOf(SourceCredential(KEY).authorization(), SourceCredential("synthetic-rotated-key").authorization()), headers)
            }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
        assertFalse(SourceCredential(KEY).toString().contains(KEY))
    }

    @Test
    fun `activity normalization preserves provenance units offset and daylight saving context`() {
        val normalized = assertNotNull(IntervalsNormalizer.activity(row(ACTIVITY)))
        val record = normalized.record
        assertEquals("intervals.icu", record.source)
        assertEquals("synthetic-a1", record.sourceRecordId)
        assertEquals(setOf("GARMIN"), record.upstreamSources)
        assertEquals("2020-06-01T09:00:00Z", record.startInstant)
        assertEquals("Europe/London", record.timeZone)
        assertEquals("+01:00", record.utcOffset)
        assertEquals("seconds", record.movingTime?.unit)
        assertEquals("kcal", record.calories?.unit)
        assertEquals("Intervals.icu load", record.intervalsTrainingLoad?.unit)
        assertFalse(record.toString().contains("synthetic-a1"))
    }

    @Test
    fun `missing numeric values stay absent while zero stays zero and scale origin stays unknown`() {
        val normalized = assertNotNull(IntervalsNormalizer.wellness(row("""{"id":"2020-06-01","weight":null,"sleepSecs":0,"steps":0}""")))
        assertTrue(normalized.incomplete)
        assertFalse(normalized.record.measurements.containsKey("weight"))
        assertEquals(0.0, normalized.record.measurements["sleepSecs"]?.value)
        assertEquals(0.0, normalized.record.measurements["steps"]?.value)
        val full = assertNotNull(IntervalsNormalizer.wellness(row(WELLNESS)))
        assertNull(full.record.measurements["weight"]?.upstreamSource)
        assertFalse(full.record.measurements.containsKey("fitnessAge"))
        val named = assertNotNull(IntervalsNormalizer.wellness(row("""{"id":"2020-06-01","weight":70,"weightSource":"GARMIN"}""")))
        assertEquals("GARMIN", named.record.measurements["weight"]?.upstreamSource)
    }

    @Test
    fun `local only and ambiguous DST timestamps preserve uncertainty without fabricating instants`() {
        val localOnly = assertNotNull(IntervalsNormalizer.activity(row("""{"id":"synthetic-local","type":"Ride","start_date_local":"2020-06-01T10:00:00"}""")))
        assertNull(localOnly.record.startInstant)
        assertEquals("local_time_only", localOnly.record.timeContext)
        assertNull(localOnly.record.calories)
        val ambiguous = assertNotNull(IntervalsNormalizer.activity(row("""{"id":"synthetic-dst","type":"Ride","start_date_local":"2020-10-25T01:30:00","timezone":"Europe/London"}""")))
        assertNull(ambiguous.record.startInstant)
        assertEquals("ambiguous_or_nonexistent_local_time", ambiguous.record.timeContext)
        val withOffset = assertNotNull(IntervalsNormalizer.activity(row("""{"id":"synthetic-offset","type":"Ride","start_date_local":"2020-06-01T10:00:00+02:00"}""")))
        assertEquals("2020-06-01T08:00:00Z", withOffset.record.startInstant)
        assertEquals("+02:00", withOffset.record.utcOffset)
    }

    @Test
    fun `invalid dates negative values conflicting units and timestamps are rejected`() {
        for (body in listOf("""{"id":"invalid-date","weight":70}""",
                            """{"id":"2020-06-01","weight":-1}""",
                            """{"id":"2020-06-01","sleepSecs":"eight hours"}""",
                            """{"id":"2020-06-01","steps":1.5}""")) {
            assertNull(IntervalsNormalizer.wellness(row(body)))
        }
        assertNull(IntervalsNormalizer.activity(row(ACTIVITY.replace("\"elapsed_time\":1500", "\"elapsed_time\":100"))))
        assertNull(IntervalsNormalizer.activity(row(ACTIVITY.replace("09:00:00Z", "08:00:00Z"))))
        assertFailsWith<IllegalArgumentException> { ReadRange(range.newest, range.oldest) }
    }
}

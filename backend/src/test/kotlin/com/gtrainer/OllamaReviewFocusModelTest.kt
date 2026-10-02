package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class OllamaReviewFocusModelTest {
    private val endpoint = "http://127.0.0.1:11434"
    private val snapshot = ReviewFocusProtocol.prepare(Trends.analysisInput(analysisReport()), "daily_combined")
    private val packet = snapshot.packet
    private val version = """{"version":"0.35.0"}"""
    private val entry = """{"name":"${syntheticModelOption.tag}","digest":"${syntheticModelOption.digest}"}"""
    private val catalogue = """{"models":[$entry]}"""
    private val valid = buildJsonObject {
        putJsonArray("focusIds") { add(packet.candidates.single { it.kind == "cross_metric_pattern" }.id) }
    }.toString()
    private fun envelope(content: String = valid) = buildJsonObject {
        put("model", syntheticModelOption.tag); put("done", true); put("done_reason", "stop")
        putJsonObject("message") { put("role", "assistant"); put("content", content) }
    }.toString()

    @Test fun `pinned local runtime and manifest surround exact separate protocol payload without credentials`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("127.0.0.1", request.url.host)
            assertEquals(11434, request.url.port)
            assertEquals("http", request.url.protocol.name)
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
            assertFalse(request.headers.contains(HttpHeaders.Cookie))
            assertFalse(request.headers.contains("Proxy-Authorization"))
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/api/version" -> { assertEquals(HttpMethod.Get, request.method); version }
                "/api/tags" -> { assertEquals(HttpMethod.Get, request.method); catalogue }
                "/api/chat" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val expected = buildJsonObject {
                        put("model", syntheticModelOption.tag); put("stream", false); put("think", false); put("keep_alive", "0s")
                        put("format", ReviewFocusProtocol.schema(packet))
                        putJsonArray("messages") {
                            add(buildJsonObject { put("role", "system"); put("content", ReviewFocusProtocol.system) })
                            add(buildJsonObject { put("role", "user"); put("content", ReviewFocusProtocol.json.encodeToString(packet)) })
                        }
                        putJsonObject("options") {
                            put("num_ctx", 2048); put("num_predict", 256); put("num_thread", 3); put("temperature", 0); put("seed", 42)
                        }
                    }
                    assertEquals(expected, payload)
                    assertNotEquals(AnalysisClaims.system, payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
                    assertEquals(ContentType.Application.Json, request.body.contentType)
                    envelope()
                }
                else -> error("Unauthorized endpoint: ${request.url.encodedPath}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
        try {
            assertEquals(valid, model.generate(packet, null))
            assertEquals(listOf("/api/version", "/api/tags", "/api/chat", "/api/tags"), paths)
            assertTrue(ReviewFocusProtocol.unchanged(snapshot))
        } finally { model.close(); client.close() }
    }

    @Test fun `correction sends only allowlisted reason with identical immutable packet never raw rejected output`() = runBlocking {
        val raw = "private-account-secret: prescribe a dangerous workout"
        val payloads = mutableListOf<JsonObject>()
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(when (request.url.encodedPath) {
                "/api/version" -> version
                "/api/tags" -> catalogue
                "/api/chat" -> {
                    payloads += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    envelope(if (payloads.size == 1) raw else valid)
                }
                else -> error("Unexpected request")
            })
        })
        val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
        try {
            assertEquals(raw, model.generate(packet, null))
            assertEquals(1, payloads.size) // Adapter does not silently repair or retry invalid content.
            val reason = assertNotNull(ReviewFocusProtocol.validate(raw, snapshot).reason)
            assertEquals("invalid_json", reason)
            assertEquals(valid, model.generate(packet, reason))
            assertEquals(2, payloads.size)
            val firstMessages = payloads[0]["messages"]!!.jsonArray
            val nextMessages = payloads[1]["messages"]!!.jsonArray
            assertEquals(2, firstMessages.size)
            assertEquals(3, nextMessages.size)
            assertEquals(firstMessages, JsonArray(nextMessages.take(2)))
            assertEquals(buildJsonObject {
                put("role", "user")
                put("content", "Previous whole response rejected: invalid_json. Return a new complete selection for the same packet and requestedFocus.")
            }, nextMessages.last())
            assertEquals(payloads[0].filterKeys { it != "messages" }, payloads[1].filterKeys { it != "messages" })
            assertFalse(payloads[1].toString().contains(raw))
            assertFalse(payloads[1].toString().contains("private-account-secret"))
            assertEquals(List(2) { listOf("/api/version", "/api/tags", "/api/chat", "/api/tags") }.flatten(), paths)
            assertTrue(ReviewFocusProtocol.unchanged(snapshot))
        } finally { model.close(); client.close() }
    }

    @Test fun `every feedback enum is accepted and arbitrary feedback profile or oversized input fails before requests`() = runBlocking {
        val corrections = setOf("invalid_json", "invalid_shape", "invalid_focus_count", "unsupported_focus", "duplicate_focus", "irrelevant_focus")
        assertEquals(corrections, ReviewFocusProtocol.correctionReasons)
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            respond(when (request.url.encodedPath) {
                "/api/version" -> version
                "/api/tags" -> catalogue
                "/api/chat" -> {
                    val messages = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["messages"]!!.jsonArray
                    assertEquals(3, messages.size)
                    envelope()
                }
                else -> error("Unexpected request")
            })
        })
        val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
        try {
            for (reason in corrections) assertEquals(valid, model.generate(packet, reason))
            assertEquals(corrections.size * 4, requests)
            val before = requests
            for (reason in listOf("", "evidence_changed", "model_unavailable", "invalid_json\nsecret", "raw private rejected prose")) {
                assertFailsWith<ModelUnavailable> { model.generate(packet, reason) }
            }
            assertFailsWith<ModelUnavailable> { model.generate(packet.copy(profile = "prepared-focus-v1"), null) }
            assertFailsWith<ModelUnavailable> { model.generate(packet.copy(requestedFocus = "x".repeat(6001)), null) }
            assertFailsWith<ModelUnavailable> { model.generate(packet.copy(requestedFocus = "é".repeat(3001)), null) }
            assertEquals(before, requests)
        } finally { model.close(); client.close() }
    }

    @Test fun `wrong malformed duplicated oversized runtime or catalogue prevents any chat`() = runBlocking {
        val cases = listOf(
            """{"version":"0.34.0"}""" to catalogue,
            """{"version":0.35}""" to catalogue,
            """{"version":"0.35.0","ver\u0073ion":"0.35.0"}""" to catalogue,
            "{" to catalogue, " ".repeat(1025) to catalogue,
            version to """{"models":[]}""", version to """{"models":null}""",
            version to catalogue.replace(syntheticModelOption.tag, "other:3b"),
            version to catalogue.replace(syntheticModelOption.digest, "b".repeat(64)),
            version to """{"models":[$entry,$entry]}""",
            version to catalogue.replace("\"digest\":", "\"remote_host\":\"https://hosted.invalid\",\"digest\":"),
            version to catalogue.replace("\"digest\":", "\"remote_model\":\"remote\",\"digest\":"),
            version to catalogue.replace("\"digest\":", "\"remote_host\":null,\"digest\":"),
            version to catalogue.replace("\"digest\":", "\"digest\":\"${syntheticModelOption.digest}\",\"dige\\u0073t\":"),
            version to "{" , version to "[]", version to " ".repeat(32_769),
            version to """{"models":[null]}""", version to """{"models":[{"name":"${syntheticModelOption.tag}"}]}""",
        )
        for ((runtime, tags) in cases) {
            val paths = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                assertEquals(HttpMethod.Get, request.method)
                respond(if (request.url.encodedPath == "/api/version") runtime else tags)
            })
            val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
            try {
                val error = assertFailsWith<ModelUnavailable> { model.generate(packet, null) }
                assertEquals("Local inference unavailable; diagnostics withheld", error.message)
                assertNull(error.cause)
                assertFalse("/api/chat" in paths)
                assertTrue(paths.all { it in setOf("/api/version", "/api/tags") })
            } finally { model.close(); client.close() }
        }
    }

    @Test fun `wrong identity role thinking truncation done types duplicate keys and oversized envelopes fail closed`() = runBlocking {
        val response = envelope()
        val bodies = listOf(response.replace(syntheticModelOption.tag, "other:3b"),
            response.replace("\"assistant\"", "\"user\""), response.replace("\"done\":true", "\"done\":false"),
            response.replace("\"done\":true", "\"done\":\"true\""), response.replace("\"stop\"", "\"length\""),
            response.replace("\"role\":", "\"thinking\":\"private diagnostics\",\"role\":"),
            response.replace("\"role\":", "\"thinking\":false,\"role\":"),
            response.replace("\"role\":", "\"thinking\":null,\"role\":"),
            response.replace("\"role\":", "\"thinking\":{},\"role\":"),
            response.replace("\"done\":", "\"done\":true,\"do\\u006ee\":"),
            response.dropLast(1), " ".repeat(32_769), "[]", "{", "$response extra prose",
            """{"model":"${syntheticModelOption.tag}","done":true,"done_reason":"stop","message":{"role":"assistant","content":0}}""",
            """{"model":"${syntheticModelOption.tag}","done":true,"done_reason":"stop","message":null}""",
            """{"model":"${syntheticModelOption.tag}","done":true,"message":{"role":"assistant","content":"{}"}}""",
        )
        for (body in bodies) {
            val paths = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                respond(when (request.url.encodedPath) {
                    "/api/version" -> version
                    "/api/tags" -> catalogue
                    "/api/chat" -> body
                    else -> error("Unexpected request")
                })
            })
            val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
            try {
                val error = assertFailsWith<ModelUnavailable> { model.generate(packet, null) }
                assertNull(error.cause)
                assertEquals(listOf("/api/version", "/api/tags", "/api/chat"), paths)
            } finally { model.close(); client.close() }
        }
    }

    @Test fun `redirects outages and unauthorized errors never retry follow redirects pull or fall back`() = runBlocking {
        for (stage in listOf("/api/version", "/api/tags", "/api/chat")) {
            for (status in listOf(HttpStatusCode.Found, HttpStatusCode.TemporaryRedirect, HttpStatusCode.Unauthorized,
                HttpStatusCode.InternalServerError)) {
                val paths = mutableListOf<String>()
                val client = HttpClient(MockEngine { request ->
                    assertEquals("127.0.0.1", request.url.host)
                    assertFalse(request.headers.contains(HttpHeaders.Authorization))
                    assertFalse(request.headers.contains(HttpHeaders.Cookie))
                    paths += request.url.encodedPath
                    if (request.url.encodedPath == stage) respond("private provider error", status,
                        headersOf(HttpHeaders.Location, "https://hosted.invalid/api/chat"))
                    else respond(if (request.url.encodedPath == "/api/version") version else catalogue)
                })
                val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
                try {
                    val error = assertFailsWith<ModelUnavailable> { model.generate(packet, null) }
                    assertEquals("Local inference unavailable; diagnostics withheld", error.message)
                    assertNull(error.cause)
                    assertEquals(listOf("/api/version", "/api/tags", "/api/chat").takeWhile { it != stage } + stage, paths)
                } finally { model.close(); client.close() }
            }
        }
    }

    @Test fun `artifact changes after inference invalidate otherwise valid selection`() = runBlocking {
        for (after in listOf(catalogue.replace(syntheticModelOption.digest, "b".repeat(64)), """{"models":[]}""",
            """{"models":[$entry,$entry]}""", "{")) {
            var manifests = 0
            val paths = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                respond(when (request.url.encodedPath) {
                    "/api/version" -> version
                    "/api/tags" -> if (++manifests == 1) catalogue else after
                    "/api/chat" -> envelope()
                    else -> error("Unexpected request")
                })
            })
            val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
            try {
                assertFailsWith<ModelUnavailable> { model.generate(packet, null) }
                assertEquals(listOf("/api/version", "/api/tags", "/api/chat", "/api/tags"), paths)
            } finally { model.close(); client.close() }
        }
    }

    @Test fun `caller cancellation propagates at every stage with no retry or fallback`() = runBlocking {
        for (stage in 1..4) {
            var requests = 0
            val cancelled = CancellationException("synthetic caller cancellation")
            val client = HttpClient(MockEngine { request ->
                if (++requests == stage) throw cancelled
                respond(when (request.url.encodedPath) {
                    "/api/version" -> version
                    "/api/tags" -> catalogue
                    "/api/chat" -> envelope()
                    else -> error("Unexpected request")
                })
            })
            val model = OllamaReviewFocusModel(client, endpoint, syntheticModelOption)
            try {
                val propagated = assertFailsWith<CancellationException> { model.generate(packet, null) }
                assertEquals(cancelled.message, propagated.message) // Coroutine stack recovery may copy the exception.
                assertEquals(stage, requests)
            } finally { model.close(); client.close() }
        }
    }

    @Test fun `separate configuration requires app profile and restricted local catalogue`() {
        val config = ReviewLocalConfiguration(ReviewFocusProtocol.PROFILE, endpoint, listOf(syntheticModelOption))
        assertEquals(ReviewExecutionPolicy(), config.policy)
        for (profile in listOf("", "prepared-focus-v1", "factual-review-v1")) {
            assertFailsWith<IllegalArgumentException> { config.copy(profile = profile) }
        }
        for (bad in listOf("https://hosted.invalid", "$endpoint/", "http://localhost:11434",
            "http://user:secret@127.0.0.1:11434", "http://192.168.1.1:11434", "$endpoint?proxy=remote")) {
            assertFailsWith<IllegalArgumentException> { config.copy(endpoint = bad) }
        }
        assertFailsWith<IllegalArgumentException> { config.copy(models = emptyList()) }
        assertFailsWith<IllegalArgumentException> { config.copy(models = listOf(syntheticModelOption, syntheticModelOption)) }
        assertFailsWith<IllegalArgumentException> { config.copy(models = listOf(syntheticModelOption, syntheticModelOption.copy(id = "another"))) }
        assertFailsWith<IllegalArgumentException> { config.copy(models = (1..5).map { syntheticModelOption.copy(id = "m$it", tag = "synthetic:$it") }) }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(tag = "synthetic:cloud") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(digest = "bad") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(experimental = false) }
        for (local in LocalModelConfiguration.LOCAL_ENDPOINTS) assertEquals(local, config.copy(endpoint = local).endpoint)
    }

    @Test fun `execution budgets are positive bounded and total job cannot be shorter than one call`() {
        assertEquals(120_000L, ReviewExecutionPolicy().callTimeoutMillis)
        assertEquals(130_000L, ReviewExecutionPolicy().jobTimeoutMillis)
        assertFalse(ReviewExecutionPolicy().allowCorrectiveAttempt)
        for ((call, job) in listOf(0L to 1L, -1L to 1L, 240_001L to 600_000L, 1L to 0L,
            120_000L to 119_999L, 1L to 600_001L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ReviewExecutionPolicy(call, job) }
        }
        assertEquals(1L, ReviewExecutionPolicy(1, 1).callTimeoutMillis)
        assertEquals(600_000L, ReviewExecutionPolicy(240_000, 600_000, true).jobTimeoutMillis)
    }

    @Test fun `strict bounded configuration file rejects legacy missing profile duplicate keys unknown fields and invalid UTF8`() {
        val file = Files.createTempFile("gtrainer-review-synthetic-config-", ".json")
        try {
            val config = ReviewLocalConfiguration(ReviewFocusProtocol.PROFILE, endpoint, listOf(syntheticModelOption))
            val valid = ReviewFocusProtocol.json.encodeToString(config)
            Files.writeString(file, valid)
            assertEquals(config, OllamaReviewFocusModel.configurationFromFile(file))
            for (bad in listOf("", " ".repeat(16_385), "{", valid + valid,
                AnalysisClaims.json.encodeToString(LocalModelConfiguration(endpoint, listOf(syntheticModelOption))),
                valid.replace("\"profile\":", "\"profile\":\"prepared-focus-v1\",\"pro\\u0066ile\":"),
                valid.replace("\"endpoint\":", "\"endpoint\":\"https://hosted.invalid\",\"endpoint\":"),
                valid.replace("\"models\":", "\"credentials\":\"private-secret\",\"models\":"),
                valid.replace("120000", "0"), valid.replace("130000", "600001"),
                valid.replace("120000", "120000,\"callTimeoutMillis\":120000"),
                "[".repeat(18) + "0" + "]".repeat(18))) {
                Files.writeString(file, bad)
                assertFailsWith<Exception> { OllamaReviewFocusModel.configurationFromFile(file) }
            }
            Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
            assertFailsWith<Exception> { OllamaReviewFocusModel.configurationFromFile(file) }
        } finally { Files.deleteIfExists(file) }
    }
}

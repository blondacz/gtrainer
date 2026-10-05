package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.*
import kotlin.test.*

class OllamaConnectedReviewProviderTest {
    private val binding = DevelopmentReviewModelBinding("127.0.0.1", "ollama", "qwen3-q3", "Qwen Q3",
        "gtrainer-qwen3:8b-q3_k_m", "1cfe11082ca46aa64ac99dd0f41f874671c654686fc6d6b3d48942ca04b13c8a",
        "0.35.0", 2048, 1536, 3, 17, 0.1)
    private val token = OwnedOllamaRuntimeToken(3, 7, "container-identity", binding.copy())
    private val prompt = InterpretationPromptV1("system marker", "packet marker")
    private val tags get() = """{"models":[{"name":"${binding.tag}","digest":"${binding.manifestDigest}"}]}"""
    private fun envelope(content: String = "{\"draft\":true}", done: Boolean = true) =
        """{"model":"${binding.tag}","done":$done,"done_reason":"stop","message":{"role":"assistant","content":${JsonPrimitive(content)}}}"""

    private fun provider(engine: MockEngine, allowed: OllamaRuntimeOwnershipCheck = OllamaRuntimeOwnershipCheck { true }) =
        OllamaConnectedReviewProvider(binding, token, allowed, HttpClient(engine), 123_000)

    @Test fun `exact local chat options and fixed correction only`(): Unit = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val client = provider(MockEngine { request ->
            assertEquals("127.0.0.1", request.url.host); assertEquals(11434, request.url.port)
            respond(when (request.url.encodedPath) {
                "/api/version" -> """{"version":"0.35.0"}"""
                "/api/tags" -> tags
                "/api/chat" -> {
                    val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    bodies += json
                        assertEquals("0", json["keep_alive"]!!.jsonPrimitive.content)
                        assertEquals(false, json["think"]!!.jsonPrimitive.boolean)
                        assertEquals(false, json["stream"]!!.jsonPrimitive.boolean)
                        assertEquals(binding.tag, json["model"]!!.jsonPrimitive.content)
                        assertEquals("json", json["format"]!!.jsonPrimitive.content)
                        assertEquals(2048, json["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
                        assertEquals(1536, json["options"]!!.jsonObject["num_predict"]!!.jsonPrimitive.int)
                        assertEquals(3, json["options"]!!.jsonObject["num_thread"]!!.jsonPrimitive.int)
                        assertEquals(17, json["options"]!!.jsonObject["seed"]!!.jsonPrimitive.int)
                        assertEquals(0.1, json["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.double)
                        val messages = json["messages"]!!.jsonArray
                        assertEquals("system marker", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
                        assertEquals("packet marker", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
                        if (bodies.size == 2) assertTrue(messages.last().jsonObject["content"]!!.jsonPrimitive.content.contains("invalid_output"))
                        else assertEquals(2, messages.size)
                        assertFalse(messages.toString().contains("sensitive-rejected-text"))
                    envelope()
                }
                else -> error("unexpected request")
            })
        })
        try {
            assertNull(client.enforcedMaximumCostUsd)
            assertNull(client.generate(prompt, null, 12 * 1024, null).costUsd)
            assertNull(client.generate(prompt, "invalid_output", 12 * 1024, null).costUsd)
            assertFailsWith<OllamaConnectedReviewFailure> { client.generate(prompt, "sensitive-rejected-text", 12288, null) }
            assertEquals(2, bodies.size)
        } finally { client.close() }
    }

    @Test fun `default gate refuses and changed ownership prevents send`(): Unit = runBlocking {
        var calls = 0
        val client = OllamaConnectedReviewProvider(binding, token, client = HttpClient(MockEngine { calls++; respond("") }))
        try {
            assertFailsWith<OllamaConnectedReviewFailure> { client.generate(prompt, null, 12288, null) }
            assertEquals(0, calls)
        } finally { client.close() }
        var checks = 0
        val gated = provider(MockEngine { respond(when (it.url.encodedPath) {
            "/api/version" -> """{"version":"0.35.0"}"""
            else -> error("request after ownership lost")
        }) }, OllamaRuntimeOwnershipCheck { ++checks < 3 })
        try { assertFailsWith<OllamaConnectedReviewFailure> { gated.generate(prompt, null, 12288, null) } } finally { gated.close() }
    }

    @Test fun `binding and envelope errors are fixed and redirect not followed`() = runBlocking {
        for ((version, chat) in listOf("0.36.0" to envelope(), "0.35.0" to envelope(done = false),
            "0.35.0" to "{")) {
            val client = provider(MockEngine { request -> respond(when (request.url.encodedPath) {
                "/api/version" -> """{"version":"$version"}"""
                "/api/tags" -> tags
                "/api/chat" -> chat
                else -> error("unexpected path")
            }) })
            try { assertFailsWith<OllamaConnectedReviewFailure> { client.generate(prompt, null, 12288, null) } }
            finally { client.close() }
        }
        var chatCalled = false
        val redirect = provider(MockEngine { request -> when (request.url.encodedPath) {
            "/api/version" -> respond("""{"version":"0.35.0"}""")
            "/api/tags" -> respond(tags)
            else -> { chatCalled = true; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "http://example.test")) }
        } })
        try { assertFailsWith<OllamaConnectedReviewFailure> { redirect.generate(prompt, null, 12288, null) }; assertTrue(chatCalled) }
        finally { redirect.close() }
    }

    @Test fun `digest mismatch oversized envelope invalid utf8 and http failure are rejected`() = runBlocking {
        val cases = listOf("digest", "large", "utf8", "status")
        for (kind in cases) {
            val client = provider(MockEngine { request -> when (request.url.encodedPath) {
                "/api/version" -> respond("""{"version":"0.35.0"}""")
                "/api/tags" -> respond(if (kind == "digest") tags.replace(binding.manifestDigest, "0".repeat(64)) else tags)
                "/api/chat" -> when (kind) {
                    "large" -> respond("{" + " ".repeat(64 * 1024))
                    "utf8" -> {
                        respond(content = ByteReadChannel(byteArrayOf(0xc3.toByte(), 0x28)), status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    "status" -> respond("private error marker", HttpStatusCode.InternalServerError)
                    else -> respond(envelope())
                }
                else -> error("unexpected request")
            } })
            try {
                val failure = assertFailsWith<OllamaConnectedReviewFailure> { client.generate(prompt, null, 12288, null) }
                assertFalse(failure.message.orEmpty().contains("private error marker"))
            } finally { client.close() }
        }
    }

    @Test fun `cancellation propagates and thinking or oversize content rejected`() = runBlocking {
        val cancelled = provider(MockEngine { throw CancellationException("private marker") })
        try { assertFailsWith<CancellationException> { cancelled.generate(prompt, null, 12288, null) } } finally { cancelled.close() }
        val bad = envelope().replace("\"content\":", "\"thinking\":\"secret\",\"content\":")
        for (response in listOf(bad, envelope("x".repeat(12 * 1024 + 1)))) {
            val client = provider(MockEngine { respond(when (it.url.encodedPath) {
                "/api/version" -> """{"version":"0.35.0"}"""
                "/api/tags" -> tags
                else -> response
            }) })
            try { assertFailsWith<OllamaConnectedReviewFailure> { client.generate(prompt, null, 12288, null) } }
            finally { client.close() }
        }
    }

    @Test fun `publication preflight rechecks exact version tag digest and ownership`() = runBlocking {
        for (changed in listOf("version", "tag", "digest", "generation")) {
            var chatFinished = false
            var chats = 0
            val runtime = provider(MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/version" -> respond(if (chatFinished && changed == "version") """{"version":"wrong"}""" else """{"version":"0.35.0"}""")
                    "/api/tags" -> respond(when {
                        !chatFinished -> tags
                        changed == "tag" -> tags.replace(binding.tag, "other-tag")
                        changed == "digest" -> tags.replace(binding.manifestDigest, "0".repeat(64))
                        else -> tags
                    })
                    "/api/chat" -> { chats++; chatFinished = true; respond(envelope()) }
                    else -> error("request outside owned endpoint")
                }
            }, OllamaRuntimeOwnershipCheck { candidate -> candidate == token && !(chatFinished && changed == "generation") })
            runtime.use {
                val failure = assertFailsWith<OllamaConnectedReviewFailure> { it.generate(prompt, null, 12288, null) }
                assertEquals(if (changed == "generation") ConnectedReviewProviderFailureCode.OWNERSHIP_REFUSED
                    else ConnectedReviewProviderFailureCode.BINDING_MISMATCH, failure.code)
                assertEquals(1, chats)
            }
        }
    }

    @Test fun `ambiguous nonassistant and length truncated envelopes never return content`() = runBlocking {
        val invalidEnvelopes = listOf(
            envelope().replace("\"stop\"", "\"length\""),
            envelope().replace("\"stop\"", "\"max_tokens\""),
            envelope().replace("\"assistant\"", "\"user\""),
            envelope().replace("\"done\":true", "\"done\":\"true\""),
            envelope().replace("\"done\":true", "\"done\":false,\"done\":true"),
            envelope().replace(binding.tag, "other-model"),
            envelope().dropLast(2),
            "[]",
            "{\"private-error-marker\":true}",
        )
        for (response in invalidEnvelopes) {
            var sends = 0
            provider(MockEngine { request -> respond(when (request.url.encodedPath) {
                "/api/version" -> """{"version":"0.35.0"}"""
                "/api/tags" -> tags
                else -> { sends++; response }
            }) }).use { runtime ->
                val failure = assertFailsWith<OllamaConnectedReviewFailure> { runtime.generate(prompt, null, 12288, null) }
                assertEquals(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME, failure.code)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("private-error-marker"))
                assertEquals(1, sends)
            }
        }
    }

    @Test fun `metadata reads request and utf8 draft bytes stay bounded`() = runBlocking {
        for (endpoint in listOf("/api/version", "/api/tags")) {
            var chats = 0
            provider(MockEngine { request -> respond(when (request.url.encodedPath) {
                endpoint -> " ".repeat(64 * 1024 + 1)
                "/api/version" -> """{"version":"0.35.0"}"""
                "/api/tags" -> tags
                else -> { chats++; envelope() }
            }) }).use { runtime ->
                val failure = assertFailsWith<OllamaConnectedReviewFailure> { runtime.generate(prompt, null, 12288, null) }
                assertEquals(ConnectedReviewProviderFailureCode.RESPONSE_TOO_LARGE, failure.code)
                assertEquals(0, chats)
            }
        }
        var requests = 0
        provider(MockEngine { requests++; respond("") }).use { runtime ->
            assertFailsWith<OllamaConnectedReviewFailure> {
                runtime.generate(InterpretationPromptV1("x".repeat(32 * 1024), ""), null, 12288, null)
            }
            assertEquals(0, requests)
        }
        provider(MockEngine { request -> respond(when (request.url.encodedPath) {
            "/api/version" -> """{"version":"0.35.0"}"""
            "/api/tags" -> tags
            else -> envelope("é".repeat(7 * 1024))
        }) }).use { runtime ->
            val failure = assertFailsWith<OllamaConnectedReviewFailure> { runtime.generate(prompt, null, 12288, null) }
            assertEquals(ConnectedReviewProviderFailureCode.RESPONSE_TOO_LARGE, failure.code)
        }
    }

    @Test fun `redirect and server error send no second request or external request`() = runBlocking {
        for (status in listOf(HttpStatusCode.TemporaryRedirect, HttpStatusCode.InternalServerError)) {
            val paths = mutableListOf<String>()
            provider(MockEngine { request ->
                assertEquals("127.0.0.1", request.url.host)
                paths.add(request.url.encodedPath)
                when (request.url.encodedPath) {
                    "/api/version" -> respond("""{"version":"0.35.0"}""")
                    "/api/tags" -> respond(tags)
                    else -> respond("private-error-marker", status, headersOf(HttpHeaders.Location, "http://example.test/redirect"))
                }
            }).use { runtime ->
                assertFailsWith<OllamaConnectedReviewFailure> { runtime.generate(prompt, null, 12288, null) }
                assertEquals(listOf("/api/version", "/api/tags", "/api/chat"), paths)
            }
        }
    }

    @Test fun `injected retry policy cannot turn operational failure into a retry`() = runBlocking {
        var requests = 0
        val injected = HttpClient(MockEngine { requests++; respond("private-error-marker", HttpStatusCode.InternalServerError) }) {
            install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 3); constantDelay(millis = 1) }
        }
        OllamaConnectedReviewProvider(binding, token, OllamaRuntimeOwnershipCheck { true }, injected).use { runtime ->
            assertFailsWith<OllamaConnectedReviewFailure> { runtime.generate(prompt, null, 12288, null) }
            assertEquals(1, requests)
        }
    }
}

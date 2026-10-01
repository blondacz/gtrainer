package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AnalysisModelsTest {
    private val packet = AnalysisClaims.prepare(Trends.analysisInput(analysisReport())).packet
    private val catalogue = """{"models":[{"name":"synthetic:3b","digest":"${syntheticModelOption.digest}"}]}"""
    private val response = buildJsonObject {
        put("model", "synthetic:3b"); put("done", true); put("done_reason", "stop")
        putJsonObject("message") { put("role", "assistant"); put("content", validClaims()) }
    }.toString()

    @Test fun `only pinned local runtime artifact and bounded summary are sent with no cloud pull or credentials`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("127.0.0.1", request.url.host)
            assertEquals(11434, request.url.port)
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
            assertFalse(request.headers.contains(HttpHeaders.Cookie))
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/api/version" -> { assertEquals(HttpMethod.Get, request.method); """{"version":"0.35.0"}""" }
                "/api/tags" -> { assertEquals(HttpMethod.Get, request.method); catalogue }
                "/api/chat" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val sent = (request.body as io.ktor.http.content.TextContent).text
                    assertFalse(sent.contains("synthetic-analysis"))
                    assertFalse(sent.contains("sourceRecordId"))
                    val payload = Json.parseToJsonElement(sent).jsonObject
                    assertEquals(JsonPrimitive(false), payload["stream"])
                    assertEquals(JsonPrimitive(false), payload["think"])
                    assertEquals(JsonPrimitive("0s"), payload["keep_alive"])
                    assertEquals(JsonPrimitive(2048), payload["options"]!!.jsonObject["num_ctx"])
                    assertEquals(JsonPrimitive(256), payload["options"]!!.jsonObject["num_predict"])
                    assertEquals(AnalysisClaims.schema(packet), payload["format"])
                    assertEquals(AnalysisClaims.json.encodeToString(packet), payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
                    response
                }
                else -> error("Unexpected request")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val model = OllamaAnalysisModel(client, "http://127.0.0.1:11434", syntheticModelOption)
        try {
            assertEquals(validClaims(), model.generate(packet))
            assertEquals(listOf("/api/version", "/api/tags", "/api/chat", "/api/tags"), paths)
        } finally { model.close(); client.close() }
    }

    @Test fun `runtime mismatch changed missing duplicate remote or malformed catalogue never receives health packet`() = runBlocking {
        for ((version, tags) in listOf(
            "0.34.0" to catalogue, "0.35.0" to "{\"models\":[]}",
            "0.35.0" to catalogue.replace(syntheticModelOption.digest, "b".repeat(64)),
            "0.35.0" to catalogue.replace("\"digest\":", "\"remote_host\":\"https://hosted.invalid\",\"digest\":"),
            "0.35.0" to catalogue.replace("\"digest\":", "\"remote_model\":\"remote\",\"digest\":"),
            "0.35.0" to catalogue.replace("]", ",${Json.parseToJsonElement(catalogue).jsonObject["models"]!!.jsonArray.first()}]"),
            "0.35.0" to "{", "0.35.0" to " ".repeat(32_769),
        )) {
            var posts = 0
            val client = HttpClient(MockEngine { request ->
                if (request.method == HttpMethod.Post) posts++
                respond(if (request.url.encodedPath == "/api/version") """{"version":"$version"}""" else tags)
            })
            val model = OllamaAnalysisModel(client, "http://127.0.0.1:11434", syntheticModelOption)
            try {
                assertFailsWith<ModelUnavailable> { model.generate(packet) }
                assertEquals(0, posts)
            } finally { model.close(); client.close() }
        }
    }

    @Test fun `redirects response truncation thinking wrong role or identity oversized bodies and outages fail closed`() = runBlocking {
        val badBodies = listOf(response.replace("\"done\":true", "\"done\":false"),
            response.replace("\"stop\"", "\"length\""), response.replace("\"assistant\"", "\"user\""),
            response.replace("\"role\":", "\"thinking\":\"private diagnostic\",\"role\":"),
            response.replace("\"role\":", "\"thinking\":false,\"role\":"),
            response.replace("synthetic:3b", "other:3b"), response.replace("\"done\":", "\"done\":true,\"done\":"),
            " ".repeat(32_769), "{", "{\"done\":true,\"done_reason\":\"stop\",\"model\":\"synthetic:3b\",\"message\":{\"role\":\"assistant\",\"content\":0}}")
        for (body in badBodies) {
            val client = HttpClient(MockEngine { request -> respond(when (request.url.encodedPath) {
                "/api/version" -> """{"version":"0.35.0"}"""
                "/api/tags" -> catalogue
                else -> body
            }) })
            val model = OllamaAnalysisModel(client, "http://127.0.0.1:11434", syntheticModelOption)
            try { assertFailsWith<ModelUnavailable> { model.generate(packet) } } finally { model.close(); client.close() }
        }
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.InternalServerError)) {
            var requests = 0
            val client = HttpClient(MockEngine { requests++; respond("sensitive-provider-error", status,
                headersOf(HttpHeaders.Location, "https://hosted.invalid/")) })
            val model = OllamaAnalysisModel(client, "http://127.0.0.1:11434", syntheticModelOption)
            try {
                val error = assertFailsWith<ModelUnavailable> { model.generate(packet) }
                assertEquals("Local inference unavailable; diagnostics withheld", error.message)
                assertNull(error.cause)
                assertEquals(1, requests)
            } finally { model.close(); client.close() }
        }
    }

    @Test fun `artifact changes after inference invalidate result and caller cancellation propagates`() = runBlocking {
        var tags = 0
        val client = HttpClient(MockEngine { request -> respond(when (request.url.encodedPath) {
            "/api/version" -> """{"version":"0.35.0"}"""
            "/api/tags" -> { tags++; if (tags == 1) catalogue else catalogue.replace(syntheticModelOption.digest, "b".repeat(64)) }
            else -> response
        }) })
        val model = OllamaAnalysisModel(client, "http://127.0.0.1:11434", syntheticModelOption)
        try { assertFailsWith<ModelUnavailable> { model.generate(packet) } } finally { model.close(); client.close() }
        val cancelledClient = HttpClient(MockEngine { throw CancellationException("synthetic cancellation") })
        val cancelledModel = OllamaAnalysisModel(cancelledClient, "http://127.0.0.1:11434", syntheticModelOption)
        try { assertFailsWith<CancellationException> { cancelledModel.generate(packet) } } finally { cancelledModel.close(); cancelledClient.close() }
    }

    @Test fun `configuration forbids hosted endpoints URL credentials cloud models invalid identities and duplicate options`() {
        for (endpoint in listOf("https://hosted.invalid", "http://127.0.0.1:11434/", "http://localhost:11434",
            "http://user:secret@127.0.0.1:11434", "http://192.168.1.1:11434", "http://127.0.0.1:11434?proxy=remote")) {
            assertFailsWith<IllegalArgumentException> { LocalModelConfiguration(endpoint, listOf(syntheticModelOption)) }
        }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(tag = "test:cloud") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(digest = "bad") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(id = "bad/id") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(label = "<script>") }
        assertFailsWith<IllegalArgumentException> { syntheticModelOption.copy(experimental = false) }
        assertFailsWith<IllegalArgumentException> { LocalModelConfiguration("http://127.0.0.1:11434", listOf(syntheticModelOption, syntheticModelOption)) }
        assertFailsWith<IllegalArgumentException> { LocalModelConfiguration("http://127.0.0.1:11434", emptyList()) }
    }

    @Test fun `external catalogue parsing is bounded rejects duplicate keys and refuses invalid UTF8`() {
        val file = Files.createTempFile("gtrainer-synthetic-model-config-", ".json")
        try {
            val config = LocalModelConfiguration("http://127.0.0.1:11434", listOf(syntheticModelOption))
            Files.writeString(file, AnalysisClaims.json.encodeToString(config))
            assertEquals(config, OllamaAnalysisModel.configurationFromFile(file))
            for (body in listOf("", " ".repeat(16_385), "{", AnalysisClaims.json.encodeToString(config).replace(
                "\"endpoint\":", "\"endpoint\":\"https://hosted.invalid\",\"endpoint\":"))) {
                Files.writeString(file, body)
                assertFailsWith<Exception> { OllamaAnalysisModel.configurationFromFile(file) }
            }
            Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
            assertFailsWith<Exception> { OllamaAnalysisModel.configurationFromFile(file) }
        } finally { Files.deleteIfExists(file) }
    }
}

package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class LocalModelOption(val id: String, val label: String, val tag: String, val digest: String,
                            val experimental: Boolean = true) {
    init {
        require(experimental) { "Only experimental local models are supported" }
        require(id.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid local model configuration" }
        require(label.matches(Regex("[A-Za-z0-9 .()-]{1,80}"))) { "Invalid local model configuration" }
        require(tag.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_.-]{0,63}")) &&
            !tag.contains("cloud")) { "Invalid local model configuration" }
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid local model configuration" }
    }
}

@Serializable
data class LocalModelConfiguration(val endpoint: String, val models: List<LocalModelOption>) {
    init {
        require(endpoint in LOCAL_ENDPOINTS) { "Only explicitly supported local model endpoints are allowed" }
        require(models.size in 1..4 && models.map { it.id }.distinct().size == models.size &&
            models.map { it.tag }.distinct().size == models.size) { "Invalid local model configuration" }
    }
    companion object {
        val LOCAL_ENDPOINTS = setOf("http://127.0.0.1:11434", "http://gtrainer-ollama.gtrainer-models.svc.cluster.local:11434")
    }
}

interface AnalysisModel {
    val option: LocalModelOption
    suspend fun generate(packet: ModelPacket): String
}

class ModelUnavailable : IllegalStateException("Local inference unavailable; diagnostics withheld")

/** No pull endpoint, credentials, Logging plugin, redirects, hosted URL, or automatic fallback. */
class OllamaAnalysisModel(client: HttpClient, private val endpoint: String, override val option: LocalModelOption) : AnalysisModel {
    private val client = client.config { followRedirects = false; expectSuccess = false }
    init { require(endpoint in LocalModelConfiguration.LOCAL_ENDPOINTS) { "Invalid local endpoint" } }

    private suspend fun runtimeMatches(): Boolean = client.prepareGet("$endpoint/api/version").execute { response ->
        if (response.status.value != 200) return@execute false
        val bytes = response.bodyAsChannel().readBuffer(1025L).readByteArray()
        if (bytes.size > 1024) return@execute false
        StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject["version"] == JsonPrimitive(RUNTIME_VERSION)
    }

    private suspend fun catalogueMatches(): Boolean = client.prepareGet("$endpoint/api/tags") {
        header(HttpHeaders.Accept, "application/json")
    }.execute { response ->
        if (response.status.value != 200) return@execute false
        val bytes = response.bodyAsChannel().readBuffer(32_769L).readByteArray()
        if (bytes.size > 32_768) return@execute false
        val body = StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        val models = body["models"] as? JsonArray ?: return@execute false
        val matching = models.map { it.jsonObject }.filter { it["name"]?.jsonPrimitive?.content == option.tag }
        matching.size == 1 && matching.single()["digest"] == JsonPrimitive(option.digest) &&
            matching.single().keys.none { it in setOf("remote_host", "remote_model") }
    }

    override suspend fun generate(packet: ModelPacket): String = try {
        if (!runtimeMatches() || !catalogueMatches()) throw ModelUnavailable() // No health input sent to an unverified/missing artifact.
        val input = AnalysisClaims.json.encodeToString(packet)
        if (input.length > 6000) throw ModelUnavailable()
        val body = buildJsonObject {
            put("model", option.tag); put("stream", false); put("think", false); put("keep_alive", "0s")
            put("format", AnalysisClaims.schema(packet))
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", AnalysisClaims.system) })
                add(buildJsonObject { put("role", "user"); put("content", input) })
            }
            putJsonObject("options") {
                put("num_ctx", 2048); put("num_thread", 3); put("num_predict", 256); put("temperature", 0); put("seed", 42)
            }
        }
        val output = client.preparePost("$endpoint/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (response.status.value != 200) throw ModelUnavailable()
            val bytes = response.bodyAsChannel().readBuffer(32_769L).readByteArray()
            if (bytes.size > 32_768) throw ModelUnavailable()
            val value = StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            if (value["done"] != JsonPrimitive(true) || value["done_reason"] != JsonPrimitive("stop") ||
                value["model"] != JsonPrimitive(option.tag)) throw ModelUnavailable()
            val message = value["message"]?.jsonObject ?: throw ModelUnavailable()
            if (message["role"] != JsonPrimitive("assistant") ||
                message["thinking"]?.let { it !is JsonPrimitive || !it.isString || it.content.isNotEmpty() } == true) throw ModelUnavailable()
            message["content"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: throw ModelUnavailable()
        }
        if (!catalogueMatches()) throw ModelUnavailable()
        output
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { throw ModelUnavailable() }

    fun close() { client.close() }

    companion object {
        const val RUNTIME_VERSION = "0.35.0"
        fun client(): HttpClient = HttpClient(CIO) {
            followRedirects = false; expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 5000; socketTimeoutMillis = 120_000 }
        }
        fun configurationFromEnvironment(): LocalModelConfiguration? {
            val file = System.getenv("GTRAINER_LOCAL_MODELS_FILE") ?: return null
            return configurationFromFile(Path.of(file))
        }
        internal fun configurationFromFile(path: Path): LocalModelConfiguration {
            val bytes = Files.newInputStream(path).use { it.readNBytes(16_385) }
            require(bytes.size in 1..16_384) { "Invalid local model configuration" }
            return Json.decodeFromJsonElement<LocalModelConfiguration>(StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)))
        }
    }
}

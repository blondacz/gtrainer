package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ReviewLocalConfiguration(val profile: String, val endpoint: String, val models: List<LocalModelOption>,
                                   val policy: ReviewExecutionPolicy = ReviewExecutionPolicy()) {
    init {
        require(profile == ReviewFocusProtocol.PROFILE) { "Explicit review profile required" }
        LocalModelConfiguration(endpoint, models) // Same restricted local-only catalogue; no implicit provider migration.
    }
}

/** Separate wire protocol; the original OllamaAnalysisModel is unchanged. No retries inside the adapter. */
class OllamaReviewFocusModel(client: HttpClient, private val endpoint: String, override val option: LocalModelOption) : ReviewFocusModel {
    private val client = client.config { followRedirects = false; expectSuccess = false }
    init { require(endpoint in LocalModelConfiguration.LOCAL_ENDPOINTS) }

    private suspend fun get(path: String, maximumBytes: Int): JsonObject = client.prepareGet("$endpoint$path") {
        header(HttpHeaders.Accept, "application/json")
    }.execute { response ->
        if (response.status.value != 200) throw ModelUnavailable()
        val body = response.bodyAsChannel().readBuffer(maximumBytes.toLong() + 1).readByteArray()
        if (body.size > maximumBytes) throw ModelUnavailable()
        StrictModelJson.parse(body.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    private suspend fun checkArtifact() {
        val entries = get("/api/tags", 32_768)["models"] as? JsonArray ?: throw ModelUnavailable()
        val matching = entries.map { it.jsonObject }.filter { it["name"] == JsonPrimitive(option.tag) }
        if (matching.size != 1 || matching.single()["digest"] != JsonPrimitive(option.digest) ||
            matching.single().keys.any { it in setOf("remote_host", "remote_model") }) throw ModelUnavailable()
    }

    override suspend fun generate(packet: ReviewFocusPacket, feedback: String?): String = try {
        require(packet.profile == ReviewFocusProtocol.PROFILE)
        require(feedback == null || feedback in ReviewFocusProtocol.correctionReasons)
        val input = ReviewFocusProtocol.json.encodeToString(packet)
        require(input.toByteArray(Charsets.UTF_8).size <= 6000)
        if (get("/api/version", 1024)["version"] != JsonPrimitive(OllamaAnalysisModel.RUNTIME_VERSION)) throw ModelUnavailable()
        checkArtifact() // No summarized input goes to an unverified runtime/artifact.
        val payload = buildJsonObject {
            put("model", option.tag); put("stream", false); put("think", false); put("keep_alive", "0s")
            put("format", ReviewFocusProtocol.schema(packet))
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", ReviewFocusProtocol.system) })
                add(buildJsonObject { put("role", "user"); put("content", input) })
                if (feedback != null) add(buildJsonObject {
                    put("role", "user"); put("content", "Previous whole response rejected: $feedback. Return a new complete selection for the same packet and requestedFocus.")
                })
            }
            putJsonObject("options") {
                put("num_ctx", 2048); put("num_thread", 3); put("num_predict", 256); put("temperature", 0); put("seed", 42)
            }
        }
        val output = client.preparePost("$endpoint/api/chat") {
            contentType(ContentType.Application.Json); setBody(payload.toString())
        }.execute { response ->
            if (response.status.value != 200) throw ModelUnavailable()
            val bytes = response.bodyAsChannel().readBuffer(32_769).readByteArray()
            if (bytes.size > 32_768) throw ModelUnavailable()
            val body = StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            val message = body["message"]?.jsonObject ?: throw ModelUnavailable()
            if (body["model"] != JsonPrimitive(option.tag) || body["done"] != JsonPrimitive(true) || body["done_reason"] != JsonPrimitive("stop") ||
                message["role"] != JsonPrimitive("assistant") ||
                message["thinking"]?.let { it !is JsonPrimitive || !it.isString || it.content.isNotEmpty() } == true) throw ModelUnavailable()
            message["content"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: throw ModelUnavailable()
        }
        checkArtifact()
        output
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { throw ModelUnavailable() }

    fun close() { client.close() }

    companion object {
        internal fun configurationFromFile(path: Path): ReviewLocalConfiguration {
            val body = Files.newInputStream(path).use { it.readNBytes(16_385) }
            require(body.size in 1..16_384)
            return Json.decodeFromJsonElement<ReviewLocalConfiguration>(StrictModelJson.parse(body.decodeToString(throwOnInvalidSequence = true)))
        }

        internal fun serviceFromEnvironment(operation: Mutex): ReviewInterpretationService {
            val file = System.getenv("GTRAINER_REVIEW_MODELS_FILE") ?: return ReviewInterpretationService(operation = operation)
            return try {
                val config = configurationFromFile(Path.of(file))
                val client = HttpClient(CIO) {
                    followRedirects = false; expectSuccess = false
                    install(HttpTimeout) {
                        requestTimeoutMillis = config.policy.callTimeoutMillis
                        socketTimeoutMillis = config.policy.callTimeoutMillis
                        connectTimeoutMillis = minOf(5000, config.policy.callTimeoutMillis)
                    }
                }
                val providers = config.models.map { OllamaReviewFocusModel(client, config.endpoint, it) }
                // No production resource/live-health guard is qualified or wired by this change.
                // Configuration/selection alone cannot bypass that missing guard or start live inference.
                ReviewInterpretationService(providers, config.policy, operation = operation,
                    closeModels = { providers.forEach { it.close() }; client.close() })
            } catch (_: Exception) { ReviewInterpretationService(operation = operation, configurationError = true) }
        }
    }
}

package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import java.math.BigDecimal

/** Capability identifying one supervisor-owned runtime incarnation. It is not itself authorization. */
internal data class OwnedOllamaRuntimeToken(
    val epoch: Long, val runtimeGeneration: Long, val containerIdentity: String,
    val desiredBinding: DevelopmentReviewModelBinding,
)

/** Supervisor must compare durable ownership and exact running-container identity on every check. */
internal fun interface OllamaRuntimeOwnershipCheck {
    suspend fun owns(token: OwnedOllamaRuntimeToken): Boolean
}

class OllamaConnectedReviewProvider internal constructor(
    binding: DevelopmentReviewModelBinding,
    private val token: OwnedOllamaRuntimeToken,
    private val ownership: OllamaRuntimeOwnershipCheck = OllamaRuntimeOwnershipCheck { false },
    client: HttpClient,
    private val attemptTimeoutMillis: Long = DEFAULT_ATTEMPT_TIMEOUT_MILLIS,
) : ConnectedReviewProvider, AutoCloseable {
    private val binding = binding.copy()
    init {
        require(this.binding.bindAddress == "127.0.0.1" && token.desiredBinding == this.binding)
        require(token.epoch > 0 && token.runtimeGeneration > 0 && token.containerIdentity.isNotBlank())
        require(attemptTimeoutMillis in 1..MAX_ATTEMPT_TIMEOUT_MILLIS)
    }

    private val originalClient = client
    private val client = client.config {
        followRedirects = false
        expectSuccess = false
        install(HttpRequestRetry) { maxRetries = 0 }
        install(HttpTimeout) {
            requestTimeoutMillis = attemptTimeoutMillis
            socketTimeoutMillis = attemptTimeoutMillis
            connectTimeoutMillis = minOf(5_000, attemptTimeoutMillis)
        }
    }

    override val providerId get() = binding.providerId
    override val modelId get() = binding.modelId
    override val hosted = false
    override val enforcedMaximumCostUsd: BigDecimal? = null

    override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                                  costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
        try {
            require(responseLimitBytes in 1..MAX_DRAFT_BYTES && costLimitUsd == null)
            require(correctionReason == null || correctionReason == INVALID_OUTPUT)
            require(InterpretationContractV1.encodePrompt(prompt).toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_BYTES)
            ensureOwned()
            verifyVersionAndTag()
            ensureOwned()
            val body = chat(prompt, correctionReason, responseLimitBytes)
            ensureOwned()
            verifyVersionAndTag()
            ensureOwned()
            return ConnectedReviewProviderReply(body, null)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: OllamaConnectedReviewFailure) { throw failure }
          catch (_: Exception) { throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME) }
    }

    private suspend fun ensureOwned() {
        if (!ownership.owns(token)) throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.OWNERSHIP_REFUSED)
    }

    private suspend fun read(path: String, maximumBytes: Int): JsonObject {
        ensureOwned()
        return client.prepareGet("$ENDPOINT$path") {
            header(HttpHeaders.Accept, ContentType.Application.Json)
        }.execute { response ->
            ensureOwned()
            if (response.status.value !in 200..299) throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME)
            parseBounded(response.bodyAsChannel().readBuffer(maximumBytes.toLong() + 1).readByteArray(), maximumBytes)
        }
    }

    private suspend fun verifyVersionAndTag() {
        if (read("/api/version", VERSION_MAX_BYTES)["version"] != JsonPrimitive(binding.ollamaVersion))
            throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.BINDING_MISMATCH)
        val models = read("/api/tags", TAGS_MAX_BYTES)["models"] as? JsonArray
            ?: throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.BINDING_MISMATCH)
        val matching = models.filterIsInstance<JsonObject>().filter { it["name"] == JsonPrimitive(binding.tag) }
        if (matching.size != 1 || matching.single()["digest"] != JsonPrimitive(binding.manifestDigest))
            throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.BINDING_MISMATCH)
    }

    private suspend fun chat(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int): String {
        val payload = buildJsonObject {
            put("model", binding.tag); put("stream", false); put("think", false); put("keep_alive", "0")
            put("format", "json")
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", prompt.systemInstructions) })
                add(buildJsonObject { put("role", "user"); put("content", prompt.userPacketJson) })
                if (correctionReason != null) add(buildJsonObject {
                    put("role", "user"); put("content", "The previous response was rejected ($INVALID_OUTPUT). Return a new complete JSON draft for the same packet.")
                })
            }
            putJsonObject("options") {
                put("num_thread", binding.threads); put("num_ctx", binding.contextWindow)
                put("num_predict", binding.tokenLimit); put("seed", binding.seed); put("temperature", binding.temperature)
            }
        }
        val serialized = payload.toString()
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES)
        return client.preparePost("$ENDPOINT/api/chat") {
            contentType(ContentType.Application.Json); setBody(serialized)
        }.execute { response ->
            if (response.status.value !in 200..299) throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME)
            val envelope = parseBounded(response.bodyAsChannel().readBuffer(CHAT_MAX_BYTES.toLong() + 1).readByteArray(), CHAT_MAX_BYTES)
            val doneReason = (envelope["done_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (envelope["done"] != JsonPrimitive(true) || envelope["model"] != JsonPrimitive(binding.tag) ||
                doneReason != "stop")
                throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME)
            val message = envelope["message"] as? JsonObject ?: throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME)
            if (message["role"] != JsonPrimitive("assistant") || message["thinking"]?.let {
                    it !is JsonPrimitive || !it.isString || it.content.isNotEmpty()
                } == true) throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME)
            val content = message["content"] as? JsonPrimitive
            if (content == null || !content.isString) throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.PROTOCOL_OR_RUNTIME)
            val text = content.content
            if (text.toByteArray(Charsets.UTF_8).size > MAX_DRAFT_BYTES || text.toByteArray(Charsets.UTF_8).size > responseLimitBytes)
                throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.RESPONSE_TOO_LARGE)
            text
        }
    }

    private fun parseBounded(bytes: ByteArray, maximumBytes: Int): JsonObject {
        if (bytes.size > maximumBytes) throw OllamaConnectedReviewFailure(OllamaConnectedReviewFailureReason.RESPONSE_TOO_LARGE)
        return StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    companion object {
        const val ENDPOINT = "http://127.0.0.1:11434"
        const val VERSION_MAX_BYTES = 4 * 1024
        const val TAGS_MAX_BYTES = 64 * 1024
        const val CHAT_MAX_BYTES = 64 * 1024
        const val MAX_DRAFT_BYTES = 12 * 1024
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val DEFAULT_ATTEMPT_TIMEOUT_MILLIS = 300_000L
        const val MAX_ATTEMPT_TIMEOUT_MILLIS = 900_000L
        const val INVALID_OUTPUT = "invalid_output"

        /** Private client factory; each owned run receives finite limits and no retry/redirect plugin. */
        internal fun ownedClient(attemptTimeoutMillis: Long): HttpClient {
            require(attemptTimeoutMillis in 1..MAX_ATTEMPT_TIMEOUT_MILLIS)
            return HttpClient(CIO) {
                followRedirects = false; expectSuccess = false
                install(HttpRequestRetry) { maxRetries = 0 }
                engine { endpoint.apply { connectAttempts = 1; connectTimeout = minOf(5_000, attemptTimeoutMillis) } }
                install(HttpTimeout) {
                    requestTimeoutMillis = attemptTimeoutMillis; socketTimeoutMillis = attemptTimeoutMillis
                    connectTimeoutMillis = minOf(5_000, attemptTimeoutMillis)
                }
            }
        }
    }

    override fun close() {
        client.close()
        originalClient.close()
    }
}

internal enum class OllamaConnectedReviewFailureReason { OWNERSHIP_REFUSED, BINDING_MISMATCH, RESPONSE_TOO_LARGE, PROTOCOL_OR_RUNTIME }
internal class OllamaConnectedReviewFailure(val reason: OllamaConnectedReviewFailureReason) :
    ConnectedReviewProviderFailure(ConnectedReviewProviderFailureCode.valueOf(reason.name)) {
    override fun toString() = "OllamaConnectedReviewFailure(${reason.name})"
}

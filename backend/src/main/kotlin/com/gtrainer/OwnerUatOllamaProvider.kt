package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

/** One claim, one attempted generation, exact prepared bytes. No constructor or normal status GET sends. */
internal class OwnerUatOllamaProvider(
    private val binding: OwnerUatOllamaBinding,
    private val token: OwnerUatOllamaToken,
    private val control: OwnerUatOllamaControl,
    client: HttpClient,
    timeoutMillis: Long = 900_000,
) : ConnectedReviewProvider, AutoCloseable {
    init {
        require(token.claim.resource == binding.resource && token.runtimeSha256 == binding.runtimeSha256)
        require(token.claim.profile == ScopedDescriptiveContractV1.PROFILE)
        require(token.claim.supervisionRequired)
        require(timeoutMillis in 1..900_000)
    }
    private val originalClient = client
    private val client = client.config {
        followRedirects = false; expectSuccess = false
        install(HttpRequestRetry) { maxRetries = 0 }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis; socketTimeoutMillis = timeoutMillis
            connectTimeoutMillis = minOf(5_000, timeoutMillis)
        }
    }
    private val attempted = AtomicBoolean()
    private val closed = AtomicBoolean()
    override val providerId get() = binding.resource.selection.providerId
    override val modelId get() = binding.resource.selection.modelId
    override val hosted = false
    override val enforcedMaximumCostUsd: BigDecimal? = null

    override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
        costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
        if (!attempted.compareAndSet(false, true) || closed.get()) fail(ConnectedReviewProviderFailureCode.OWNERSHIP_REFUSED)
        try {
            require(correctionReason == null && costLimitUsd == null && responseLimitBytes in 1..12 * 1024)
            val request = OwnerUatOllamaRequest.prepare(binding, prompt)
            if (request.packetDigest != token.claim.packetDigest) fail(ConnectedReviewProviderFailureCode.BINDING_MISMATCH)
            ensureOwned()
            val proof = control.contextProof(token, request)
            if (proof?.matches(token, request) != true) fail(ConnectedReviewProviderFailureCode.CONTEXT_REFUSED)
            ensureOwned()
            verifyMetadata()
            if (!control.beginGeneration(token)) fail(ConnectedReviewProviderFailureCode.OWNERSHIP_REFUSED)
            // Metadata is not authorization. The authoritative claim/endpoint fence is checked last.
            ensureOwned()
            val envelope = exchange("/api/chat", request.body, 64 * 1024)
            val draft = content(envelope, proof, responseLimitBytes)
            ensureOwned()
            verifyMetadata()
            ensureOwned()
            return ConnectedReviewProviderReply(draft, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ConnectedReviewProviderFailure) {
            throw failure
        } catch (_: Exception) {
            fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
        } finally {
            close()
        }
    }

    private suspend fun ensureOwned() {
        if (closed.get() || control.observeOwned(token)?.matches(token, binding) != true)
            fail(ConnectedReviewProviderFailureCode.OWNERSHIP_REFUSED)
    }

    private suspend fun exchange(path: String, body: String?, limit: Int): JsonObject {
        ensureOwned()
        val statement = if (body == null) client.prepareGet("$ENDPOINT$path") {
            header(HttpHeaders.Accept, ContentType.Application.Json)
        } else client.preparePost("$ENDPOINT$path") {
            contentType(ContentType.Application.Json); setBody(body)
        }
        return statement.execute { response ->
            ensureOwned()
            if (response.status.value !in 200..299) fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
            val bytes = response.bodyAsChannel().readBuffer(limit.toLong() + 1).readByteArray()
            if (bytes.size > limit) fail(ConnectedReviewProviderFailureCode.RESPONSE_TOO_LARGE)
            if (path == "/api/chat" && !control.beginValidation(token)) fail(ConnectedReviewProviderFailureCode.OWNERSHIP_REFUSED)
            StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        }
    }

    private suspend fun verifyMetadata() {
        if (exchange("/api/version", null, 4096)["version"] != JsonPrimitive(OwnerUatOllamaBinding.OLLAMA_VERSION))
            fail(ConnectedReviewProviderFailureCode.BINDING_MISMATCH)
        val models = exchange("/api/tags", null, 32 * 1024)["models"] as? JsonArray
            ?: fail(ConnectedReviewProviderFailureCode.BINDING_MISMATCH)
        val matching = models.filterIsInstance<JsonObject>().filter { it["name"] == JsonPrimitive(OwnerUatOllamaBinding.TAG) }
        if (matching.size != 1 || matching.single()["digest"] != JsonPrimitive(OwnerUatOllamaBinding.MODEL_MANIFEST))
            fail(ConnectedReviewProviderFailureCode.BINDING_MISMATCH)
    }

    private fun content(envelope: JsonObject, proof: OwnerUatOllamaContextProof, maximumBytes: Int): String {
        if (envelope["done"] != JsonPrimitive(true) || envelope["model"] != JsonPrimitive(OwnerUatOllamaBinding.TAG) ||
            envelope["done_reason"] != JsonPrimitive("stop") || envelope["prompt_eval_count"] != JsonPrimitive(proof.inputTokens))
            fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
        val evaluated = envelope["eval_count"] as? JsonPrimitive
        if (evaluated == null || evaluated.isString || evaluated.intOrNull == null || evaluated.intOrNull !in 0..OwnerUatOllamaBinding.OUTPUT_TOKENS)
            fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
        val message = envelope["message"] as? JsonObject ?: fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
        if (message["role"] != JsonPrimitive("assistant") || message["tool_calls"] != null ||
            message["thinking"]?.let { it != JsonPrimitive("") } == true) fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
        val draft = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: fail(ConnectedReviewProviderFailureCode.PROTOCOL_OR_RUNTIME)
        if (draft.toByteArray(Charsets.UTF_8).size > maximumBytes) fail(ConnectedReviewProviderFailureCode.RESPONSE_TOO_LARGE)
        return draft
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) { client.close(); originalClient.close() }
    }

    companion object {
        // Reserved loopback forwarding for the separately owned Mac VM runtime; never browser-selected.
        const val ENDPOINT = "http://127.0.0.1:11435"
        private fun fail(code: ConnectedReviewProviderFailureCode): Nothing = throw ConnectedReviewProviderFailure(code)
    }
}

/** Existing admission creates this only after installed policy acceptance and durable START_INTENT. */
internal class OwnerUatOllamaProviderFactory(
    private val binding: OwnerUatOllamaBinding,
    private val installed: () -> OwnerUatInstallationBinding?,
    private val control: OwnerUatOllamaControl,
    private val client: () -> HttpClient = { OllamaConnectedReviewProvider.ownedClient(900_000) },
) : PersonalProviderFactory {
    override val identity get() = binding.resource
    override fun create(claim: PersonalReviewClaim): ConnectedReviewProvider {
        val current = requireNotNull(installed())
        // Larger bindings can be prepared/tested, but this policy version has not accepted larger limits.
        require(binding.contextTokens == OwnerUatJobLimits().contextTokens)
        require(current.resource == binding.resource && current.runtimeSha256 == binding.runtimeSha256 &&
            current.modelSha256 == OwnerUatOllamaBinding.MODEL_MANIFEST && current.resourcePolicySha256 == binding.resourcePolicySha256)
        val profile = current.profiles.single { it.profile == ScopedDescriptiveContractV1.PROFILE }
        require(profile.schemaSha256 == OwnerUatDescriptiveSchemaV1.sha256 && profile.promptSha256s == ScopedDescriptiveContractV1.promptVariantSha256s())
        require(claim.resource == identity && claim.profile == ScopedDescriptiveContractV1.PROFILE && claim.supervisionRequired)
        val token = requireNotNull(control.acquire(claim, binding))
        require(token.claim == claim && token.runtimeSha256 == binding.runtimeSha256)
        return OwnerUatOllamaProvider(binding, token, control, client())
    }
}

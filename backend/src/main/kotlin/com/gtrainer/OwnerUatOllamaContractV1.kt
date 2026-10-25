package com.gtrainer

import kotlinx.serialization.json.*

/** Main-source native response schema; semantic/source/UTF-8 limits remain in the original validator. */
internal object OwnerUatDescriptiveSchemaV1 {
    private fun text(vararg values: String) = buildJsonObject {
        put("type", "string")
        if (values.isNotEmpty()) put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    private fun record(vararg fields: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(linkedMapOf(*fields)))
        put("required", JsonArray(fields.map { JsonPrimitive(it.first) }))
    }
    private fun list(item: JsonObject, minimum: Int, maximum: Int) = buildJsonObject {
        put("type", "array"); put("items", item); put("minItems", minimum); put("maxItems", maximum)
    }
    private val reference = record(
        "kind" to text("session", "aggregate", "wellness", "context", "commitment", "plan_session", "comparison", "baseline", "adaptation"),
        "id" to text(),
        "revision" to buildJsonObject {
            put("anyOf", JsonArray(listOf(buildJsonObject { put("type", "integer"); put("minimum", 1) },
                buildJsonObject { put("type", "null") })))
        },
    )
    private val item = record("kind" to text("observation", "comparison", "context"), "text" to text(),
        "sources" to list(reference, 1, 12), "uncertainty" to text("low", "moderate", "high", "unknown"))
    val schema: JsonObject = record("profile" to text(ScopedDescriptiveContractV1.PROFILE),
        "mode" to text("activities", "period"), "intent" to text("review", "compare"),
        "interpretations" to list(item, 0, 8), "questions" to list(item, 0, 8))
    val sha256: String get() = AnalysisClaims.hash(schema.toString())
}

/** Compiled candidate identity. This configuration alone is neither installed acceptance nor ownership. */
internal data class OwnerUatOllamaBinding(
    val resource: PersonalResourceIdentity,
    val resourcePolicySha256: String,
    val contextTokens: Int = 4096,
) {
    init {
        require(resource.selection == ConnectedReviewProviderSelection("ollama", TAG, "local"))
        require(resourcePolicySha256.matches(Regex("[a-f0-9]{64}")))
        require(contextTokens in 4096..40960)
    }
    val runtimeSha256: String get() = AnalysisClaims.hash(buildJsonObject {
        put("version", "owner-uat-ollama-runtime-v1"); put("image", IMAGE); put("ollamaVersion", OLLAMA_VERSION)
        put("modelManifestSha256", MODEL_MANIFEST); put("modelBlobSha256", MODEL_BLOB); put("templateSha256", TEMPLATE)
        put("tokenizerLibrarySha256", TOKENIZER_LIBRARY); put("wireVersion", OwnerUatDescriptiveWireV1.VERSION)
        put("decodingInstructionsSha256", OwnerUatDescriptiveWireV1.decodingInstructionsSha256)
        put("schemaSha256", OwnerUatDescriptiveSchemaV1.sha256); put("resourcePolicySha256", resourcePolicySha256)
        put("contextTokens", contextTokens); put("outputTokens", OUTPUT_TOKENS); put("marginTokens", MARGIN_TOKENS)
        put("threads", 3); put("seed", 42); put("temperature", 0); put("gpuLayers", 0); put("useMmap", false)
    }.toString())

    companion object {
        const val TAG = "qwen3:8b"
        const val OLLAMA_VERSION = "0.35.0"
        const val IMAGE = "ollama/ollama@sha256:9c1dc45ea758396139ec0adfa52947714c61f8d1e4537a6e2daef8138e7a64a9"
        const val MODEL_MANIFEST = "500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41"
        const val MODEL_BLOB = "a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f"
        const val TEMPLATE = "ae370d884f108d16e7cc8fd5259ebc5773a0afa6e078b11f4ed7e39a27e0dfc4"
        const val TOKENIZER_LIBRARY = "8dc7056ee6548c619bded63829696b439478c357df2c4f6c41594e4f86d56324"
        const val OUTPUT_TOKENS = 1536
        const val MARGIN_TOKENS = 256
    }
}

/** Exact bytes presented to owned control for request-specific vocabulary accounting. */
internal class OwnerUatOllamaRequest private constructor(
    val binding: OwnerUatOllamaBinding,
    val compact: OwnerUatDescriptiveWireV1.Prepared,
    val body: String,
    val renderedInput: String,
    val packetDigest: String,
) {
    val requestSha256 = AnalysisClaims.hash(body)
    val renderedSha256 = AnalysisClaims.hash(renderedInput)
    override fun toString() = "OwnerUatOllamaRequest([REDACTED])"

    companion object {
        fun prepare(binding: OwnerUatOllamaBinding, canonical: InterpretationPromptV1): OwnerUatOllamaRequest {
            val compact = OwnerUatDescriptiveWireV1.prepare(canonical)
            val packet = OwnerUatDescriptiveWireV1.inspect(compact)
            val prompt = compact.modelPrompt
            val body = buildJsonObject {
                put("model", OwnerUatOllamaBinding.TAG); put("stream", false); put("think", false); put("keep_alive", "0s")
                put("format", OwnerUatDescriptiveSchemaV1.schema)
                put("messages", JsonArray(listOf(
                    buildJsonObject { put("role", "system"); put("content", prompt.systemInstructions) },
                    buildJsonObject { put("role", "user"); put("content", prompt.userPacketJson) })))
                putJsonObject("options") {
                    put("num_ctx", binding.contextTokens); put("num_predict", OwnerUatOllamaBinding.OUTPUT_TOKENS)
                    put("num_thread", 3); put("num_gpu", 0); put("temperature", 0); put("seed", 42); put("use_mmap", false)
                }
            }.toString()
            require(body.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            // This is the audited pinned non-thinking branch, not an assumption about arbitrary templates.
            val rendered = "<|im_start|>system\n\n${prompt.systemInstructions}<|im_end|>\n<|im_start|>user\n" +
                "${prompt.userPacketJson} /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
            return OwnerUatOllamaRequest(binding, compact, body, rendered, ConnectedReviewInvocation.descriptive(packet).consentDigest)
        }
    }
}

/** Issued only by the owned supervisor, never decoded from an HTTP request or a model reply. */
internal data class OwnerUatOllamaToken(val claim: PersonalReviewClaim, val containerId: String, val runtimeSha256: String) {
    init { require(containerId.matches(Regex("[a-f0-9]{64}"))); require(runtimeSha256.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString() = "OwnerUatOllamaToken([REDACTED])"
}

internal data class OwnerUatOllamaContextProof(
    val token: OwnerUatOllamaToken,
    val requestSha256: String,
    val renderedSha256: String,
    val tokenizerLibrarySha256: String,
    val modelBlobSha256: String,
    val inputTokens: Int,
) {
    fun matches(expected: OwnerUatOllamaToken, request: OwnerUatOllamaRequest): Boolean =
        token == expected && requestSha256 == request.requestSha256 && renderedSha256 == request.renderedSha256 &&
            tokenizerLibrarySha256 == OwnerUatOllamaBinding.TOKENIZER_LIBRARY && modelBlobSha256 == OwnerUatOllamaBinding.MODEL_BLOB &&
            inputTokens > 0 && inputTokens.toLong() + OwnerUatOllamaBinding.OUTPUT_TOKENS + OwnerUatOllamaBinding.MARGIN_TOKENS <= request.binding.contextTokens
    override fun toString() = "OwnerUatOllamaContextProof([REDACTED])"
}

/** Observed by trusted control after journal/endpoint/physical ownership and cached-byte verification. */
internal data class OwnerUatOllamaObservation(
    val token: OwnerUatOllamaToken,
    val image: String,
    val modelManifestSha256: String,
    val modelBlobSha256: String,
    val templateSha256: String,
    val tokenizerLibrarySha256: String,
    val resourcePolicySha256: String,
) {
    fun matches(expected: OwnerUatOllamaToken, binding: OwnerUatOllamaBinding): Boolean =
        token == expected && token.runtimeSha256 == binding.runtimeSha256 && image == OwnerUatOllamaBinding.IMAGE &&
            modelManifestSha256 == OwnerUatOllamaBinding.MODEL_MANIFEST && modelBlobSha256 == OwnerUatOllamaBinding.MODEL_BLOB &&
            templateSha256 == OwnerUatOllamaBinding.TEMPLATE && tokenizerLibrarySha256 == OwnerUatOllamaBinding.TOKENIZER_LIBRARY &&
            resourcePolicySha256 == binding.resourcePolicySha256
    override fun toString() = "OwnerUatOllamaObservation([REDACTED])"
}

/** No HTTP metadata substitutes for these authoritative checks. Real control is installed separately.
 * acquire requires durable START_INTENT and fences late starts. observeOwned checks the journal, exact running
 * container/image/assets (including all manifest layers), endpoint ownership, resource policy,
 * boot/deadline and revocation on every call; unverified/expired/revoked ownership returns null.
 * contextProof performs runtime-matched accounting of these exact bytes under the same owned deadline. */
internal interface OwnerUatOllamaControl : PersonalRuntimeControl {
    fun acquire(claim: PersonalReviewClaim, binding: OwnerUatOllamaBinding): OwnerUatOllamaToken?
    suspend fun observeOwned(token: OwnerUatOllamaToken): OwnerUatOllamaObservation?
    suspend fun contextProof(token: OwnerUatOllamaToken, request: OwnerUatOllamaRequest): OwnerUatOllamaContextProof?
    suspend fun beginGeneration(token: OwnerUatOllamaToken): Boolean
    suspend fun beginValidation(token: OwnerUatOllamaToken): Boolean
}

package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class OwnerUatOllamaProviderTest {
    private val selection = ConnectedReviewProviderSelection("ollama", OwnerUatOllamaBinding.TAG, "local")
    private val resource = PersonalResourceIdentity("synthetic-ui-domain", "synthetic-ui-runtime", selection)
    private val binding = OwnerUatOllamaBinding(resource, AnalysisClaims.hash("invented-resource-policy"))
    private fun claim() = PersonalReviewClaim(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1,
        UUID.randomUUID().toString(), 1, ScopedDescriptiveContractV1.PROFILE, resource, "b".repeat(64), "c".repeat(64),
        "2020-06-10T12:00:00Z", "2020-06-10T12:20:00Z",supervisionRequired=true)
    private fun token(claim: PersonalReviewClaim = claim()) = OwnerUatOllamaToken(claim, "a".repeat(64), binding.runtimeSha256)
    private fun tokenFor(prompt: InterpretationPromptV1): OwnerUatOllamaToken {
        val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(prompt.userPacketJson)
        return token(claim().copy(packetDigest = ConnectedReviewInvocation.descriptive(packet).consentDigest))
    }
    private fun observation(token: OwnerUatOllamaToken) = OwnerUatOllamaObservation(token,
        OwnerUatOllamaBinding.IMAGE, OwnerUatOllamaBinding.MODEL_MANIFEST, OwnerUatOllamaBinding.MODEL_BLOB,
        OwnerUatOllamaBinding.TEMPLATE, OwnerUatOllamaBinding.TOKENIZER_LIBRARY, binding.resourcePolicySha256)

    private inner class Control : OwnerUatOllamaControl {
        var acquireCalls = 0
        var proofCalls = 0
        var owned = true
        var onAcquire: ((PersonalReviewClaim)->Unit)? = null
        var onPhase: (suspend (OwnerUatOllamaToken,String)->Boolean)? = null
        var tokens = 2000
        var changeObservation: (OwnerUatOllamaObservation) -> OwnerUatOllamaObservation = { it }
        var changeProof: (OwnerUatOllamaContextProof) -> OwnerUatOllamaContextProof? = { it }
        override suspend fun observe(expected: PersonalResourceIdentity) = null
        override suspend fun stop(claim: PersonalReviewClaim) = null
        override fun acquire(claim: PersonalReviewClaim, binding: OwnerUatOllamaBinding): OwnerUatOllamaToken {
            acquireCalls++;onAcquire?.invoke(claim); return token(claim)
        }
        override suspend fun observeOwned(token: OwnerUatOllamaToken) = if (owned) changeObservation(observation(token)) else null
        override suspend fun contextProof(token: OwnerUatOllamaToken, request: OwnerUatOllamaRequest): OwnerUatOllamaContextProof? {
            proofCalls++
            return changeProof(OwnerUatOllamaContextProof(token, request.requestSha256, request.renderedSha256,
                OwnerUatOllamaBinding.TOKENIZER_LIBRARY, OwnerUatOllamaBinding.MODEL_BLOB, tokens))
        }
        override suspend fun beginGeneration(token: OwnerUatOllamaToken) = onPhase?.invoke(token,"GENERATING") ?: owned
        override suspend fun beginValidation(token: OwnerUatOllamaToken) = onPhase?.invoke(token,"VALIDATING") ?: owned
    }

    private suspend fun fixture(block: suspend (DescriptiveUiFixture) -> Unit) {
        val dir = Files.createTempDirectory("synthetic-owner-provider-")
        val f = DescriptiveUiFixture(dir)
        try { block(f) } finally { f.close(); Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun installation() = OwnerUatInstallationBinding(AnalysisClaims.hash("invented-app"), resource,
        binding.runtimeSha256, OwnerUatOllamaBinding.MODEL_MANIFEST, binding.resourcePolicySha256,
        listOf(OwnerUatProfileBinding(ScopedDescriptiveContractV1.PROFILE, ScopedDescriptiveContractV1.promptVariantSha256s(), OwnerUatDescriptiveSchemaV1.sha256)))
    private val tags get() = buildJsonObject { putJsonArray("models") {
        add(buildJsonObject { put("name", OwnerUatOllamaBinding.TAG); put("digest", OwnerUatOllamaBinding.MODEL_MANIFEST) })
    } }.toString()
    private fun envelope(content: String, tokens: Int = 2000) = buildJsonObject {
        put("model", OwnerUatOllamaBinding.TAG); put("done", true); put("done_reason", "stop")
        put("prompt_eval_count", tokens); put("eval_count", 100)
        putJsonObject("message") { put("role", "assistant"); put("content", content) }
    }.toString()
    private fun response(path: String, content: String = "{}", tokens: Int = 2000) = when(path) {
        "/api/version" -> """{"version":"0.35.0"}"""
        "/api/tags" -> tags
        "/api/chat" -> envelope(content, tokens)
        else -> error("Unexpected HTTP path")
    }

    @Test fun `prepared request binds native schema options full effective rendering and source bytes`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        val request = OwnerUatOllamaRequest.prepare(binding, canonical)
        val body = StrictModelJson.parse(request.body).jsonObject
        assertEquals(OwnerUatDescriptiveSchemaV1.schema, body["format"])
        assertEquals(JsonPrimitive(false), body["think"]); assertEquals(JsonPrimitive(false), body["stream"])
        assertEquals(JsonPrimitive("0s"), body["keep_alive"])
        assertEquals(2, body.getValue("messages").jsonArray.size)
        val options = body.getValue("options").jsonObject
        assertEquals(JsonPrimitive(4096), options["num_ctx"]); assertEquals(JsonPrimitive(1536), options["num_predict"])
        assertEquals(JsonPrimitive(3), options["num_thread"]); assertEquals(JsonPrimitive(0), options["num_gpu"])
        assertEquals(JsonPrimitive(42), options["seed"]); assertEquals(JsonPrimitive(false), options["use_mmap"])
        assertEquals(canonical, request.compact.canonicalPrompt)
        assertTrue(request.renderedInput.contains(request.compact.modelPrompt.userPacketJson + " /no_think<|im_end|>"))
        assertEquals(AnalysisClaims.hash(request.body), request.requestSha256)
        assertNotEquals(request.requestSha256, OwnerUatOllamaRequest.prepare(binding.copy(contextTokens = 8192), canonical).requestSha256)
        assertNotEquals(binding.runtimeSha256, binding.copy(contextTokens = 8192).runtimeSha256)
        assertEquals("OwnerUatOllamaRequest([REDACTED])", request.toString())
    } }

    @Test fun `native schema admits original encoded drafts while strict semantic validation remains authoritative`() = runBlocking { fixture { f ->
        fun accepts(schema: JsonObject, value: JsonElement): Boolean {
            val choices = schema["anyOf"] as? JsonArray
            if (choices != null) return choices.any { accepts(it.jsonObject, value) }
            val enumeration = schema["enum"] as? JsonArray
            if (enumeration != null && value !in enumeration) return false
            return when (schema.getValue("type").jsonPrimitive.content) {
                "null" -> value == JsonNull
                "string" -> value is JsonPrimitive && value.isString
                "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null &&
                    value.int >= schema.getValue("minimum").jsonPrimitive.int
                "object" -> value is JsonObject && value.keys == schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet() &&
                    value.all { (key, item) -> accepts(schema.getValue("properties").jsonObject.getValue(key).jsonObject, item) }
                "array" -> value is JsonArray && value.size in schema.getValue("minItems").jsonPrimitive.int..schema.getValue("maxItems").jsonPrimitive.int &&
                    value.all { accepts(schema.getValue("items").jsonObject, it) }
                else -> error("Unknown schema keyword")
            }
        }
        for (mode in listOf("activities", "period")) for (intent in listOf("review", "compare")) {
            val original = f.prepare(mode, intent).binding.exactPrompt
            val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(original.userPacketJson)
            val source = packet.references.first { it.kind in setOf("session", "aggregate") }
            val draft = ScopedDescriptiveDraftV1(ScopedDescriptiveContractV1.PROFILE, mode, intent,
                listOf(ScopedDescriptiveItem("observation", "Recorded source is available.", listOf(source), "high")), emptyList())
            val raw = ScopedDescriptiveContractV1.encodeDraft(draft)
            assertTrue(accepts(OwnerUatDescriptiveSchemaV1.schema, StrictModelJson.parse(raw)))
            assertEquals(draft, ScopedDescriptiveContractV1.decodeDraft(raw, packet))
            val inventedItem = draft.interpretations.single().copy(sources = listOf(source.copy(id = "invented-source")))
            val invented = ScopedDescriptiveContractV1.encodeDraft(draft.copy(interpretations = listOf(inventedItem)))
            assertTrue(accepts(OwnerUatDescriptiveSchemaV1.schema, StrictModelJson.parse(invented)))
            assertFailsWith<IllegalArgumentException> { ScopedDescriptiveContractV1.decodeDraft(invented, packet) }
        }
    } }

    @Test fun `missing stale changed or over budget proof sends no HTTP and boundary is inclusive`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        val changes: List<(OwnerUatOllamaContextProof) -> OwnerUatOllamaContextProof?> = listOf(
            { null }, { it.copy(requestSha256 = "d".repeat(64)) }, { it.copy(renderedSha256 = "d".repeat(64)) },
            { it.copy(tokenizerLibrarySha256 = "d".repeat(64)) }, { it.copy(modelBlobSha256 = "d".repeat(64)) },
            { it.copy(token = it.token.copy(claim = claim())) }, { it.copy(inputTokens = 2305) },
            { it.copy(inputTokens = 0) }, { it.copy(inputTokens = Int.MAX_VALUE) },
        )
        for (change in changes) {
            val control = Control().also { it.changeProof = change }
            var http = 0
            OwnerUatOllamaProvider(binding, tokenFor(canonical), control, HttpClient(MockEngine { http++; respond("") })).use { provider ->
                val failure = assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) }
                assertEquals(ConnectedReviewProviderFailureCode.CONTEXT_REFUSED, failure.code)
                assertEquals(0, http)
            }
        }
        val control = Control().also { it.tokens = 2304 }
        var chats = 0
        OwnerUatOllamaProvider(binding, tokenFor(canonical), control, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/chat") chats++
            respond(response(request.url.encodedPath, tokens = 2304))
        })).use { assertEquals("{}", it.generate(canonical, null, 12288, null).response) }
        assertEquals(1, chats)
    } }

    @Test fun `exact owned asset image template policy and incarnation drift prevent any HTTP`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        val changes: List<(OwnerUatOllamaObservation) -> OwnerUatOllamaObservation> = listOf(
            { it.copy(image = "other") }, { it.copy(modelManifestSha256 = "d".repeat(64)) },
            { it.copy(modelBlobSha256 = "d".repeat(64)) }, { it.copy(templateSha256 = "d".repeat(64)) },
            { it.copy(tokenizerLibrarySha256 = "d".repeat(64)) }, { it.copy(resourcePolicySha256 = "d".repeat(64)) },
            { it.copy(token = it.token.copy(containerId = "d".repeat(64))) },
        )
        for (change in changes) {
            val control = Control().also { it.changeObservation = change }
            var http = 0
            OwnerUatOllamaProvider(binding, tokenFor(canonical), control, HttpClient(MockEngine { http++; respond("") })).use { provider ->
                val failure = assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) }
                assertEquals(ConnectedReviewProviderFailureCode.OWNERSHIP_REFUSED, failure.code)
            }
            assertEquals(0, http); assertEquals(0, control.proofCalls)
        }
    } }

    @Test fun `normal guarded journey publishes actual contract through owned factory and fake HTTP`() = runBlocking {
        val dir = Files.createTempDirectory("synthetic-owner-http-journey-")
        val control = Control()
        var chats = 0
        lateinit var f: DescriptiveUiFixture
        val sample=OwnerUatClockSample(UUID.randomUUID().toString(),"synthetic-continuous-v1",1,1,0)
        val supervisorEpoch=UUID.randomUUID().toString()
        val supervision=OwnerUatSupervisorClient(OwnerUatContinuousClock { sample },OwnerUatSupervisorTransport { bytes ->
            val request=StrictProfileDraftJson.decode<OwnerUatSupervisorRequest>(bytes.decodeToString())
            check(request.op=="register")
            val job=OwnerUatSupervisorDatabase(dir.resolve("history.db")).use { db -> db.transaction { connection ->
                val journal=OwnerUatSupervisionStore(connection)
                check(journal.acknowledge(request.claim,supervisorEpoch,sample))
                requireNotNull(journal.get(request.claim.claimId))
            } }
            ScopedEvidencePreparation.json.encodeToString(OwnerUatSupervisorReply(true,request.claim.claimId,supervisorEpoch,job)).toByteArray()
        })
        control.onAcquire={ claim -> OwnerUatSupervisorDatabase(dir.resolve("history.db")).use { db -> db.transaction { connection ->
            check(PersonalReviewRuntimeStore(connection).get(claim.claimId)?.phase==PersonalRuntimePhase.START_INTENT)
            val journal=OwnerUatSupervisionStore(connection);val job=requireNotNull(journal.get(claim.claimId))
            check(journal.save(job,job.copy(launchConsumed=true,worker=OwnerUatProcessIdentity.capture())))
        } } }
        control.onPhase={ token,phase -> OwnerUatSupervisorDatabase(dir.resolve("history.db")).use { db -> db.transaction {
            OwnerUatSupervisionStore(it).transition(token.claim,phase,sample)
        } } }
        f = DescriptiveUiFixture(dir, providerSelection = selection, installationOverride = { installation() },supervision=supervision, providerOverride = { claim ->
            OwnerUatOllamaProviderFactory(binding, { f.installation }, control) {
                HttpClient(MockEngine { request ->
                    assertEquals("127.0.0.1", request.url.host); assertEquals(11435, request.url.port)
                    val content = if (request.url.encodedPath == "/api/chat") {
                        chats++
                        val body = StrictModelJson.parse((request.body as TextContent).text).jsonObject
                        assertEquals(OwnerUatDescriptiveSchemaV1.schema, body["format"])
                        val original = f.store.captureScopedExecution(claim.executionId).stored.binding.exactPrompt
                        assertEquals(OwnerUatOllamaRequest.prepare(binding, original).body, (request.body as TextContent).text)
                        val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(original.userPacketJson)
                        val ref = packet.references.first { it.kind in setOf("session", "aggregate") }
                        val selected = packet.evidence.request.selection
                        ScopedDescriptiveContractV1.encodeDraft(ScopedDescriptiveDraftV1(ScopedDescriptiveContractV1.PROFILE,
                            selected.mode, selected.intent, listOf(ScopedDescriptiveItem("observation", "Recorded source is available.", listOf(ref), "high")), emptyList()))
                    } else "{}"
                    respond(response(request.url.encodedPath, content))
                })
            }.create(claim)
        })
        try {
            for (mode in listOf("activities", "period")) for (intent in listOf("review", "compare")) {
                val result = f.run(f.prepare(mode, intent))
                assertEquals("PUBLISHED", result.state); assertNotNull(result.validatedOutputJson)
                assertFalse(result.admission.executionAllowed)
                assertEquals("STOP_CONFIRMED", f.jobs.status(result.binding.executionId).runtimePhase)
            }
            assertEquals(4, chats); assertEquals(4, control.acquireCalls); assertEquals(4, control.proofCalls)
            assertEquals(0, f.source.calls)
        } finally { f.close(); Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun `factory refuses absent or drifted installation and unaccepted context before acquire`() {
        val control = Control()
        val altered = listOf<OwnerUatInstallationBinding?>(null, installation().copy(runtimeSha256 = "d".repeat(64)),
            installation().copy(modelSha256 = "d".repeat(64)), installation().copy(profiles = listOf(
                OwnerUatProfileBinding(ScopedDescriptiveContractV1.PROFILE, ScopedDescriptiveContractV1.promptVariantSha256s(), "d".repeat(64)))))
        for (current in altered) assertFails {
            OwnerUatOllamaProviderFactory(binding, { current }, control) { error("client created for refused installation") }.create(claim())
        }
        assertFails { OwnerUatOllamaProviderFactory(binding.copy(contextTokens = 8192), { installation() }, control).create(claim()) }
        assertFails { OwnerUatOllamaProviderFactory(binding,{ installation() },control) { error("Client created without supervision") }
            .create(claim().copy(supervisionRequired=false)) }
        assertEquals(0, control.acquireCalls)
    }

    @Test fun `no correction retry redirect or unverified completion returns content`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        val invalid = listOf(
            envelope("private marker").replace("\"stop\"", "\"length\""),
            envelope("private marker", tokens = 1999),
            envelope("private marker").replace("\"eval_count\":100", "\"eval_count\":1537"),
            envelope("private marker").replace("\"role\":\"assistant\"", "\"role\":\"user\""),
            envelope("private marker").replace("\"content\":", "\"thinking\":\"private marker\",\"content\":"),
            envelope("private marker").replace("\"content\":", "\"tool_calls\":[],\"content\":"),
            envelope("private marker").replace("\"done\":true", "\"done\":false,\"done\":true"),
            envelope("x".repeat(12289)),
        )
        for (body in invalid) {
            var chats = 0
            val control = Control()
            val client = HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/api/chat") { chats++; respond(body) }
                else respond(response(request.url.encodedPath))
            }) { install(HttpRequestRetry) { maxRetries = 3; retryOnServerErrors(maxRetries = 3) } }
            OwnerUatOllamaProvider(binding, tokenFor(canonical), control, client).use { provider ->
                val failure = assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) }
                assertNull(failure.cause); assertFalse(failure.message.orEmpty().contains("private marker"))
                assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) }
            }
            assertEquals(1, chats)
        }
        for (status in listOf(HttpStatusCode.TemporaryRedirect, HttpStatusCode.InternalServerError)) {
            var chats = 0
            OwnerUatOllamaProvider(binding, tokenFor(canonical), Control(), HttpClient(MockEngine { request ->
                assertEquals("127.0.0.1", request.url.host)
                if (request.url.encodedPath == "/api/chat") { chats++; respond("private marker", status, headersOf(HttpHeaders.Location, "https://example.test")) }
                else respond(response(request.url.encodedPath))
            })).use { provider -> assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) } }
            assertEquals(1, chats)
        }
    } }

    @Test fun `revocation before send or after response and metadata drift cannot return a draft`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        for (kind in listOf("revoked-before", "revoked-after", "version-before", "version-after", "digest", "duplicate")) {
            val control = Control()
            var chats = 0
            OwnerUatOllamaProvider(binding, tokenFor(canonical), control, HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                var body = response(path)
                when {
                    path == "/api/version" && (kind == "version-before" || kind == "version-after" && chats > 0) -> body = """{"version":"changed"}"""
                    path == "/api/tags" && kind == "digest" -> body = tags.replace(OwnerUatOllamaBinding.MODEL_MANIFEST, "d".repeat(64))
                    path == "/api/tags" && kind == "duplicate" -> body = buildJsonObject { put("models", JsonArray(List(2) {
                        Json.parseToJsonElement(tags).jsonObject.getValue("models").jsonArray.single()
                    })) }.toString()
                    path == "/api/tags" && kind == "revoked-before" -> control.owned = false
                    path == "/api/chat" -> { chats++; if (kind == "revoked-after") control.owned = false }
                }
                respond(body)
            })).use { provider -> assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) } }
            assertEquals(if (kind in setOf("revoked-after", "version-after")) 1 else 0, chats)
        }
    } }

    @Test fun `malformed utf8 large bodies cancellation and illegal arguments fail without retry`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        for (kind in listOf("utf8", "large")) {
            var chats = 0
            OwnerUatOllamaProvider(binding, tokenFor(canonical), Control(), HttpClient(MockEngine { request ->
                if (request.url.encodedPath != "/api/chat") respond(response(request.url.encodedPath))
                else {
                    chats++
                    if (kind == "large") respond("x".repeat(65537))
                    else respond(ByteReadChannel(byteArrayOf(0xc3.toByte(), 0x28)), HttpStatusCode.OK)
                }
            })).use { provider -> assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, null, 12288, null) } }
            assertEquals(1, chats)
        }
        var calls = 0
        OwnerUatOllamaProvider(binding, tokenFor(canonical), Control(), HttpClient(MockEngine { calls++; respond("") })).use { provider ->
            assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(canonical, "invalid_output", 12288, null) }
        }
        assertEquals(0, calls)
        val started = CompletableDeferred<Unit>()
        var chats = 0
        OwnerUatOllamaProvider(binding, tokenFor(canonical), Control(), HttpClient(MockEngine { request ->
            if (request.url.encodedPath != "/api/chat") respond(response(request.url.encodedPath))
            else { chats++; started.complete(Unit); awaitCancellation() }
        })).use { provider ->
            val child = async { provider.generate(canonical, null, 12288, null) }
            withTimeout(5000) { started.await() }; child.cancel()
            assertFailsWith<CancellationException> { child.await() }
        }
        assertEquals(1, chats)
    } }

    @Test fun `canonical source prompt must match the original durable claim before proof or HTTP`() = runBlocking { fixture { f ->
        val original = f.prepare("activities", "review").binding.exactPrompt
        val changed = f.prepare("activities", "compare").binding.exactPrompt
        val control = Control()
        var http = 0
        OwnerUatOllamaProvider(binding, tokenFor(original), control, HttpClient(MockEngine { http++; respond("") })).use { provider ->
            val failure = assertFailsWith<ConnectedReviewProviderFailure> { provider.generate(changed, null, 12288, null) }
            assertEquals(ConnectedReviewProviderFailureCode.BINDING_MISMATCH, failure.code)
        }
        assertEquals(0, http); assertEquals(0, control.proofCalls)
    } }

    @Test fun `provider close failure cannot skip authoritative admission cleanup`() = runBlocking {
        val dir = Files.createTempDirectory("synthetic-owner-close-")
        var closes = 0
        val f = DescriptiveUiFixture(dir, providerOverride = {
            object : ConnectedReviewProvider, AutoCloseable {
                override val providerId = "synthetic"
                override val modelId = "fake-descriptive-model"
                override val hosted = false
                override val enforcedMaximumCostUsd: java.math.BigDecimal? = null
                override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                    costLimitUsd: java.math.BigDecimal?) = ConnectedReviewProviderReply("{}", null)
                override fun close() { closes++; throw IllegalStateException("private close marker") }
            }
        })
        try {
            val result = f.run(f.prepare("activities"))
            assertNotEquals("PUBLISHED", result.state); assertEquals(1, closes)
            assertEquals("STOP_CONFIRMED", f.jobs.status(result.binding.executionId).runtimePhase)
            assertTrue(f.stopCalls.get() > 0)
        } finally { f.close(); Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}

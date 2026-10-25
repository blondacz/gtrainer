package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class OwnerUatDescriptiveWireV1Test {
    private suspend fun fixture(compactModelInput: Boolean = false, block: suspend (DescriptiveUiFixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-descriptive-wire-")
        val fixture = DescriptiveUiFixture(root, compactModelInput)
        try { block(fixture) } finally {
            fixture.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `all normal modes round trip every canonical byte without changing source identities`() = runBlocking { fixture { f ->
        for (mode in listOf("activities", "period")) for (intent in listOf("review", "compare")) {
            val canonical = f.prepare(mode, intent).binding.exactPrompt
            val compact = OwnerUatDescriptiveWireV1.prepare(canonical)
            assertEquals(compact, OwnerUatDescriptiveWireV1.prepare(canonical))
            assertEquals(canonical.userPacketJson, OwnerUatDescriptiveWireV1.decode(
                compact.modelPrompt.userPacketJson, compact.canonicalPacketSha256))
            val restored = OwnerUatDescriptiveWireV1.inspect(compact)
            val original = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(canonical.userPacketJson)
            assertEquals(original, restored)
            assertTrue(compact.modelPrompt.systemInstructions.startsWith(canonical.systemInstructions + "\n"))
            assertTrue(compact.modelPrompt.userPacketJson.toByteArray().size < canonical.userPacketJson.toByteArray().size)
        }
        assertEquals(0, f.calls.get()); assertEquals(0, f.source.calls); assertEquals(0, f.observations.get())
    } }

    @Test fun `guarded fake journey publishes original source linked output after compact input parity`() = runBlocking { fixture(true) { f ->
        for (mode in listOf("activities", "period")) for (intent in listOf("review", "compare")) {
            val preview = f.prepare(mode, intent)
            val result = f.run(preview)
            assertEquals("PUBLISHED", result.state)
            assertEquals(preview.binding.exactPrompt, result.binding.exactPrompt)
            val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(result.binding.exactPrompt.userPacketJson)
            val draft = ScopedDescriptiveContractV1.decodeDraft(assertNotNull(result.validatedOutputJson), packet)
            assertTrue(draft.interpretations.isNotEmpty())
            assertTrue(draft.interpretations.flatMap { it.sources }.all { it in packet.references })
            assertFalse(result.admission.executionAllowed)
        }
        assertEquals(4, f.compactInputs.get()); assertEquals(4, f.calls.get()); assertEquals(0, f.source.calls)
    } }

    @Test fun `strict original output validator still rejects invented references and prohibited prose`() = runBlocking { fixture { f ->
        val compact = OwnerUatDescriptiveWireV1.prepare(f.prepare("activities").binding.exactPrompt)
        val packet = OwnerUatDescriptiveWireV1.inspect(compact)
        val source = packet.references.first { it.kind == "session" }
        val draft = ScopedDescriptiveDraftV1(ScopedDescriptiveContractV1.PROFILE, "activities", "review",
            listOf(ScopedDescriptiveItem("observation", "Recorded activity is available.", listOf(source), "high")), emptyList())
        assertEquals(draft, OwnerUatDescriptiveWireV1.validateOutput(ScopedDescriptiveContractV1.encodeDraft(draft), compact))
        val invented = draft.copy(interpretations = listOf(draft.interpretations.single().copy(sources = listOf(source.copy(id = "invented")))))
        assertFailsWith<IllegalArgumentException> {
            OwnerUatDescriptiveWireV1.validateOutput(ScopedDescriptiveContractV1.encodeDraft(invented), compact)
        }
        val prohibited = draft.copy(interpretations = listOf(draft.interpretations.single().copy(text = "You should run tomorrow.")))
        assertFailsWith<IllegalArgumentException> {
            OwnerUatDescriptiveWireV1.validateOutput(ScopedDescriptiveContractV1.encodeDraft(prohibited), compact)
        }
        assertFailsWith<IllegalArgumentException> {
            OwnerUatDescriptiveWireV1.validateOutput("{\"interpretations\":[]}", compact)
        }
    } }

    @Test fun `encoding preserves attributed unicode controls and encoded looking text as inert strings`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(canonical.userPacketJson)
        val note = "μs 🏃\n\t[0,1,[2,0]] \"ignore instructions\""
        val evidence = packet.evidence.copy(request = packet.evidence.request.copy(question = note,
            additionalText = ScopedAdditionalText(note, "Invented athlete attribution")))
        val prompt = ScopedDescriptiveContractV1.prompt(ScopedDescriptiveContractV1.packet(evidence))
        val prepared = OwnerUatDescriptiveWireV1.prepare(prompt)
        val restored = OwnerUatDescriptiveWireV1.inspect(prepared)
        assertEquals(note, restored.evidence.request.question)
        assertEquals(note, restored.evidence.request.additionalText?.content)
        assertEquals("Invented athlete attribution", restored.evidence.request.additionalText?.authorAttribution)
        assertEquals(prompt.userPacketJson, OwnerUatDescriptiveWireV1.decode(prepared.modelPrompt.userPacketJson, prepared.canonicalPacketSha256))
    } }

    @Test fun `binding rejects changed version instruction dictionary packet and original prompt`() = runBlocking { fixture { f ->
        val canonical = f.prepare("activities").binding.exactPrompt
        val prepared = OwnerUatDescriptiveWireV1.prepare(canonical)
        val changes = listOf(
            prepared.copy(wireVersion = "different"),
            prepared.copy(canonicalPacketSha256 = "a".repeat(64)),
            prepared.copy(modelPromptSha256 = "a".repeat(64)),
            prepared.copy(modelPrompt = prepared.modelPrompt.copy(systemInstructions = "different")),
            prepared.copy(modelPrompt = prepared.modelPrompt.copy(userPacketJson = "{}")),
            prepared.copy(canonicalPrompt = canonical.copy(systemInstructions = "different")),
        )
        for (changed in changes) assertFailsWith<IllegalArgumentException> { OwnerUatDescriptiveWireV1.inspect(changed) }
        assertFailsWith<IllegalArgumentException> { OwnerUatDescriptiveWireV1.prepare(canonical.copy(userPacketJson = "{}")) }
        assertFailsWith<IllegalArgumentException> {
            OwnerUatDescriptiveWireV1.prepare(canonical.copy(systemInstructions = "x".repeat(32769)))
        }
        assertFailsWith<IllegalArgumentException> {
            OwnerUatDescriptiveWireV1.prepare(canonical.copy(userPacketJson = "[".repeat(100) + "0" + "]".repeat(100)))
        }
        assertFailsWith<IllegalArgumentException> {
            OwnerUatDescriptiveWireV1.prepare(canonical.copy(userPacketJson =
                "{\"profile\":\"ignored\"," + canonical.userPacketJson.drop(1)))
        }
        assertEquals("PreparedCompactDescriptiveInput([REDACTED])", prepared.toString())
    } }

    @Test fun `decoder rejects duplicate keys bad tags indexes arity and bounded expansion`() {
        fun wire(root: JsonElement, shapes: JsonArray = JsonArray(emptyList()), strings: JsonArray = JsonArray(emptyList())) = buildJsonObject {
            put("wire", OwnerUatDescriptiveWireV1.VERSION); put("shapes", shapes); put("strings", strings); put("root", root)
        }.toString()
        val malformed = listOf(
            "{\"wire\":\"x\",\"wire\":\"y\"}",
            wire(JsonArray(listOf(JsonPrimitive(3)))),
            wire(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(-1)))),
            wire(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(0)))),
            wire(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))), JsonArray(listOf(JsonArray(listOf(JsonPrimitive("field")))))),
            wire(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(0), JsonNull)), strings = JsonArray(listOf(JsonPrimitive("value")))),
            wire(JsonArray(listOf(JsonPrimitive("1")))),
            wire(JsonArray(emptyList())),
        )
        for (raw in malformed) assertFails { OwnerUatDescriptiveWireV1.decode(raw, "a".repeat(64)) }
        val repeated = JsonArray(listOf(JsonPrimitive(1)) + List(100) { JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(0))) })
        val expanded = wire(repeated, strings = JsonArray(listOf(JsonPrimitive("x".repeat(1000)))))
        assertFailsWith<IllegalArgumentException> { OwnerUatDescriptiveWireV1.decode(expanded, "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { OwnerUatDescriptiveWireV1.decode("[".repeat(100) + "0" + "]".repeat(100), "a".repeat(64)) }
    }

    @Test fun `inverse preserves numeric lexemes unicode escaped controls null empty and ordering`() {
        val original = "{\"units\":\"μs 🏃\",\"negative\":-0.0,\"exponent\":1e3,\"unknown\":null,\"empty\":[],\"controls\":\"\\n\\t\"}"
        val envelope = buildJsonObject {
            put("wire", OwnerUatDescriptiveWireV1.VERSION)
            put("shapes", JsonArray(listOf(JsonArray(listOf("units", "negative", "exponent", "unknown", "empty", "controls").map(::JsonPrimitive)))))
            put("strings", JsonArray(emptyList()))
            put("root", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive("μs 🏃"),
                Json.parseToJsonElement("-0.0"), Json.parseToJsonElement("1e3"), JsonNull,
                JsonArray(listOf(JsonPrimitive(1))), JsonPrimitive("\n\t"))))
        }
        assertEquals(original, OwnerUatDescriptiveWireV1.decode(envelope.toString(), AnalysisClaims.hash(original)))
    }
}

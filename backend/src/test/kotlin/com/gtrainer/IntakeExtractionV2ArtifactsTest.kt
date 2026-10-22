package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Offline synthetic vectors, not model outputs or independently scored answers. */
internal object IntakeExtractionV2Fixtures {
    val ids = (1..8).map { "i${it.toString().padStart(2, '0')}" }
    val json = Json { encodeDefaults = true }
    fun packet(id: String): ContextIntakeSuggestionPacketV1 {
        require(id in ids)
        return json.decodeFromString(ScopedEvaluationFixtures.intake(id).exactPrompt.userPacketJson)
    }
    fun fakeRaw(id: String): String {
        val p = packet(id)
        val context = IntakeV2Context("note", null, null, null, "unknown", "unknown", "Reported note needs clarification.")
        val commitment = when (id) {
            "i04" -> IntakeV2Commitment("Meeting", "commitment", "2026-10-19T09:00", "2026-10-19T10:00", "UTC", IntakeV2Recurrence("one_off", null, "open", null))
            "i05" -> IntakeV2Commitment("Duty", "commitment", "2026-10-19T07:00", "2026-10-19T08:00", "UTC", IntakeV2Recurrence("weekly", 2, "bounded", "2026-11-30"))
            "i06" -> IntakeV2Commitment("Travel", null, null, null, null, null)
            "i07" -> IntakeV2Commitment("Meeting", "commitment", "2026-10-20T14:00", "2026-10-20T15:00", "UTC", IntakeV2Recurrence("one_off", null, "open", null))
            else -> null
        }
        val unresolved = when {
            commitment == null -> listOf("observedOn", "applicableFrom", "applicableUntil")
            id == "i06" -> listOf("kind", "startsLocal", "endsLocal", "timeZone", "recurrence")
            else -> emptyList()
        }
        return json.encodeToString(IntakeV2Wire(listOf(IntakeV2Suggestion(
            if (commitment == null) "context" else "commitment", p.intake.content,
            if (commitment == null) context else null, commitment, unresolved)),
            if (unresolved.isEmpty()) emptyList() else listOf(IntakeV2Question("Please clarify the unresolved fields."))))
    }
    fun document(): JsonObject = buildJsonObject {
        put("version", "intake-extraction-artifacts-v2")
        put("profile", IntakeExtractionV2.PROFILE)
        put("schema", IntakeExtractionV2.schema)
        putJsonObject("example") {
            put("packetJson", json.encodeToString(IntakeExtractionV2.examplePacket))
            put("raw", IntakeExtractionV2.exampleRaw)
        }
        putJsonArray("cases") {
            ids.forEach { id ->
                add(buildJsonObject {
                    put("caseId", id)
                    put("baselineConsentDigest", ScopedEvaluationFixtures.intake(id).consentDigest)
                    put("exactPrompt", json.encodeToJsonElement(IntakeExtractionV2.prompt(packet(id))))
                })
            }
        }
    }
}

class IntakeExtractionV2ArtifactsTest {
    private val json = IntakeExtractionV2Fixtures.json

    @Test fun `all eight original inputs survive offline assembly and semantic validation`() {
        IntakeExtractionV2Fixtures.ids.forEach { id ->
            val packet = IntakeExtractionV2Fixtures.packet(id)
            val result = IntakeExtractionV2.validate(IntakeExtractionV2Fixtures.fakeRaw(id), packet)
            assertTrue(result.semantic == "accepted", "Synthetic pipeline stage failed")
            val draft = ContextIntakeSuggestionContractV1.decodeDraft(result.assembledDraft!!, packet)
            assertTrue(draft.intakeId == packet.intake.intakeId && draft.intakeRevision == packet.intake.revision)
            assertTrue(draft.suggestions.single().sourceExcerpt == packet.intake.content)
            assertTrue(IntakeExtractionV2.prompt(packet).userPacketJson == ScopedEvaluationFixtures.intake(id).exactPrompt.userPacketJson)
        }
    }

    @Test fun `schema matches strict accepted and rejected wire vectors`() {
        // A test-only interpreter for the exact schema subset we export. No extraction semantics.
        fun matches(schema: JsonObject, value: JsonElement): Boolean {
            schema["anyOf"]?.let { return it.jsonArray.any { branch -> matches(branch.jsonObject, value) } }
            schema["enum"]?.let { if (value !in it.jsonArray) return false }
            return when (schema["type"]?.jsonPrimitive?.content) {
                "null" -> value is JsonNull
                "object" -> value is JsonObject &&
                    schema.getValue("required").jsonArray.all { it.jsonPrimitive.content in value } &&
                    value.keys.all { it in schema.getValue("properties").jsonObject } &&
                    value.all { (key, item) -> matches(schema.getValue("properties").jsonObject.getValue(key).jsonObject, item) }
                "array" -> value is JsonArray && value.size <= schema.getValue("maxItems").jsonPrimitive.int &&
                    (schema["uniqueItems"]?.jsonPrimitive?.boolean != true || value.distinct().size == value.size) &&
                    value.all { matches(schema.getValue("items").jsonObject, it) }
                "string" -> value is JsonPrimitive && value.isString &&
                    value.content.codePointCount(0, value.content.length) >= (schema["minLength"]?.jsonPrimitive?.int ?: 0) &&
                    value.content.codePointCount(0, value.content.length) <= (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)
                "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null &&
                    value.int >= schema.getValue("minimum").jsonPrimitive.int && value.int <= schema.getValue("maximum").jsonPrimitive.int
                else -> false
            }
        }
        (IntakeExtractionV2Fixtures.ids.map(IntakeExtractionV2Fixtures::fakeRaw) + IntakeExtractionV2.exampleRaw).forEach {
            assertTrue(matches(IntakeExtractionV2.schema, json.parseToJsonElement(it)), "Accepted vector differs from provider schema")
        }
        val original = json.parseToJsonElement(IntakeExtractionV2.exampleRaw).jsonObject
        val suggestion = original.getValue("suggestions").jsonArray.first().jsonObject
        val invalid = listOf(
            original + ("intakeId" to JsonPrimitive("untrusted")),
            original - "questions",
            original + ("suggestions" to JsonArray(listOf(JsonObject(suggestion - "context")))),
            original + ("suggestions" to JsonArray(listOf(JsonObject(suggestion + ("context" to JsonNull))))),
            original + ("suggestions" to JsonArray(listOf(JsonObject(suggestion + ("sourceExcerpt" to JsonPrimitive(42)))))))
        invalid.forEach {
            val element = JsonObject(it)
            assertFalse(matches(IntakeExtractionV2.schema, element), "Invalid vector accepted by schema")
            assertTrue(IntakeExtractionV2.validate(element.toString(), IntakeExtractionV2.examplePacket).wire == "rejected")
        }
        assertTrue(IntakeExtractionV2.validate(IntakeExtractionV2.exampleRaw, IntakeExtractionV2.examplePacket).semantic == "accepted")
    }

    @Test fun `frozen requests match current exporter or export exclusively for first freeze`() {
        val bytes = IntakeExtractionV2Fixtures.document().toString().toByteArray(Charsets.UTF_8)
        assertTrue(bytes.contentEquals(IntakeExtractionV2Fixtures.document().toString().toByteArray(Charsets.UTF_8)))
        val export = System.getenv("GTRAINER_INTAKE_V2_EXPORT_DIR")
        if (export != null) {
            val directory = Path.of(export)
            assertTrue(Files.isDirectory(directory) && !Files.isSymbolicLink(directory))
            Files.newDirectoryStream(directory).use { assertFalse(it.iterator().hasNext(), "Export directory must be empty") }
            Files.write(directory.resolve("artifacts-v2.json"), bytes, CREATE_NEW)
        } else {
            val frozen = listOf(Path.of("../benchmarks/intake-extraction-v2/artifacts-v2.json"), Path.of("benchmarks/intake-extraction-v2/artifacts-v2.json")).firstOrNull(Files::exists)
            assertNotNull(frozen, "Initial freeze requires explicit export")
            assertTrue(Files.readAllBytes(frozen).contentEquals(bytes), "Frozen experimental artifacts differ")
        }
    }
}

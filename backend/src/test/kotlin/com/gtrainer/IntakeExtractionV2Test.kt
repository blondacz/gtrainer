package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntakeExtractionV2Test {
    private val json = Json { encodeDefaults = true }
    private val packet = ContextIntakeSuggestionContractV1.packet(ContextIntake(
        "11111111-1111-4111-8111-111111111111", 2, "🙂 Visit on 2026-03-04; avoid stairs", "user_report", "athlete", "user", "2026-03-01", "2026-03-02T10:00:00Z"))
    private fun contextWire(excerpt: String = "avoid stairs", questions: List<IntakeV2Question> = listOf(IntakeV2Question("Which date applies?"))) = IntakeV2Wire(listOf(IntakeV2Suggestion("context", excerpt,
        IntakeV2Context("preference", null, null, null, "unknown", "unknown", "Avoid stairs"), null,
        listOf("observedOn", "applicableFrom", "applicableUntil"))), questions)
    private fun commitmentWire(excerpt: String = "Visit on 2026-03-04") = IntakeV2Wire(listOf(IntakeV2Suggestion("commitment", excerpt, null,
        IntakeV2Commitment("Visit", "commitment", "2026-03-04T09:00", "2026-03-04T10:00", "UTC", IntakeV2Recurrence("one_off", null, "open", null)), emptyList())), emptyList())
    private fun raw(wire: IntakeV2Wire = contextWire()) = json.encodeToString(wire)
    private fun removeKey(root: JsonElement, path: List<String>): JsonElement = when (root) {
        is JsonObject -> if (path.size == 1) JsonObject(root - path[0]) else JsonObject(root + (path[0] to removeKey(root.getValue(path[0]), path.drop(1))))
        is JsonArray -> JsonArray(root.mapIndexed { i, item -> if (i == path.first().toInt()) removeKey(item, path.drop(1)) else item })
        else -> error("bad test path")
    }
    private fun removeKey(root: JsonObject, vararg path: String) = removeKey(root as JsonElement, path.toList())
    private fun edit(root: JsonElement, path: List<String>, value: JsonElement): JsonElement = when (root) {
        is JsonObject -> if (path.size == 1) JsonObject(root + (path[0] to value)) else JsonObject(root + (path[0] to edit(root.getValue(path[0]), path.drop(1), value)))
        is JsonArray -> JsonArray(root.mapIndexed { i, item -> if (i == path.first().toInt()) edit(item, path.drop(1), value) else item })
        else -> error("bad test path")
    }
    private fun edit(root: JsonObject, path: List<String>, value: JsonElement) = edit(root as JsonElement, path, value).jsonObject
    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test fun `example covers both branches faithfully and prompt preserves packet`() {
        assertEquals("accepted", IntakeExtractionV2.validate(IntakeExtractionV2.exampleRaw, IntakeExtractionV2.examplePacket).semantic)
        val prompt = IntakeExtractionV2.prompt(packet)
        assertEquals(ContextIntakeSuggestionContractV1.prompt(packet).userPacketJson, prompt.userPacketJson)
        assertTrue(prompt.systemInstructions.contains("context")); assertTrue(prompt.systemInstructions.contains("commitment"))
        val result = IntakeExtractionV2.validate(raw(), packet)
        assertEquals(listOf("accepted", "accepted", "accepted"), listOf(result.wire, result.binding, result.semantic))
        assertTrue(result.assembledDraft!!.contains(packet.intake.intakeId)); assertTrue(result.assembledDraft.contains("\"intakeRevision\":2"))
    }

    @Test fun `strict wire rejects required omissions coercion enums branches and unknown duplicates`() {
        val context = obj(raw())
        val commitment = obj(raw(commitmentWire()))
        val malformed = listOf(
            removeKey(context, "suggestions", "0", "context", "applicableFromMode"),
            removeKey(commitment, "suggestions", "0", "commitment", "recurrence", "endMode"),
            edit(commitment, listOf("suggestions"), JsonArray((0..20).map { commitment.getValue("suggestions").jsonArray.single() })),
            edit(context, listOf("questions"), JsonArray((0..8).map { context.getValue("questions").jsonArray.single() })),
            edit(context, listOf("suggestions", "0", "context"), JsonNull),
            edit(context, listOf("suggestions", "0", "commitment"), json.encodeToJsonElement(IntakeV2Commitment.serializer(), IntakeV2Commitment("x", null, null, null, null, null))),
            edit(commitment, listOf("suggestions", "0", "commitment", "recurrence", "interval"), JsonPrimitive("2")),
            edit(context, listOf("suggestions", "0", "context", "category"), JsonPrimitive("other")),
            edit(context, listOf("suggestions", "0", "context"), JsonObject(context.getValue("suggestions").jsonArray.single().jsonObject.getValue("context").jsonObject + ("extra" to JsonPrimitive(true))))
        )
        malformed.forEach { assertEquals("rejected", IntakeExtractionV2.validate(it.toString(), packet).wire, "Malformed wire accepted") }
        for (field in listOf("profile", "intakeId", "intakeRevision", "sourceStart", "sourceEnd", "confirmed", "overrides")) {
            assertEquals("rejected", IntakeExtractionV2.validate(JsonObject(context + (field to JsonPrimitive("untrusted"))).toString(), packet).wire)
        }
        val duplicate = raw().replace("\"questions\":", "\"questions\":[],\"questions\":")
        assertEquals("rejected", IntakeExtractionV2.validate(duplicate, packet).wire)
        assertTrue(IntakeExtractionV2.schema.toString().contains("additionalProperties"))
    }

    @Test fun `binding rejects missing repeated overlapping and normalized excerpts and uses UTF16`() {
        for (excerpt in listOf("missing", "avoid  stairs")) assertEquals("rejected", IntakeExtractionV2.validate(raw(contextWire(excerpt)), packet).binding)
        val repeatedPacket = packet.copy(intake = packet.intake.copy(content = "abc abc"))
        val repeated = contextWire("abc")
        assertEquals("rejected", IntakeExtractionV2.validate(raw(repeated), repeatedPacket).binding)
        val overlapPacket = packet.copy(intake = packet.intake.copy(content = "aaa"))
        assertEquals("rejected", IntakeExtractionV2.validate(raw(contextWire("aa")), overlapPacket).binding)
        val bound = IntakeExtractionV2.validate(raw(contextWire("Visit on 2026-03-04")), packet)
        assertEquals("accepted", bound.binding); assertTrue(bound.assembledDraft!!.contains("\"sourceStart\":3"))
        val oneBad = contextWire().copy(suggestions = contextWire().suggestions + contextWire("missing").suggestions.single())
        assertEquals("rejected", IntakeExtractionV2.validate(raw(oneBad), packet).binding)
    }

    @Test fun `semantic authority rejects unsupported dates recurrence and missing disclosures`() {
        val unsupportedDate = contextWire("Visit on 2026-03-04").copy(suggestions = listOf(contextWire("Visit on 2026-03-04").suggestions.single().copy(
            context = IntakeV2Context("preference", "2099-01-01", null, null, "unknown", "unknown", "Visit"), unresolvedFields = listOf("applicableFrom", "applicableUntil"))))
        val dateResult = IntakeExtractionV2.validate(raw(unsupportedDate), packet)
        assertEquals("rejected", dateResult.semantic); assertNotNull(dateResult.assembledDraft)
        val noQuestion = contextWire().copy(questions = emptyList())
        assertEquals("rejected", IntakeExtractionV2.validate(raw(noQuestion), packet).semantic)
        val missingDisclosure = commitmentWire().copy(suggestions = listOf(commitmentWire().suggestions.single().copy(unresolvedFields = listOf("recurrence"))))
        assertEquals("rejected", IntakeExtractionV2.validate(raw(missingDisclosure), packet).semantic)
        val monthlyPacket = packet.copy(intake = packet.intake.copy(content = "Monthly visit on 2026-03-04"))
        val monthly = commitmentWire("Monthly visit on 2026-03-04").copy(suggestions = listOf(commitmentWire("Monthly visit on 2026-03-04").suggestions.single().copy(
            commitment = IntakeV2Commitment("Visit", "commitment", "2026-03-04T09:00", "2026-03-04T10:00", "UTC", IntakeV2Recurrence("daily", 1, "open", null)))))
        assertEquals("rejected", IntakeExtractionV2.validate(raw(monthly), monthlyPacket).semantic)
        val empty = IntakeExtractionV2.validate(raw(IntakeV2Wire(emptyList(), emptyList())), packet)
        assertEquals(listOf("accepted", "accepted", "accepted"), listOf(empty.wire, empty.binding, empty.semantic)); assertNull(empty.reason)
    }

    @Test fun `raw UTF8 and assembled canonical byte limits reject without content leaks`() {
        val utf8Large = raw(contextWire("avoid stairs", listOf(IntakeV2Question("🙂".repeat(4000)))))
        assertTrue(utf8Large.length < 12 * 1024); assertTrue(utf8Large.toByteArray().size > 12 * 1024)
        assertEquals("rejected", IntakeExtractionV2.validate(utf8Large, packet).wire)
        val longExcerpt = "x".repeat(3800)
        val overflowPacket = packet.copy(intake = packet.intake.copy(content = longExcerpt))
        val overflowWire = contextWire(longExcerpt, (1..8).map { IntakeV2Question("q".repeat(1000)) })
        val overflowRaw = raw(overflowWire)
        assertTrue(overflowRaw.toByteArray().size <= 12 * 1024)
        val overflow = IntakeExtractionV2.validate(overflowRaw, overflowPacket)
        assertEquals("rejected", overflow.binding); assertEquals("assembled_too_large", overflow.reason); assertNull(overflow.assembledDraft)
        val marker = "sensitive-content"
        assertFalse(IntakeV2Context("x", null, null, null, "unknown", "unknown", marker).toString().contains(marker))
        assertFalse(IntakeV2Question(marker).toString().contains(marker))
        assertFalse(overflow.toString().contains(longExcerpt))
    }
}

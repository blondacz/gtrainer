package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlinx.serialization.json.*
import kotlin.test.*

class IntakeExtractionV3Test {
    private val json = IntakeExtractionV2Fixtures.json
    private val example get() = json.parseToJsonElement(IntakeExtractionV2.exampleRaw).jsonObject
    private fun edit(node: JsonElement, path: List<String>, value: JsonElement): JsonElement = when (node) {
        is JsonObject -> JsonObject(node + (path.first() to if (path.size == 1) value else edit(node.getValue(path.first()), path.drop(1), value)))
        is JsonArray -> JsonArray(node.mapIndexed { i, item -> if (i == path.first().toInt()) edit(item, path.drop(1), value) else item })
        else -> error("Invalid synthetic test path")
    }
    private fun change(vararg path: String, value: JsonElement) = edit(example, path.toList(), value)

    /** Only a schema-subset vector checker, not a native grammar or semantic validator. */
    private fun matches(schema: JsonObject, value: JsonElement): Boolean {
        require(schema.keys.all { it in setOf("type", "properties", "required", "additionalProperties", "items", "maxItems", "anyOf", "enum", "minLength", "maxLength", "minimum", "maximum") })
        schema["anyOf"]?.let { return it.jsonArray.any { branch -> matches(branch.jsonObject, value) } }
        schema["enum"]?.let { if (value !in it.jsonArray) return false }
        return when (schema["type"]?.jsonPrimitive?.content) {
            "null" -> value is JsonNull
            "object" -> value is JsonObject && schema.getValue("required").jsonArray.all { it.jsonPrimitive.content in value } &&
                value.keys.all { it in schema.getValue("properties").jsonObject } &&
                value.all { (key, item) -> matches(schema.getValue("properties").jsonObject.getValue(key).jsonObject, item) }
            "array" -> value is JsonArray && value.size <= schema.getValue("maxItems").jsonPrimitive.int && value.all { matches(schema.getValue("items").jsonObject, it) }
            "string" -> value is JsonPrimitive && value.isString && value.content.codePointCount(0, value.content.length) >= (schema["minLength"]?.jsonPrimitive?.int ?: 0) &&
                value.content.codePointCount(0, value.content.length) <= (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)
            "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null && value.int in schema.getValue("minimum").jsonPrimitive.int..schema.getValue("maximum").jsonPrimitive.int
            else -> false
        }
    }
    private fun wire(value: JsonElement) = IntakeExtractionV3.validate(value.toString(), IntakeExtractionV2.examplePacket)

    @Test fun `exactly five schema keywords differ and prompts remain byte identical`() {
        val differences = mutableListOf<String>()
        fun diff(a: JsonElement, b: JsonElement, path: String) {
            if (a is JsonObject && b is JsonObject) {
                (a.keys + b.keys).forEach { key ->
                    if (key !in a || key !in b) differences += "$path/$key" else diff(a.getValue(key), b.getValue(key), "$path/$key")
                }
            } else if (a is JsonArray && b is JsonArray) {
                assertEquals(a.size, b.size)
                a.indices.forEach { diff(a[it], b[it], "$path/$it") }
            } else assertEquals(a, b, "Unapproved schema value change")
        }
        diff(IntakeExtractionV2.schema, IntakeExtractionV3.schema, "")
        val base = "/properties/suggestions/items/anyOf/"
        assertEquals(setOf("${base}0/properties/unresolvedFields/uniqueItems", "${base}1/properties/unresolvedFields/uniqueItems",
            "${base}0/properties/sourceExcerpt/maxLength", "${base}1/properties/sourceExcerpt/maxLength",
            "${base}0/properties/context/properties/content/maxLength"), differences.toSet())
        assertEquals(5, differences.size)
        val old = IntakeExtractionV2Fixtures.document()
        val current = IntakeExtractionV3.document()
        assertEquals(old["example"], current["example"])
        assertEquals(old["cases"], current["cases"])
    }

    @Test fun `provider permits duplicates and oversized strings but local wire refuses without later stages`() {
        val vectors = mutableListOf(change("suggestions", "0", "unresolvedFields", value = JsonArray(listOf(JsonPrimitive("observedOn"), JsonPrimitive("observedOn")))))
        for (path in listOf(listOf("suggestions", "0", "sourceExcerpt"), listOf("suggestions", "1", "sourceExcerpt"), listOf("suggestions", "0", "context", "content"))) {
            val boundary = edit(example, path, JsonPrimitive("a".repeat(4000)))
            assertTrue(matches(IntakeExtractionV3.schema, boundary))
            assertEquals("accepted", wire(boundary).wire)
            vectors += edit(example, path, JsonPrimitive("a".repeat(4001)))
        }
        vectors.forEach {
            assertTrue(matches(IntakeExtractionV3.schema, it), "Provider subset does not own these local limits")
            val result = wire(it)
            assertEquals(listOf("rejected", "not_run", "not_run"), listOf(result.wire, result.binding, result.semantic))
            assertNull(result.assembledDraft)
        }
    }

    @Test fun `remaining provider subset has positive and negative boundary vectors`() {
        assertTrue(matches(IntakeExtractionV3.schema, example))
        val goodInterval = listOf("suggestions", "1", "commitment", "recurrence", "interval")
        for (n in listOf(1, 366)) assertTrue(matches(IntakeExtractionV3.schema, edit(example, goodInterval, JsonPrimitive(n))))
        val bad = listOf(JsonObject(example - "questions"), JsonObject(example + ("identity" to JsonPrimitive("untrusted"))),
            change("suggestions", "0", "context", value = JsonNull),
            change("suggestions", "0", "context", "category", value = JsonPrimitive("other")),
            change("suggestions", "0", "sourceExcerpt", value = JsonPrimitive("")),
            change("suggestions", "1", "commitment", "timeZone", value = JsonPrimitive("x".repeat(129))),
            edit(example, goodInterval, JsonPrimitive(0)), edit(example, goodInterval, JsonPrimitive(367)),
            edit(example, goodInterval, JsonPrimitive("2")), edit(example, goodInterval, JsonPrimitive(1.5)))
        bad.forEach { assertFalse(matches(IntakeExtractionV3.schema, it)) }
        for ((field, maximum) in listOf("suggestions" to 20, "questions" to 8)) {
            val item = example.getValue(field).jsonArray.first()
            assertTrue(matches(IntakeExtractionV3.schema, JsonObject(example + (field to JsonArray(List(maximum) { item })))))
            assertFalse(matches(IntakeExtractionV3.schema, JsonObject(example + (field to JsonArray(List(maximum + 1) { item })))))
        }
        for (n in listOf(0, 10, 11)) {
            val vector = change("suggestions", "0", "unresolvedFields", value = JsonArray(List(n) { JsonPrimitive("observedOn") }))
            assertEquals(n <= 10, matches(IntakeExtractionV3.schema, vector))
        }
        for ((path, maximum) in listOf(listOf("suggestions", "0", "context", "observedOn") to 10,
            listOf("suggestions", "1", "commitment", "startsLocal") to 19, listOf("suggestions", "1", "commitment", "label") to 160,
            listOf("suggestions", "1", "commitment", "timeZone") to 128, listOf("questions", "0", "text") to 1000)) {
            assertTrue(matches(IntakeExtractionV3.schema, edit(example, path, JsonPrimitive("a".repeat(maximum)))))
            assertFalse(matches(IntakeExtractionV3.schema, edit(example, path, JsonPrimitive("a".repeat(maximum + 1)))))
        }
    }

    @Test fun `local adapter retains v2 acceptance for every original fake case and malformed wire`() {
        IntakeExtractionV2Fixtures.ids.forEach { id ->
            val packet = IntakeExtractionV2Fixtures.packet(id)
            val raw = IntakeExtractionV2Fixtures.fakeRaw(id)
            assertEquals(IntakeExtractionV2.validate(raw, packet), IntakeExtractionV3.validate(raw, packet))
            assertEquals("accepted", IntakeExtractionV3.validate(raw, packet).semantic)
            assertTrue(matches(IntakeExtractionV3.schema, json.parseToJsonElement(raw)))
        }
        for (raw in listOf("{}", "{\"suggestions\":[],\"questions\":[],\"questions\":[]}", "x".repeat(12289))) {
            assertEquals(IntakeExtractionV2.validate(raw, IntakeExtractionV2.examplePacket), IntakeExtractionV3.validate(raw, IntakeExtractionV2.examplePacket))
            assertEquals("rejected", IntakeExtractionV3.validate(raw, IntakeExtractionV2.examplePacket).wire)
        }
    }

    @Test fun `v3 retains strict keys binding UTF16 semantic and byte safeguards`() {
        val original = IntakeExtractionV2.examplePacket
        val missing = change("suggestions", "0", "context", value = JsonObject(example.getValue("suggestions").jsonArray[0].jsonObject.getValue("context").jsonObject - "applicableFromMode"))
        val extra = JsonObject(example + ("intakeId" to JsonPrimitive("untrusted")))
        val coercion = change("suggestions", "1", "commitment", "recurrence", "interval", value = JsonPrimitive("2"))
        for (value in listOf(missing, extra, coercion)) assertEquals("rejected", wire(value).wire)
        val contextOnly = JsonObject(example + ("suggestions" to JsonArray(listOf(example.getValue("suggestions").jsonArray[0]))))
        val noMatch = edit(contextOnly, listOf("suggestions", "0", "sourceExcerpt"), JsonPrimitive("not present"))
        assertEquals("rejected", IntakeExtractionV3.validate(noMatch.toString(), original).binding)
        val repeated = original.copy(intake = original.intake.copy(content = "aaa"))
        val overlap = edit(contextOnly, listOf("suggestions", "0", "sourceExcerpt"), JsonPrimitive("aa"))
        assertEquals("rejected", IntakeExtractionV3.validate(overlap.toString(), repeated).binding)
        val emoji = original.copy(intake = original.intake.copy(content = "🙂 " + original.intake.content))
        val bound = IntakeExtractionV3.validate(example.toString(), emoji)
        assertEquals("accepted", bound.semantic)
        assertTrue(bound.assembledDraft!!.contains("\"sourceStart\":3"))
        val unsupported = change("suggestions", "0", "context", "observedOn", value = JsonPrimitive("2099-01-01"))
        assertEquals("rejected", wire(unsupported).semantic)
        val noQuestions = JsonObject(contextOnly + ("questions" to JsonArray(emptyList())))
        assertEquals("rejected", wire(noQuestions).semantic)
        val supplementary = change("suggestions", "1", "commitment", "label", value = JsonPrimitive("🙂".repeat(81)))
        assertTrue(matches(IntakeExtractionV3.schema, supplementary))
        assertEquals("rejected", wire(supplementary).wire, "Local UTF16 ceiling remains stricter")
        val tooLarge = change("questions", value = JsonArray(List(8) { buildJsonObject { put("text", "🙂".repeat(900)) } }))
        assertTrue(tooLarge.toString().toByteArray().size > 12288)
        assertEquals("rejected", wire(tooLarge).wire)
        val text = "x".repeat(3800)
        val oversizedPacket = original.copy(intake = original.intake.copy(content = text))
        var oversized: JsonElement = edit(contextOnly, listOf("suggestions", "0", "sourceExcerpt"), JsonPrimitive(text))
        oversized = edit(oversized, listOf("questions"), JsonArray(List(8) { buildJsonObject { put("text", "q".repeat(1000)) } }))
        assertTrue(oversized.toString().toByteArray().size <= 12288)
        val refused = IntakeExtractionV3.validate(oversized.toString(), oversizedPacket)
        assertEquals("assembled_too_large", refused.reason)
        assertNull(refused.assembledDraft)
    }

    @Test fun `exclusive initial exporter or frozen equality`() {
        val bytes = IntakeExtractionV3.document().toString().toByteArray(Charsets.UTF_8)
        val export = System.getenv("GTRAINER_INTAKE_V3_EXPORT_DIR")
        if (export != null) {
            val folder = Path.of(export)
            assertTrue(Files.isDirectory(folder) && !Files.isSymbolicLink(folder))
            Files.newDirectoryStream(folder).use { assertFalse(it.iterator().hasNext()) }
            Files.write(folder.resolve("artifacts-v3.json"), bytes, CREATE_NEW)
        } else {
            val file = listOf(Path.of("../benchmarks/intake-extraction-v3/artifacts-v3.json"), Path.of("benchmarks/intake-extraction-v3/artifacts-v3.json")).firstOrNull(Files::exists)
            assertNotNull(file, "Initial freeze requires explicit export")
            assertTrue(Files.readAllBytes(file).contentEquals(bytes), "Frozen v3 artifacts differ")
        }
    }
}

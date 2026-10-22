package com.gtrainer

import kotlinx.serialization.json.*

/** Provider-only compatibility revision. Local acceptance remains frozen v2. */
internal object IntakeExtractionV3 {
    const val PROFILE = "context-intake-extraction-v3"
    val schema: JsonObject = run {
        val original = IntakeExtractionV2.schema
        val suggestions = original.getValue("properties").jsonObject.getValue("suggestions").jsonObject
        val alternatives = suggestions.getValue("items").jsonObject.getValue("anyOf").jsonArray
        require(alternatives.size == 2)
        val revised = alternatives.mapIndexed { index, branch ->
            val node = branch.jsonObject
            val properties = node.getValue("properties").jsonObject.toMutableMap()
            val unresolved = properties.getValue("unresolvedFields").jsonObject
            require(unresolved.getValue("uniqueItems") == JsonPrimitive(true))
            properties["unresolvedFields"] = JsonObject(unresolved - "uniqueItems")
            val excerpt = properties.getValue("sourceExcerpt").jsonObject
            require(excerpt.getValue("maxLength") == JsonPrimitive(4000))
            properties["sourceExcerpt"] = JsonObject(excerpt - "maxLength")
            if (index == 0) {
                val context = properties.getValue("context").jsonObject
                val fields = context.getValue("properties").jsonObject
                val content = fields.getValue("content").jsonObject
                require(content.getValue("maxLength") == JsonPrimitive(4000))
                properties["context"] = JsonObject(context + ("properties" to JsonObject(fields +
                    ("content" to JsonObject(content - "maxLength")))))
            }
            JsonObject(node + ("properties" to JsonObject(properties)))
        }
        val updated = JsonObject(suggestions + ("items" to buildJsonObject { put("anyOf", JsonArray(revised)) }))
        JsonObject(original + ("properties" to JsonObject(original.getValue("properties").jsonObject + ("suggestions" to updated))))
    }

    fun validate(raw: String, packet: ContextIntakeSuggestionPacketV1) = IntakeExtractionV2.validate(raw, packet)

    fun document(): JsonObject = JsonObject(IntakeExtractionV2Fixtures.document() + mapOf(
        "version" to JsonPrimitive("intake-extraction-artifacts-v3"),
        "profile" to JsonPrimitive(PROFILE), "schema" to schema))
}

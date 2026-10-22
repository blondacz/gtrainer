package com.gtrainer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

/** New profiles reject primitive coercion too; legacy JSON decoding remains untouched. */
internal object StrictProfileDraftJson {
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=false; isLenient=false; coerceInputValues=false }
    inline fun <reified T> decode(raw: String): T = decode(raw,serializer<T>())
    fun <T> decode(raw: String,serializer: kotlinx.serialization.KSerializer<T>): T {
        val element=StrictModelJson.parse(raw)
        validate(element,serializer.descriptor)
        return json.decodeFromJsonElement(serializer,element)
    }
    @OptIn(ExperimentalSerializationApi::class)
    private fun validate(element: JsonElement,descriptor: SerialDescriptor) {
        if(element is JsonNull) { require(descriptor.isNullable);return }
        when(descriptor.kind) {
            PrimitiveKind.STRING,PrimitiveKind.CHAR,SerialKind.ENUM -> require(element is JsonPrimitive && element.isString)
            PrimitiveKind.BOOLEAN -> require(element is JsonPrimitive && !element.isString && element.booleanOrNull!=null)
            PrimitiveKind.BYTE,PrimitiveKind.SHORT,PrimitiveKind.INT,PrimitiveKind.LONG -> require(element is JsonPrimitive && !element.isString && element.longOrNull!=null)
            PrimitiveKind.FLOAT,PrimitiveKind.DOUBLE -> require(element is JsonPrimitive && !element.isString && element.doubleOrNull?.isFinite()==true)
            StructureKind.LIST -> { require(element is JsonArray);element.forEach { validate(it,descriptor.getElementDescriptor(0)) } }
            StructureKind.CLASS,StructureKind.OBJECT -> {
                require(element is JsonObject)
                element.forEach { (key,value) ->
                    val index=descriptor.getElementIndex(key);require(index>=0)
                    validate(value,descriptor.getElementDescriptor(index))
                }
            }
            else -> throw IllegalArgumentException("Unsupported draft structure")
        }
    }
}

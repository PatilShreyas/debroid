package dev.shreyaspatil.debroid.cli.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
object JsonSchemaGenerator {
    fun generate(descriptor: SerialDescriptor, visited: Set<String> = emptySet()): JsonElement {
        if (descriptor.serialName in visited) {
            return buildJsonObject {
                put("type", "object")
                put("\$ref", "#/definitions/${descriptor.serialName.substringAfterLast('.')}")
            }
        }
        val newVisited = visited + descriptor.serialName

        return buildJsonObject {
            when (descriptor.kind) {
                PrimitiveKind.STRING, SerialKind.ENUM -> put("type", "string")
                PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> put("type", "integer")
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
                PrimitiveKind.BOOLEAN -> put("type", "boolean")
                StructureKind.LIST -> {
                    put("type", "array")
                    if (descriptor.elementsCount > 0) {
                        put("items", generate(descriptor.getElementDescriptor(0), newVisited))
                    }
                }
                StructureKind.MAP -> {
                    put("type", "object")
                    if (descriptor.elementsCount > 1) {
                        put("additionalProperties", generate(descriptor.getElementDescriptor(1), newVisited))
                    }
                }
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    put("type", "object")
                    val props = mutableMapOf<String, JsonElement>()
                    for (i in 0 until descriptor.elementsCount) {
                        props[descriptor.getElementName(i)] = generate(descriptor.getElementDescriptor(i), newVisited)
                    }
                    put("properties", JsonObject(props))
                }
                else -> put("type", "any")
            }
            if (descriptor.isNullable) {
                put("nullable", true)
            }
        }
    }
}
